package app.bitcam

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Revelação de RAW em C++ (src/main/cpp). */
object Native {
    init {
        System.loadLibrary("bitraw")
    }

    /** Devolve [kelvin, tint, ganho, modo de WB usado, confiança] ou null se algo for inválido. */
    @JvmStatic
    external fun develop(raw: ByteBuffer, ip: IntArray, fp: FloatArray, shading: FloatArray?, out: Bitmap): FloatArray?

    /** Ponto neutro da câmera (máx = 1) para um iluminante. Usa só a calibração de [fp]. */
    @JvmStatic
    external fun neutral(kelvin: Float, tint: Float, fp: FloatArray): FloatArray?
}
