// BITCAM — revelação de RAW Bayer (Etapa 1). Sem dependências de Android: compila e é testado no host.
#pragma once
#include <cstdint>

namespace bitraw {

enum WbMode { WB_AUTO = 0, WB_SENSOR = 1, WB_KELVIN = 2 };

// Calibração de cor do sensor, como o Android a expõe (matrizes 3x3 em ordem de linha).
struct ColorCal {
    float cm1[9], cm2[9];   // SENSOR_COLOR_TRANSFORM1/2: XYZ -> câmera
    float cc1[9], cc2[9];   // SENSOR_CALIBRATION_TRANSFORM1/2
    float fm1[9], fm2[9];   // SENSOR_FORWARD_MATRIX1/2: câmera balanceada -> XYZ D50
    float t1, t2;           // temperatura (K) dos iluminantes de referência 1 e 2
};

struct RawParams {
    int width, height;      // tamanho do buffer RAW
    int stride;             // bytes por linha
    int cfa;                // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR (na origem do buffer)
    int cropX, cropY, cropW, cropH;   // área ativa
    int rotation;           // 0/90/180/270, horário, aplicado na saída
    int mirror;             // != 0: inverte a saída na horizontal (depois da rotação), como num espelho
    int wbMode;
    int iso;
    float black[4];         // nível de preto por posição do bloco 2x2 (ordem de leitura)
    float white;
    float neutral[3];       // SENSOR_NEUTRAL_COLOR_POINT (modo WB_SENSOR e prior do automático)
    float kelvin, tint;     // modo WB_KELVIN; tint: + = magenta
    ColorCal cal;
    float look;             // 0 = revelação neutra (fiel); 1 = acabamento padrão: mais contraste, cor e brilho
    int shCols, shRows;     // mapa de sombreamento da lente (0 = sem mapa)
    const float* shading;   // [linhas][colunas][R, Gpar, Gímpar, B]
};

struct RawInfo {
    float kelvin, tint;     // balanço de branco efetivamente usado
    float gain;             // ganho de exposição aplicado
    int wbUsed;             // modo efetivo (o automático pode cair para WB_SENSOR)
    float confidence;       // 0..1, do automático
};

struct BurstInfo {
    int frames;             // quadros recebidos
    int refIndex;           // quadro escolhido como referência (o mais nítido dos primeiros)
    float meanWeight;       // quantos quadros, em média, entraram em cada pixel (1 = só a referência)
    float noise;            // ruído medido entre quadros (DN, no cinza de meia resolução, tom médio)
};

// Tamanho da saída depois do recorte e da rotação.
void outputSize(const RawParams& p, int* w, int* h);

// Revela para RGBA8 (R,G,B,A em bytes). outStride em bytes. Retorna false se os parâmetros forem inválidos.
bool develop(const uint16_t* raw, const RawParams& p, uint8_t* out, int outStride, RawInfo* info);

// Burst: alinha os quadros RAW (mesma geometria e exposição) por blocos, funde com rejeição de
// movimento e revela o resultado. Com n = 1 equivale a develop().
bool developBurst(const uint16_t* const* frames, int n, const RawParams& p, uint8_t* out, int outStride,
                  RawInfo* info, BurstInfo* burst);

// Ponto neutro da câmera (normalizado, máx = 1) para um iluminante em Kelvin + tint.
bool neutralFor(const ColorCal& cal, float kelvin, float tint, float out[3]);

// Curva de tons usada na saída: valor linear (0..1 antes do ganho) -> 0..255. Exposta para os testes.
int toneForTest(float linear, float gain);

} // namespace bitraw
