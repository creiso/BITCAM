// Ponte JNI entre o app (Kotlin) e a revelação (bitraw).
#include <jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <mutex>
#include <new>
#include <vector>
#include "bitraw.h"

using namespace bitraw;

namespace {
// Layout de fp[] (ver RawProcessor.kt): 0-3 preto, 4 branco, 5-7 neutro, 8 kelvin, 9 tint,
// 10 cm1, 19 cm2, 28 cc1, 37 cc2, 46 fm1, 55 fm2, 64 t1, 65 t2, 66 look.
const int FP_LEN = 67, IP_LEN = 14;

void fillCal(const jfloat* f, ColorCal& c) {
    memcpy(c.cm1, f + 10, 9 * sizeof(float));
    memcpy(c.cm2, f + 19, 9 * sizeof(float));
    memcpy(c.cc1, f + 28, 9 * sizeof(float));
    memcpy(c.cc2, f + 37, 9 * sizeof(float));
    memcpy(c.fm1, f + 46, 9 * sizeof(float));
    memcpy(c.fm2, f + 55, 9 * sizeof(float));
    c.t1 = f[64];
    c.t2 = f[65];
}
} // namespace

// Quadros do burst em memória nativa, entre burstAdd e burstDevelop (uma captura por vez).
namespace {
std::mutex g_lock;
std::vector<std::vector<uint16_t>> g_frames;
int g_w = 0, g_h = 0, g_stride = 0;
const int MAX_FRAMES = 12;
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_bitcam_Native_burstClear(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> l(g_lock);
    g_frames.clear();
    g_frames.shrink_to_fit();
    g_w = g_h = g_stride = 0;
}

// Copia um quadro RAW. Devolve quantos quadros há guardados, ou -1 se este foi recusado.
extern "C" JNIEXPORT jint JNICALL
Java_app_bitcam_Native_burstAdd(JNIEnv* env, jclass, jobject rawBuf, jint w, jint h, jint stride) {
    if (!rawBuf || w <= 0 || h <= 0 || stride < w * 2 || (stride & 1)) return -1;
    const auto* raw = static_cast<const uint8_t*>(env->GetDirectBufferAddress(rawBuf));
    const jlong cap = env->GetDirectBufferCapacity(rawBuf);
    const jlong need = (jlong)stride * (h - 1) + (jlong)w * 2;
    if (!raw || cap < need) return -1;
    std::lock_guard<std::mutex> l(g_lock);
    if (g_frames.empty()) { g_w = w; g_h = h; g_stride = stride; }
    else if (w != g_w || h != g_h || stride != g_stride) return -1;
    if ((int)g_frames.size() >= MAX_FRAMES) return -1;
    try {
        std::vector<uint16_t> f((size_t)stride / 2 * h);
        memcpy(f.data(), raw, (size_t)need);
        g_frames.push_back(std::move(f));
    } catch (const std::bad_alloc&) {
        return -1;   // sem memória: segue com os quadros que já temos
    }
    return (jint)g_frames.size();
}

// Funde os quadros guardados e revela. Devolve [kelvin, tint, ganho, modo de WB, confiança,
// quadros, média de quadros por pixel, índice da referência, ruído] e libera os quadros.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_bitcam_Native_burstDevelop(JNIEnv* env, jclass, jintArray jip, jfloatArray jfp,
                                    jfloatArray jsh, jobject bitmap) {
    std::lock_guard<std::mutex> l(g_lock);
    std::vector<std::vector<uint16_t>> frames;
    frames.swap(g_frames);   // libera ao sair, dê certo ou não
    if (frames.empty() || !jip || !jfp || !bitmap) return nullptr;
    if (env->GetArrayLength(jip) < IP_LEN || env->GetArrayLength(jfp) < FP_LEN) return nullptr;

    jint ip[IP_LEN];
    jfloat fp[FP_LEN];
    env->GetIntArrayRegion(jip, 0, IP_LEN, ip);
    env->GetFloatArrayRegion(jfp, 0, FP_LEN, fp);

    RawParams p;
    memset(&p, 0, sizeof p);
    p.width = g_w; p.height = g_h; p.stride = g_stride; p.cfa = ip[3];
    p.cropX = ip[4]; p.cropY = ip[5]; p.cropW = ip[6]; p.cropH = ip[7];
    p.rotation = ip[8]; p.wbMode = ip[9]; p.iso = ip[10];
    p.mirror = ip[13];
    p.shCols = ip[11]; p.shRows = ip[12];
    for (int i = 0; i < 4; i++) p.black[i] = fp[i];
    p.white = fp[4];
    for (int i = 0; i < 3; i++) p.neutral[i] = fp[5 + i];
    p.kelvin = fp[8]; p.tint = fp[9];
    p.look = fp[66];
    fillCal(fp, p.cal);
    if (ip[0] != g_w || ip[1] != g_h) return nullptr;

    jfloat* sh = nullptr;
    if (jsh && p.shCols >= 2 && p.shRows >= 2 && env->GetArrayLength(jsh) >= p.shCols * p.shRows * 4) {
        sh = env->GetFloatArrayElements(jsh, nullptr);
    }
    p.shading = sh;
    if (!sh) p.shCols = p.shRows = 0;

    jfloatArray result = nullptr;
    AndroidBitmapInfo bi;
    int ow = 0, oh = 0;
    outputSize(p, &ow, &oh);
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &bi) == ANDROID_BITMAP_RESULT_SUCCESS &&
        bi.format == ANDROID_BITMAP_FORMAT_RGBA_8888 && (int)bi.width == ow && (int)bi.height == oh &&
        AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS && pixels) {
        std::vector<const uint16_t*> ptrs;
        for (auto& f : frames) ptrs.push_back(f.data());
        RawInfo info;
        BurstInfo burst;
        memset(&info, 0, sizeof info);
        memset(&burst, 0, sizeof burst);
        bool ok = false;
        try {
            ok = developBurst(ptrs.data(), (int)ptrs.size(), p, static_cast<uint8_t*>(pixels), (int)bi.stride, &info, &burst);
        } catch (const std::bad_alloc&) {
            // sem memória para a fusão: revela só o primeiro quadro
            try {
                frames.resize(1);
                frames.shrink_to_fit();
                ok = develop(frames[0].data(), p, static_cast<uint8_t*>(pixels), (int)bi.stride, &info);
                burst.frames = 1; burst.meanWeight = 1; burst.refIndex = 0; burst.noise = 0;
            } catch (const std::bad_alloc&) {
                ok = false;
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        if (ok) {
            const jfloat out[9] = {info.kelvin, info.tint, info.gain, (jfloat)info.wbUsed, info.confidence,
                                   (jfloat)burst.frames, burst.meanWeight, (jfloat)burst.refIndex, burst.noise};
            result = env->NewFloatArray(9);
            if (result) env->SetFloatArrayRegion(result, 0, 9, out);
        }
    }
    if (sh) env->ReleaseFloatArrayElements(jsh, sh, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_bitcam_Native_neutral(JNIEnv* env, jclass, jfloat kelvin, jfloat tint, jfloatArray jfp) {
    if (!jfp || env->GetArrayLength(jfp) < FP_LEN) return nullptr;
    jfloat fp[FP_LEN];
    env->GetFloatArrayRegion(jfp, 0, FP_LEN, fp);
    ColorCal cal;
    fillCal(fp, cal);
    float n[3];
    if (!neutralFor(cal, kelvin, tint, n)) return nullptr;
    jfloatArray r = env->NewFloatArray(3);
    if (r) env->SetFloatArrayRegion(r, 0, 3, n);
    return r;
}
