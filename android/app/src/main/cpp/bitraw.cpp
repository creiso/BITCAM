#include "bitraw.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <functional>
#include <thread>
#include <vector>

namespace bitraw {
namespace {

// ---------------------------------------------------------------- álgebra 3x3
struct V3 { double v[3]; };
struct M3 { double m[9]; };

M3 fromF(const float* f) { M3 r; for (int i = 0; i < 9; i++) r.m[i] = f[i]; return r; }
M3 identity() { return M3{{1, 0, 0, 0, 1, 0, 0, 0, 1}}; }
M3 mul(const M3& a, const M3& b) {
    M3 r;
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            r.m[i * 3 + j] = a.m[i * 3] * b.m[j] + a.m[i * 3 + 1] * b.m[3 + j] + a.m[i * 3 + 2] * b.m[6 + j];
    return r;
}
V3 mul(const M3& a, const V3& x) {
    V3 r;
    for (int i = 0; i < 3; i++) r.v[i] = a.m[i * 3] * x.v[0] + a.m[i * 3 + 1] * x.v[1] + a.m[i * 3 + 2] * x.v[2];
    return r;
}
double det(const M3& a) {
    const double* m = a.m;
    return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
}
M3 inv(const M3& a) {
    const double* m = a.m;
    double d = det(a);
    if (std::fabs(d) < 1e-12) return identity();
    double s = 1.0 / d;
    return M3{{(m[4] * m[8] - m[5] * m[7]) * s, (m[2] * m[7] - m[1] * m[8]) * s, (m[1] * m[5] - m[2] * m[4]) * s,
               (m[5] * m[6] - m[3] * m[8]) * s, (m[0] * m[8] - m[2] * m[6]) * s, (m[2] * m[3] - m[0] * m[5]) * s,
               (m[3] * m[7] - m[4] * m[6]) * s, (m[1] * m[6] - m[0] * m[7]) * s, (m[0] * m[4] - m[1] * m[3]) * s}};
}
M3 diag(double a, double b, double c) { return M3{{a, 0, 0, 0, b, 0, 0, 0, c}}; }
M3 lerp(const M3& a, const M3& b, double wa) {
    M3 r;
    for (int i = 0; i < 9; i++) r.m[i] = a.m[i] * wa + b.m[i] * (1 - wa);
    return r;
}
bool finite9(const M3& a) {
    for (double x : a.m) if (!std::isfinite(x)) return false;
    return true;
}

// ---------------------------------------------------------------- colorimetria
const V3 D50 = {{0.9642, 1.0, 0.8251}};
// XYZ (D50) -> sRGB linear, com adaptação de Bradford embutida.
const M3 SRGB_FROM_XYZ_D50 = {{3.1338561, -1.6168667, -0.4906146,
                               -0.9787684, 1.9161415, 0.0334540,
                               0.0719453, -0.2289914, 1.4052427}};

void planckXy(double T, double* x, double* y) {
    T = std::min(25000.0, std::max(1667.0, T));
    double t = 1.0 / T, xc, yc;
    if (T <= 4000) xc = -0.2661239e9 * t * t * t - 0.2343589e6 * t * t + 0.8776956e3 * t + 0.179910;
    else xc = -3.0258469e9 * t * t * t + 2.1070379e6 * t * t + 0.2226347e3 * t + 0.240390;
    double x2 = xc * xc, x3 = x2 * xc;
    if (T <= 2222) yc = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * xc - 0.20219683;
    else if (T <= 4000) yc = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * xc - 0.16748867;
    else yc = 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * xc - 0.37001483;
    *x = xc; *y = yc;
}
void xyToUv(double x, double y, double* u, double* v) {
    double d = -2 * x + 12 * y + 3;
    *u = 4 * x / d; *v = 6 * y / d;
}
void uvToXy(double u, double v, double* x, double* y) {
    double d = 2 * u - 8 * v + 4;
    *x = 3 * u / d; *y = 2 * v / d;
}
// Iluminante: temperatura no locus de Planck + desvio duv (positivo = mais verde).
void illuminantXy(double T, double duv, double* x, double* y) {
    double x0, y0, x1, y1, u0, v0, u1, v1;
    planckXy(T, &x0, &y0);
    planckXy(T * 1.01, &x1, &y1);
    xyToUv(x0, y0, &u0, &v0);
    xyToUv(x1, y1, &u1, &v1);
    double tu = u1 - u0, tv = v1 - v0, n = std::sqrt(tu * tu + tv * tv);
    if (n < 1e-12) { *x = x0; *y = y0; return; }
    // T maior anda para o azul (u menor); a normal "para cima" (verde) é (tv, -tu)/n
    double nu = tv / n, nv = -tu / n;
    if (nv < 0) { nu = -nu; nv = -nv; }
    uvToXy(u0 + nu * duv, v0 + nv * duv, x, y);
}
V3 xyToXYZ(double x, double y) { return V3{{x / y, 1.0, (1 - x - y) / y}}; }
double mccamy(double x, double y) {
    double n = (x - 0.3320) / (0.1858 - y);
    return 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33;
}
double duvOf(double x, double y, double T) {
    double x0, y0, u, v, u0, v0;
    planckXy(T, &x0, &y0);
    xyToUv(x, y, &u, &v);
    xyToUv(x0, y0, &u0, &v0);
    double d = std::sqrt((u - u0) * (u - u0) + (v - v0) * (v - v0));
    return v >= v0 ? d : -d;
}
M3 bradford(const V3& src, const V3& dst) {
    const M3 B = {{0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296}};
    V3 s = mul(B, src), d = mul(B, dst);
    return mul(inv(B), mul(diag(d.v[0] / s.v[0], d.v[1] / s.v[1], d.v[2] / s.v[2]), B));
}

const double TINT_TO_DUV = 0.0004;   // tint de -50..+50 -> duv de -0.02..+0.02

struct ColorModel {
    M3 cm1, cm2, cc1, cc2, fm1, fm2;
    double t1 = 2856, t2 = 6504;
    bool valid = false, hasFm = false;

    explicit ColorModel(const ColorCal& c) {
        cm1 = fromF(c.cm1); cm2 = fromF(c.cm2);
        cc1 = fromF(c.cc1); cc2 = fromF(c.cc2);
        fm1 = fromF(c.fm1); fm2 = fromF(c.fm2);
        t1 = c.t1 > 1000 ? c.t1 : 2856;
        t2 = c.t2 > 1000 ? c.t2 : t1;
        bool ok1 = finite9(cm1) && std::fabs(det(cm1)) > 1e-6;
        bool ok2 = finite9(cm2) && std::fabs(det(cm2)) > 1e-6;
        if (ok1 && !ok2) { cm2 = cm1; cc2 = cc1; fm2 = fm1; t2 = t1; }
        if (ok2 && !ok1) { cm1 = cm2; cc1 = cc2; fm1 = fm2; t1 = t2; }
        valid = ok1 || ok2;
        if (!finite9(cc1) || std::fabs(det(cc1)) < 1e-6) cc1 = identity();
        if (!finite9(cc2) || std::fabs(det(cc2)) < 1e-6) cc2 = identity();
        hasFm = fmOk(fm1) && fmOk(fm2);
    }
    static bool fmOk(const M3& f) {
        if (!finite9(f)) return false;
        V3 w = mul(f, V3{{1, 1, 1}});
        return std::fabs(w.v[0] - D50.v[0]) < 0.06 && std::fabs(w.v[1] - D50.v[1]) < 0.06 && std::fabs(w.v[2] - D50.v[2]) < 0.06;
    }
    // peso da matriz 1, interpolando em 1/T como manda a especificação DNG
    double w1(double T) const {
        if (std::fabs(t1 - t2) < 1) return 1;
        double w = (1 / T - 1 / t2) / (1 / t1 - 1 / t2);
        return std::min(1.0, std::max(0.0, w));
    }
    M3 xyzToCam(double T) const { double w = w1(T); return mul(lerp(cc1, cc2, w), lerp(cm1, cm2, w)); }
    V3 neutral(double T, double duv) const {
        double x, y;
        illuminantXy(T, duv, &x, &y);
        V3 n = mul(xyzToCam(T), xyToXYZ(x, y));
        double mx = std::max(n.v[0], std::max(n.v[1], n.v[2]));
        for (double& c : n.v) c = std::max(1e-4, c / mx);
        return n;
    }
    // Temperatura e duv que explicam um ponto neutro medido.
    void fromNeutral(const V3& n, double* T, double* duv) const {
        double t = 5000, x = 0.3457, y = 0.3585;
        for (int i = 0; i < 8; i++) {
            V3 xyz = mul(inv(xyzToCam(t)), n);
            double s = xyz.v[0] + xyz.v[1] + xyz.v[2];
            if (!(s > 1e-9)) break;
            x = xyz.v[0] / s; y = xyz.v[1] / s;
            double nt = mccamy(x, y);
            if (!std::isfinite(nt)) break;
            t = std::min(15000.0, std::max(1800.0, nt));
        }
        *T = t; *duv = duvOf(x, y, t);
    }
    // Matriz que leva RGB da câmera já balanceado (ganhos g) a sRGB linear; branco -> (1,1,1).
    M3 wbCamToSrgb(double T, const V3& n, const V3& g) const {
        double w = w1(T);
        M3 cc = lerp(cc1, cc2, w), camToXyz;
        if (hasFm) {
            M3 cci = inv(cc);
            V3 rn = mul(cci, n);
            camToXyz = mul(lerp(fm1, fm2, w), mul(diag(1 / rn.v[0], 1 / rn.v[1], 1 / rn.v[2]), cci));
        } else {
            M3 c2x = inv(mul(cc, lerp(cm1, cm2, w)));
            V3 white = mul(c2x, n);
            camToXyz = mul(bradford(white, V3{{D50.v[0] * white.v[1], white.v[1], D50.v[2] * white.v[1]}}), c2x);
        }
        M3 t = mul(SRGB_FROM_XYZ_D50, mul(camToXyz, diag(1 / g.v[0], 1 / g.v[1], 1 / g.v[2])));
        V3 one = mul(t, V3{{1, 1, 1}});
        double s = (one.v[0] + one.v[1] + one.v[2]) / 3;
        if (!(std::fabs(s) > 1e-9)) return identity();
        for (double& x : t.m) x /= s;
        return t;
    }
};

// ---------------------------------------------------------------- utilidades
const int CFA[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};

void parallelRows(int h, const std::function<void(int, int)>& fn) {
    int n = (int)std::thread::hardware_concurrency();
    n = std::max(1, std::min(8, n));
    if (h < 64) n = 1;
    std::vector<std::thread> th;
    for (int i = 0; i < n; i++) {
        int y0 = (int)((long long)h * i / n), y1 = (int)((long long)h * (i + 1) / n);
        if (i == n - 1) fn(y0, y1); else th.emplace_back(fn, y0, y1);
    }
    for (auto& t : th) t.join();
}

inline int mirror(int i, int n) {
    if (i < 0) i = -i;
    if (i >= n) i = 2 * (n - 1) - i;
    return i < 0 ? 0 : i;
}

// Curva de saída: joelho + ombro suave para as altas-luzes, gama sRGB e leve S.
struct Tone {
    std::vector<uint8_t> lut;
    float scale;
    Tone(double gain, double kS) : lut(65536) {
        const double W = std::max(1.0, gain), k = 0.55;
        // inclinação 1 no joelho: (1-k)·s / (1 - e^{-s(W-k)}) = 1
        double s = 0;
        if (W - k > (1 - k) * 1.0001) {
            double lo = 1e-6, hi = 200;
            for (int i = 0; i < 80; i++) {
                double m = (lo + hi) / 2;
                ((1 - k) * m / (1 - std::exp(-m * (W - k))) > 1 ? hi : lo) = m;
            }
            s = (lo + hi) / 2;
        }
        for (int i = 0; i < 65536; i++) {
            double x = i / 65535.0 * W, y;
            if (x <= k || s == 0) y = std::min(1.0, x);
            else y = k + (1 - k) * (1 - std::exp(-s * (x - k))) / (1 - std::exp(-s * (W - k)));
            y = y <= 0.0031308 ? 12.92 * y : 1.055 * std::pow(y, 1 / 2.4) - 0.055;
            y = y + kS * y * (1 - y) * (2 * y - 1);
            lut[i] = (uint8_t)std::lround(std::min(1.0, std::max(0.0, y)) * 255);
        }
        scale = (float)(65535.0 / W);
    }
    inline uint8_t operator()(float linearTimesGain) const {
        float f = linearTimesGain * scale;
        int i = f <= 0 ? 0 : f >= 65535 ? 65535 : (int)(f + 0.5f);
        return lut[i];
    }
};

void boxBlur16(std::vector<int16_t>& a, int w, int h, int r) {
    if (r <= 0) return;
    std::vector<int16_t> t(a.size());
    for (int y = 0; y < h; y++) {
        const int16_t* s = &a[(size_t)y * w];
        int16_t* d = &t[(size_t)y * w];
        int sum = 0, cnt = 0;
        for (int x = 0; x <= r && x < w; x++) { sum += s[x]; cnt++; }
        for (int x = 0; x < w; x++) {
            d[x] = (int16_t)(sum / cnt);
            if (x + r + 1 < w) { sum += s[x + r + 1]; cnt++; }
            if (x - r >= 0) { sum -= s[x - r]; cnt--; }
        }
    }
    for (int x = 0; x < w; x++) {
        int sum = 0, cnt = 0;
        for (int y = 0; y <= r && y < h; y++) { sum += t[(size_t)y * w + x]; cnt++; }
        for (int y = 0; y < h; y++) {
            a[(size_t)y * w + x] = (int16_t)(sum / cnt);
            if (y + r + 1 < h) { sum += t[(size_t)(y + r + 1) * w + x]; cnt++; }
            if (y - r >= 0) { sum -= t[(size_t)(y - r) * w + x]; cnt--; }
        }
    }
}

struct Block { float lr, lb, w, r, g, b; };

} // namespace

void outputSize(const RawParams& p, int* w, int* h) {
    bool swap = p.rotation == 90 || p.rotation == 270;
    *w = swap ? p.cropH : p.cropW;
    *h = swap ? p.cropW : p.cropH;
}

bool neutralFor(const ColorCal& cal, float kelvin, float tint, float out[3]) {
    ColorModel cm(cal);
    if (!cm.valid) return false;
    V3 n = cm.neutral(kelvin, tint * TINT_TO_DUV);
    for (int i = 0; i < 3; i++) out[i] = (float)n.v[i];
    return true;
}

int toneForTest(float linear, float gain) { Tone t(gain, 0.20); return t(linear * gain); }

// Fonte: raw (uint16, com stride em bytes) ou rawF (float, width por linha: resultado da fusão).
static bool developImpl(const uint16_t* raw, const float* rawF, const RawParams& p, uint8_t* out, int outStride, RawInfo* info) {
    const int W = p.cropW, H = p.cropH;
    if ((!raw && !rawF) || !out || W < 8 || H < 8 || p.cropX < 0 || p.cropY < 0 || p.cropX + W > p.width || p.cropY + H > p.height) return false;
    if (p.cfa < 0 || p.cfa > 3 || (raw && p.stride < p.width * 2)) return false;
    if (p.rotation != 0 && p.rotation != 90 && p.rotation != 180 && p.rotation != 270) return false;

    // cor (0 R, 1 G, 2 B) e posição no bloco 2x2 de um pixel do recorte
    auto colorAt = [&](int x, int y) { return CFA[p.cfa][(((y + p.cropY) & 1) << 1) | ((x + p.cropX) & 1)]; };

    // -------- 1. linearização: preto, branco e sombreamento da lente
    std::vector<float> plane((size_t)W * H);
    float invRange[4];
    for (int i = 0; i < 4; i++) {
        float range = p.white - p.black[i];
        invRange[i] = range > 1 ? 1.0f / range : 1.0f / 1023.0f;
    }
    const bool shade = p.shading && p.shCols >= 2 && p.shRows >= 2;
    parallelRows(H, [&](int y0, int y1) {
        std::vector<float> gr(shade ? p.shCols * 3 : 0);
        for (int y = y0; y < y1; y++) {
            const int ry = y + p.cropY;
            const uint16_t* src = raw ? (const uint16_t*)((const uint8_t*)raw + (size_t)ry * p.stride) + p.cropX : nullptr;
            const float* srcF = raw ? nullptr : rawF + (size_t)ry * p.width + p.cropX;
            float* dst = &plane[(size_t)y * W];
            if (shade) {   // linha do mapa interpolada na vertical: [coluna][R,G,B]
                float fy = (float)ry / (p.height - 1) * (p.shRows - 1);
                int iy = std::min(p.shRows - 2, (int)fy);
                float wy = fy - iy;
                const float* a = p.shading + (size_t)iy * p.shCols * 4;
                const float* b = a + (size_t)p.shCols * 4;
                for (int c = 0; c < p.shCols; c++) {
                    gr[c * 3] = a[c * 4] * (1 - wy) + b[c * 4] * wy;
                    gr[c * 3 + 1] = 0.5f * ((a[c * 4 + 1] + a[c * 4 + 2]) * (1 - wy) + (b[c * 4 + 1] + b[c * 4 + 2]) * wy);
                    gr[c * 3 + 2] = a[c * 4 + 3] * (1 - wy) + b[c * 4 + 3] * wy;
                }
            }
            const float sx = shade ? (float)(p.shCols - 1) / (p.width - 1) : 0;
            for (int x = 0; x < W; x++) {
                int pos = ((ry & 1) << 1) | ((x + p.cropX) & 1);
                float v = ((src ? (float)src[x] : srcF[x]) - p.black[pos]) * invRange[pos];
                if (v < 0) v = 0;
                if (shade) {
                    float fx = (x + p.cropX) * sx;
                    int ix = std::min(p.shCols - 2, (int)fx);
                    float wx = fx - ix;
                    int c = CFA[p.cfa][pos];
                    v *= gr[ix * 3 + c] * (1 - wx) + gr[(ix + 1) * 3 + c] * wx;
                }
                dst[x] = v;
            }
        }
    });

    // -------- 2. estatísticas em blocos (espaço da câmera, antes do balanço de branco)
    const int BS = std::max(8, ((std::max(W, H) / 80) + 1) & ~1);
    const int BW = W / BS, BH = H / BS;
    std::vector<Block> blocks;
    blocks.reserve((size_t)BW * BH);
    for (int by = 0; by < BH; by++) {
        for (int bx = 0; bx < BW; bx++) {
            double s[3] = {0, 0, 0};
            int n[3] = {0, 0, 0};
            float mx = 0;
            for (int y = by * BS; y < (by + 1) * BS; y += 2) {
                for (int x = bx * BS; x < (bx + 1) * BS; x += 2) {
                    for (int k = 0; k < 4; k++) {
                        int xx = x + (k & 1), yy = y + (k >> 1);
                        float v = plane[(size_t)yy * W + xx];
                        int c = colorAt(xx, yy);
                        s[c] += v; n[c]++;
                        if (v > mx) mx = v;
                    }
                }
            }
            if (!n[0] || !n[1] || !n[2]) continue;
            float r = (float)(s[0] / n[0]), g = (float)(s[1] / n[1]), b = (float)(s[2] / n[2]);
            Block bl;
            bl.r = r; bl.g = g; bl.b = b;
            // blocos estourados ou escuros demais não servem para estimar cor
            bl.w = (mx > 0.92f || g < 0.012f || r < 1e-4f || b < 1e-4f) ? 0 : std::sqrt(g);
            bl.lr = bl.w > 0 ? std::log(r / g) : 0;
            bl.lb = bl.w > 0 ? std::log(b / g) : 0;
            blocks.push_back(bl);
        }
    }

    // -------- 3. balanço de branco
    ColorModel cm(p.cal);
    V3 sensorN = {{p.neutral[0], p.neutral[1], p.neutral[2]}};
    bool sensorOk = sensorN.v[0] > 1e-3 && sensorN.v[1] > 1e-3 && sensorN.v[2] > 1e-3;
    if (sensorOk) {
        double mx = std::max(sensorN.v[0], std::max(sensorN.v[1], sensorN.v[2]));
        for (double& c : sensorN.v) c /= mx;
    }
    double sensorT = 5000, sensorDuv = 0;
    if (cm.valid && sensorOk) cm.fromNeutral(sensorN, &sensorT, &sensorDuv);

    V3 n = {{1, 1, 1}};
    double T = 5000, duv = 0, conf = 0;
    int mode = p.wbMode;
    if (!cm.valid) mode = WB_SENSOR;   // sem calibração só resta o ponto neutro do sensor

    if (mode == WB_AUTO) {
        // Para cada iluminante plausível, conta quanto da cena fica neutra sob ele.
        // Priors: luz do dia e a estimativa do próprio sensor (fracos, só desempatam).
        double totalW = 0;
        for (const Block& b : blocks) totalW += b.w;
        double best = -1;
        const double sig2 = 2 * 0.075 * 0.075;
        for (double mired = 105; mired <= 440; mired += 5) {
            double t = 1e6 / mired;
            for (int di = -3; di <= 3; di++) {
                double d = di * 0.004;
                V3 c = cm.neutral(t, d);
                double a = std::log(c.v[0] / c.v[1]), bb = std::log(c.v[2] / c.v[1]), sc = 0;
                for (const Block& b : blocks) {
                    if (b.w <= 0) continue;
                    double e = (b.lr - a) * (b.lr - a) + (b.lb - bb) * (b.lb - bb);
                    if (e < 0.12) sc += b.w * std::exp(-e / sig2);
                }
                double pd = std::exp(-(mired - 190) * (mired - 190) / (2 * 110.0 * 110.0)) * std::exp(-d * d / (2 * 0.008 * 0.008));
                double ps = sensorOk ? std::exp(-(mired - 1e6 / sensorT) * (mired - 1e6 / sensorT) / (2 * 80.0 * 80.0)) : pd;
                double total = sc * (0.4 + 0.3 * pd + 0.3 * ps);
                if (total > best) { best = total; T = t; duv = d; conf = totalW > 0 ? sc / totalW : 0; }
            }
        }
        if (conf < 0.04 && sensorOk) mode = WB_SENSOR;   // cena sem nada neutro: confia no sensor
        else n = cm.neutral(T, duv);
    }
    if (mode == WB_KELVIN) {
        T = std::min(15000.0f, std::max(1800.0f, p.kelvin));
        duv = p.tint * TINT_TO_DUV;
        n = cm.neutral(T, duv);
    }
    if (mode == WB_SENSOR) {
        if (sensorOk) { n = sensorN; T = sensorT; duv = sensorDuv; }
        else if (cm.valid) { T = 5200; duv = 0; n = cm.neutral(T, duv); }
    }
    double nmax = std::max(n.v[0], std::max(n.v[1], n.v[2]));
    V3 g = {{nmax / n.v[0], nmax / n.v[1], nmax / n.v[2]}};   // ganhos >= 1: realce estourado vira branco
    M3 M = cm.valid ? cm.wbCamToSrgb(T, n, g) : identity();
    float m[9];
    for (int i = 0; i < 9; i++) m[i] = (float)M.m[i];

    // -------- 4. exposição: leva a média logarítmica da cena a um cinza médio
    const double look = std::min(1.0f, std::max(0.0f, p.look));
    double ls = 0;
    std::vector<float> lums;
    lums.reserve(blocks.size());
    for (const Block& b : blocks) {
        double yl = 0.25 * std::min(1.0, b.r * g.v[0]) + 0.6 * std::min(1.0, b.g * g.v[1]) + 0.15 * std::min(1.0, b.b * g.v[2]);
        ls += std::log(std::max(1e-4, yl));
        lums.push_back((float)yl);
    }
    double key = lums.empty() ? 0.16 : std::exp(ls / lums.size());
    double want = 0.16 / key;
    if (look > 0 && !lums.empty()) {
        // cena clara (parede branca, céu): o que é branco tem que chegar perto do branco
        size_t k = (size_t)(lums.size() * 0.99);
        std::nth_element(lums.begin(), lums.begin() + k, lums.end());
        double hi = std::max(0.05f, lums[k]);
        want = std::max(want, 1 + look * (std::min(1.4, 0.92 / hi) - 1));
    }
    float gain = (float)std::min(3.0, std::max(1.0, want));
    Tone tone(gain, 0.20 + 0.25 * look);

    // balanço de branco aplicado no próprio mosaico, com corte em 1
    const float gf[3] = {(float)g.v[0], (float)g.v[1], (float)g.v[2]};
    parallelRows(H, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            float* d = &plane[(size_t)y * W];
            for (int x = 0; x < W; x++) {
                float v = d[x] * gf[colorAt(x, y)];
                d[x] = v > 1 ? 1 : v;
            }
        }
    });

    // -------- 5. demosaico (Malvar-He-Cutler) + matriz de cor + curva de tons
    std::vector<uint8_t> rgb((size_t)W * H * 3);
    parallelRows(H, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* r[5];
            for (int k = 0; k < 5; k++) r[k] = &plane[(size_t)mirror(y + k - 2, H) * W];
            uint8_t* o = &rgb[(size_t)y * W * 3];
            for (int x = 0; x < W; x++, o += 3) {
                int xm2, xm1, xp1, xp2;
                if (x >= 2 && x < W - 2) { xm2 = x - 2; xm1 = x - 1; xp1 = x + 1; xp2 = x + 2; }
                else { xm2 = mirror(x - 2, W); xm1 = mirror(x - 1, W); xp1 = mirror(x + 1, W); xp2 = mirror(x + 2, W); }
                float c = r[2][x];
                float N = r[1][x], S = r[3][x], Ee = r[2][xp1], Ww = r[2][xm1];
                float N2 = r[0][x], S2 = r[4][x], E2 = r[2][xp2], W2 = r[2][xm2];
                float D = r[1][xm1] + r[1][xp1] + r[3][xm1] + r[3][xp1];
                int col = colorAt(x, y);
                float R, G, B;
                if (col == 1) {
                    float hz = (5 * c + 4 * (Ee + Ww) - D - (E2 + W2) + 0.5f * (N2 + S2)) * 0.125f;
                    float vt = (5 * c + 4 * (N + S) - D - (N2 + S2) + 0.5f * (E2 + W2)) * 0.125f;
                    G = c;
                    if (colorAt(x + 1, y) == 0) { R = hz; B = vt; } else { B = hz; R = vt; }
                } else {
                    G = (4 * c + 2 * (N + S + Ee + Ww) - (N2 + S2 + E2 + W2)) * 0.125f;
                    float ot = (6 * c + 2 * D - 1.5f * (N2 + S2 + E2 + W2)) * 0.125f;
                    if (col == 0) { R = c; B = ot; } else { B = c; R = ot; }
                }
                if (R < 0) R = 0;
                if (G < 0) G = 0;
                if (B < 0) B = 0;
                o[0] = tone((m[0] * R + m[1] * G + m[2] * B) * gain);
                o[1] = tone((m[3] * R + m[4] * G + m[5] * B) * gain);
                o[2] = tone((m[6] * R + m[7] * G + m[8] * B) * gain);
            }
        }
    });
    std::vector<float>().swap(plane);

    // -------- 6. ruído de cor (croma em meia resolução), nitidez leve na luma, rotação
    std::vector<uint8_t> Y((size_t)W * H);
    const int hw = (W + 1) / 2, hh = (H + 1) / 2;
    std::vector<int16_t> cb((size_t)hw * hh), cr((size_t)hw * hh);
    parallelRows(hh, [&](int y0, int y1) {
        for (int cy = y0; cy < y1; cy++) {
            for (int cx = 0; cx < hw; cx++) {
                int sb = 0, sr = 0, cnt = 0;
                for (int k = 0; k < 4; k++) {
                    int x = cx * 2 + (k & 1), y = cy * 2 + (k >> 1);
                    if (x >= W || y >= H) continue;
                    const uint8_t* q = &rgb[((size_t)y * W + x) * 3];
                    int yl = (q[0] * 77 + q[1] * 150 + q[2] * 29 + 128) >> 8;
                    Y[(size_t)y * W + x] = (uint8_t)yl;
                    sb += q[2] - yl; sr += q[0] - yl; cnt++;
                }
                cb[(size_t)cy * hw + cx] = (int16_t)(sb / cnt);
                cr[(size_t)cy * hw + cx] = (int16_t)(sr / cnt);
            }
        }
    });
    std::vector<uint8_t>().swap(rgb);
    const int rad = p.iso >= 3200 ? 3 : p.iso >= 800 ? 2 : p.iso >= 200 ? 1 : 0;
    boxBlur16(cb, hw, hh, rad); boxBlur16(cr, hw, hh, rad);
    if (rad > 1) { boxBlur16(cb, hw, hh, rad); boxBlur16(cr, hw, hh, rad); }

    // ruído de luminância: média só dos vizinhos 5x5 parecidos com o centro (preserva bordas)
    {
        const int thr = p.iso >= 1600 ? 11 : p.iso >= 800 ? 9 : p.iso >= 400 ? 7 : p.iso >= 200 ? 5 : 4;
        std::vector<uint8_t> src(Y);
        parallelRows(H, [&](int y0, int y1) {
            for (int y = std::max(2, y0); y < std::min(H - 2, y1); y++) {
                const uint8_t* r[5];
                for (int k = 0; k < 5; k++) r[k] = &src[(size_t)(y + k - 2) * W];
                uint8_t* d = &Y[(size_t)y * W];
                for (int x = 2; x < W - 2; x++) {
                    int c = r[2][x], sum = 0, cnt = 0;
                    for (int k = 0; k < 5; k++) {
                        const uint8_t* q = r[k] + x - 2;
                        for (int j = 0; j < 5; j++) {
                            int v = q[j], e = v - c;
                            if (e <= thr && e >= -thr) { sum += v; cnt++; }
                        }
                    }
                    d[x] = (uint8_t)((sum + cnt / 2) / cnt);
                }
            }
        });
    }

    const float amt = 0.6f + 0.3f * (float)look, core = p.iso >= 1600 ? 4.0f : p.iso >= 400 ? 2.5f : 1.5f;
    const float sat = 1.10f + 0.25f * (float)look;
    const int rot = p.rotation;
    const int outW = (rot == 90 || rot == 270) ? H : W;
    parallelRows(H, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint8_t* c1 = &Y[(size_t)y * W];
            const uint8_t* c0 = y > 0 ? c1 - W : c1;
            const uint8_t* c2 = y < H - 1 ? c1 + W : c1;
            for (int x = 0; x < W; x++) {
                int xa = x > 0 ? x - 1 : x, xb = x < W - 1 ? x + 1 : x;
                float yc = c1[x];
                float d = yc - (4 * yc + 2 * (c1[xa] + c1[xb] + c0[x] + c2[x]) + c0[xa] + c0[xb] + c2[xa] + c2[xb]) * (1.0f / 16);
                d = d > core ? d - core : d < -core ? d + core : 0;
                if (d > 24) d = 24; else if (d < -24) d = -24;
                float yl = yc + d * amt;
                size_t ci = (size_t)(y >> 1) * hw + (x >> 1);
                float fb = cb[ci] * sat, fr = cr[ci] * sat;
                float R = yl + fr, B = yl + fb, G = yl - 0.5094f * fr - 0.1942f * fb;
                int ox, oy;
                if (rot == 0) { ox = x; oy = y; }
                else if (rot == 90) { ox = H - 1 - y; oy = x; }
                else if (rot == 180) { ox = W - 1 - x; oy = H - 1 - y; }
                else { ox = y; oy = W - 1 - x; }
                if (p.mirror) ox = outW - 1 - ox;
                uint8_t* o = out + (size_t)oy * outStride + (size_t)ox * 4;
                o[0] = (uint8_t)(R < 0 ? 0 : R > 255 ? 255 : (int)(R + 0.5f));
                o[1] = (uint8_t)(G < 0 ? 0 : G > 255 ? 255 : (int)(G + 0.5f));
                o[2] = (uint8_t)(B < 0 ? 0 : B > 255 ? 255 : (int)(B + 0.5f));
                o[3] = 255;
            }
        }
    });

    if (info) {
        info->kelvin = (float)T;
        info->tint = (float)(duv / TINT_TO_DUV);
        info->gain = gain;
        info->wbUsed = mode;
        info->confidence = (float)conf;
    }
    return true;
}

bool develop(const uint16_t* raw, const RawParams& p, uint8_t* out, int outStride, RawInfo* info) {
    return developImpl(raw, nullptr, p, out, outStride, info);
}

// ================================================================ fusão de burst
namespace {

// Cinza de meia resolução: média de cada bloco Bayer 2x2. Deslocamentos inteiros aqui valem
// 2 px no RAW, o que preserva a fase do mosaico.
struct Gray {
    int w = 0, h = 0;
    std::vector<uint16_t> d;
};

Gray grayHalf(const uint16_t* raw, int width, int height, int stride) {
    Gray g;
    g.w = width / 2; g.h = height / 2;
    g.d.resize((size_t)g.w * g.h);
    parallelRows(g.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint16_t* a = (const uint16_t*)((const uint8_t*)raw + (size_t)(2 * y) * stride);
            const uint16_t* b = (const uint16_t*)((const uint8_t*)raw + (size_t)(2 * y + 1) * stride);
            uint16_t* o = &g.d[(size_t)y * g.w];
            for (int x = 0; x < g.w; x++) o[x] = (uint16_t)((a[2 * x] + a[2 * x + 1] + b[2 * x] + b[2 * x + 1] + 2) >> 2);
        }
    });
    return g;
}

Gray down4(const Gray& s) {
    Gray g;
    g.w = s.w / 4; g.h = s.h / 4;
    g.d.resize((size_t)g.w * g.h);
    for (int y = 0; y < g.h; y++) {
        for (int x = 0; x < g.w; x++) {
            uint32_t sum = 0;
            for (int k = 0; k < 4; k++) {
                const uint16_t* q = &s.d[(size_t)(y * 4 + k) * s.w + x * 4];
                sum += q[0] + q[1] + q[2] + q[3];
            }
            g.d[(size_t)y * g.w + x] = (uint16_t)((sum + 8) >> 4);
        }
    }
    return g;
}

inline uint32_t sadRow(const uint16_t* a, const uint16_t* b, int n) {
    uint32_t s = 0;
    for (int i = 0; i < n; i++) { int d = (int)a[i] - (int)b[i]; s += (uint32_t)(d < 0 ? -d : d); }
    return s;
}

// Deslocamento global (dx,dy) tal que alt[y+dy][x+dx] ≈ ref[y][x], procurado em torno de (cx,cy).
void searchGlobal(const Gray& ref, const Gray& alt, int cx, int cy, int r, int* bx, int* by) {
    const int W = ref.w, H = ref.h;
    const int m = std::max(std::abs(cx), std::abs(cy)) + r + 1;
    *bx = cx; *by = cy;
    if (W - 2 * m < 8 || H - 2 * m < 8) return;
    double best = 1e300;
    for (int dy = cy - r; dy <= cy + r; dy++) {
        for (int dx = cx - r; dx <= cx + r; dx++) {
            uint64_t s = 0;
            for (int y = m; y < H - m; y++) s += sadRow(&ref.d[(size_t)y * W + m], &alt.d[(size_t)(y + dy) * W + m + dx], W - 2 * m);
            double c = (double)s * (1 + 0.002 * (std::abs(dx - cx) + std::abs(dy - cy)));
            if (c < best) { best = c; *bx = dx; *by = dy; }
        }
    }
}

struct Field {
    int ts = 0, tw = 0, th = 0;       // tamanho do bloco e quantos blocos
    std::vector<int16_t> dx, dy;
};

// Alinhamento por blocos: cada bloco procura o melhor deslocamento perto do palpite inicial.
// Lida com rotação leve da mão e paralaxe, que um deslocamento global não corrige.
template <class Init>
Field alignTiles(const Gray& ref, const Gray& alt, int ts, int r, Init init) {
    Field f;
    f.ts = ts;
    f.tw = (ref.w + ts - 1) / ts; f.th = (ref.h + ts - 1) / ts;
    f.dx.assign((size_t)f.tw * f.th, 0); f.dy.assign((size_t)f.tw * f.th, 0);
    const int W = ref.w, H = ref.h;
    parallelRows(f.th, [&](int t0, int t1) {
        for (int ty = t0; ty < t1; ty++) {
            for (int tx = 0; tx < f.tw; tx++) {
                int x0 = tx * ts, y0 = ty * ts;
                int w = std::min(ts, W - x0), h = std::min(ts, H - y0);
                int ix = 0, iy = 0;
                init(tx, ty, &ix, &iy);
                double best = 1e300;
                int bx = ix, by = iy;
                bool found = false;
                for (int dy = iy - r; dy <= iy + r; dy++) {
                    if (y0 + dy < 0 || y0 + h + dy > H) continue;
                    for (int dx = ix - r; dx <= ix + r; dx++) {
                        if (x0 + dx < 0 || x0 + w + dx > W) continue;
                        uint32_t s = 0;
                        for (int y = 0; y < h; y++) s += sadRow(&ref.d[(size_t)(y0 + y) * W + x0], &alt.d[(size_t)(y0 + y + dy) * W + x0 + dx], w);
                        // em área lisa o ruído decide; o viés mantém o palpite inicial
                        double c = (double)s * (1 + 0.01 * (std::abs(dx - ix) + std::abs(dy - iy))) + 1e-3 * (std::abs(dx - ix) + std::abs(dy - iy));
                        if (c < best) { best = c; bx = dx; by = dy; found = true; }
                    }
                }
                if (!found) { bx = 0; by = 0; }
                f.dx[(size_t)ty * f.tw + tx] = (int16_t)bx;
                f.dy[(size_t)ty * f.tw + tx] = (int16_t)by;
            }
        }
    });
    return f;
}

double sharpness(const Gray& g) {
    double s = 0;
    for (int y = 0; y < g.h - 1; y++) {
        const uint16_t* a = &g.d[(size_t)y * g.w];
        for (int x = 0; x < g.w - 1; x++) s += std::abs((int)a[x] - (int)a[x + 1]) + std::abs((int)a[x] - (int)a[x + g.w]);
    }
    return s;
}

// Diferença esperada entre dois quadros só por ruído, em função do tom, medida nos próprios dados
// (mediana, que ignora o que se mexeu). absDiff = soma das diferenças absolutas das 4 amostras Bayer.
std::vector<float> noiseCurve(const Gray& ref, const std::vector<uint16_t>& absDiff, float black, float white, float* mid) {
    const int NB = 32, HB = 1024;
    std::vector<uint32_t> hist((size_t)NB * HB, 0);
    const float range = std::max(16.0f, white - black);
    auto binOf = [&](float v) {
        float l = (v - black) / range;
        l = l < 0 ? 0 : l > 1 ? 1 : l;
        return std::sqrt(l) * (NB - 1);
    };
    for (int y = 0; y < ref.h; y += 2) {
        for (int x = 0; x < ref.w; x += 2) {
            size_t i = (size_t)y * ref.w + x;
            uint16_t a = absDiff[i];
            if (a == 0xFFFF) continue;
            int b = (int)(binOf(ref.d[i]) + 0.5f);
            hist[(size_t)b * HB + std::min<int>(a, HB - 1)]++;
        }
    }
    float sig[NB];
    bool ok[NB];
    bool any = false;
    for (int b = 0; b < NB; b++) {
        const uint32_t* hrow = &hist[(size_t)b * HB];
        uint64_t total = 0;
        for (int k = 0; k < HB; k++) total += hrow[k];
        ok[b] = total >= 300;
        sig[b] = 0;
        if (!ok[b]) continue;
        uint64_t half = total / 2, cum = 0;
        for (int k = 0; k < HB; k++) {
            if (cum + hrow[k] > half) {
                double med = k - 0.5 + (double)(half - cum) / hrow[k];
                sig[b] = (float)std::max(2.0, med);
                break;
            }
            cum += hrow[k];
        }
        any = true;
    }
    for (int b = 0; b < NB; b++) {   // preenche tons sem amostras com o vizinho válido mais próximo
        if (ok[b] || !any) continue;
        for (int d = 1; d < NB; d++) {
            if (b - d >= 0 && ok[b - d]) { sig[b] = sig[b - d]; break; }
            if (b + d < NB && ok[b + d]) { sig[b] = sig[b + d]; break; }
        }
    }
    if (!any) for (int b = 0; b < NB; b++) sig[b] = 16;
    for (int b = 1; b < NB; b++) sig[b] = std::max(sig[b], sig[b - 1] * 0.85f);   // ruído não cai muito com a luz
    int top = (int)std::min(65535.0f, std::max(white, 255.0f));
    std::vector<float> lut((size_t)top + 1);
    for (int v = 0; v <= top; v++) {
        float fb = binOf((float)v);
        int b0 = std::min(NB - 2, (int)fb);
        float t = fb - b0;
        lut[v] = sig[b0] * (1 - t) + sig[b0 + 1] * t;
    }
    // soma de 4 |diferenças| -> desvio-padrão de uma amostra de um quadro: /4, /0,8 (média do módulo), /raiz de 2
    if (mid) *mid = sig[(int)(std::sqrt(0.18f) * (NB - 1))] / (4 * 0.798f * 1.414f);
    return lut;
}

bool mergeBurst(const uint16_t* const* frames, int n, const RawParams& p, std::vector<float>& out, BurstInfo* info) {
    const int W = p.width, H = p.height;
    const int ew = W & ~1, eh = H & ~1;   // área coberta por blocos 2x2 inteiros

    // referência = o mais nítido dos três primeiros (o toque no botão costuma tremer o primeiro)
    int refIdx = 0;
    {
        double best = -1;
        for (int i = 0; i < std::min(3, n); i++) {
            double s = sharpness(down4(grayHalf(frames[i], W, H, p.stride)));
            if (s > best) { best = s; refIdx = i; }
        }
    }
    const uint16_t* refRaw = frames[refIdx];
    Gray r0 = grayHalf(refRaw, W, H, p.stride), r1 = down4(r0), r2 = down4(r1);
    const int GW = r0.w, GH = r0.h;

    out.resize((size_t)W * H);
    parallelRows(H, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint16_t* s = (const uint16_t*)((const uint8_t*)refRaw + (size_t)y * p.stride);
            float* d = &out[(size_t)y * W];
            for (int x = 0; x < W; x++) d[x] = s[x];
        }
    });
    std::vector<float> wsum((size_t)GW * GH, 1.0f);
    std::vector<uint16_t> ad((size_t)GW * GH);
    std::vector<float> sigma;
    float black = (p.black[0] + p.black[1] + p.black[2] + p.black[3]) * 0.25f, midNoise = 0;

    for (int i = 0; i < n; i++) {
        if (i == refIdx) continue;
        const uint16_t* altRaw = frames[i];
        Gray a0 = grayHalf(altRaw, W, H, p.stride), a1 = down4(a0), a2 = down4(a1);

        int gx = 0, gy = 0;
        searchGlobal(r2, a2, 0, 0, 6, &gx, &gy);
        searchGlobal(r1, a1, gx * 4, gy * 4, 3, &gx, &gy);
        Field f1 = alignTiles(r1, a1, 16, 3, [&](int, int, int* ix, int* iy) { *ix = gx; *iy = gy; });
        Field f0 = alignTiles(r0, a0, 32, 3, [&](int tx, int ty, int* ix, int* iy) {
            int px = std::min(f1.tw - 1, tx / 2), py = std::min(f1.th - 1, ty / 2);
            *ix = f1.dx[(size_t)py * f1.tw + px] * 4;
            *iy = f1.dy[(size_t)py * f1.tw + px] * 4;
        });

        // diferença contra a referência, já com o deslocamento de cada bloco: soma dos módulos das
        // 4 amostras Bayer. Comparar cada cor (e não o cinza) pega objetos que mudam de cor sem mudar de brilho.
        parallelRows(f0.th, [&](int t0, int t1) {
            for (int ty = t0; ty < t1; ty++) {
                for (int tx = 0; tx < f0.tw; tx++) {
                    int dx = f0.dx[(size_t)ty * f0.tw + tx], dy = f0.dy[(size_t)ty * f0.tw + tx];
                    int x1 = std::min(GW, (tx + 1) * 32), yEnd = std::min(GH, (ty + 1) * 32);
                    for (int y = ty * 32; y < yEnd; y++) {
                        int ay = y + dy;
                        const uint16_t* rr0 = (const uint16_t*)((const uint8_t*)refRaw + (size_t)(2 * y) * p.stride);
                        const uint16_t* rr1 = (const uint16_t*)((const uint8_t*)refRaw + (size_t)(2 * y + 1) * p.stride);
                        const bool rowOk = ay >= 0 && ay < GH;
                        const uint16_t* aa0 = rowOk ? (const uint16_t*)((const uint8_t*)altRaw + (size_t)(2 * ay) * p.stride) : nullptr;
                        const uint16_t* aa1 = rowOk ? (const uint16_t*)((const uint8_t*)altRaw + (size_t)(2 * ay + 1) * p.stride) : nullptr;
                        for (int x = tx * 32; x < x1; x++) {
                            int ax = x + dx;
                            size_t k = (size_t)y * GW + x;
                            if (!rowOk || ax < 0 || ax >= GW) { ad[k] = 0xFFFF; continue; }
                            int d0 = (int)rr0[2 * x] - (int)aa0[2 * ax], d1 = (int)rr0[2 * x + 1] - (int)aa0[2 * ax + 1];
                            int d2 = (int)rr1[2 * x] - (int)aa1[2 * ax], d3 = (int)rr1[2 * x + 1] - (int)aa1[2 * ax + 1];
                            int d = std::abs(d0) + std::abs(d1) + std::abs(d2) + std::abs(d3);
                            ad[k] = (uint16_t)std::min(0xFFFE, d);
                        }
                    }
                }
            }
        });
        if (sigma.empty()) sigma = noiseCurve(r0, ad, black, p.white, &midNoise);
        const int top = (int)sigma.size() - 1;

        // peso por pixel: a diferença média 3x3 (36 amostras) comparada à esperada só por ruído.
        // Ruído puro dá ~1,0 (com desvio ~0,13); acima de 1,45 começa a desconfiar; em 2,1 o quadro é ignorado ali.
        parallelRows(f0.th, [&](int t0, int t1) {
            for (int ty = t0; ty < t1; ty++) {
                for (int tx = 0; tx < f0.tw; tx++) {
                    int dx = f0.dx[(size_t)ty * f0.tw + tx] * 2, dy = f0.dy[(size_t)ty * f0.tw + tx] * 2;
                    int x1 = std::min(GW, (tx + 1) * 32), yEnd = std::min(GH, (ty + 1) * 32);
                    for (int y = ty * 32; y < yEnd; y++) {
                        int ya = y > 0 ? y - 1 : y, yb = y < GH - 1 ? y + 1 : y;
                        for (int x = tx * 32; x < x1; x++) {
                            int xa = x > 0 ? x - 1 : x, xb = x < GW - 1 ? x + 1 : x;
                            uint32_t e = (uint32_t)ad[(size_t)ya * GW + xa] + ad[(size_t)ya * GW + x] + ad[(size_t)ya * GW + xb] +
                                         ad[(size_t)y * GW + xa] + ad[(size_t)y * GW + x] + ad[(size_t)y * GW + xb] +
                                         ad[(size_t)yb * GW + xa] + ad[(size_t)yb * GW + x] + ad[(size_t)yb * GW + xb];
                            if (e >= 0xFFFF) continue;   // algum vizinho fora do quadro
                            float sg = sigma[std::min<int>(top, r0.d[(size_t)y * GW + x])];
                            float w = (2.1f - (e * (1.0f / 9)) / sg) * (1.0f / 0.65f);
                            if (w <= 0) continue;
                            if (w > 1) w = 1;
                            wsum[(size_t)y * GW + x] += w;
                            for (int j = 0; j < 2; j++) {
                                const uint16_t* s = (const uint16_t*)((const uint8_t*)altRaw + (size_t)(2 * y + j + dy) * p.stride) + 2 * x + dx;
                                float* d = &out[(size_t)(2 * y + j) * W + 2 * x];
                                d[0] += w * s[0];
                                d[1] += w * s[1];
                            }
                        }
                    }
                }
            }
        });
    }

    double total = 0;
    for (int y = 0; y < GH; y++) {
        double rowSum = 0;
        for (int x = 0; x < GW; x++) rowSum += wsum[(size_t)y * GW + x];
        total += rowSum;
    }
    parallelRows(eh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            float* d = &out[(size_t)y * W];
            const float* ws = &wsum[(size_t)(y >> 1) * GW];
            for (int x = 0; x < ew; x++) d[x] /= ws[x >> 1];
        }
    });
    if (info) {
        info->frames = n;
        info->refIndex = refIdx;
        info->meanWeight = (float)(total / ((double)GW * GH));
        info->noise = midNoise;
    }
    return true;
}

} // namespace

bool developBurst(const uint16_t* const* frames, int n, const RawParams& p, uint8_t* out, int outStride,
                  RawInfo* info, BurstInfo* burst) {
    if (!frames || n < 1 || !frames[0]) return false;
    if (burst) { burst->frames = n; burst->refIndex = 0; burst->meanWeight = 1; burst->noise = 0; }
    if (n == 1 || p.width < 64 || p.height < 64) return developImpl(frames[0], nullptr, p, out, outStride, info);
    if (p.stride < p.width * 2 || p.cropX < 0 || p.cropY < 0 || p.cropX + p.cropW > p.width || p.cropY + p.cropH > p.height) return false;
    for (int i = 0; i < n; i++) if (!frames[i]) return false;

    std::vector<float> merged;
    BurstInfo bi;
    if (!mergeBurst(frames, n, p, merged, &bi)) return false;
    if (burst) *burst = bi;
    // menos ruído depois da fusão: o acabamento (redução de ruído, nitidez) trabalha como em ISO mais baixo
    RawParams q = p;
    q.iso = std::max(50, (int)(p.iso / std::max(1.0f, bi.meanWeight)));
    return developImpl(nullptr, merged.data(), q, out, outStride, info);
}

} // namespace bitraw
