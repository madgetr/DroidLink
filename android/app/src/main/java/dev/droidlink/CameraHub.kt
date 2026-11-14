package dev.droidlink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Keeps a camera open and streaming frames into memory, so a snapshot is just
 * "encode the latest frame" (~100 ms) instead of opening the camera each time.
 * The camera closes itself after [IDLE_CLOSE_MS] without use to save battery.
 */
@SuppressLint("MissingPermission") // CAMERA is requested by MainActivity
class CameraHub(ctx: Context, private val ambientLux: () -> Float?) {

    class Frame(val nv21: ByteArray, val width: Int, val height: Int, val ts: Double)
    class Shot(val jpeg: ByteArray, val ts: Double, val torch: Boolean, val luma: Int)

    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val lock = Any()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var openId: String? = null
    @Volatile private var latest: Frame? = null
    @Volatile private var frames = 0L
    @Volatile private var lastConvert = 0L
    @Volatile private var lastUse = 0L

    // Flashlight: "auto" turns it on when the picture is too dark and off again
    // when the room is bright, the camera goes idle, or the camera closes.
    private val settings = Settings(ctx)
    val torchMode: String get() = settings.torchMode   // saved; set from the app or API
    @Volatile private var torchOn = false
    @Volatile private var torchCameraId: String? = null   // set when lit via CameraManager
    @Volatile private var torchSince = 0L

    init {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastUse
                if (torchOn && torchMode == "auto" && idle > TORCH_IDLE_OFF_MS) {
                    Log.i(TAG, "idle, flashlight off")
                    synchronized(lock) { setTorch(false) }
                }
                if (openId != null && idle > IDLE_CLOSE_MS) {
                    Log.i(TAG, "idle, closing camera")
                    synchronized(lock) { close() }
                }
                handler.postDelayed(this, 5_000)
            }
        }, 5_000)
    }

    /** "on", "off" or "auto" (saved). */
    fun setTorchMode(mode: String): JSONObject {
        require(mode in listOf("on", "off", "auto")) { "torch mode must be on, off or auto" }
        synchronized(lock) {
            settings.torchMode = mode
            when (mode) {
                "on" -> setTorch(true)
                "off" -> setTorch(false)
            }
        }
        return status()
    }

    /** Switch the light off now without changing the saved mode (auto may relight it). */
    fun lightOff(): JSONObject {
        synchronized(lock) { setTorch(false) }
        return status()
    }

    fun list(): JSONArray = JSONArray(cm.cameraIdList.map { id ->
        val c = cm.getCameraCharacteristics(id)
        JSONObject()
            .put("id", id.toInt())
            .put("facing", when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "external"
            })
            .put("focal_lengths", JSONArray(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList<Float>()))
            .put("sensor_orientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
            .put("flash", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
    })

    /**
     * JPEG of the latest frame from camera [id], scaled to [width] px wide after
     * rotating by [rotate] degrees (default: the camera's sensor orientation, which
     * makes images upright when the phone is in portrait).
     */
    fun jpeg(id: String, width: Int, quality: Int, rotate: Int?,
             draw: ((android.graphics.Canvas, Int, Int) -> Unit)? = null): Shot {
        lastUse = System.currentTimeMillis()
        ensureOpen(id)
        autoTorch()
        val f = latest ?: throw Unavailable("no camera frame yet")
        val rot = rotate ?: (cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)

        val full = ByteArrayOutputStream()
        YuvImage(f.nv21, ImageFormat.NV21, f.width, f.height, null)
            .compressToJpeg(Rect(0, 0, f.width, f.height), 92, full)

        val rotatedW = if (rot % 180 == 0) f.width else f.height
        val scale = (width.toFloat() / rotatedW).coerceAtMost(1f)
        var sample = 1
        while (scale * sample * 2 <= 1f) sample *= 2   // decode at reduced size when we can
        val bmp = BitmapFactory.decodeByteArray(full.toByteArray(), 0, full.size(),
            BitmapFactory.Options().apply { inSampleSize = sample })
        val m = Matrix().apply {
            val s = scale * sample
            postScale(s, s)
            postRotate(rot.toFloat())
        }
        var out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (draw != null) {   // e.g. tracking overlay on the live stream
            if (!out.isMutable) out = out.copy(Bitmap.Config.ARGB_8888, true)
            draw(android.graphics.Canvas(out), out.width, out.height)
        }
        val result = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, result)
        bmp.recycle(); out.recycle()
        return Shot(result.toByteArray(), f.ts, torchOn, luma(f))
    }

    fun status(): JSONObject = JSONObject()
        .put("open", openId ?: JSONObject.NULL)
        .put("frames", frames)
        .put("frame_size", latest?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL)
        .put("torch", torchOn)
        .put("torch_mode", torchMode)
        .put("torch_recheck_s", settings.torchRecheckS)
        .put("luma", latest?.let { luma(it) } ?: JSONObject.NULL)
        .put("ambient_lux", ambientLux() ?: JSONObject.NULL)

    // ------------------------------------------------------------ flashlight

    /** In auto mode, light up a dark scene and switch off once it's bright. */
    private fun autoTorch() = synchronized(lock) {
        if (torchMode != "auto") return@synchronized
        if (openId == null) return@synchronized
        val f = latest ?: return@synchronized
        try {
            if (!torchOn && luma(f) < DARK_LUMA) {
                Log.i(TAG, "scene dark (luma ${luma(f)}), flashlight on")
                setTorch(true)
                waitFrames(12, 1_000)        // let auto-exposure adjust to the light
            } else if (torchOn && System.currentTimeMillis() - torchSince > settings.torchRecheckS * 1000L) {
                // With the light on, the picture always looks bright, so we can't
                // tell from it whether the light is still needed. The phone's light
                // sensor faces the other way and reads low, so it's only a shortcut.
                // The reliable test: switch off, let exposure settle, and look.
                val lux = ambientLux()
                setTorch(false)
                if (lux != null && lux > BRIGHT_LUX) {
                    Log.i(TAG, "room bright ($lux lux), flashlight off")
                    waitFrames(12, 1_000)
                } else {
                    waitFrames(15, 1_200)
                    val l = latest?.let { luma(it) } ?: 0
                    if (l < BRIGHT_LUMA) {
                        setTorch(true)               // still dark: back on
                        waitFrames(12, 1_000)
                    } else {
                        Log.i(TAG, "bright without flashlight (luma $l), leaving it off")
                    }
                }
            }
        } catch (e: Unavailable) {
            Log.w(TAG, "auto flashlight skipped: ${e.message}")   // never fail the photo over it
        }
    }

    /**
     * Switch the flashlight. While our camera is open it must go through the
     * camera's own capture request; otherwise use the system torch.
     */
    private fun setTorch(on: Boolean) {
        if (on == torchOn) return
        val open = openId
        try {
            if (open != null && hasFlash(open)) {
                torchOn = on
                startRepeating()
            } else {
                // Camera closed, or a lens without its own flash (e.g. the ultrawide,
                // which sits right next to the LED): use the system flashlight.
                val id = torchCameraId ?: cm.cameraIdList.firstOrNull { hasFlash(it) }
                    ?: throw Unavailable("no camera with a flashlight")
                cm.setTorchMode(id, on)
                torchCameraId = if (on) id else null
                torchOn = on
            }
            if (on) torchSince = System.currentTimeMillis()
        } catch (e: Unavailable) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "flashlight change failed", e)
            throw Unavailable("flashlight unavailable: ${e.message}")
        }
    }

    private fun hasFlash(id: String) =
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    /**
     * A tiny 16x12 brightness map of the latest frame from camera [id], for telling
     * whether the view is changing (are we actually moving?). Null if no frame yet.
     */
    fun fingerprint(id: String): IntArray? {
        lastUse = System.currentTimeMillis()
        ensureOpen(id)
        val f = latest ?: return null
        val gw = FP_W; val gh = FP_H
        val out = IntArray(gw * gh)
        val bw = f.width / gw; val bh = f.height / gh
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var sum = 0; var n = 0
            // Average each block (sampled) so motor vibration doesn't look like motion
            for (y in gy * bh until (gy + 1) * bh step 4) {
                val row = y * f.width
                for (x in gx * bw until (gx + 1) * bw step 4) { sum += f.nv21[row + x].toInt() and 0xff; n++ }
            }
            out[gy * gw + gx] = if (n == 0) 0 else sum / n
        }
        return out
    }

    /** [fingerprint], but only if camera [id] is already running (never opens it). */
    fun fingerprintIfOpen(id: String): IntArray? =
        if (openId == id && device != null && System.currentTimeMillis() - lastUse < 5_000) fingerprint(id) else null

    /** Average brightness (0-255) of a frame, sampled on a coarse grid. */
    private fun luma(f: Frame): Int {
        var sum = 0L
        var n = 0
        for (y in 0 until f.height step 16) {
            val row = y * f.width
            for (x in 0 until f.width step 16) {
                sum += f.nv21[row + x].toInt() and 0xff
                n++
            }
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    private fun waitFrames(n: Int, timeoutMs: Long) {
        val start = frames
        val deadline = System.currentTimeMillis() + timeoutMs
        while (frames - start < n && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(120)   // make sure `latest` is a frame from after the change
    }

    fun shutdown() {
        synchronized(lock) {
            close()
            torchCameraId?.let { try { cm.setTorchMode(it, false) } catch (_: Exception) {} }
            torchCameraId = null; torchOn = false
        }
        thread.quitSafely()
    }

    // ----------------------------------------------------------- internals

    private fun ensureOpen(id: String) = synchronized(lock) {
        if (id !in cm.cameraIdList) throw IllegalArgumentException("no camera $id (see /camera/list)")
        if (openId == id && device != null) return@synchronized
        close()
        try {
            open(id)
        } catch (e: Exception) {
            close()   // don't leave a half-open camera behind
            throw if (e is Unavailable || e is IllegalArgumentException) e
                  else Unavailable("camera $id unavailable: ${e.cause?.message ?: e.message}")
        }
    }

    private fun open(id: String) {
        // The system torch would block the camera; it's re-lit via the capture request if needed
        if (torchCameraId != null) {
            try { cm.setTorchMode(torchCameraId!!, false) } catch (_: Exception) {}
            torchCameraId = null
            torchOn = false
        }
        val chars = cm.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw Unavailable("camera $id has no stream config")
        val size = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888))

        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            img.use {
                frames++
                // Converting every frame is wasteful; ~10 per second is plenty.
                val t = System.currentTimeMillis()
                if (t - lastConvert >= 100) {
                    lastConvert = t
                    latest = Frame(toNv21(it), it.width, it.height, now())
                }
            }
        }, handler)
        reader = r

        val opened = CompletableFuture<CameraDevice>()
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { opened.complete(d) }
            override fun onDisconnected(d: CameraDevice) { d.close(); if (device === d) { device = null; openId = null } }
            override fun onError(d: CameraDevice, error: Int) {
                d.close(); opened.completeExceptionally(Unavailable("camera $id error $error"))
            }
        }, handler)
        val d = opened.get(5, TimeUnit.SECONDS)
        device = d

        val configured = CompletableFuture<CameraCaptureSession>()
        d.createCaptureSession(SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(r.surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { configured.complete(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configured.completeExceptionally(Unavailable("camera session failed"))
                }
            }))
        val s = configured.get(5, TimeUnit.SECONDS)
        session = s

        openId = id
        startRepeating()
        if (torchMode == "on") {
            try { setTorch(true) } catch (e: Unavailable) { Log.w(TAG, "flashlight: ${e.message}") }
        }
        latest = null

        // Let auto-exposure settle so the first snapshot isn't dark.
        val start = frames
        val deadline = System.currentTimeMillis() + 2_000
        while (frames - start < 10 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Log.i(TAG, "camera $id open at ${size.width}x${size.height}")
    }

    /** (Re)start the camera's frame stream, with the flashlight on or off. */
    private fun startRepeating() {
        val d = device ?: return
        val s = session ?: return
        val r = reader ?: return
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(r.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            // Torch needs plain auto-exposure (not an auto-flash AE mode)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.FLASH_MODE,
                if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }.build()
        s.setRepeatingRequest(req, null, handler)
    }

    private fun close() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null; openId = null; latest = null
        // Closing the camera turns its flashlight off; keep our state honest.
        if (torchCameraId == null) torchOn = false
    }

    /** Largest 4:3 size up to 1600 px wide (plenty for vision models, light on CPU). */
    private fun pickSize(sizes: Array<Size>): Size =
        sizes.filter { it.width <= 1600 && it.width * 3 == it.height * 4 }.maxByOrNull { it.width }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.first()

    private fun toNv21(img: Image): ByteArray = Companion.toNv21(img)

    companion object {
        /** YUV_420_888 image -> NV21 bytes. */
        fun toNv21(img: Image): ByteArray {
            val w = img.width
            val h = img.height
            val out = ByteArray(w * h * 3 / 2)
            val y = img.planes[0]
            val yBuf = y.buffer
            var pos = 0
            if (y.pixelStride == 1 && y.rowStride == w) {
                yBuf.get(out, 0, w * h); pos = w * h
            } else {
                for (row in 0 until h) {
                    yBuf.position(row * y.rowStride)
                    yBuf.get(out, pos, w); pos += w
                }
            }
            val u = img.planes[1].buffer
            val v = img.planes[2].buffer
            val rowStride = img.planes[2].rowStride
            val pixStride = img.planes[2].pixelStride
            for (row in 0 until h / 2) {
                for (col in 0 until w / 2) {
                    val i = row * rowStride + col * pixStride
                    out[pos++] = v.get(i)
                    out[pos++] = u.get(i)
                }
            }
            return out
        }

        private const val TAG = "CameraHub"
        private const val IDLE_CLOSE_MS = 60_000L
        private const val TORCH_IDLE_OFF_MS = 30_000L   // flashlight off after 30 s without a snapshot
        private const val DARK_LUMA = 45                  // average brightness (0-255) that counts as dark
        private const val BRIGHT_LUMA = 60                // ...and bright enough to switch off (gap avoids flicker)
        private const val BRIGHT_LUX = 60f                // room light at which the flashlight goes off
    }
}

/** Size of a view fingerprint (see CameraHub.fingerprint, MotionController.viewChange). */
const val FP_W = 32
const val FP_H = 24
