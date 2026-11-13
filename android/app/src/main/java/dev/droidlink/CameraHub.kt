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
class CameraHub(ctx: Context) {

    class Frame(val nv21: ByteArray, val width: Int, val height: Int, val ts: Double)

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

    init {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (openId != null && System.currentTimeMillis() - lastUse > IDLE_CLOSE_MS) {
                    Log.i(TAG, "idle, closing camera")
                    synchronized(lock) { close() }
                }
                handler.postDelayed(this, 10_000)
            }
        }, 10_000)
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
    })

    /**
     * JPEG of the latest frame from camera [id], scaled to [width] px wide after
     * rotating by [rotate] degrees (default: the camera's sensor orientation, which
     * makes images upright when the phone is in portrait).
     */
    fun jpeg(id: String, width: Int, quality: Int, rotate: Int?): Pair<ByteArray, Double> {
        lastUse = System.currentTimeMillis()
        ensureOpen(id)
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
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val result = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, result)
        bmp.recycle(); out.recycle()
        return result.toByteArray() to f.ts
    }

    fun status(): JSONObject = JSONObject()
        .put("open", openId ?: JSONObject.NULL)
        .put("frames", frames)
        .put("frame_size", latest?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL)

    fun shutdown() {
        synchronized(lock) { close() }
        thread.quitSafely()
    }

    // ----------------------------------------------------------- internals

    private fun ensureOpen(id: String) = synchronized(lock) {
        if (openId == id && device != null) return@synchronized
        close()
        open(id)
    }

    private fun open(id: String) {
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

        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(r.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }.build()
        s.setRepeatingRequest(req, null, handler)
        openId = id
        latest = null

        // Let auto-exposure settle so the first snapshot isn't dark.
        val start = frames
        val deadline = System.currentTimeMillis() + 2_000
        while (frames - start < 10 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Log.i(TAG, "camera $id open at ${size.width}x${size.height}")
    }

    private fun close() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null; openId = null; latest = null
    }

    /** Largest 4:3 size up to 1600 px wide (plenty for vision models, light on CPU). */
    private fun pickSize(sizes: Array<Size>): Size =
        sizes.filter { it.width <= 1600 && it.width * 3 == it.height * 4 }.maxByOrNull { it.width }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.first()

    private fun toNv21(img: Image): ByteArray {
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

    companion object {
        private const val TAG = "CameraHub"
        private const val IDLE_CLOSE_MS = 60_000L
    }
}
