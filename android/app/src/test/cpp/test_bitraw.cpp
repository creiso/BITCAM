// Teste no host: simula um sensor Bayer (matrizes de cor, sombreamento, ruído, 4 padrões CFA)
// e confere que a revelação devolve as cores originais.
//   g++ -O2 -std=c++17 -fsanitize=address,undefined -Imain/cpp test/cpp/test_bitraw.cpp main/cpp/bitraw.cpp -lpthread
#include "bitraw.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace bitraw;

static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FALHOU  " __VA_ARGS__); printf("\n"); } } while (0)

struct V { double v[3]; };
struct M { double m[9]; };
static V mv(const M& a, V x) { V r; for (int i = 0; i < 3; i++) r.v[i] = a.m[i*3]*x.v[0] + a.m[i*3+1]*x.v[1] + a.m[i*3+2]*x.v[2]; return r; }
static M mm(const M& a, const M& b) { M r; for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r.m[i*3+j] = a.m[i*3]*b.m[j] + a.m[i*3+1]*b.m[3+j] + a.m[i*3+2]*b.m[6+j]; return r; }
static M inv(const M& a) {
    const double* m = a.m;
    double d = m[0]*(m[4]*m[8]-m[5]*m[7]) - m[1]*(m[3]*m[8]-m[5]*m[6]) + m[2]*(m[3]*m[7]-m[4]*m[6]), s = 1/d;
    return M{{(m[4]*m[8]-m[5]*m[7])*s, (m[2]*m[7]-m[1]*m[8])*s, (m[1]*m[5]-m[2]*m[4])*s,
              (m[5]*m[6]-m[3]*m[8])*s, (m[0]*m[8]-m[2]*m[6])*s, (m[2]*m[3]-m[0]*m[5])*s,
              (m[3]*m[7]-m[4]*m[6])*s, (m[1]*m[6]-m[0]*m[7])*s, (m[0]*m[4]-m[1]*m[3])*s}};
}
static M diag(V d) { return M{{d.v[0],0,0, 0,d.v[1],0, 0,0,d.v[2]}}; }
static V planckXYZ(double T) {
    double t = 1/T, x, y;
    if (T <= 4000) x = -0.2661239e9*t*t*t - 0.2343589e6*t*t + 0.8776956e3*t + 0.179910;
    else x = -3.0258469e9*t*t*t + 2.1070379e6*t*t + 0.2226347e3*t + 0.240390;
    if (T <= 2222) y = -1.1063814*x*x*x - 1.34811020*x*x + 2.18555832*x - 0.20219683;
    else if (T <= 4000) y = -0.9549476*x*x*x - 1.37418593*x*x + 2.09137015*x - 0.16748867;
    else y = 3.0817580*x*x*x - 5.87338670*x*x + 3.75112997*x - 0.37001483;
    return V{{x/y, 1, (1-x-y)/y}};
}
static M bradford(V s, V d) {
    const M B = {{0.8951,0.2664,-0.1614, -0.7502,1.7135,0.0367, 0.0389,-0.0685,1.0296}};
    V a = mv(B, s), b = mv(B, d);
    return mm(inv(B), mm(diag(V{{b.v[0]/a.v[0], b.v[1]/a.v[1], b.v[2]/a.v[2]}}), B));
}
static const V D50 = {{0.9642, 1.0, 0.8251}};
static const M SRGB = {{3.1338561,-1.6168667,-0.4906146, -0.9787684,1.9161415,0.0334540, 0.0719453,-0.2289914,1.4052427}};

// sensor fictício, mas com cara de sensor de celular
static const M CM_A   = {{1.20,-0.50,-0.10, -0.40,1.30,0.15, -0.05,0.15,0.65}};
static const M CM_D65 = {{0.90,-0.30,-0.10, -0.40,1.30,0.10, -0.05,0.20,0.60}};
static const double TA = 2856, TD = 6504;

static M cmAt(double T) {
    double w = (1/T - 1/TD) / (1/TA - 1/TD); w = w < 0 ? 0 : w > 1 ? 1 : w;
    M r; for (int i = 0; i < 9; i++) r.m[i] = CM_A.m[i]*w + CM_D65.m[i]*(1-w); return r;
}
static M forward(const M& cm, double T) {   // câmera balanceada -> XYZ D50
    V white = planckXYZ(T);
    return mm(bradford(white, D50), mm(inv(cm), diag(mv(cm, white))));
}
static ColorCal makeCal(bool withFm) {
    ColorCal c; memset(&c, 0, sizeof c);
    M fa = forward(CM_A, TA), fd = forward(CM_D65, TD);
    for (int i = 0; i < 9; i++) {
        c.cm1[i] = CM_A.m[i]; c.cm2[i] = CM_D65.m[i];
        c.cc1[i] = c.cc2[i] = (i % 4 == 0) ? 1 : 0;
        if (withFm) { c.fm1[i] = fa.m[i]; c.fm2[i] = fd.m[i]; }
    }
    c.t1 = TA; c.t2 = TD;
    return c;
}

static unsigned seed = 7;
static double rnd() { seed = seed * 1664525u + 1013904223u; return (seed >> 8) / 16777216.0; }
static double gauss() { double s = 0; for (int i = 0; i < 6; i++) s += rnd(); return (s - 3) * 1.41421356; }

// cena: grade de amostras 40x40 px, cinzas e cores (sRGB linear)
static const int GW = 16, GH = 12, PS = 40;
static V patch(int gx, int gy) {
    int k = gy * GW + gx;
    if (k % 3 == 0) { double g = 0.04 + 0.72 * ((k * 37) % 100) / 100.0; return V{{g, g, g}}; }
    static const double C[10][3] = {{.45,.08,.06},{.10,.35,.08},{.05,.09,.40},{.55,.45,.05},{.40,.07,.35},
                                    {.06,.38,.42},{.50,.25,.15},{.20,.30,.12},{.12,.15,.30},{.60,.50,.40}};
    return V{{C[k % 10][0], C[k % 10][1], C[k % 10][2]}};
}

static double vmax(V a) { return fmax(a.v[0], fmax(a.v[1], a.v[2])); }
struct Shot { std::vector<uint16_t> raw; std::vector<float> shading; V neutral; int w, h; };
static const double EXPO = 0.6, BLACK = 64, WHITE = 1023;
static const int SC = 17, SR = 13;

static Shot shoot(double T, int cfa, int padX, int padY, double noise) {
    static const int CF[4][4] = {{0,1,1,2},{1,0,2,1},{1,2,0,1},{2,1,1,0}};
    Shot s; s.w = GW * PS + padX * 2; s.h = GH * PS + padY * 2;
    s.raw.assign((size_t)s.w * s.h, (uint16_t)BLACK);
    V white = planckXYZ(T);
    M toCam = mm(cmAt(T), mm(bradford(D50, white), inv(SRGB)));
    s.neutral = mv(cmAt(T), white);
    s.shading.resize(SC * SR * 4);
    for (int r = 0; r < SR; r++) for (int c = 0; c < SC; c++) {
        double dx = c / (SC - 1.0) - 0.5, dy = r / (SR - 1.0) - 0.5, g = 1 + 1.6 * (dx*dx + dy*dy);
        for (int k = 0; k < 4; k++) s.shading[(r * SC + c) * 4 + k] = (float)(g * (k == 0 ? 1.05 : k == 3 ? 1.1 : 1));
    }
    for (int y = 0; y < s.h; y++) for (int x = 0; x < s.w; x++) {
        int px = x - padX, py = y - padY;
        if (px < 0 || py < 0 || px >= GW * PS || py >= GH * PS) continue;
        V cam = mv(toCam, patch(px / PS, py / PS));
        int col = CF[cfa][((y & 1) << 1) | (x & 1)];
        // inverso do mapa de sombreamento (bilinear, igual ao da revelação)
        double fx = x / (s.w - 1.0) * (SC - 1), fy = y / (s.h - 1.0) * (SR - 1);
        int ix = fx >= SC - 1 ? SC - 2 : (int)fx, iy = fy >= SR - 1 ? SR - 2 : (int)fy;
        double wx = fx - ix, wy = fy - iy;
        auto G = [&](int r, int c) { const float* q = &s.shading[(r * SC + c) * 4]; return col == 0 ? q[0] : col == 2 ? q[3] : 0.5 * (q[1] + q[2]); };
        double sh = (G(iy, ix) * (1 - wx) + G(iy, ix + 1) * wx) * (1 - wy) + (G(iy + 1, ix) * (1 - wx) + G(iy + 1, ix + 1) * wx) * wy;
        double v = cam.v[col] * EXPO / sh * (WHITE - BLACK) + BLACK + gauss() * noise;
        s.raw[(size_t)y * s.w + x] = (uint16_t)(v < 0 ? 0 : v > WHITE ? WHITE : lround(v));
    }
    return s;
}

static RawParams params(const Shot& s, int cfa, int padX, int padY, const ColorCal& cal) {
    RawParams p; memset(&p, 0, sizeof p);
    p.width = s.w; p.height = s.h; p.stride = s.w * 2; p.cfa = cfa;
    p.cropX = padX; p.cropY = padY; p.cropW = GW * PS; p.cropH = GH * PS;
    p.iso = 100; p.white = WHITE;
    for (int i = 0; i < 4; i++) p.black[i] = BLACK;
    for (int i = 0; i < 3; i++) p.neutral[i] = (float)s.neutral.v[i];
    p.cal = cal; p.shCols = SC; p.shRows = SR; p.shading = s.shading.data();
    return p;
}

// confere o centro de cada amostra contra a cor original passada pela mesma curva de tons
// scale: o branco da cena no canal mais forte do sensor (a revelação normaliza o branco por ele)
static void verify(const char* name, const std::vector<uint8_t>& out, const RawInfo& info, double scale, int tolGray, int tolColor) {
    int worstG = 0, worstC = 0, worstN = 0;
    for (int gy = 0; gy < GH; gy++) for (int gx = 0; gx < GW; gx++) {
        V L = patch(gx, gy);
        bool gray = L.v[0] == L.v[1] && L.v[1] == L.v[2];
        double acc[3] = {0, 0, 0};
        for (int y = 12; y < 28; y++) for (int x = 12; x < 28; x++) {
            const uint8_t* q = &out[((size_t)(gy * PS + y) * GW * PS + gx * PS + x) * 4];
            for (int c = 0; c < 3; c++) acc[c] += q[c];
        }
        for (int c = 0; c < 3; c++) {
            int got = (int)lround(acc[c] / 256), want = toneForTest((float)(L.v[c] * EXPO * scale), info.gain);
            int e = abs(got - want);
            if (gray) { if (e > worstG) worstG = e; } else if (e > worstC) worstC = e;
        }
        if (gray) { int n = (int)lround(fmax(fabs(acc[0] - acc[1]), fabs(acc[2] - acc[1])) / 256); if (n > worstN) worstN = n; }
    }
    printf("%-34s K=%5.0f tint=%+5.1f ganho=%.2f | erro cinza %d, neutralidade %d, cor %d\n", name, info.kelvin, info.tint, info.gain, worstG, worstN, worstC);
    CHECK(worstG <= tolGray && worstN <= tolGray, "%s: cinzas fora (%d / %d)", name, worstG, worstN);
    CHECK(worstC <= tolColor, "%s: cores fora (%d)", name, worstC);
}

int main() {
    const double temps[] = {2856, 4000, 5500, 7000};
    // 1. Kelvin informado e ponto neutro do sensor, nos 4 padrões CFA, com recorte de origem ímpar
    for (int cfa = 0; cfa < 4; cfa++) {
        double T = temps[cfa];
        int padX = cfa == 1 ? 7 : 8, padY = cfa == 2 ? 5 : 6;
        Shot s = shoot(T, cfa, padX, padY, 1.0);
        for (int fm = 0; fm < 2; fm++) for (int mode = 1; mode <= 2; mode++) {
            RawParams p = params(s, cfa, padX, padY, makeCal(fm));
            p.wbMode = mode; p.kelvin = (float)T;
            std::vector<uint8_t> out((size_t)p.cropW * p.cropH * 4);
            RawInfo info;
            CHECK(develop(s.raw.data(), p, out.data(), p.cropW * 4, &info), "develop retornou false");
            char name[80]; snprintf(name, sizeof name, "cfa%d %4.0fK %s %s", cfa, T, mode == 1 ? "sensor" : "kelvin", fm ? "FM" : "CM");
            // sem matriz direta (CM) a adaptação cromática difere um pouco: tolerância de cor maior
            verify(name, out, info, vmax(s.neutral), 4, fm ? 16 : 22);
            if (mode == 1) CHECK(fabs(info.kelvin - T) < T * 0.04, "%s: K estimado do neutro = %.0f", name, info.kelvin);
        }
    }
    // 2. Automático: sem saber a luz, tem que achar a temperatura pela cena
    for (double T : temps) {
        Shot s = shoot(T, 0, 8, 6, 2.0);
        RawParams p = params(s, 0, 8, 6, makeCal(true));
        p.wbMode = WB_AUTO;
        p.neutral[0] = p.neutral[1] = p.neutral[2] = 0;   // sem ajuda do sensor
        std::vector<uint8_t> out((size_t)p.cropW * p.cropH * 4);
        RawInfo info;
        develop(s.raw.data(), p, out.data(), p.cropW * 4, &info);
        char name[80]; snprintf(name, sizeof name, "auto %4.0fK (conf %.2f)", T, info.confidence);
        verify(name, out, info, vmax(s.neutral), 7, 24);
        CHECK(fabs(1e6 / info.kelvin - 1e6 / T) < 12, "%s: estimou %.0f K", name, info.kelvin);
        CHECK(info.wbUsed == WB_AUTO, "%s: caiu para o sensor", name);
    }
    // 3. Erro de balanço aparece: revelar 2856 K como se fosse 6500 K tem que ficar alaranjado
    {
        Shot s = shoot(2856, 0, 8, 6, 1.0);
        RawParams p = params(s, 0, 8, 6, makeCal(true));
        p.wbMode = WB_KELVIN; p.kelvin = 6500;
        std::vector<uint8_t> out((size_t)p.cropW * p.cropH * 4);
        develop(s.raw.data(), p, out.data(), p.cropW * 4, nullptr);
        const uint8_t* q = &out[((size_t)20 * GW * PS + 20) * 4];   // amostra (0,0) é cinza
        printf("cinza sob 2856K revelado a 6500K: %d %d %d\n", q[0], q[1], q[2]);
        CHECK(q[0] > q[1] + 15 && q[1] > q[2] + 15, "deveria ficar alaranjado");
    }
    // 3b. Acabamento (look=1): cinzas continuam neutros; cor e contraste aumentam
    {
        Shot s = shoot(5500, 0, 8, 6, 2.0);
        RawParams p = params(s, 0, 8, 6, makeCal(true));
        p.wbMode = WB_KELVIN; p.kelvin = 5500;
        std::vector<uint8_t> flat((size_t)p.cropW * p.cropH * 4), punchy(flat.size());
        RawInfo a, b;
        develop(s.raw.data(), p, flat.data(), p.cropW * 4, &a);
        p.look = 1;
        develop(s.raw.data(), p, punchy.data(), p.cropW * 4, &b);
        double satA = 0, satB = 0; int neutral = 0, lo = 255, hi = 0;
        for (int gy = 0; gy < GH; gy++) for (int gx = 0; gx < GW; gx++) {
            V L = patch(gx, gy);
            // média de 16x16 no centro da amostra (um pixel só mediria ruído)
            uint8_t qa[3], qb[3];
            for (int c = 0; c < 3; c++) {
                int sa = 0, sb = 0;
                for (int y = 12; y < 28; y++) for (int x = 12; x < 28; x++) {
                    size_t i = ((size_t)(gy * PS + y) * GW * PS + gx * PS + x) * 4 + c;
                    sa += flat[i]; sb += punchy[i];
                }
                qa[c] = (uint8_t)(sa / 256); qb[c] = (uint8_t)(sb / 256);
            }
            if (L.v[0] == L.v[1] && L.v[1] == L.v[2]) {
                int n = abs(qb[0] - qb[1]) > abs(qb[2] - qb[1]) ? abs(qb[0] - qb[1]) : abs(qb[2] - qb[1]);
                if (n > neutral) neutral = n;
                if (qb[1] < lo) lo = qb[1];
                if (qb[1] > hi) hi = qb[1];
            } else {
                auto sat = [](const uint8_t* q) { int mx = q[0] > q[1] ? (q[0] > q[2] ? q[0] : q[2]) : (q[1] > q[2] ? q[1] : q[2]); int mn = q[0] < q[1] ? (q[0] < q[2] ? q[0] : q[2]) : (q[1] < q[2] ? q[1] : q[2]); return (mx - mn) / (mx + 1.0); };
                satA += sat(qa); satB += sat(qb);
            }
        }
        printf("acabamento: saturação %.0f%% maior, ganho %.2f -> %.2f, cinzas de %d a %d, neutralidade %d\n", (satB / satA - 1) * 100, a.gain, b.gain, lo, hi, neutral);
        CHECK(satB > satA * 1.1, "acabamento deveria saturar mais");
        CHECK(neutral <= 6, "acabamento tirou a neutralidade dos cinzas (%d)", neutral);
        CHECK(b.gain >= a.gain, "acabamento não deveria escurecer");
    }
    // 4. Rotação: tamanhos e cantos
    {
        Shot s = shoot(5500, 0, 0, 0, 0);
        RawParams p = params(s, 0, 0, 0, makeCal(true));
        p.wbMode = WB_KELVIN; p.kelvin = 5500;
        std::vector<uint8_t> base((size_t)p.cropW * p.cropH * 4);
        develop(s.raw.data(), p, base.data(), p.cropW * 4, nullptr);
        for (int rot : {90, 180, 270}) {
            p.rotation = rot;
            int w, h; outputSize(p, &w, &h);
            std::vector<uint8_t> out((size_t)w * h * 4);
            CHECK(develop(s.raw.data(), p, out.data(), w * 4, nullptr), "rotação %d", rot);
            int x = 50, y = 30, ox, oy;   // pixel de origem (x,y) -> destino
            if (rot == 90) { ox = p.cropH - 1 - y; oy = x; } else if (rot == 180) { ox = p.cropW - 1 - x; oy = p.cropH - 1 - y; } else { ox = y; oy = p.cropW - 1 - x; }
            CHECK(memcmp(&base[((size_t)y * p.cropW + x) * 4], &out[((size_t)oy * w + ox) * 4], 4) == 0, "rotação %d: pixel no lugar errado", rot);
            CHECK((rot == 180) == (w == p.cropW), "rotação %d: tamanho %dx%d", rot, w, h);
        }
        printf("rotações ok\n");
    }
    // 5. Parâmetros inválidos são recusados, sem travar
    {
        Shot s = shoot(5500, 0, 0, 0, 0);
        RawParams p = params(s, 0, 0, 0, makeCal(true));
        std::vector<uint8_t> out((size_t)p.cropW * p.cropH * 4);
        p.cropW += 2; CHECK(!develop(s.raw.data(), p, out.data(), p.cropW * 4, nullptr), "recorte maior que o buffer deveria falhar"); p.cropW -= 2;
        p.cfa = 9; CHECK(!develop(s.raw.data(), p, out.data(), p.cropW * 4, nullptr), "cfa inválido deveria falhar"); p.cfa = 0;
        // calibração zerada e sem mapa: ainda revela usando só o neutro do sensor
        memset(&p.cal, 0, sizeof p.cal); p.shading = nullptr; p.wbMode = WB_AUTO;
        RawInfo info;
        CHECK(develop(s.raw.data(), p, out.data(), p.cropW * 4, &info) && info.wbUsed == WB_SENSOR, "sem calibração deveria usar o sensor");
        printf("validação ok\n");
    }
    // imagem para inspeção visual
    if (getenv("DUMP")) {
        Shot s = shoot(3200, 0, 8, 6, 3.0);
        RawParams p = params(s, 0, 8, 6, makeCal(true));
        p.wbMode = WB_AUTO;
        std::vector<uint8_t> out((size_t)p.cropW * p.cropH * 4);
        develop(s.raw.data(), p, out.data(), p.cropW * 4, nullptr);
        FILE* f = fopen(getenv("DUMP"), "wb");
        fprintf(f, "P6\n%d %d\n255\n", p.cropW, p.cropH);
        for (size_t i = 0; i < out.size(); i += 4) fwrite(&out[i], 1, 3, f);
        fclose(f);
    }
    printf(fails ? "%d FALHA(S)\n" : "tudo certo\n", fails);
    return fails ? 1 : 0;
}
