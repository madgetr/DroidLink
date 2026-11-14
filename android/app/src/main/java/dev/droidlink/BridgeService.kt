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
    private lateinit var sound: SoundHub
    private lateinit var droid: DroidDepotDriver
    private lateinit var ears: VoiceInput
    private lateinit var gestures: Gestures
    private lateinit var odometry: Odometry
    private lateinit var navMap: NavMap
    private lateinit var nav: Navigator
    private lateinit var world: WorldMemory
    private val log = AgentLog()
    private val overlay = Overlay()
    private lateinit var settings: Settings
    private var server: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val startTime = now()

    override fun onCreate() {
        super.onCreate()
        ble = BleManager(this)
        sensors = SensorHub(this)
        camera = CameraHub(this) { sensors.lux() }
        settings = Settings(this)
        droid = DroidDepotDriver(ble, settings)
        odometry = Odometry(sensors, { droid.left to droid.right }, settings)
        world = WorldMemory(odometry)
        motion = MotionController(sensors, droid, settings).also { it.camera = camera; it.odometry = odometry }
        odometry.view = { camera.fingerprintIfOpen(motion.progressCamera) }
        // Walkable-space map: saved on the phone; driving over floor marks it free
        navMap = NavMap(java.io.File(filesDir, "navmap.json")).also { it.load() }
        nav = Navigator(navMap, odometry, motion)
        odometry.onMoved = { x, y -> navMap.markFreeDisc(x, y) }
        alignMap()
        kotlin.concurrent.thread(name = "navmap-save", isDaemon = true) {
            while (true) {
                Thread.sleep(15_000)
                if (navMap.dirty) navMap.save()
            }
        }
        // Stuck = something in front of us the camera may not even show (a low edge,
        // a rug, a chair leg): mark it on the radar just ahead of the droid's nose
        motion.onStuck = { relDeg ->
            try {
                val yaw = odometry.yaw
                val o = world.see("obstacle", yaw + relDeg, null, 90.0, OBSTACLE_AHEAD_CM, 0.9, "obstacle")
                if (!o.isNull("x")) navMap.markBlocked(o.getDouble("x"), o.getDouble("y"))
                log.add("droid", if (relDeg == 0.0) "Stuck on something ahead: marked an obstacle"
                                 else "Couldn't turn ${if (relDeg > 0) "right" else "left"}: marked an obstacle", false)
            } catch (_: Exception) {}
        }
        sound = SoundHub(this)
        gestures = Gestures({ droid }, motion, settings) { mood ->
            try { sound.chirp(mood, null) } catch (_: Exception) {}
        }
        ears = VoiceInput(this)
        ears.isSpeaking = { sound.isPlaying() }
        ears.spokeSince = { sound.playedSince(it) }
        // Hearing its name: stop right here (no waiting for the laptop) and answer
        // straight away. The laptop then interrupts its mission and takes over.
        ears.onWake = { command ->
            motion.hold(2_000)
            log.add("You", "(said) ${settings.name}${if (command.isBlank()) "" else ", $command"}", false)
            try { gestures.perform("glance") } catch (_: Exception) {}   // "hm? who said that?"
            try {
                if (command.isBlank()) {
                    sound.chirp("curious", null)
                    sound.speak(ACKS.random())
                } else {
                    sound.chirp("happy", null)        // "got it", the laptop answers properly
                }
            } catch (e: Exception) {
                android.util.Log.w("BridgeService", "couldn't acknowledge: ${e.message}")
            }
        }
        voice = sound
        cameraHub = camera
        droidLink = droid
        service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only claim the microphone type if we have the permission; otherwise
        // startForeground would throw and take the whole service down.
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        startForeground(
            NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        )
        if (server == null) {
            sensors.start()
            server = HttpServer(PORT, ::route).also { it.start() }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidLink::bridge")
                .apply { acquire() }
            // Reconnect to the droid we used last time, if the user wants that
            settings.droidAddress?.takeIf { settings.autoConnect }?.let { addr ->
                kotlin.concurrent.thread(name = "droid-autoconnect") {
                    try { droid.connect(addr) } catch (e: Exception) {
                        android.util.Log.i("BridgeService", "droid auto-connect failed: ${e.message}")
                    }
                }
            }
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ears.stopWake()
        voice = null
        cameraHub = null
        droidLink = null
        service = null
        motion.stop()
        nav.cancel = true
        navMap.save()
        odometry.shutdown()
        try { droid.disconnect() } catch (_: Exception) {}
        server?.stop()
        server = null
        sensors.stop()
        camera.shutdown()
        sound.shutdown()
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

        // Commands from the browser control panel carry src=ui; they still work
        // during a manual override, while the agent's are refused.
        motion.fromUi.set(p["src"] == "ui")

        return when {
            // --- browser control panel
            path == "/" || path == "/ui" -> Bytes("text/html; charset=utf-8",
                assets.open("index.html").use { it.readBytes() })
            path == "/ui/manual" -> Json(motion.setManual(flag("on", true)).also {
                log.add("panel", if (motion.manual) "You took manual control" else "You gave control back", false)
            })
            path == "/agent/status" -> Json(JSONObject()
                .put("manual", motion.manual)
                .put("agent_active", System.currentTimeMillis() - log.lastAgentPost < 20_000)
                .put("droid", droid.connected)
                .put("name", settings.name))
            path == "/agent/say" -> {                    // typed in the panel: to the agent
                val text = need("text")
                log.add("You", text, false)
                Json(ears.pushTyped(text))
            }
            path == "/agent/log" -> Json(
                p["add"]?.let { log.add(p["who"] ?: settings.name, it, true) }
                    ?: log.since(p["since"]?.toLongOrNull() ?: 0))
            path == "/control/head_velocity" -> Json(motion.headVelocity(
                num("speed"), p["ttl_ms"]?.toLongOrNull() ?: 300))

            path == "/health" -> Json(JSONObject()
                .put("status", "ok")
                .put("app", "droidlink")
                .put("version", BuildConfig.VERSION_NAME)
                .put("uptime_s", Math.round((now() - startTime) * 10) / 10.0))

            // --- settings (name; voice settings are under /sound/voice)
            path == "/settings" -> {
                p["name"]?.let { settings.name = it }
                Json(settings.toJson())
            }

            // --- sensors
            path == "/sensors" -> Json(sensors.snapshot())
            path.startsWith("/sensors/") -> {
                val name = path.removePrefix("/sensors/")
                Json(sensors.one(name) ?: throw Unavailable("no reading for '$name'"))
            }

            // --- camera
            path == "/camera/list" -> Json(JSONObject().put("cameras", camera.list()))
            path == "/camera/status" -> Json(camera.status())
            path == "/camera/overlay" -> {
                val box = listOf("x1", "y1", "x2", "y2").map { p[it]?.toFloatOrNull() }
                overlay.set(
                    if (box.all { it != null }) FloatArray(4) { box[it]!! } else null,
                    p["label"] ?: "", p["status"] ?: "", p["color"] ?: "green")
                Json(JSONObject().put("ok", true))
            }
            path == "/camera/torch" -> Json(when {
                p["mode"] != null -> camera.setTorchMode(p["mode"]!!)   // saved setting
                p["light"] == "off" -> camera.lightOff()                // just switch off now
                else -> camera.status()
            })
            path == "/camera/snapshot" -> snapshot(
                int("camera", 0).toString(), int("width", 1280).coerceIn(160, 1600),
                int("quality", 80).coerceIn(10, 95), p["rotate"]?.toIntOrNull())
            path == "/camera/stream" -> stream(
                int("camera", 0).toString(), int("width", 640).coerceIn(160, 1600),
                int("quality", 70).coerceIn(10, 95), p["rotate"]?.toIntOrNull(),
                (p["fps"]?.toDoubleOrNull() ?: 5.0).coerceIn(0.5, 15.0))

            // --- the droid (BLE motor driver)
            path == "/droid/status" -> Json(droid.status())
            path == "/droid/connect" -> Json(connectDroid(p["address"]?.takeIf { it.isNotEmpty() }))
            path == "/droid/disconnect" -> { droid.disconnect(); Json(droid.status()) }
            path == "/droid/forget" -> { droid.disconnect(); settings.droidAddress = null; Json(droid.status()) }

            // --- motion
            path == "/control/status" -> Json(motion.status())
            path == "/control/config" -> Json(motion.config(
                p["turn_power"]?.toFloatOrNull(), p["drive_power"]?.toFloatOrNull(),
                p["min_power"]?.toFloatOrNull(), p["cm_per_second"]?.toFloatOrNull(),
                p["coast_s"]?.toFloatOrNull(), p["ramp_ms"]?.toIntOrNull(),
                p["ease_deg"]?.toFloatOrNull(), p["reverse_power"]?.toFloatOrNull()))
            path == "/control/spin_test" -> Json(motion.spinTest(
                num("power").coerceIn(-1.0, 1.0), p["seconds"]?.toDoubleOrNull() ?: 0.6,
                head = p["part"] == "head"))
            path == "/control/gesture" -> Json(
                p["name"]?.takeIf { it.isNotEmpty() }?.let { gestures.perform(it) }
                    ?: JSONObject().put("gestures", gestures.list()))
            path == "/control/head/calibrate" -> Json(motion.headCalibrate())
            path == "/control/head" -> Json(motion.headTurn(
                motion.headClamp(num("degrees").coerceIn(-180.0, 180.0)),
                tolerance = p["tolerance"]?.toDoubleOrNull()?.coerceIn(2.0, 30.0) ?: 4.0,
                maxAttempts = p["attempts"]?.toIntOrNull()?.coerceIn(1, 5) ?: 4))
            path == "/control/head/center" -> Json(motion.headCenter())
            path == "/control/head/zero" -> Json(motion.headZero())
            path == "/control/head/config" -> {
                p["power"]?.toFloatOrNull()?.let { settings.headPower = it }
                p["min_power"]?.toFloatOrNull()?.let { settings.headMinPower = it }
                p["coast_s"]?.toFloatOrNull()?.let { settings.headCoastS = it }
                p["sign"]?.toIntOrNull()?.let { settings.headSign = it }
                Json(JSONObject().put("power", settings.headPower).put("min_power", settings.headMinPower)
                    .put("coast_s", settings.headCoastS).put("sign", settings.headSign)
                    .put("head_angle", motion.headAngle))
            }
            path == "/control/stop" -> { nav.cancel = true; Json(motion.stop()) }
            // --- walkable-space map and path finding
            path == "/map" -> Json(navMap.snapshot(odometry.x, odometry.y, p["r"]?.toDoubleOrNull() ?: 400.0)
                .put("pose", JSONObject().put("x", odometry.x).put("y", odometry.y).put("yaw", odometry.yaw))
                .put("nav", nav.json()))
            path == "/map/free_cone" -> {               // the floor ahead looked clear
                navMap.markFreeCone(odometry.x, odometry.y, odometry.yaw, num("dist").coerceIn(10.0, 150.0))
                Json(JSONObject().put("ok", true))
            }
            path == "/map/ahead" -> Json(JSONObject().put("unknown",
                navMap.unknownAhead(odometry.x, odometry.y, odometry.yaw, p["dist"]?.toDoubleOrNull() ?: 50.0)))
            path == "/map/clear" -> { resetMap(); Json(JSONObject().put("ok", true)) }
            path == "/map/path" -> {                    // plan only (no driving), for checking
                val pth = navMap.path(odometry.x, odometry.y, num("x"), num("y"), flag("unknown", false))
                Json(JSONObject().put("path", if (pth == null) JSONObject.NULL
                    else org.json.JSONArray(pth.map { org.json.JSONArray().put(it.first).put(it.second) })))
            }
            path == "/map/mark" -> {                    // testing: mark a blocked spot at (x, y)
                navMap.markBlocked(num("x"), num("y")); Json(JSONObject().put("ok", true))
            }
            path == "/nav/goto" -> {
                val target = when {
                    flag("home", false) -> 0.0 to 0.0
                    p["thing"] != null -> world.locate(need("thing"))
                        ?: throw IllegalArgumentException("no position on the radar for '${p["thing"]}'")
                    else -> num("x") to num("y")
                }
                Json(nav.goTo(target.first, target.second, flag("unknown", false),
                    p["stop_short"]?.toDoubleOrNull() ?: 0.0, null,
                    p["timeout"]?.toDoubleOrNull() ?: 60.0))
            }
            path == "/nav/explore" -> Json(nav.exploreStep(p["timeout"]?.toDoubleOrNull() ?: 45.0))
            path == "/nav/cancel" -> { nav.cancel = true; motion.stop(); Json(nav.json()) }
            path == "/nav/status" -> Json(nav.json())
            // --- what the droid has seen, and where (confidence fades); drawn as the panel's radar
            path == "/memory" -> Json(world.snapshot())
            path == "/memory/see" -> Json(world.see(need("thing"),
                p["bearing"]?.toDoubleOrNull(), p["offset"]?.toDoubleOrNull(),
                p["hfov"]?.toDoubleOrNull() ?: 90.0, p["distance"]?.toDoubleOrNull(),
                p["confidence"]?.toDoubleOrNull() ?: 0.9, p["kind"], flag("detector", false)))
            path == "/memory/missed" -> Json(world.missed(num("yaw"), p["hfov"]?.toDoubleOrNull() ?: 90.0,
                (p["seen"] ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()))
            path == "/memory/doubt" -> Json(world.doubt(need("thing"), p["factor"]?.toDoubleOrNull() ?: 0.4, p["id"]?.toIntOrNull())
                ?: JSONObject().put("thing", p["thing"]).put("known", false))
            path == "/memory/forget" -> { world.forget(p["thing"]); Json(world.snapshot()) }
            path == "/memory/reset" -> { world.forget(null); resetMap(); Json(world.snapshot()) }
            path == "/control/view_change" -> {         // tuning the stuck detector: how much
                val cam = p["camera"] ?: motion.progressCamera   // the view changes over `ms`
                val a = camera.fingerprint(cam) ?: throw Unavailable("no camera frame yet")
                Thread.sleep((p["ms"]?.toLongOrNull() ?: 1000L).coerceIn(100, 5000))
                val b = camera.fingerprint(cam) ?: throw Unavailable("no camera frame yet")
                Json(JSONObject().put("change", MotionController.viewChange(a, b))
                    .put("still_below", motion.stillDiff))
            }
            path == "/control/turn" -> Json(motion.turn(
                num("degrees").coerceIn(-180.0, 180.0),
                tolerance = p["tolerance"]?.toDoubleOrNull()?.coerceIn(2.0, 30.0) ?: 5.0,
                maxAttempts = p["attempts"]?.toIntOrNull()?.coerceIn(1, 5) ?: 5))
            path == "/control/velocity" -> Json(motion.velocity(
                num("left"), num("right"), p["ttl_ms"]?.toLongOrNull() ?: 400))
            path == "/control/drive" -> Json(motion.drive(num("cm").coerceIn(-100.0, 200.0)))
            path == "/control/wheels" -> Json(motion.wheels(
                num("left").coerceIn(-1.0, 1.0), num("right").coerceIn(-1.0, 1.0),
                (p["seconds"]?.toDoubleOrNull() ?: 1.0)))

            // --- voice
            path == "/sound/chirp" -> Json(sound.chirp(p["mood"] ?: "happy", p["seed"]?.toLongOrNull()))
            path == "/sound/speak" -> Json(sound.speak(need("text")))
            path == "/sound/voices" -> Json(sound.voices())

            // --- ears
            path == "/voice/status" -> Json(JSONObject()
                .put("available", ears.available()).put("on_device", ears.onDevice)
                .put("language", ears.language)
                .put("installed_languages", org.json.JSONArray(ears.installedLanguages()))
                .put("mic_permission", checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED))
            path == "/voice/wake" -> Json(when (p["enable"]) {
                "1", "true" -> ears.startWake((p["names"] ?: settings.name).split(",").map { it.trim() })
                "0", "false" -> ears.stopWake()
                else -> ears.wakeStatus()
            })
            path == "/voice/events" -> Json(ears.events(p["since"]?.toLongOrNull() ?: 0))
            path == "/voice/listen" -> {
                // Let anything we're saying finish first (don't hear ourselves)...
                val t0 = System.currentTimeMillis()
                while (sound.isPlaying() && System.currentTimeMillis() - t0 < 8_000) Thread.sleep(50)
                // ...then a little "I'm listening" chirp, so people know when to talk
                if (flag("cue", true)) Thread.sleep(sound.chirp("listen", null).getLong("ms") + 150)
                Json(ears.listen(((p["seconds"]?.toDoubleOrNull() ?: 6.0).coerceIn(1.0, 20.0) * 1000).toLong()))
            }
            path == "/sound/voice" -> Json(sound.configure(
                p["name"]?.takeIf { it.isNotEmpty() },
                p["robot"]?.let { it == "1" || it == "true" },
                p["pitch"]?.toFloatOrNull(),
                p["rate"]?.toFloatOrNull()))

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

    /** Connect to the given droid, the saved one, or the nearest one found by scanning. */
    @Synchronized
    fun connectDroid(address: String?): JSONObject {
        if (droid.connected) return droid.status()
        val known = address ?: settings.droidAddress
        var r: JSONObject? = null
        if (known != null) {
            // Connect straight to the known address: no scan needed. The first
            // attempt sometimes fails with a generic error (133), so try twice.
            for (attempt in 1..2) {
                try { r = droid.connect(known); break } catch (e: Exception) {
                    android.util.Log.i("BridgeService", "direct connect attempt $attempt failed: ${e.message}")
                    Thread.sleep(1_000)
                }
            }
        }
        if (r == null) {
            if (address != null) throw Unavailable("could not connect to $address")
            r = droid.connect(findDroid())   // unknown or not reachable: look for one
        }
        settings.droidAddress = r.optString("address", known)   // remember it for next time
        return r
    }

    /** Scan for a droid advertising as "DROID" and return the strongest one's address. */
    private fun findDroid(): String {
        // One filtered scan (works with the screen off): Disney's ID OR the name
        val found = ble.scan(5.0, null, exactName = DroidDepotDriver.NAME,
            manufacturerId = DroidDepotDriver.MANUFACTURER_ID)
        if (found.length() == 0) throw Unavailable("no droid found nearby (is it switched on?)")
        return found.getJSONObject(0).getString("address")   // sorted by signal strength
    }

    /**
     * Line a saved map up with this session. Positions: the droid is assumed to start
     * where it started when the map was made ("home"). Rotation: the gyro's zero is
     * different every session, but the compass isn't, so the saved difference
     * between compass and map heading tells us how to turn this session's gyro.
     * (The motors bend the compass, but by the same amount every time.)
     */
    private fun alignMap() = kotlin.concurrent.thread(name = "align", isDaemon = true) {
        Thread.sleep(1_500)                              // let the sensors settle
        var sx = 0.0; var sy = 0.0; var n = 0
        repeat(10) {
            val c = sensors.azimuth; val g = sensors.yaw
            if (!c.isNaN() && !g.isNaN()) {
                val d = Math.toRadians(c - g); sx += kotlin.math.cos(d); sy += kotlin.math.sin(d); n++
            }
            Thread.sleep(100)
        }
        if (n == 0) return@thread
        val compassMinusGyro = Math.toDegrees(kotlin.math.atan2(sy, sx))
        val saved = navMap.compassMinusYaw
        if (saved == null) {
            navMap.compassMinusYaw = compassMinusGyro      // a new map: this session defines it
            odometry.yawOffset = 0.0
        } else {
            odometry.yawOffset = MotionController.angleDiff(compassMinusGyro, saved)
            log.add("droid", "Loaded the saved map (assuming I started at home)", false)
        }
        navMap.markFreeDisc(odometry.x, odometry.y)       // we're sitting on floor
    }

    private fun resetMap() {
        nav.cancel = true
        odometry.reset()
        odometry.yawOffset = 0.0
        navMap.clear()
        alignMap()
    }

    private fun poseHeaders(): Map<String, String> {
        if (sensors.azimuth.isNaN()) return emptyMap()
        return mapOf(
            "X-Yaw" to "%.1f".format(java.util.Locale.US, odometry.yaw),   // map heading (radar bearings)
            "X-Azimuth" to "%.1f".format(sensors.azimuth),
            "X-Pitch" to "%.1f".format(sensors.pitch),
            "X-Roll" to "%.1f".format(sensors.roll),
        )
    }

    private fun snapshot(id: String, width: Int, quality: Int, rotate: Int?): Response {
        val pose = poseHeaders() // pose at (just before) capture
        val shot = camera.jpeg(id, width, quality, rotate)
        return Bytes("image/jpeg", shot.jpeg, pose + mapOf(
            "X-Capture-Ts" to "%.3f".format(shot.ts),
            "X-Camera" to id,
            "X-Torch" to if (shot.torch) "on" else "off",
            "X-Luma" to shot.luma.toString()))
    }

    /** MJPEG stream: open http://<phone>:8765/camera/stream in a browser. */
    private fun stream(id: String, width: Int, quality: Int, rotate: Int?, fps: Double): Response =
        Stream("multipart/x-mixed-replace; boundary=frame") { out ->
            val period = (1000 / fps).toLong()
            var errors = 0
            while (true) {
                val t = System.currentTimeMillis()
                val jpeg = try {
                    camera.jpeg(id, width, quality, rotate, overlay::draw).jpeg.also { errors = 0 }
                } catch (e: Exception) {
                    // Camera hiccup (e.g. another app grabbed it): retry a few times, then give up
                    if (++errors >= 5) break
                    Thread.sleep(1000)
                    continue
                }
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
        private const val OBSTACLE_AHEAD_CM = 22.0   // camera to just past the droid's front
        private val ACKS = listOf("What's up?", "Yes?", "You called?", "I'm listening!",
            "Hmm? What is it?", "Right here!", "What do you need?")
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 1
        @Volatile var running = false
        /** The running service's voice, so the settings screen can test it. */
        @Volatile var voice: SoundHub? = null
        /** The running service's camera, so the settings screen can switch the flashlight. */
        @Volatile var cameraHub: CameraHub? = null
        /** The running service's droid driver, for the Connect button. */
        @Volatile var droidLink: DroidDepotDriver? = null
        @Volatile var service: BridgeService? = null
    }
}
