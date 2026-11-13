package dev.droidlink

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sign

/** Low-level motor control. Speeds are -1..1; positive = forward. */
interface MotorDriver {
    val name: String
    val connected: Boolean
    fun drive(left: Double, right: Double)
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
class MotionController(private val sensors: SensorHub, @Volatile var driver: MotorDriver) {

    // Tunables (calibrate once the real droid is connected)
    var turnSpeed = 0.5          // motor power while turning
    var driveSpeed = 0.5         // motor power while driving
    var cmPerSecond = 20.0       // how far the droid drives per second at driveSpeed
    var turnToleranceDeg = 5.0
    var bumpThreshold = 8.0      // m/s^2 of linear acceleration that counts as a bump
    var tiltLimitDeg = 20.0      // max change in pitch/roll from the start of a move

    @Volatile private var stopRequested = false
    @Volatile private var activeMove: String? = null
    @Volatile private var lastResult: JSONObject? = null
    private val moveLock = Any()

    fun status(): JSONObject = JSONObject()
        .put("driver", driver.name)
        .put("connected", driver.connected)
        .put("moving", activeMove ?: JSONObject.NULL)
        .put("heading", sensors.azimuth.takeUnless { it.isNaN() } ?: JSONObject.NULL)
        .put("last", lastResult ?: JSONObject.NULL)

    fun stop(): JSONObject {
        stopRequested = true
        driver.stop()
        return status()
    }

    /** Rotate in place by [degrees] (positive = right/clockwise from above). */
    fun turn(degrees: Double, timeoutS: Double = 10.0): JSONObject = move("turn") { guard ->
        val start = sensors.azimuth
        if (start.isNaN()) throw Unavailable("no compass heading")
        val target = (start + degrees + 360) % 360
        var reason = "timeout"
        val deadline = System.currentTimeMillis() + (timeoutS * 1000).toLong()
        while (System.currentTimeMillis() < deadline) {
            val g = guard(); if (g != null) { reason = g; break }
            val remaining = angleDiff(target, sensors.azimuth)
            if (abs(remaining) <= turnToleranceDeg) { reason = "done"; break }
            // Slow down near the target to avoid overshooting
            val power = turnSpeed * (abs(remaining) / 45.0).coerceIn(0.4, 1.0) * sign(remaining)
            driver.drive(power, -power)
            Thread.sleep(20)
        }
        driver.stop()
        Thread.sleep(150) // let the compass settle
        JSONObject()
            .put("requested", degrees)
            .put("turned", angleDiff(sensors.azimuth, start))
            .put("heading", sensors.azimuth)
            .put("result", reason)
    }

    /** Drive straight [cm] (negative = backwards). Distance is time-based for now. */
    fun drive(cm: Double, timeoutS: Double = 15.0): JSONObject = move("drive") { guard ->
        val seconds = (abs(cm) / cmPerSecond).coerceAtMost(timeoutS)
        val power = driveSpeed * sign(cm)
        val t0 = System.currentTimeMillis()
        var reason = "done"
        while (System.currentTimeMillis() - t0 < seconds * 1000) {
            val g = guard(); if (g != null) { reason = g; break }
            driver.drive(power, power)
            Thread.sleep(20)
        }
        driver.stop()
        val elapsed = (System.currentTimeMillis() - t0) / 1000.0
        JSONObject()
            .put("requested_cm", cm)
            .put("estimated_cm", Math.round(elapsed * cmPerSecond * sign(cm) * 10) / 10.0)
            .put("result", reason)
    }

    /** Runs one move at a time with the safety guard; always leaves the motors stopped. */
    private fun move(kind: String, body: (guard: () -> String?) -> JSONObject): JSONObject {
        if (!driver.connected) throw Unavailable("no droid connected")
        synchronized(moveLock) {
            stopRequested = false
            activeMove = kind
            val pitch0 = sensors.pitch
            val roll0 = sensors.roll
            val guard: () -> String? = {
                when {
                    stopRequested -> "stopped"
                    sensors.linearAccel() > bumpThreshold -> "bump"
                    abs(sensors.pitch - pitch0) > tiltLimitDeg || abs(sensors.roll - roll0) > tiltLimitDeg -> "tilt"
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
        /** Signed shortest angle from [b] to [a], degrees in -180..180. */
        fun angleDiff(a: Double, b: Double) = (a - b + 540) % 360 - 180
    }
}
