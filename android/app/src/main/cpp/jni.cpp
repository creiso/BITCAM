// Ponte JNI entre o app (Kotlin) e a revelação (bitraw).
#include <jni.h>
#include <android/bitmap.h>
#include <cstring>
#include "bitraw.h"

using namespace bitraw;

namespace {
// Layout de fp[] (ver RawProcessor.kt): 0-3 preto, 4 branco, 5-7 neutro, 8 kelvin, 9 tint,
// 10 cm1, 19 cm2, 28 cc1, 37 cc2, 46 fm1, 55 fm2, 64 t1, 65 t2.
const int FP_LEN = 66, IP_LEN = 13;

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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_bitcam_Native_develop(JNIEnv* env, jclass, jobject rawBuf, jintArray jip, jfloatArray jfp,
                               jfloatArray jsh, jobject bitmap) {
    if (!rawBuf || !jip || !jfp || !bitmap) return nullptr;
    if (env->GetArrayLength(jip) < IP_LEN || env->GetArrayLength(jfp) < FP_LEN) return nullptr;
    const auto* raw = static_cast<const uint16_t*>(env->GetDirectBufferAddress(rawBuf));
    const jlong cap = env->GetDirectBufferCapacity(rawBuf);
    if (!raw || cap <= 0) return nullptr;

    jint ip[IP_LEN];
    jfloat fp[FP_LEN];
    env->GetIntArrayRegion(jip, 0, IP_LEN, ip);
    env->GetFloatArrayRegion(jfp, 0, FP_LEN, fp);

    RawParams p;
    memset(&p, 0, sizeof p);
    p.width = ip[0]; p.height = ip[1]; p.stride = ip[2]; p.cfa = ip[3];
    p.cropX = ip[4]; p.cropY = ip[5]; p.cropW = ip[6]; p.cropH = ip[7];
    p.rotation = ip[8]; p.wbMode = ip[9]; p.iso = ip[10];
    p.shCols = ip[11]; p.shRows = ip[12];
    for (int i = 0; i < 4; i++) p.black[i] = fp[i];
    p.white = fp[4];
    for (int i = 0; i < 3; i++) p.neutral[i] = fp[5 + i];
    p.kelvin = fp[8]; p.tint = fp[9];
    fillCal(fp, p.cal);

    if (p.width <= 0 || p.height <= 0 || p.stride < p.width * 2) return nullptr;
    if ((jlong)p.stride * (p.height - 1) + (jlong)p.width * 2 > cap) return nullptr;

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
        RawInfo info;
        memset(&info, 0, sizeof info);
        bool ok = develop(raw, p, static_cast<uint8_t*>(pixels), (int)bi.stride, &info);
        AndroidBitmap_unlockPixels(env, bitmap);
        if (ok) {
            const jfloat out[5] = {info.kelvin, info.tint, info.gain, (jfloat)info.wbUsed, info.confidence};
            result = env->NewFloatArray(5);
            if (result) env->SetFloatArrayRegion(result, 0, 5, out);
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
