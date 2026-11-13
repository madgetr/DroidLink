package dev.droidlink

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Canned moves for personality: head glances, dances, wiggles...
 *
 * Moves are timed sequences (no sensor feedback needed). Head-only moves run on
 * their own, so they can happen while the droid is driving or tracking (the
 * camera is on the body, so the head moving doesn't disturb it). Moves that use
 * the wheels go through the motion controller's normal safety checks.
 *
 * Every head move turns back as far as it turned out, so the head stays
 * roughly centred over time.
 */
class Gestures(
    private val driver: () -> MotorDriver,
    private val motion: MotionController,
    private val settings: Settings,
    private val chirp: (String) -> Unit,
) {
    /** One step: wheel speeds, head speed (-1..1), for ms. */
    private class Step(val left: Double = 0.0, val right: Double = 0.0, val head: Double = 0.0, val ms: Long)

    private class Move(val mood: String?, val usesWheels: Boolean, val steps: (Random) -> List<Step>)

    private val headLock = Any()
    @Volatile private var headBusy = false

    // Head power without the phone on it (the dome is light): smooth at ~0.5.
    private val h get() = settings.headGesturePower.toDouble() * settings.headSign
    private fun pause(ms: Long) = Step(ms = ms)
    private fun head(dir: Int, ms: Long) = Step(head = h * dir, ms = ms)
    private fun wheels(l: Double, r: Double, ms: Long) = Step(left = l, right = r, ms = ms)

    private val moves: Map<String, Move> = mapOf(
        // --- head only (safe while driving)
        "glance" to Move(null, false) { r ->
            val d = if (r.nextBoolean()) 1 else -1
            val t = r.nextLong(180, 380)
            listOf(head(d, t), pause(r.nextLong(400, 900)), head(-d, t))
        },
        "look_around" to Move("curious", false) {
            listOf(head(-1, 350), pause(500), head(1, 700), pause(500), head(-1, 350))
        },
        "shake" to Move("sad", false) {
            listOf(head(-1, 140), head(1, 280), head(-1, 280), head(1, 280), head(-1, 140))
        },
        "double_take" to Move("alert", false) { r ->
            val d = if (r.nextBoolean()) 1 else -1
            listOf(head(d, 250), head(-d, 250), pause(300), head(d, 350), pause(700), head(-d, 350))
        },
        // --- whole body
        "wiggle" to Move("happy", true) {
            List(3) { listOf(wheels(0.5, -0.5, 130), wheels(-0.5, 0.5, 130)) }.flatten()
        },
        "nod" to Move("happy", true) {
            List(2) { listOf(wheels(0.5, 0.5, 120), pause(80), wheels(-0.5, -0.5, 120), pause(80)) }.flatten()
        },
        "happy_spin" to Move("excited", true) {
            listOf(wheels(0.6, -0.6, 1300), pause(200), head(1, 200), head(-1, 200))
        },
        "dance" to Move("excited", true) {
            listOf(
                wheels(0.6, -0.6, 500), pause(150),
                head(-1, 150), head(1, 300), head(-1, 150),
                wheels(0.5, -0.5, 130), wheels(-0.5, 0.5, 130), wheels(0.5, -0.5, 130), wheels(-0.5, 0.5, 130),
                wheels(0.5, 0.5, 150), wheels(-0.5, -0.5, 150), pause(100),
                wheels(-0.6, 0.6, 500), pause(150),
                head(1, 250), pause(200), head(-1, 250),
                wheels(0.5, -0.5, 130), wheels(-0.5, 0.5, 130),
            )
        },
        "sad" to Move("sad", true) {
            listOf(head(-1, 500), pause(600), wheels(-0.45, -0.45, 300), pause(400), head(1, 500))
        },
        "surprised" to Move("alert", true) {
            listOf(wheels(-0.6, -0.6, 180), pause(100), head(1, 150), head(-1, 300), head(1, 150))
        },
    )

    fun list(): JSONArray = JSONArray(moves.map { (name, m) ->
        JSONObject().put("name", name).put("head_only", !m.usesWheels)
    })

    /**
     * Perform a move. Head-only moves start in the background and return at once
     * (skipped if the head is already busy); body moves block until done.
     */
    fun perform(name: String): JSONObject {
        val m = moves[name] ?: throw IllegalArgumentException(
            "unknown move '$name' (${moves.keys.joinToString()})")
        if (!driver().connected) throw Unavailable("no droid connected")
        val steps = m.steps(Random.Default)
        m.mood?.let { chirp(it) }
        return if (m.usesWheels) {
            motion.gesture(name) { guard ->
                for (s in steps) {
                    val g = guard(); if (g != null) return@gesture g
                    runStep(s)
                }
                "done"
            }
        } else {
            if (headBusy) return JSONObject().put("move", name).put("result", "head busy, skipped")
            thread(name = "head-gesture") {
                synchronized(headLock) {
                    headBusy = true
                    try { for (s in steps) runStep(s) } catch (e: Exception) {
                        Log.w("Gestures", "head move failed: ${e.message}")
                    } finally {
                        try { driver().head(0.0) } catch (_: Exception) {}
                        headBusy = false
                    }
                }
            }
            JSONObject().put("move", name).put("result", "started")
        }
    }

    private fun runStep(s: Step) {
        val d = driver()
        val end = System.currentTimeMillis() + s.ms
        while (System.currentTimeMillis() < end) {
            if (s.left != 0.0 || s.right != 0.0) d.drive(s.left, s.right)
            d.head(s.head)
            Thread.sleep(20)
        }
        if (s.left != 0.0 || s.right != 0.0) d.drive(0.0, 0.0)
        d.head(0.0)
    }
}
