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
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
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
    private var lastStatusAt = 0L
    @Volatile private var holdStatusUntil = 0L   // mantém o resultado da foto legível

    // ---- estado compartilhado
    @Volatile private var facing = CameraCharacteristics.LENS_FACING_BACK
    @Volatile private var busy = false
    @Volatile private var wbMode = RawProcessor.WB_AUTO
    @Volatile private var kelvin = 5200f
    @Volatile private var tint = 0f
    @Volatile private var deviceRotation = 0
    @Volatile private var saveDng = true
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
        wbButtons = mapOf(
            RawProcessor.WB_AUTO to findViewById(R.id.wbAuto),
            RawProcessor.WB_SENSOR to findViewById(R.id.wbSensor),
            RawProcessor.WB_KELVIN to findViewById<Button>(R.id.wbKelvin)
        )

        preview.holder.addCallback(this)
        setupControls()
        showWb()

        // mantém o visor em 3:4 (retrato) dentro do espaço disponível
        val stage = findViewById<View>(R.id.stage)
        stage.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = minOf(v.width, v.height * 3 / 4)
            val h = w * 4 / 3
            val lp = frame.layoutParams as FrameLayout.LayoutParams
            if (w > 0 && (lp.width != w || lp.height != h)) {
                lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
                frame.post { frame.layoutParams = lp }
            }
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) deviceRotation = ((o + 45) / 90 * 90) % 360
            }
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
            previewSize = sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width }
                ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                ?: Size(1440, 1080)
            val ps = previewSize
            ui.post { preview.holder.setFixedSize(ps.width, ps.height) }

            rawReader = ImageReader.newInstance(raw.width, raw.height, ImageFormat.RAW_SENSOR, 2).apply {
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
            val rawText = "RAW ${raw.width}×${raw.height}"
            ui.post { flip.text = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "Traseira" else "Frontal" }
            status("$rawText · abrindo…")
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
        closeSession()
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { rawReader?.close() } catch (_: Exception) { }
        rawReader = null
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
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
            val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface))
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
                        status("A câmera recusou a configuração RAW + visor.")
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
        } else if (supports(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (supports(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        applyWb(b)
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
            val now = SystemClock.elapsedRealtime()
            if (!busy && now > holdStatusUntil && now - lastStatusAt > 400) {
                lastStatusAt = now
                val r = rawReader
                status("RAW ${r?.width}×${r?.height} · ${exposureText(result)}")
            }
        }
    }

    private fun updatePreview() {
        val s = session ?: return
        val d = device ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview.holder.surface)
            common(b)
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
        val half = (minOf(w, h) * 0.06f).toInt()
        val cx = (sx * w).toInt().coerceIn(half, w - half)
        val cy = (sy * h).toInt().coerceIn(half, h - half)
        afRegion = MeteringRectangle(cx - half, cy - half, half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX)
        val s = session ?: return
        val d = device ?: return
        try {
            updatePreview()
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview.holder.surface)
            common(b)
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
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview.holder.surface)
            common(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, cam)
            updatePreview()
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ captura

    private fun shoot() {
        cam?.post {
            val s = session
            val d = device
            val reader = rawReader
            if (s == null || d == null || reader == null || busy) return@post
            busy = true
            val token = ++captureToken
            pendingImage?.close()
            pendingImage = null
            pendingResult = null
            ui.post { shutter.isEnabled = false; hint("Capturando RAW…") }
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                common(b)
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                if (supports(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)) {
                    b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
                }
                s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(ses: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        if (token != captureToken) return
                        pendingResult = result
                        processIfReady()
                    }

                    override fun onCaptureFailed(ses: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        if (token == captureToken) finish("A câmera não entregou a foto. Tente de novo.")
                    }
                }, cam)
                // rede de segurança: se o quadro nunca chegar, libera o disparador
                cam?.postDelayed({ if (busy && token == captureToken && (pendingImage == null || pendingResult == null)) finish("A câmera demorou demais para entregar o RAW.") }, 8000)
            } catch (e: Exception) {
                finish("Falha na captura: ${e.message}")
            }
        }
    }

    private fun onRawImage(reader: ImageReader) {
        val img = try { reader.acquireNextImage() } catch (_: Exception) { null } ?: return
        if (!busy || pendingImage != null) { img.close(); return }
        pendingImage = img
        processIfReady()
    }

    private fun processIfReady() {
        val img = pendingImage ?: return
        val res = pendingResult ?: return
        val c = chars
        pendingImage = null
        pendingResult = null
        captureToken++   // desarma a rede de segurança
        if (c == null) { img.close(); finish("Câmera fechada durante a captura."); return }
        val calibration = cal
        val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
        val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotation = if (isFront) (sensorOrientation - deviceRotation + 360) % 360 else (sensorOrientation + deviceRotation) % 360
        val mode = wbMode
        val k = kelvin
        val t = tint
        val dng = saveDng
        ui.post { hint("Revelando…") }
        worker.execute { develop(img, c, res, calibration, rotation, mode, k, t, dng) }
    }

    private fun develop(
        img: Image, c: CameraCharacteristics, res: TotalCaptureResult, calibration: FloatArray,
        rotation: Int, mode: Int, k: Float, t: Float, dng: Boolean
    ) {
        val t0 = SystemClock.elapsedRealtime()
        val name = "BITCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var message: String
        try {
            var dngNote = ""
            if (dng) {
                dngNote = try { saveDng(name, c, res, img, rotation); " + DNG" } catch (e: Exception) { " (DNG falhou)" }
            }
            val out = RawProcessor.develop(img, c, res, calibration, rotation, mode, k, t)
            if (out == null) {
                message = "Não foi possível revelar este RAW" + if (dngNote == " + DNG") " (o DNG foi salvo)." else "."
            } else {
                val uri = saveJpeg(name, out.bitmap, res)
                val small = Bitmap.createScaledBitmap(out.bitmap, 160, 160 * out.bitmap.height / out.bitmap.width, true)
                out.bitmap.recycle()
                val modeName = when (out.wbUsed) {
                    RawProcessor.WB_AUTO -> "Auto BIT"
                    RawProcessor.WB_KELVIN -> "Kelvin"
                    else -> if (mode == RawProcessor.WB_AUTO) "Motorola (cena sem neutro)" else "Motorola"
                }
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000f
                message = String.format(Locale.US, "%s %d K, tint %+d · %s · %.1f s%s",
                    modeName, (out.kelvin / 10).roundToInt() * 10, out.tint.roundToInt(), exposureText(res), secs, dngNote)
                ui.post {
                    lastPhoto = uri
                    lastEstimate = out.kelvin to out.tint
                    wbLock.isEnabled = true
                    thumb.setImageBitmap(small)
                }
            }
        } catch (e: Throwable) {
            message = "Erro ao revelar: ${e.javaClass.simpleName} ${e.message ?: ""}"
        } finally {
            try { img.close() } catch (_: Exception) { }
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

    private fun saveJpeg(name: String, bmp: Bitmap, res: CaptureResult): Uri {
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
                    exif.setAttribute(ExifInterface.TAG_SOFTWARE, "BITCAM")
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
        shutter.setOnClickListener { shoot() }
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
        flip.setOnClickListener {
            if (busy) return@setOnClickListener
            facing = if (facing == CameraCharacteristics.LENS_FACING_FRONT) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            cam?.post { closeCamera(); openCamera() }
        }
        thumb.setOnClickListener {
            val uri = lastPhoto ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
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
            if (event.repeatCount == 0) shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }
}
