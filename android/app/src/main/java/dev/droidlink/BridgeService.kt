package dev.droidlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.IOException

/**
 * Foreground service that owns the sensors, camera, BLE and motion control, and
 * serves them over HTTP. Running in the foreground keeps Android from killing it.
 *
 * The /health, /sensors and /camera/snapshot endpoints match the original Termux
 * FastAPI server, so the laptop agents work against either.
 */
class BridgeService : Service() {

    private lateinit var ble: BleManager
    private lateinit var sensors: SensorHub
    private lateinit var camera: CameraHub
    private lateinit var motion: MotionController
    private var server: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val startTime = now()

    override fun onCreate() {
        super.onCreate()
        ble = BleManager(this)
        sensors = SensorHub(this)
        camera = CameraHub(this)
        motion = MotionController(sensors, NoMotorDriver())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        if (server == null) {
            sensors.start()
            server = HttpServer(PORT, ::route).also { it.start() }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidLink::bridge")
                .apply { acquire() }
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        motion.stop()
        server?.stop()
        server = null
        sensors.stop()
        camera.shutdown()
        ble.disconnect()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ API

    private fun route(path: String, p: Map<String, String>): Response {
        fun need(key: String) = p[key]?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing parameter '$key'")
        fun num(key: String) = need(key).toDoubleOrNull()
            ?: throw IllegalArgumentException("'$key' must be a number")
        fun int(key: String, default: Int) = p[key]?.toIntOrNull() ?: default
        fun flag(key: String, default: Boolean) = p[key]?.let { it == "1" || it == "true" } ?: default

        return when {
            path == "/" || path == "/health" -> Json(JSONObject()
                .put("status", "ok")
                .put("app", "droidlink")
                .put("version", BuildConfig.VERSION_NAME)
                .put("uptime_s", Math.round((now() - startTime) * 10) / 10.0))

            // --- sensors
            path == "/sensors" -> Json(sensors.snapshot())
            path.startsWith("/sensors/") -> {
                val name = path.removePrefix("/sensors/")
                Json(sensors.one(name) ?: throw Unavailable("no reading for '$name'"))
            }

            // --- camera
            path == "/camera/list" -> Json(JSONObject().put("cameras", camera.list()))
            path == "/camera/status" -> Json(camera.status())
            path == "/camera/snapshot" -> snapshot(
                int("camera", 0).toString(), int("width", 1280).coerceIn(160, 1600),
                int("quality", 80).coerceIn(10, 95), p["rotate"]?.toIntOrNull())
            path == "/camera/stream" -> stream(
                int("camera", 0).toString(), int("width", 640).coerceIn(160, 1600),
                int("quality", 70).coerceIn(10, 95), p["rotate"]?.toIntOrNull(),
                (p["fps"]?.toDoubleOrNull() ?: 5.0).coerceIn(0.5, 15.0))

            // --- motion
            path == "/control/status" -> Json(motion.status())
            path == "/control/stop" -> Json(motion.stop())
            path == "/control/turn" -> Json(motion.turn(num("degrees").coerceIn(-180.0, 180.0)))
            path == "/control/drive" -> Json(motion.drive(num("cm").coerceIn(-100.0, 200.0)))

            // --- raw BLE
            path == "/ble/status" -> Json(ble.status())
            path == "/ble/scan" -> {
                val seconds = (p["seconds"]?.toDoubleOrNull() ?: 4.0).coerceIn(0.5, 20.0)
                Json(JSONObject().put("devices", ble.scan(seconds, p["name"])))
            }
            path == "/ble/connect" -> Json(ble.connect(need("address")))
            path == "/ble/disconnect" -> { ble.disconnect(); Json(ble.status()) }
            path == "/ble/services" -> Json(JSONObject().put("services", ble.services()))
            path == "/ble/write" -> Json(ble.write(need("char"), need("hex"), flag("response", true)))
            path == "/ble/read" -> Json(ble.read(need("char")))
            path == "/ble/notify" -> Json(ble.setNotify(need("char"), flag("enable", true)))
            path == "/ble/notifications" -> Json(ble.notifications(p["since"]?.toLongOrNull() ?: 0))

            else -> Json(JSONObject().put("detail", "unknown path $path"), 404)
        }
    }

    private fun poseHeaders(): Map<String, String> {
        if (sensors.azimuth.isNaN()) return emptyMap()
        return mapOf(
            "X-Azimuth" to "%.1f".format(sensors.azimuth),
            "X-Pitch" to "%.1f".format(sensors.pitch),
            "X-Roll" to "%.1f".format(sensors.roll),
        )
    }

    private fun snapshot(id: String, width: Int, quality: Int, rotate: Int?): Response {
        val pose = poseHeaders() // pose at (just before) capture
        val (jpeg, ts) = camera.jpeg(id, width, quality, rotate)
        return Bytes("image/jpeg", jpeg, pose + mapOf("X-Capture-Ts" to "%.3f".format(ts), "X-Camera" to id))
    }

    /** MJPEG stream: open http://<phone>:8765/camera/stream in a browser. */
    private fun stream(id: String, width: Int, quality: Int, rotate: Int?, fps: Double): Response =
        Stream("multipart/x-mixed-replace; boundary=frame") { out ->
            val period = (1000 / fps).toLong()
            while (true) {
                val t = System.currentTimeMillis()
                val (jpeg, _) = camera.jpeg(id, width, quality, rotate)
                try {
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                    out.write(jpeg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                } catch (e: IOException) {
                    break // viewer closed the stream
                }
                Thread.sleep((period - (System.currentTimeMillis() - t)).coerceAtLeast(0))
            }
        }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Droid bridge", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("DroidLink running")
            .setContentText("Bridge API on port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8765
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 1
        @Volatile var running = false
    }
}
