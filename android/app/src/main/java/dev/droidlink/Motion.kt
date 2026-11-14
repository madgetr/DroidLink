package dev.droidlink

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** Low-level motor control. Speeds are -1..1; positive = forward. */
interface MotorDriver {
    val name: String
    val connected: Boolean
    fun drive(left: Double, right: Double)
    /** Rotate the head/dome (-1..1); not every droid has one. */
    fun head(speed: Double): Unit = throw Unavailable("this droid has no head motor")
    fun stop()
}

/** Placeholder until the droid's BLE driver exists. */
class NoMotorDriver : MotorDriver {
    override val name = "none"
    override val connected = false
    override fun drive(left: Double, right: Double) = throw Unavailable("no droid connected")
    override fun stop() {}
}

/**
 * High-level moves on top of a [MotorDriver], with a safety monitor:
 * - turn() uses the compass, so it turns by the real angle, not a guessed time
 * - every move stops on a bump (sudden jolt), a tilt (tipping or an edge),
 *   a /control/stop request, or a timeout
 */
class MotionController(
    private val sensors: SensorHub,
    @Volatile var driver: MotorDriver,
    private val settings: Settings,
) {
    // Tunables, saved in Settings and adjustable via /control/config
    private val turnSpeed get() = settings.turnPower.toDouble()     // motor power while turning
    private val driveSpeed get() = settings.drivePower.toDouble()   // motor power while driving
    private val cmPerSecond get() = settings.cmPerSecond.toDouble() // drive speed at driveSpeed
    private val minPower get() = settings.minPower.toDouble()       // below this the wheels stall
    private val coastS get() = settings.coastS.toDouble()           // stop this many seconds early
    var turnToleranceDeg = 5.0
    var bumpThreshold = 8.0      // m/s^2 of linear acceleration that counts as a bump
    var tiltLimitDeg = 20.0      // max change in pitch/roll from the start of a move

    /** Set by the service: used to notice we're driving forward but not getting anywhere. */
    @Volatile var camera: CameraHub? = null
    @Volatile var odometry: Odometry? = null      // to undo "distance" driven while stuck
    /** Called when blocked, with where the obstacle probably is (degrees from straight
     *  ahead: 0 = in front, + = front-right): e.g. mark it on the radar. */
    @Volatile var onStuck: ((Double) -> Unit)? = null
    var progressCamera = "2"     // the camera that looks ahead (same one the agent and panel use)
    var stuckMs = 1_000L         // forward with no change in view for this long = stuck
    var stillDiff = STILL_DIFF   // view change (0-255 scale) below which nothing moved
    @Volatile var lastViewChange = 0.0   // latest measurement, for tuning (/control/status)

    /**
     * Stuck detector: while driving forward, compare the camera view every 250 ms with
     * the view when we last saw progress. Pushing against a wall or caught on a rug, the
     * wheels turn but the view doesn't change. Brightness changes (auto-exposure, the
     * flashlight) don't count: each map is compared relative to its own average.
     */
    private inner class ProgressWatch {
        private var ref: IntArray? = null
        private var refT = 0L
        private var lastSample = 0L
        private var forwardSince = 0L
        val since get() = refT              // last time the view changed
        val samples = ArrayList<Double>()   // view changes measured (for tuning)

        /** Pushing forward, but no progress for at least [ms] (and we've had a proper look)? */
        fun staleFor(ms: Long) = ref != null && samples.size >= 2 && System.currentTimeMillis() - refT > ms

        /** True when we've been going forward for [stuckMs] without the view changing. */
        fun stuck(forward: Boolean): Boolean {
            val cam = camera
            if (!forward || cam == null) { ref = null; forwardSince = 0L; return false }
            val now = System.currentTimeMillis()
            if (forwardSince == 0L) forwardSince = now
            // The motors' start-up lurch moves the view even when we're about to get
            // stuck (e.g. rocking up onto a mat): start judging after it
            if (now - forwardSince < START_GRACE_MS) return false
            if (now - lastSample < 250) return false
            lastSample = now
            val fp = try { cam.fingerprint(progressCamera) } catch (e: Exception) { null } ?: return false
            val r = ref
            if (r == null) { ref = fp; refT = now; return false }
            val change = viewChange(r, fp)
            lastViewChange = change
            if (samples.size < 40) samples.add(Math.round(change * 10) / 10.0)
            if (change > stillDiff) { ref = fp; refT = now; return false }
            return now - refT > stuckMs
        }
    }

    fun config(turnPower: Float?, drivePower: Float?, minPower: Float?, cmPerSecond: Float?,
               coastS: Float? = null, rampMs: Int? = null, easeDeg: Float? = null,
               reversePower: Float? = null): JSONObject {
        reversePower?.let { settings.reversePower = it }
        turnPower?.let { settings.turnPower = it }
        drivePower?.let { settings.drivePower = it }
        minPower?.let { settings.minPower = it }
        cmPerSecond?.let { settings.cmPerSecond = it }
        coastS?.let { settings.coastS = it }
        rampMs?.let { settings.rampMs = it }
        easeDeg?.let { settings.easeDeg = it }
        return JSONObject()
            .put("turn_power", settings.turnPower).put("drive_power", settings.drivePower)
            .put("min_power", settings.minPower).put("cm_per_second", settings.cmPerSecond)
            .put("coast_s", settings.coastS).put("ramp_ms", settings.rampMs)
            .put("ease_deg", settings.easeDeg)
            .put("reverse_power", settings.reversePower)
    }

    @Volatile private var stopRequested = false
    @Volatile private var activeMove: String? = null
    @Volatile private var lastResult: JSONObject? = null
    private val moveLock = Any()

    fun status(): JSONObject = JSONObject()
        .put("driver", driver.name)
        .put("connected", driver.connected)
        .put("moving", activeMove ?: JSONObject.NULL)
        .put("heading", sensors.azimuth.takeUnless { it.isNaN() } ?: JSONObject.NULL)
        .put("yaw", sensors.yaw.takeUnless { it.isNaN() } ?: JSONObject.NULL)
        .put("last", lastResult ?: JSONObject.NULL)
        .put("view_change", Math.round(lastViewChange * 10) / 10.0)   // stuck detector, for tuning

    fun stop(): JSONObject {
        stopRequested = true
        velDeadline = 0
        driver.stop()
        return status()
    }

    /** Someone called the droid: stop now and refuse movement for [ms], so commands
     *  already on their way from the laptop can't start the motors again. */
    @Volatile private var holdUntil = 0L

    fun hold(ms: Long) {
        holdUntil = System.currentTimeMillis() + ms
        stop()
    }

    private fun checkHold() {
        if (System.currentTimeMillis() < holdUntil) throw Unavailable("paused: someone called my name")
        if (manual && !fromUi.get()) throw Unavailable("paused: manual override from the control panel")
    }

    // --- manual override from the browser control panel. While on, only commands
    // from the panel move the droid; the agent's are refused (it waits).

    @Volatile var manual = false
        private set

    /** Marks the current request thread as coming from the control panel. */
    val fromUi: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    fun setManual(on: Boolean): JSONObject {
        manual = on
        stop()
        return JSONObject().put("manual", manual)
    }

    // --- manual head driving (hold a button = turn; dead-man like velocity mode)

    @Volatile private var headSpeed = 0.0
    @Volatile private var headDeadline = 0L
    @Volatile private var headThread: Thread? = null

    fun headVelocity(speed: Double, ttlMs: Long): JSONObject {
        if (!driver.connected) throw Unavailable("no droid connected")
        if (speed != 0.0) checkHold()
        headSpeed = speed.coerceIn(-1.0, 1.0) * settings.headSign
        headDeadline = System.currentTimeMillis() + ttlMs.coerceIn(50, 1_000)
        if (headThread?.isAlive != true) {
            headThread = kotlin.concurrent.thread(name = "head-velocity") {
                try {
                    while (System.currentTimeMillis() < headDeadline) {
                        driver.head(headSpeed); Thread.sleep(20)
                    }
                } catch (_: Exception) {
                } finally {
                    try { driver.head(0.0) } catch (_: Exception) {}
                }
            }
        }
        return JSONObject().put("head", headSpeed)
    }

    // --- velocity mode: the laptop streams wheel speeds for continuous driving.
    // Dead-man switch: if updates stop arriving for ttl ms, the motors stop.

    @Volatile private var velLeft = 0.0
    @Volatile private var velRight = 0.0
    @Volatile private var velDeadline = 0L
    @Volatile private var velThread: Thread? = null
    @Volatile private var velEnded: String? = null      // why velocity mode last ended

    fun velocity(left: Double, right: Double, ttlMs: Long): JSONObject {
        if (!driver.connected) throw Unavailable("no droid connected")
        if (left != 0.0 || right != 0.0) checkHold()
        velLeft = left.coerceIn(-1.0, 1.0)
        velRight = right.coerceIn(-1.0, 1.0)
        velDeadline = System.currentTimeMillis() + ttlMs.coerceIn(50, 2_000)
        if (velThread?.isAlive != true) {
            // It stopped by itself (stuck, blocked, bump...): tell the caller once,
            // instead of quietly starting again on their next command
            val ended = velEnded
            if (ended != null && ended != "released" && (left != 0.0 || right != 0.0)) {
                velEnded = null
                return JSONObject().put("active", false).put("ended", ended)
                    .put("yaw", sensors.yaw.takeUnless { it.isNaN() } ?: JSONObject.NULL)
            }
            velEnded = null
            val ui = fromUi.get()             // carry "from the control panel" into the worker thread
            velThread = kotlin.concurrent.thread(name = "velocity") {
                fromUi.set(ui)
                velEnded = try {
                    move("velocity", VELOCITY_BUMP) { guard ->
                        var reason = "released"
                        val progress = ProgressWatch()
                        var spinSince = 0L
                        var spinYaw = 0.0
                        while (System.currentTimeMillis() < velDeadline) {
                            val g = guard()
                            if (g != null) {
                                if (g == "bump" && min(velLeft, velRight) > 0.2) onStuck?.invoke(0.0)
                                reason = g; break
                            }
                            // Spinning (wheels opposite) but the gyro isn't turning: blocked
                            val spinning = velLeft * velRight < 0 && abs(velLeft - velRight) > 0.6
                            if (!spinning) spinSince = 0L
                            else if (spinSince == 0L) { spinSince = System.currentTimeMillis(); spinYaw = sensors.yaw }
                            else if (System.currentTimeMillis() - spinSince > BLOCKED_MS &&
                                     abs(sensors.yaw - spinYaw) < BLOCKED_DEG) {
                                onStuck?.invoke(45.0 * sign(velLeft - velRight)); reason = "blocked"; break
                            }
                            if (progress.stuck(min(velLeft, velRight) > 0.2)) {
                                odometry?.rewind(progress.since); onStuck?.invoke(0.0); reason = "stuck"; break
                            }
                            driver.drive(velLeft, velRight)
                            Thread.sleep(20)
                        }
                        driver.stop()
                        JSONObject().put("result", reason)
                    }.optString("result")
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
            }
        }
        return JSONObject()
            .put("active", velThread?.isAlive == true)
            .put("ended", velEnded ?: JSONObject.NULL)   // e.g. "bump" if the guard stopped us
            .put("yaw", sensors.yaw.takeUnless { it.isNaN() } ?: JSONObject.NULL)
    }

    /**
     * Rotate in place by [degrees] (positive = right/clockwise from above).
     *
     * Measured with the gyro (yaw), not the compass: the motors' magnets throw the
     * compass off. The droid keeps turning briefly after a stop command, so we
     * stop early by the predicted coast (spin rate x coastS), let it settle, and
     * nudge slowly to fix any remaining error.
     */
    fun turn(degrees: Double, timeoutS: Double = 10.0, tolerance: Double = turnToleranceDeg,
             maxAttempts: Int = 5): JSONObject = move("turn", TURN_BUMP) { guard ->
        val y0 = sensors.yaw
        if (y0.isNaN()) throw Unavailable("no gyro heading")
        val target = y0 + degrees
        val deadline = System.currentTimeMillis() + (timeoutS * 1000).toLong()
        var reason = "timeout"
        var attempts = 0
        var peakRate = 0.0
        while (attempts < maxAttempts && System.currentTimeMillis() < deadline) {
            attempts++
            val err = target - sensors.yaw
            if (abs(err) <= tolerance) { reason = "done"; break }
            // First pass at full turn power; corrections at the gentlest power that moves
            val power = if (attempts == 1) turnSpeed else minPower
            val dir = sign(err)
            var lastYaw = sensors.yaw
            var lastT = System.nanoTime()
            var rate = 0.0      // deg/s, smoothed
            var stopReason: String? = null
            val passStart = System.currentTimeMillis()
            val passYaw = sensors.yaw
            while (System.currentTimeMillis() < deadline) {
                stopReason = guard(); if (stopReason != null) break
                val y = sensors.yaw
                // Wheels pushing but the gyro says we're not rotating: something's in the way
                if (System.currentTimeMillis() - passStart > BLOCKED_MS && abs(y - passYaw) < BLOCKED_DEG) {
                    if (attempts == 1) {
                        stopReason = "blocked"
                        onStuck?.invoke(45.0 * dir)   // a front corner on the side we turn toward
                    }
                    break                             // (a gentle correction stalling isn't news)
                }
                val t = System.nanoTime()
                val dt = (t - lastT) / 1e9
                if (dt >= 0.015) {
                    rate = 0.6 * rate + 0.4 * ((y - lastYaw) / dt)
                    lastYaw = y; lastT = t
                    peakRate = maxOf(peakRate, abs(rate))
                }
                val remaining = target - y
                if (sign(remaining) != dir) break                         // went past it
                if (abs(remaining) <= maxOf(tolerance, abs(rate) * coastS)) break
                // Ease off as we approach: full power far away, down to the
                // minimum that still moves the droid as we close in.
                val eased = (power * abs(remaining) / settings.easeDeg).coerceIn(minPower, power)
                driver.drive(eased * dir, -eased * dir)
                Thread.sleep(15)
            }
            driver.stop()
            if (stopReason != null) { reason = stopReason; break }
            Thread.sleep(400)   // let it coast to a stop
        }
        if (reason == "timeout" && abs(target - sensors.yaw) <= tolerance) reason = "done"
        val turned = sensors.yaw - y0
        JSONObject()
            .put("requested", degrees)
            .put("turned", Math.round(turned * 10) / 10.0)
            .put("error", Math.round((degrees - turned) * 10) / 10.0)
            .put("attempts", attempts)
            .put("peak_rate", Math.round(peakRate))
            .put("heading", sensors.azimuth)
            .put("result", reason)
    }

    // --- head (dome). The phone rides on the head, so its gyro measures where the
    // head points. Head and body never move at the same time, so every head move
    // tells us exactly how far the head turned relative to the body.

    /** Head angle relative to the body, degrees (+ = right). 0 = facing forward. */
    @Volatile var headAngle = 0.0
        private set

    /** Declare the head to be facing straight ahead right now. */
    fun headZero(): JSONObject {
        headAngle = 0.0
        return headStatus()
    }

    fun headStatus(): JSONObject = JSONObject().put("head_angle", Math.round(headAngle * 10) / 10.0)

    /**
     * Turn the head by [degrees] (+ = right), measured with the gyro, with the
     * same ease-off / stop-early / correct approach as body turns.
     */
    fun headTurn(degrees: Double, tolerance: Double = 4.0, maxAttempts: Int = 4,
                 timeoutS: Double = 8.0): JSONObject = move("head", TURN_BUMP, checkTilt = false) { guard ->
        val y0 = sensors.yaw
        if (y0.isNaN()) throw Unavailable("no gyro heading")
        val target = y0 + degrees
        val deadline = System.currentTimeMillis() + (timeoutS * 1000).toLong()
        val sign = settings.headSign.toDouble()      // which motor direction turns the head right
        var reason = "timeout"
        var attempts = 0
        while (attempts < maxAttempts && System.currentTimeMillis() < deadline) {
            attempts++
            val err = target - sensors.yaw
            if (abs(err) <= tolerance) { reason = "done"; break }
            val power = if (attempts == 1) settings.headPower.toDouble() else settings.headMinPower.toDouble()
            val dir = sign(err)
            var lastYaw = sensors.yaw
            var lastT = System.nanoTime()
            var rate = 0.0
            var stopReason: String? = null
            while (System.currentTimeMillis() < deadline) {
                stopReason = guard(); if (stopReason != null) break
                val y = sensors.yaw
                val t = System.nanoTime()
                val dt = (t - lastT) / 1e9
                if (dt >= 0.015) {
                    rate = 0.6 * rate + 0.4 * ((y - lastYaw) / dt)
                    lastYaw = y; lastT = t
                }
                val remaining = target - y
                if (sign(remaining) != dir) break
                if (abs(remaining) <= maxOf(tolerance, abs(rate) * settings.headCoastS)) break
                val eased = (power * abs(remaining) / settings.easeDeg)
                    .coerceIn(settings.headMinPower.toDouble(), power)
                driver.head(eased * dir * sign)
                Thread.sleep(15)
            }
            driver.stop()
            if (stopReason != null) { reason = stopReason; break }
            Thread.sleep(300)
        }
        if (reason == "timeout" && abs(target - sensors.yaw) <= tolerance) reason = "done"
        val turned = sensors.yaw - y0
        headAngle += turned
        JSONObject()
            .put("requested", degrees)
            .put("turned", Math.round(turned * 10) / 10.0)
            .put("error", Math.round((degrees - turned) * 10) / 10.0)
            .put("head_angle", Math.round(headAngle * 10) / 10.0)
            .put("attempts", attempts)
            .put("result", reason)
    }

    /** Run a canned body move (see Gestures) with the usual safety guard. */
    fun gesture(name: String, body: (guard: () -> String?) -> String): JSONObject =
        move("gesture", TURN_BUMP) { guard -> JSONObject().put("gesture", name).put("result", body(guard)) }

    /** Point the head straight ahead again. */
    fun headCenter(): JSONObject =
        if (abs(headAngle) < 3) headStatus().put("result", "already centred") else headTurn(-headAngle)

    /** Clamp a head turn so the head stays inside its calibrated range (never hits a stop). */
    fun headClamp(degrees: Double): Double {
        val half = settings.headHalfRange.toDouble()
        if (half <= 0) return degrees                     // range not calibrated yet
        val margin = 5.0
        val target = (headAngle + degrees).coerceIn(-half + margin, half - margin)
        return target - headAngle
    }

    /**
     * Find the head's end stops: turn each way until the gyro says it has stopped
     * moving, then centre between them. Saves the range, so head turns never push
     * against a stop afterwards.
     */
    fun headCalibrate(): JSONObject = move("head_calibrate", TURN_BUMP, checkTilt = false) { guard ->
        val sign = settings.headSign.toDouble()
        val power = settings.headMinPower.toDouble()

        fun runToStop(dir: Double): Double {
            val t0 = System.currentTimeMillis()
            var lastYaw = sensors.yaw
            var lastMove = System.currentTimeMillis()
            var moved = false
            while (System.currentTimeMillis() - t0 < 6_000) {
                if (guard() != null) break
                driver.head(power * dir * sign)
                Thread.sleep(40)
                val y = sensors.yaw
                if (abs(y - lastYaw) > 0.8) { lastMove = System.currentTimeMillis(); moved = true }
                lastYaw = y
                // Stalled: no rotation for a while (after it started, or never started)
                if (System.currentTimeMillis() - lastMove > (if (moved) 250 else 1_000)) break
            }
            driver.stop()
            Thread.sleep(300)
            return sensors.yaw
        }

        val left = runToStop(-1.0)
        val right = runToStop(+1.0)
        val range = right - left
        settings.headHalfRange = (range / 2).toFloat()
        headAngle = range / 2                          // we're at the right stop now
        JSONObject().put("range", Math.round(range)).put("half_range", Math.round(range / 2))
            .put("note", "head is at its right stop; call /control/head/center")
    }

    /**
     * Calibration: spin at [power] for [seconds] and record the gyro yaw (50 Hz)
     * from before the start until well after the stop, all timed on the phone.
     */
    fun spinTest(power: Double, seconds: Double, head: Boolean = false): JSONObject =
        move("spin_test", TURN_BUMP, checkTilt = !head) { guard ->
        val samples = org.json.JSONArray()
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1e6
        fun sample() = samples.put(org.json.JSONArray().put(Math.round(ms())).put(sensors.yaw))
        repeat(10) { sample(); Thread.sleep(20) }            // 200 ms before
        val startAt = ms()
        var result = "done"
        while (ms() - startAt < seconds.coerceIn(0.1, 2.0) * 1000) {
            val g = guard(); if (g != null) { result = g; break }
            if (head) driver.head(power) else driver.drive(power, -power)
            sample(); Thread.sleep(20)
        }
        val stopAt = ms()   // when the stop is issued (the stop itself takes a moment)
        driver.stop()
        while (ms() - stopAt < 1500) { sample(); Thread.sleep(20) }   // coast + settle
        JSONObject().put("power", power).put("start_at", startAt).put("stop_at", stopAt)
            .put("result", result).put("samples", samples)
    }

    /** Run the wheels at fixed speeds (-1..1) for a short time. For testing and calibration. */
    fun wheels(left: Double, right: Double, seconds: Double): JSONObject = move("wheels") { guard ->
        val t0 = System.currentTimeMillis()
        var reason = "done"
        while (System.currentTimeMillis() - t0 < seconds.coerceIn(0.0, 3.0) * 1000) {
            val g = guard(); if (g != null) { reason = g; break }
            driver.drive(left, right)
            Thread.sleep(20)
        }
        driver.stop()
        JSONObject().put("left", left).put("right", right).put("seconds", seconds).put("result", reason)
    }

    /** Drive straight [cm] (negative = backwards). Distance is time-based for now.
     *  Going forward, stops early with result "stuck" if the view stops changing. */
    fun drive(cm: Double, timeoutS: Double = 15.0): JSONObject = move("drive") { guard ->
        val p = if (cm < 0) maxOf(driveSpeed, settings.reversePower.toDouble()) else driveSpeed
        val speed = cmPerSecond * p / driveSpeed         // faster at more power (roughly)
        val seconds = (abs(cm) / speed).coerceAtMost(timeoutS)
        val power = p * sign(cm)
        val t0 = System.currentTimeMillis()
        var reason = "done"
        val progress = ProgressWatch()
        while (System.currentTimeMillis() - t0 < seconds * 1000) {
            val g = guard(); if (g != null) { reason = g; break }
            if (progress.stuck(cm > 0)) {
                odometry?.rewind(progress.since); onStuck?.invoke(0.0); reason = "stuck"; break
            }
            driver.drive(power, power)
            Thread.sleep(20)
        }
        val elapsed = (System.currentTimeMillis() - t0) / 1000.0   // driving time (not stopping)
        driver.stop()
        // Going forward and jolted to a stop: we ran into something
        if (reason == "bump" && cm > 0) onStuck?.invoke(0.0)
        // Finished on time, but the last stretch went nowhere (wheels clicking on a mat)
        if (reason == "done" && cm > 0 && progress.staleFor(END_STALE_MS)) {
            odometry?.rewind(progress.since); onStuck?.invoke(0.0); reason = "stuck"
        }
        JSONObject()
            .put("requested_cm", cm)
            .put("estimated_cm", Math.round(elapsed * speed * sign(cm) * 10) / 10.0)
            .put("power", Math.round(power * 100) / 100.0)
            .put("view_changes", org.json.JSONArray(progress.samples))   // tuning the stuck check
            .put("result", reason)
    }

    /** Runs one move at a time with the safety guard; always leaves the motors stopped. */
    private fun move(kind: String, bumpLimit: Double = bumpThreshold, checkTilt: Boolean = true,
                     body: (guard: () -> String?) -> JSONObject): JSONObject {
        if (!driver.connected) throw Unavailable("no droid connected")
        checkHold()
        synchronized(moveLock) {
            stopRequested = false
            activeMove = kind
            val pitch0 = sensors.pitch
            val roll0 = sensors.roll
            val t0 = System.currentTimeMillis()
            val guard: () -> String? = {
                // The motors' start-up jolt isn't a collision: ignore jolts briefly
                val settled = System.currentTimeMillis() - t0 > BUMP_GRACE_MS
                when {
                    stopRequested -> "stopped"
                    settled && sensors.linearAccel() > bumpLimit -> "bump"
                    checkTilt && (abs(sensors.pitch - pitch0) > tiltLimitDeg ||
                        abs(sensors.roll - roll0) > tiltLimitDeg) -> "tilt"
                    else -> null
                }
            }
            try {
                return body(guard).also { lastResult = it.put("move", kind) }
            } finally {
                driver.stop()
                activeMove = null
            }
        }
    }

    companion object {
        /** Spinning jolts the off-centre phone; only a hard knock counts as a bump. */
        private const val TURN_BUMP = 20.0
        private const val VELOCITY_BUMP = 14.0   // steering while driving jolts the phone a bit
        private const val BUMP_GRACE_MS = 400L
        private const val STILL_DIFF = 6.0   // standing still: 0.2-0.4; driving: ~30
        private const val START_GRACE_MS = 400L  // ignore the view for this long after starting forward
        private const val END_STALE_MS = 600L    // a drive that ends with no progress for this long = stuck
        private const val BLOCKED_MS = 700L      // turning at full power this long...
        private const val BLOCKED_DEG = 4.0      // ...but rotated less than this = blocked

        /** How different two brightness maps are (0-255), ignoring overall brightness. */
        /**
         * How different two view fingerprints are (0-255), after lining them up: tries
         * shifting one by up to [MAX_SHIFT] cells each way and keeps the best match.
         * Shaking, rocking and pivoting just slide the picture, so they cancel out;
         * driving forward makes things grow, which no shift can undo. Overall
         * brightness doesn't count (auto-exposure, the flashlight).
         */
        fun viewChange(a: IntArray, b: IntArray, w: Int = FP_W, h: Int = FP_H): Double {
            if (a.size != w * h || b.size != w * h) return 0.0
            val ma = a.average(); val mb = b.average()
            var best = Double.MAX_VALUE
            for (dy in -MAX_SHIFT..MAX_SHIFT) for (dx in -MAX_SHIFT..MAX_SHIFT) {
                var sum = 0.0; var n = 0
                for (y in maxOf(0, -dy) until minOf(h, h - dy)) {
                    val ra = y * w; val rb = (y + dy) * w
                    for (x in maxOf(0, -dx) until minOf(w, w - dx)) {
                        sum += abs((a[ra + x] - ma) - (b[rb + x + dx] - mb)); n++
                    }
                }
                if (n > 0) best = minOf(best, sum / n)
            }
            return best
        }

        /** Max alignment shift, in fingerprint cells (~3 degrees each). */
        private const val MAX_SHIFT = 4

        /** Signed shortest angle from [b] to [a], degrees in -180..180. */
        fun angleDiff(a: Double, b: Double) = (a - b + 540) % 360 - 180
    }
}
