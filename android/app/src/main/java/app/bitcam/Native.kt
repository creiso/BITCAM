package app.bitcam

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Fusão de burst e revelação de RAW em C++ (src/main/cpp). */
object Native {
    init {
        System.loadLibrary("bitraw")
    }

    /** Descarta os quadros guardados. */
    @JvmStatic
    external fun burstClear()

    /** Copia um quadro RAW para a memória nativa. Devolve quantos há guardados, ou -1 se foi recusado. */
    @JvmStatic
    external fun burstAdd(raw: ByteBuffer, width: Int, height: Int, stride: Int): Int

    /**
     * Funde os quadros guardados, revela e libera a memória. Devolve
     * [kelvin, tint, ganho, modo de WB usado, confiança, quadros, média de quadros por pixel, referência, ruído]
     * ou null se algo for inválido.
     */
    @JvmStatic
    external fun burstDevelop(ip: IntArray, fp: FloatArray, shading: FloatArray?, out: Bitmap): FloatArray?

    /** Ponto neutro da câmera (máx = 1) para um iluminante. Usa só a calibração de [fp]. */
    @JvmStatic
    external fun neutral(kelvin: Float, tint: Float, fp: FloatArray): FloatArray?
}
