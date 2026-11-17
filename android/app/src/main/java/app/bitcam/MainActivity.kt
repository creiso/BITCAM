package app.bitcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * BITCAM, Etapa 1: captura um quadro RAW pelo Camera2 e revela com pipeline próprio
 * (balanço de branco, matrizes de cor do sensor, curva de tons). Salva JPEG e, opcionalmente, DNG.
 *
 * Todo o estado da câmera é tocado só na thread "camera"; a revelação roda em "worker".
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var manager: CameraManager
    private lateinit var preview: SurfaceView
    private lateinit var frame: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var focusRing: View
    private lateinit var shutter: Button
    private lateinit var thumb: ImageView
    private lateinit var kelvinPanel: View
    private lateinit var kelvinLabel: TextView
    private lateinit var tintLabel: TextView
    private lateinit var kelvinSeek: SeekBar
    private lateinit var tintSeek: SeekBar
    private lateinit var wbButtons: Map<Int, Button>
    private lateinit var wbLock: Button
    private lateinit var flip: Button
    private lateinit var dngBox: CheckBox
    private lateinit var mirrorBox: CheckBox
    private lateinit var stage: View
    private lateinit var videoPanel: View
    private lateinit var vLock: Button
    private lateinit var vReadjust: Button
    private lateinit var vNatural: Button
    private lateinit var vStab: Button
    private lateinit var modePhoto: Button
    private lateinit var modeVideo: Button
    private lateinit var orientationListener: OrientationEventListener

    private val ui = Handler(Looper.getMainLooper())
    private var camThread: HandlerThread? = null
    private var cam: Handler? = null
    private val camExecutor = Executor { r -> cam?.post(r) }
    private val worker = Executors.newSingleThreadExecutor()

    // ---- estado da câmera (thread "camera")
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var rawReader: ImageReader? = null
    private var previewSize = Size(1440, 1080)
    private var cal = FloatArray(RawProcessor.FP_LEN)
    private var opening = false
    private var configuring = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var surfaceOk = false
    private var lastTransform: ColorSpaceTransform? = null
    private var afRegion: MeteringRectangle? = null
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var captureToken = 0
    private var collecting = false   // recebendo os quadros de um burst
    private var burstExpected = 0
    private var burstGot = 0
    private var burstW = 0
    private var burstH = 0
    @Volatile private var lastIso = 400
    private var lastStatusAt = 0L
    @Volatile private var holdStatusUntil = 0L   // mantém o resultado da foto legível

    // ---- vídeo
    @Volatile private var videoMode = false
    @Volatile private var recording = false
    @Volatile private var lockOnRecord = true    // trava cor e brilho enquanto grava
    @Volatile private var natural = true         // sem nitidez artificial + curva de contraste própria
    @Volatile private var stabilize = true
    @Volatile private var unlockUntil = 0L       // janela do "Reajustar"
    private var recSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recFd: ParcelFileDescriptor? = null
    private var recStartedAt = 0L
    private var videoSize = Size(1920, 1080)
    private var videoHevc = false
    private var videoBitrate = 20_000_000
    private var avcCap = 0
    private var videoLabel = ""
    private var curve: TonemapCurve? = null
    private var curveReady = false
    private var askedAudio = false
    private var lastMime = "image/jpeg"

    // ---- estado compartilhado
    @Volatile private var facing = CameraCharacteristics.LENS_FACING_BACK
    @Volatile private var busy = false
    @Volatile private var wbMode = RawProcessor.WB_AUTO
    @Volatile private var kelvin = 5200f
    @Volatile private var tint = 0f
    @Volatile private var deviceRotation = 0
    @Volatile private var mirrorPhoto = false   // inverte a foto salva na horizontal; lembrado por câmera
    @Volatile private var saveDng = false   // o DNG é o negativo cru, não a foto final: só para quem edita RAW
    private var lastEstimate: Pair<Float, Float>? = null
    private var lastPhoto: Uri? = null
    private var resumed = false

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        manager = getSystemService(CAMERA_SERVICE) as CameraManager

        preview = findViewById(R.id.preview)
        frame = findViewById(R.id.frame)
        statusView = findViewById(R.id.status)
        hintView = findViewById(R.id.hint)
        focusRing = findViewById(R.id.focusRing)
        shutter = findViewById(R.id.shutter)
        thumb = findViewById(R.id.thumb)
        kelvinPanel = findViewById(R.id.kelvinPanel)
        kelvinLabel = findViewById(R.id.kelvinLabel)
        tintLabel = findViewById(R.id.tintLabel)
        kelvinSeek = findViewById(R.id.kelvinSeek)
        tintSeek = findViewById(R.id.tintSeek)
        wbLock = findViewById(R.id.wbLock)
        flip = findViewById(R.id.flip)
        dngBox = findViewById(R.id.dng)
        mirrorBox = findViewById(R.id.mirror)
        stage = findViewById(R.id.stage)
        videoPanel = findViewById(R.id.videoPanel)
        vLock = findViewById(R.id.vLock)
        vReadjust = findViewById(R.id.vReadjust)
        vNatural = findViewById(R.id.vNatural)
        vStab = findViewById(R.id.vStab)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        wbButtons = mapOf(
            RawProcessor.WB_AUTO to findViewById(R.id.wbAuto),
            RawProcessor.WB_SENSOR to findViewById(R.id.wbSensor),
            RawProcessor.WB_KELVIN to findViewById<Button>(R.id.wbKelvin)
        )

        preview.holder.addCallback(this)
        setupControls()
        showMode()
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutFrame() }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return   // aparelho deitado na mesa: mantém a última leitura firme
                // histerese: só troca quando o aparelho passou bem do meio do caminho (60°, não 45°),
                // para uma leitura instável perto da diagonal não virar a foto
                var d = Math.abs(o - deviceRotation) % 360
                if (d > 180) d = 360 - d
                if (d <= 60) return
                deviceRotation = ((o + 45) / 90 * 90) % 360
                showRotation()
            }
        }
    }

    /** Mantém o visor em 3:4 (foto) ou 9:16 (vídeo) dentro do espaço disponível. */
    private fun layoutFrame() {
        val aw = if (videoMode) 9 else 3
        val ah = if (videoMode) 16 else 4
        val w = minOf(stage.width, stage.height * aw / ah)
        val h = w * ah / aw
        val lp = frame.layoutParams as FrameLayout.LayoutParams
        if (w > 0 && (lp.width != w || lp.height != h)) {
            lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
            frame.post { frame.layoutParams = lp }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        orientationListener.enable()
        camThread = HandlerThread("camera").also { it.start(); cam = Handler(it.looper) }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cam?.post { openCamera() }
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onPause() {
        resumed = false
        orientationListener.disable()
        val h = cam
        val t = camThread
        cam = null
        camThread = null
        h?.post { closeCamera() }
        t?.quitSafely()
        try { t?.join(1500) } catch (_: InterruptedException) { }
        busy = false
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != 1) return   // microfone: sem ele o vídeo só fica mudo
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            cam?.post { openCamera() }
        } else {
            status("Sem permissão de câmera. Libere em Configurações > Apps > BITCAM.")
        }
    }

    // ------------------------------------------------------------------ superfície do visor

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        cam?.post {
            surfaceW = width; surfaceH = height; surfaceOk = true
            startSessionIfReady()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val h = cam ?: return
        // a sessão precisa parar antes de a superfície sumir
        val done = java.util.concurrent.CountDownLatch(1)
        h.post { surfaceOk = false; closeSession(); done.countDown() }
        try { done.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { }
    }

    // ------------------------------------------------------------------ câmera

    private fun findCamera(wanted: Int): String? {
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) continue
            if (c.get(CameraCharacteristics.LENS_FACING) == wanted) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (opening || device != null || !resumed) return
        try {
            val id = findCamera(facing)
            if (id == null) {
                status("Nenhuma câmera com RAW disponível neste aparelho.")
                return
            }
            val c = manager.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            if (map == null || raw == null) {
                status("Esta câmera não entrega RAW.")
                return
            }
            chars = c
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: facing
            cal = RawProcessor.calibration(c)
            lastTransform = null
            afRegion = null

            val sizes = map.getOutputSizes(SurfaceHolder::class.java) ?: emptyArray()
            curve = null
            curveReady = false
            if (videoMode) {
                val rec = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
                val both = rec.filter { r -> sizes.any { it == r } && r.width * 9 == r.height * 16 && r.width <= 1920 }
                videoSize = both.maxByOrNull { it.width } ?: Size(1280, 720)
                chooseEncoder(videoSize)
                previewSize = videoSize
            } else {
                previewSize = sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width }
                    ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                    ?: Size(1440, 1080)
            }
            val ps = previewSize
            ui.post { preview.holder.setFixedSize(ps.width, ps.height) }

            rawReader = ImageReader.newInstance(raw.width, raw.height, ImageFormat.RAW_SENSOR, 5).apply {
                setOnImageAvailableListener({ r -> onRawImage(r) }, cam)
            }
            opening = true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    device = d
                    startSessionIfReady()
                }

                override fun onDisconnected(d: CameraDevice) {
                    opening = false
                    d.close()
                    if (device === d) { device = null; session = null }
                }

                override fun onError(d: CameraDevice, error: Int) {
                    opening = false
                    d.close()
                    if (device === d) { device = null; session = null }
                    status("Erro ao abrir a câmera (código $error).")
                }
            }, cam)
            ui.post {
                flip.text = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "Traseira" else "Frontal"
                mirrorBox.isChecked = getPreferences(MODE_PRIVATE).getBoolean(mirrorKey(), false)
            }
            status((if (videoMode) videoLabel else "RAW ${raw.width}×${raw.height}") + " · abrindo…")
        } catch (e: Exception) {
            opening = false
            status("Não foi possível abrir a câmera: ${e.message}")
        }
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        configuring = false
    }

    private fun closeCamera() {
        if (recording) stopRecording()
        closeSession()
        try { recSurface?.release() } catch (_: Exception) { }
        recSurface = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { rawReader?.close() } catch (_: Exception) { }
        rawReader = null
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        collecting = false
        try { Native.burstClear() } catch (_: Throwable) { }
        opening = false
    }

    private fun startSessionIfReady() {
        val d = device ?: return
        val reader = rawReader ?: return
        if (session != null || configuring || !surfaceOk) return
        // só começa quando a superfície já tem o tamanho de buffer pedido
        if (surfaceW != previewSize.width || surfaceH != previewSize.height) return
        val surface = preview.holder.surface
        if (surface == null || !surface.isValid) return
        try {
            configuring = true
            val second = if (videoMode) ensureRecSurface() else reader.surface
            val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(second))
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, camExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        configuring = false
                        if (device !== d) { s.close(); return }
                        session = s
                        updatePreview()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        configuring = false
                        status(if (videoMode) "A câmera recusou a configuração de vídeo." else "A câmera recusou a configuração RAW + visor.")
                    }
                }))
        } catch (e: Exception) {
            configuring = false
            status("Falha ao iniciar a sessão: ${e.message}")
        }
    }

    private fun supports(key: CameraCharacteristics.Key<IntArray>, value: Int): Boolean =
        chars?.get(key)?.contains(value) == true

    /** Ajustes comuns ao visor e à foto: foco, exposição automática e balanço de branco. */
    private fun common(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        val region = afRegion
        if (region != null) {
            if (supports(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            }
            if ((chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            if ((chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        } else if (videoMode && supports(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)   // foco mais suave para vídeo
        } else if (supports(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (supports(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        if (videoMode) {
            // taxa de quadros constante
            chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (supports(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (stabilize) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            if (natural) {
                if (supports(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_OFF)) {
                    b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                }
                naturalCurve()?.let {
                    b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    b.set(CaptureRequest.TONEMAP_CURVE, it)
                }
            }
        }
        applyWb(b)
        // gravando com a trava ligada: exposição e balanço de branco ficam como estavam
        if (videoMode && recording && lockOnRecord && SystemClock.elapsedRealtime() >= unlockUntil) {
            if (chars?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            if (b.get(CaptureRequest.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_AUTO &&
                chars?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
        }
    }

    /** Curva de contraste do modo Natural: gama sRGB com um S leve, sem o contraste forçado de fábrica. */
    private fun naturalCurve(): TonemapCurve? {
        if (curveReady) return curve
        curveReady = true
        val max = chars?.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        if (max < 16 || !supports(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) return null
        val n = minOf(64, max)
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val x = t * t   // mais pontos nas sombras
            var y = if (x <= 0.0031308f) 12.92f * x else 1.055f * Math.pow(x.toDouble(), 1 / 2.4).toFloat() - 0.055f
            y += 0.10f * y * (1 - y) * (2 * y - 1)
            pts[i * 2] = x
            pts[i * 2 + 1] = y.coerceIn(0f, 1f)
        }
        curve = TonemapCurve(pts, pts, pts)
        return curve
    }

    /** Pedido para o visor; no vídeo usa o modelo de gravação e inclui o gravador enquanto grava. */
    private fun newBuilder(d: CameraDevice): CaptureRequest.Builder {
        val b = d.createCaptureRequest(if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(preview.holder.surface)
        if (recording) recSurface?.let { b.addTarget(it) }
        common(b)
        return b
    }

    /**
     * No modo Kelvin o visor também usa os ganhos calculados, para mostrar a cor escolhida.
     * Nos outros modos o visor fica com o automático do aparelho (a cor final sai da revelação).
     */
    private fun applyWb(b: CaptureRequest.Builder) {
        val t = lastTransform
        val manual = supports(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        if (wbMode == RawProcessor.WB_KELVIN && t != null && manual) {
            val n = try { Native.neutral(kelvin, tint, cal) } catch (_: Throwable) { null }
            if (n != null && n.all { it > 0.001f }) {
                val mx = n.max()
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(mx / n[0], mx / n[1], mx / n[1], mx / n[2]))
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, t)
                return
            }
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (result.get(CaptureResult.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastTransform = it }
            }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            val now = SystemClock.elapsedRealtime()
            if (!busy && now > holdStatusUntil && now - lastStatusAt > 400) {
                lastStatusAt = now
                val r = rawReader
                status(when {
                    recording -> "● ${clock(now - recStartedAt)} · $videoLabel · ${exposureText(result)}"
                    videoMode -> "$videoLabel · ${exposureText(result)}"
                    else -> "RAW ${r?.width}×${r?.height} · ${exposureText(result)}"
                })
            }
        }
    }

    private fun updatePreview() {
        val s = session ?: return
        val d = device ?: return
        try {
            val b = newBuilder(d)
            s.setRepeatingRequest(b.build(), previewCallback, cam)
        } catch (e: Exception) {
            status("Falha no visor: ${e.message}")
        }
    }

    private fun exposureText(r: CaptureResult): String {
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY)
        val ns = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val t = when {
            ns == null || ns <= 0 -> "?"
            ns >= 500_000_000L -> String.format(Locale.US, "%.1f s", ns / 1e9)
            else -> "1/${(1e9 / ns).roundToInt()} s"
        }
        return "ISO ${iso ?: "?"} · $t"
    }

    // ------------------------------------------------------------------ foco por toque

    private fun focusAt(u0: Float, v: Float) {
        val c = chars ?: return
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val u = if (facing == CameraCharacteristics.LENS_FACING_FRONT) 1f - u0 else u0
        // o visor está em retrato; o sensor, deitado
        val (sx, sy) = when (c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) {
            90 -> v to 1f - u
            270 -> 1f - v to u
            180 -> 1f - u to 1f - v
            else -> u to v
        }
        val w = active.width()
        val h = active.height()
        // o vídeo 16:9 usa só a faixa central do sensor 4:3
        var fx = sx
        var fy = sy
        if (videoMode) {
            val sensor = w.toFloat() / h
            val video = videoSize.width.toFloat() / videoSize.height
            if (video > sensor) fy = 0.5f + (sy - 0.5f) * sensor / video else fx = 0.5f + (sx - 0.5f) * video / sensor
        }
        val half = (minOf(w, h) * 0.06f).toInt()
        val cx = (fx * w).toInt().coerceIn(half, w - half)
        val cy = (fy * h).toInt().coerceIn(half, h - half)
        afRegion = MeteringRectangle(cx - half, cy - half, half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX)
        val s = session ?: return
        val d = device ?: return
        try {
            updatePreview()
            val b = newBuilder(d)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, cam)
        } catch (_: Exception) { }
        cam?.removeCallbacks(releaseFocus)
        cam?.postDelayed(releaseFocus, 4000)
    }

    private val releaseFocus = Runnable {
        afRegion = null
        ui.post { focusRing.visibility = View.GONE }
        val s = session ?: return@Runnable
        val d = device ?: return@Runnable
        try {
            val b = newBuilder(d)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, cam)
            updatePreview()
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ vídeo

    private fun clock(ms: Long): String = String.format(Locale.US, "%02d:%02d", ms / 60000, ms / 1000 % 60)

    /** HEVC quando o aparelho codifica esse tamanho a 30 qps; senão H.264. Taxa de bits bem acima da de fábrica. */
    private fun chooseEncoder(size: Size) {
        var hevcMax = 0
        var avcMax = 0
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val hevc = type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)
                    if (!hevc && !type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true)) continue
                    val vc = try { info.getCapabilitiesForType(type).videoCapabilities } catch (_: Exception) { null } ?: continue
                    if (!vc.areSizeAndRateSupported(size.width, size.height, 30.0)) continue
                    val top = vc.bitrateRange.upper
                    if (hevc) hevcMax = maxOf(hevcMax, top) else avcMax = maxOf(avcMax, top)
                }
            }
        } catch (_: Exception) { }
        avcCap = avcMax
        setEncoder(size, hevcMax > 0, if (hevcMax > 0) hevcMax else avcMax)
    }

    private fun setEncoder(size: Size, hevc: Boolean, cap: Int) {
        videoHevc = hevc
        val scale = size.width.toLong() * size.height / (1920.0 * 1080.0)
        val target = ((if (hevc) 36_000_000 else 48_000_000) * scale).toInt()
        videoBitrate = if (cap > 0) minOf(target, cap) else minOf(target, 20_000_000)
        videoLabel = "${size.height}p30 ${if (hevc) "HEVC" else "H.264"} ${videoBitrate / 1_000_000} Mbps"
    }

    private fun newRecorder(surface: Surface, fd: java.io.FileDescriptor?, path: String?, rotation: Int, audio: Boolean): MediaRecorder {
        @Suppress("DEPRECATION")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (fd != null) r.setOutputFile(fd) else r.setOutputFile(path)
            r.setVideoEncodingBitRate(videoBitrate)
            r.setVideoFrameRate(30)
            r.setVideoSize(videoSize.width, videoSize.height)
            r.setVideoEncoder(if (videoHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(192_000)
                r.setAudioSamplingRate(48_000)
                r.setAudioChannels(2)
            }
            r.setOrientationHint(rotation)
            r.setInputSurface(surface)
            r.prepare()
            return r
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            throw e
        }
    }

    /** Superfície de entrada do gravador, reaproveitada entre gravações da mesma sessão. */
    private fun ensureRecSurface(): Surface {
        recSurface?.let { return it }
        val s = MediaCodec.createPersistentInputSurface()
        val tmp = File(cacheDir, "prep.mp4")
        try {
            // um gravador descartável só para dar tamanho e formato à superfície
            try {
                newRecorder(s, null, tmp.absolutePath, 0, false).release()
            } catch (e: Exception) {
                if (!videoHevc) throw e
                setEncoder(videoSize, false, avcCap)   // o aparelho recusou HEVC nessa configuração: cai para H.264
                newRecorder(s, null, tmp.absolutePath, 0, false).release()
            }
        } catch (e: Exception) {
            s.release()
            throw e
        } finally {
            tmp.delete()
        }
        recSurface = s
        return s
    }

    private fun toggleRecord() {
        cam?.post { if (recording) stopRecording() else startRecording() }
    }

    private fun startRecording() {
        val surface = recSurface
        val c = chars
        if (session == null || surface == null || c == null || !videoMode || recording) return
        var uri: Uri? = null
        var fd: ParcelFileDescriptor? = null
        try {
            val name = "BITCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/BITCAM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("galeria indisponível")
            fd = contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("arquivo indisponível")
            val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rotation = if (facing == CameraCharacteristics.LENS_FACING_FRONT) (sensorOrientation - deviceRotation + 360) % 360
            else (sensorOrientation + deviceRotation) % 360
            val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val r = newRecorder(surface, fd.fileDescriptor, null, rotation, audio)
            try {
                r.start()
            } catch (e: Exception) {
                try { r.release() } catch (_: Exception) { }
                throw e
            }
            recorder = r
            recUri = uri
            recFd = fd
            recStartedAt = SystemClock.elapsedRealtime()
            unlockUntil = 0
            holdStatusUntil = 0
            recording = true
            updatePreview()
            ui.post {
                showMode()
                if (!audio) hint("Gravando sem áudio (microfone não liberado)")
            }
        } catch (e: Exception) {
            try { fd?.close() } catch (_: Exception) { }
            try { uri?.let { contentResolver.delete(it, null, null) } } catch (_: Exception) { }
            holdStatusUntil = SystemClock.elapsedRealtime() + 8000
            status("Não foi possível gravar: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        val length = SystemClock.elapsedRealtime() - recStartedAt
        val r = recorder
        recorder = null
        var ok = true
        try { r?.stop() } catch (_: Exception) { ok = false }   // gravação curta demais lança aqui
        try { r?.release() } catch (_: Exception) { }
        try { recFd?.close() } catch (_: Exception) { }
        recFd = null
        val uri = recUri
        recUri = null
        var small: Bitmap? = null
        if (uri != null) {
            try {
                if (ok) {
                    publish(uri)
                    small = try { contentResolver.loadThumbnail(uri, Size(160, 284), null) } catch (_: Exception) { null }
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (_: Exception) { ok = false }
        }
        holdStatusUntil = SystemClock.elapsedRealtime() + 8000
        status(if (ok) "Vídeo salvo · ${clock(length)} · $videoLabel" else "Gravação curta demais; nada foi salvo.")
        val saved = ok
        ui.post {
            hint(null)
            showMode()
            if (saved && uri != null) {
                lastPhoto = uri
                lastMime = "video/mp4"
                small?.let { thumb.setImageBitmap(it) }
            }
        }
        try { updatePreview() } catch (_: Exception) { }
    }

    /** Durante a gravação travada: solta a exposição e a cor por um instante para se adaptar ao novo ambiente. */
    private fun readjust() {
        cam?.post {
            if (!recording) return@post
            unlockUntil = SystemClock.elapsedRealtime() + 1500
            updatePreview()
            cam?.postDelayed({ if (recording) updatePreview() }, 1600)
        }
    }

    private fun setMode(video: Boolean) {
        if (busy || recording || videoMode == video) return
        videoMode = video
        if (video && wbMode == RawProcessor.WB_AUTO) wbMode = RawProcessor.WB_SENSOR   // o Auto BIT depende do RAW
        showMode()
        if (video && !askedAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askedAudio = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        cam?.post { closeCamera(); openCamera() }
    }

    /** Gira os controles junto com o aparelho: mostra como a foto será salva. */
    private fun showRotation() {
        val angle = when (deviceRotation) { 90 -> -90f; 270 -> 90f; 180 -> 180f; else -> 0f }
        ui.post {
            for (v in listOf(thumb, flip, dngBox, mirrorBox, modePhoto, modeVideo)) v.animate().rotation(angle).setDuration(150).start()
        }
    }

    /** A escolha de inverter é guardada separadamente para a câmera frontal e para a traseira. */
    private fun mirrorKey() = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "mirror_front" else "mirror_back"

    private fun showMode() {
        modePhoto.isSelected = !videoMode
        modeVideo.isSelected = videoMode
        modePhoto.isEnabled = !recording
        modeVideo.isEnabled = !recording
        flip.isEnabled = !recording
        videoPanel.visibility = if (videoMode) View.VISIBLE else View.GONE
        dngBox.visibility = if (videoMode) View.INVISIBLE else View.VISIBLE
        mirrorBox.visibility = if (videoMode) View.INVISIBLE else View.VISIBLE
        wbButtons[RawProcessor.WB_AUTO]?.isEnabled = !videoMode
        vLock.isSelected = lockOnRecord
        vNatural.isSelected = natural
        vStab.isSelected = stabilize
        vReadjust.isEnabled = recording && lockOnRecord
        shutter.setBackgroundResource(if (videoMode) R.drawable.record else R.drawable.shutter)
        shutter.isSelected = recording
        showWb()
        layoutFrame()
    }

    // ------------------------------------------------------------------ captura

    /** Quantos quadros fundir: mais em pouca luz, onde o ruído é maior. */
    private fun burstSize(): Int {
        val iso = lastIso
        var n = if (iso >= 800) 8 else if (iso >= 200) 6 else 4
        val r = rawReader
        if (r != null && r.width.toLong() * r.height > 20_000_000L) n = minOf(n, 4)   // sensores enormes: poupa memória
        return n
    }

    private fun shoot() {
        cam?.post {
            val s = session
            val d = device
            val reader = rawReader
            if (s == null || d == null || reader == null || busy || videoMode) return@post
            busy = true
            val token = ++captureToken
            pendingImage?.close()
            pendingImage = null
            pendingResult = null
            burstGot = 0
            try { Native.burstClear() } catch (_: Throwable) { }
            val n = burstSize()
            burstExpected = n
            collecting = true
            ui.post { shutter.isEnabled = false; hint("Capturando $n quadros… segure firme") }
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                common(b)
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                if (supports(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)) {
                    b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
                }
                // todos os quadros do burst com a mesma exposição e a mesma cor
                if (chars?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (b.get(CaptureRequest.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_AUTO &&
                    chars?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                    b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                val request = b.build()
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(ses: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        if (token != captureToken) return
                        if (pendingResult == null) pendingResult = result
                        processIfReady(false)
                    }

                    override fun onCaptureFailed(ses: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        if (token != captureToken) return
                        burstExpected--   // segue com os quadros que vierem
                        if (burstExpected <= 0) finish("A câmera não entregou a foto. Tente de novo.") else processIfReady(false)
                    }
                }
                if (n > 1) s.captureBurst(List(n) { request }, callback, cam) else s.capture(request, callback, cam)
                // rede de segurança: se faltar quadro, funde os que chegaram; se não veio nenhum, libera o disparador
                cam?.postDelayed({ if (busy && token == captureToken) processIfReady(true) }, 10000)
            } catch (e: Exception) {
                collecting = false
                finish("Falha na captura: ${e.message}")
            }
        }
    }

    private fun onRawImage(reader: ImageReader) {
        val img = try { reader.acquireNextImage() } catch (_: Exception) { null } ?: return
        if (!busy || !collecting || burstGot >= burstExpected) { img.close(); return }
        var stored = false
        try {
            val plane = img.planes[0]
            val count = Native.burstAdd(plane.buffer, img.width, img.height, plane.rowStride)
            if (count > 0) {
                burstGot = count
                burstW = img.width
                burstH = img.height
                stored = true
            } else {
                burstExpected--   // quadro recusado (geometria diferente ou sem memória)
            }
        } catch (_: Throwable) {
            burstExpected--
        }
        // o DNG, quando pedido, é o primeiro quadro: a imagem fica aberta até ser gravada
        if (stored && saveDng && pendingImage == null && burstGot == 1) pendingImage = img else img.close()
        if (burstExpected <= 0 && burstGot == 0) { collecting = false; finish("Não foi possível guardar o RAW."); return }
        processIfReady(false)
    }

    private fun processIfReady(force: Boolean) {
        if (!busy || !collecting) return
        val res = pendingResult
        if (!force && (burstGot < burstExpected || res == null)) return
        collecting = false
        captureToken++   // desarma a rede de segurança e ignora quadros atrasados
        val img = pendingImage
        pendingImage = null
        pendingResult = null
        val c = chars
        if (res == null || burstGot < 1 || c == null) {
            img?.close()
            try { Native.burstClear() } catch (_: Throwable) { }
            finish(if (c == null) "Câmera fechada durante a captura." else "A câmera demorou demais para entregar o RAW.")
            return
        }
        val calibration = cal
        val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
        val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotation = if (isFront) (sensorOrientation - deviceRotation + 360) % 360 else (sensorOrientation + deviceRotation) % 360
        val mode = wbMode
        val k = kelvin
        val t = tint
        val w = burstW
        val h = burstH
        val got = burstGot
        val mirror = mirrorPhoto
        ui.post { hint(if (got > 1) "Fundindo $got quadros…" else "Revelando…") }
        worker.execute { develop(img, w, h, c, res, calibration, rotation, mirror, mode, k, t) }
    }

    private fun develop(
        dngImage: Image?, w: Int, h: Int, c: CameraCharacteristics, res: TotalCaptureResult, calibration: FloatArray,
        rotation: Int, mirror: Boolean, mode: Int, k: Float, t: Float
    ) {
        val t0 = SystemClock.elapsedRealtime()
        val name = "BITCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var message: String
        try {
            var dngNote = ""
            if (dngImage != null) {
                dngNote = try { saveDng(name, c, res, dngImage, rotation); " + DNG" } catch (e: Exception) { " (DNG falhou)" }
                try { dngImage.close() } catch (_: Exception) { }
            }
            val out = RawProcessor.develop(w, h, c, res, calibration, rotation, mirror, mode, k, t)
            if (out == null) {
                message = "Não foi possível revelar este RAW" + if (dngNote == " + DNG") " (o DNG foi salvo)." else "."
            } else {
                val uri = saveJpeg(name, out.bitmap, res, out.debug)
                val small = Bitmap.createScaledBitmap(out.bitmap, 160, 160 * out.bitmap.height / out.bitmap.width, true)
                out.bitmap.recycle()
                val modeName = when (out.wbUsed) {
                    RawProcessor.WB_AUTO -> "Auto BIT"
                    RawProcessor.WB_KELVIN -> "Kelvin"
                    else -> if (mode == RawProcessor.WB_AUTO) "Motorola (cena sem neutro)" else "Motorola"
                }
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000f
                val burstText = if (out.frames > 1) String.format(Locale.US, "%d quadros (%.1f fundidos)", out.frames, out.meanFrames) else "1 quadro"
                message = String.format(Locale.US, "%s %d K, tint %+d · %s · %s · %.1f s%s",
                    modeName, (out.kelvin / 10).roundToInt() * 10, out.tint.roundToInt(), exposureText(res), burstText, secs, dngNote)
                ui.post {
                    lastPhoto = uri
                    lastMime = "image/jpeg"
                    lastEstimate = out.kelvin to out.tint
                    wbLock.isEnabled = true
                    thumb.setImageBitmap(small)
                }
            }
        } catch (e: Throwable) {
            message = "Erro ao revelar: ${e.javaClass.simpleName} ${e.message ?: ""}"
            try { Native.burstClear() } catch (_: Throwable) { }
        } finally {
            try { dngImage?.close() } catch (_: Exception) { }
        }
        finish(message)
    }

    private fun finish(message: String) {
        holdStatusUntil = SystemClock.elapsedRealtime() + 8000
        busy = false
        status(message)
        ui.post {
            shutter.isEnabled = true
            hint(null)
        }
    }

    // ------------------------------------------------------------------ gravação

    private fun insert(name: String, mime: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/BITCAM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("galeria indisponível")
    }

    private fun publish(uri: Uri) {
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private fun saveDng(name: String, c: CameraCharacteristics, res: TotalCaptureResult, img: Image, rotation: Int) {
        val uri = insert("$name.dng", "image/x-adobe-dng")
        try {
            DngCreator(c, res).use { creator ->
                creator.setOrientation(when (rotation) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                })
                contentResolver.openOutputStream(uri)!!.use { creator.writeImage(it, img) }
            }
            publish(uri)
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
    }

    private fun saveJpeg(name: String, bmp: Bitmap, res: CaptureResult, debug: String): Uri {
        val uri = insert("$name.jpg", "image/jpeg")
        try {
            contentResolver.openOutputStream(uri)!!.use {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw IllegalStateException("falha ao comprimir")
            }
            try {
                contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
                    val exif = ExifInterface(fd.fileDescriptor)
                    exif.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
                    exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                    val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null } ?: "?"
                    exif.setAttribute(ExifInterface.TAG_SOFTWARE, "BITCAM $version")
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, debug)   // diagnóstico da revelação
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
                    res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
                    res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString()) }
                    res.get(CaptureResult.LENS_APERTURE)?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
                    res.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).roundToInt()}/1000") }
                    exif.saveAttributes()
                }
            } catch (_: Exception) {
                // EXIF é opcional: a foto já está gravada
            }
            publish(uri)
            return uri
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
    }

    // ------------------------------------------------------------------ interface

    private fun status(text: String) = ui.post { statusView.text = text }

    private fun hint(text: String?) {
        hintView.text = text ?: ""
        hintView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun showWb() {
        for ((mode, button) in wbButtons) button.isSelected = mode == wbMode
        kelvinPanel.visibility = if (wbMode == RawProcessor.WB_KELVIN) View.VISIBLE else View.GONE
        kelvinLabel.text = "Temperatura: ${kelvin.roundToInt()} K"
        tintLabel.text = String.format(Locale.US, "Tint: %+d  (− verde · + magenta)", tint.roundToInt())
    }

    private fun setWb(mode: Int) {
        wbMode = mode
        showWb()
        cam?.post { updatePreview() }
    }

    private fun setKelvin(k: Float, t: Float) {
        kelvin = k.coerceIn(2000f, 10000f)
        tint = t.coerceIn(-50f, 50f)
        kelvinSeek.progress = ((kelvin - 2000f) / 50f).roundToInt()
        tintSeek.progress = (tint + 50f).roundToInt()
        showWb()
        cam?.post { updatePreview() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        shutter.setOnClickListener { if (videoMode) toggleRecord() else shoot() }
        modePhoto.setOnClickListener { setMode(false) }
        modeVideo.setOnClickListener { setMode(true) }
        vLock.setOnClickListener { lockOnRecord = !lockOnRecord; showMode(); cam?.post { updatePreview() } }
        vNatural.setOnClickListener { natural = !natural; showMode(); cam?.post { updatePreview() } }
        vStab.setOnClickListener { stabilize = !stabilize; showMode(); cam?.post { updatePreview() } }
        vReadjust.setOnClickListener { readjust() }
        for ((mode, button) in wbButtons) button.setOnClickListener { setWb(mode) }
        wbLock.setOnClickListener {
            lastEstimate?.let { (k, t) ->
                wbMode = RawProcessor.WB_KELVIN
                setKelvin(k, t)
            }
        }
        kelvinSeek.progress = ((kelvin - 2000f) / 50f).roundToInt()
        tintSeek.progress = 50
        kelvinSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                kelvin = 2000f + p * 50f
                showWb()
                cam?.post { updatePreview() }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        tintSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                tint = p - 50f
                showWb()
                cam?.post { updatePreview() }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val presets = findViewById<LinearLayout>(R.id.presets)
        for ((label, k) in listOf("Tungstênio" to 2850f, "Fluorescente" to 4000f, "Sol" to 5500f, "Nublado" to 6500f, "Sombra" to 7500f)) {
            val b = Button(this, null, 0, R.style.Chip)
            b.text = label
            b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (36 * resources.displayMetrics.density).toInt()).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
            b.setOnClickListener { setKelvin(k, 0f) }
            presets.addView(b)
        }
        dngBox.setOnCheckedChangeListener { _, on -> saveDng = on }
        mirrorBox.setOnCheckedChangeListener { _, on ->
            mirrorPhoto = on
            getPreferences(MODE_PRIVATE).edit().putBoolean(mirrorKey(), on).apply()
        }
        flip.setOnClickListener {
            if (busy || recording) return@setOnClickListener
            facing = if (facing == CameraCharacteristics.LENS_FACING_FRONT) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            cam?.post { closeCamera(); openCamera() }
        }
        thumb.setOnClickListener {
            val uri = lastPhoto ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                status("Nenhum app de galeria abriu a foto. Ela está em DCIM/BITCAM.")
            }
        }
        preview.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP && v.width > 0 && v.height > 0) {
                val u = (e.x / v.width).coerceIn(0f, 1f)
                val vv = (e.y / v.height).coerceIn(0f, 1f)
                focusRing.translationX = e.x - focusRing.width / 2f
                focusRing.translationY = e.y - focusRing.height / 2f
                focusRing.visibility = View.VISIBLE
                if (focusRing.width == 0) focusRing.post {
                    focusRing.translationX = e.x - focusRing.width / 2f
                    focusRing.translationY = e.y - focusRing.height / 2f
                }
                cam?.post { focusAt(u, vv) }
            }
            true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0) { if (videoMode) toggleRecord() else shoot() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }
}
