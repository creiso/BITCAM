package app.bitcam

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Rational

/** Empacota os metadados do Camera2 no formato que a revelação nativa espera. */
object RawProcessor {
    const val WB_AUTO = 0
    const val WB_SENSOR = 1
    const val WB_KELVIN = 2

    const val FP_LEN = 67
    private const val FP_CAL = 10

    class Result(
        val bitmap: Bitmap, val kelvin: Float, val tint: Float, val gain: Float, val wbUsed: Int,
        val frames: Int, val meanFrames: Float, val refIndex: Int, val noise: Float, val debug: String
    )

    /** Calibração de cor do sensor (fixa por câmera). As posições 0..9 ficam para a captura. */
    fun calibration(c: CameraCharacteristics): FloatArray {
        val fp = FloatArray(FP_LEN)
        put(fp, FP_CAL, c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1), false)
        put(fp, FP_CAL + 9, c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2), false)
        put(fp, FP_CAL + 18, c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1), true)
        put(fp, FP_CAL + 27, c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2), true)
        put(fp, FP_CAL + 36, c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1), false)
        put(fp, FP_CAL + 45, c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2), false)
        val t1 = illuminantKelvin(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
        val t2 = illuminantKelvin(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt())
        fp[64] = t1 ?: 0f
        fp[65] = t2 ?: 0f
        return fp
    }

    private fun put(fp: FloatArray, at: Int, m: ColorSpaceTransform?, identityIfMissing: Boolean) {
        if (m == null) {
            if (identityIfMissing) { fp[at] = 1f; fp[at + 4] = 1f; fp[at + 8] = 1f }
            return
        }
        val r = arrayOfNulls<Rational>(9)
        m.copyElements(r, 0)
        for (i in 0 until 9) fp[at + i] = r[i]?.toFloat()?.takeIf { it.isFinite() } ?: 0f
    }

    private fun illuminantKelvin(v: Int?): Float? = when (v) {
        null -> null
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2856f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3200f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> 3450f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4150f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> 4874f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5003f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH -> 5503f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6430f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> 6504f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C -> 6774f
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> 7504f
        else -> 5503f
    }

    /** Área útil do RAW: a matriz ativa, se couber no buffer; senão o buffer inteiro. */
    fun cropFor(c: CameraCharacteristics, w: Int, h: Int): Rect {
        val a = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        if (a != null && a.left >= 0 && a.top >= 0 && a.right <= w && a.bottom <= h && a.width() >= 64 && a.height() >= 64) {
            return Rect(a)
        }
        return Rect(0, 0, w, h)
    }

    /** Funde e revela os quadros já entregues a [Native.burstAdd] (todos de [w]×[h]). */
    fun develop(
        w: Int, h: Int, c: CameraCharacteristics, r: CaptureResult, cal: FloatArray,
        rotation: Int, mirror: Boolean, wbMode: Int, kelvin: Float, tint: Float
    ): Result? {
        val crop = cropFor(c, w, h)

        val fp = cal.copyOf()
        // nível de preto: o dinâmico (medido no quadro) quando existe, senão o de fábrica
        val staticBlack = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val dynBlack = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        for (i in 0 until 4) {
            fp[i] = dynBlack?.getOrNull(i)?.takeIf { it.isFinite() && it >= 0f }
                ?: staticBlack?.getOffsetForIndex(i and 1, i shr 1)?.toFloat() ?: 0f
        }
        val staticWhite = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        var white = (r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: staticWhite).toFloat()
        val maxBlack = maxOf(fp[0], fp[1], fp[2], fp[3])
        if (white <= maxBlack + 16f) white = staticWhite.toFloat()
        if (white <= maxBlack + 16f) return null
        fp[4] = white

        val neutral = r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        for (i in 0 until 3) fp[5 + i] = neutral?.getOrNull(i)?.toFloat()?.takeIf { it.isFinite() } ?: 0f
        fp[8] = kelvin
        fp[9] = tint
        fp[66] = 1f   // acabamento padrão

        val map = r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        var shading: FloatArray? = null
        var shCols = 0
        var shRows = 0
        if (map != null && map.columnCount >= 2 && map.rowCount >= 2) {
            shading = FloatArray(map.gainFactorCount)
            map.copyGainFactors(shading, 0)
            shCols = map.columnCount
            shRows = map.rowCount
        }

        val iso = (r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100) *
            (r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100) / 100
        val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        if (cfa !in 0..3) return null   // sensor mono ou NIR: fora do escopo

        val ip = intArrayOf(
            w, h, 0, cfa,
            crop.left, crop.top, crop.width(), crop.height(),
            rotation, wbMode, iso, shCols, shRows, if (mirror) 1 else 0
        )
        val swap = rotation == 90 || rotation == 270
        val bmp = Bitmap.createBitmap(
            if (swap) crop.height() else crop.width(),
            if (swap) crop.width() else crop.height(),
            Bitmap.Config.ARGB_8888
        )
        val info = Native.burstDevelop(ip, fp, shading, bmp)
        if (info == null) {
            bmp.recycle()
            return null
        }
        // diagnóstico gravado no EXIF: é o que permite afinar a revelação a partir de uma foto enviada
        val noiseProfile = r.get(CaptureResult.SENSOR_NOISE_PROFILE)
            ?.joinToString(",") { String.format(java.util.Locale.US, "%.2e/%.2e", it.first, it.second) } ?: "-"
        val debug = String.format(java.util.Locale.US,
            "frames=%d mean=%.2f ref=%d noise=%.1f wb=%d K=%.0f tint=%.1f gain=%.2f black=%.1f/%.1f/%.1f/%.1f white=%.0f " +
                "neutral=%.4f/%.4f/%.4f cfa=%d iso=%d crop=%d,%d,%d,%d raw=%dx%d shading=%dx%d dynBlack=%s np=%s",
            info[5].toInt(), info[6], info[7].toInt(), info[8], info[3].toInt(), info[0], info[1], info[2],
            fp[0], fp[1], fp[2], fp[3], fp[4], fp[5], fp[6], fp[7], cfa, iso,
            crop.left, crop.top, crop.width(), crop.height(), w, h, shCols, shRows,
            if (dynBlack != null) "s" else "n", noiseProfile)
        return Result(bmp, info[0], info[1], info[2], info[3].toInt(), info[5].toInt(), info[6], info[7].toInt(), info[8], debug)
    }
}
