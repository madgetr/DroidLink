package dev.droidlink

import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Motor driver for a Droid Depot droid over BLE.
 *
 * Protocol (confirmed on an R-series unit):
 *   service  09b600a0-3e42-41fc-b474-e9c0c8f0c801
 *   write    09b600b1-...  commands
 *   notify   09b600b0-...  status from the droid
 *   handshake: 222001 x3, 27420f4444001f00, 27420f4444001802  (droid drops the
 *              connection after ~10 s without it)
 *   motor:   29420546 MM SS 012C0000
 *            MM = motor (00 left, 01 right) | 0x80 for reverse; SS = speed 00-FF
 */
class DroidDepotDriver(private val ble: BleManager, private val settings: Settings) : MotorDriver {

    override val name = "droid-depot"
    @Volatile private var ready = false
    override val connected: Boolean get() = ready && ble.isConnected()

    // Last command sent per motor, to avoid flooding the BLE link
    private val lastByte = IntArray(3) { -1 }   // left, right, head
    private val lastSent = LongArray(3)

    fun connect(address: String): JSONObject {
        ready = false
        ble.connect(address)
        ble.setNotify(NOTIFY, true)
        for (hex in HANDSHAKE) {
            ble.write(WRITE, hex, withResponse = true)
            Thread.sleep(150)
        }
        lastByte.fill(-1)
        ready = true
        stop()
        Log.i(TAG, "droid connected at $address")
        return status()
    }

    fun disconnect() {
        if (connected) try { stop() } catch (_: Exception) {}
        ready = false
        ble.disconnect()
    }

    fun status(): JSONObject = ble.status().put("driver", name).put("ready", connected)

    /** What the wheels were last told to do (for dead reckoning). */
    @Volatile var left = 0.0; private set
    @Volatile var right = 0.0; private set

    /** Wheel speeds in -1..1 (positive = forward). */
    override fun drive(left: Double, right: Double) {
        if (!connected) throw Unavailable("droid not connected")
        this.left = left; this.right = right
        send(0, left, force = false)
        send(1, right, force = false)
    }

    /** Dome motor (motor 2 on the R-unit). */
    override fun head(speed: Double) {
        if (!connected) throw Unavailable("droid not connected")
        send(2, speed, force = false)
    }

    override fun stop() {
        left = 0.0; right = 0.0
        if (!ble.isConnected()) return
        // Stopping matters most. First an immediate stop to every motor (no
        // waiting for acknowledgements), then the same again, confirmed.
        for (m in 0..2) ble.write(WRITE, motorHex(m, 0.0), withResponse = false)
        for (m in 0..2) send(m, 0.0, force = true)
    }

    /** Motor command: 29420546 MM SS RRRR 0000 (RRRR = ramp, 0x012C = 300 by default). */
    private fun motorHex(motor: Int, speed: Double): String {
        val s = speed.coerceIn(-1.0, 1.0)
        val mag = (abs(s) * 255).roundToInt()
        val code = (motor or if (s < 0 && mag > 0) 0x80 else 0x00)
        return "29420546%02x%02x%04x0000".format(code, mag, settings.rampMs)
    }

    private fun send(motor: Int, speed: Double, force: Boolean) {
        val hex = motorHex(motor, speed)
        val key = hex.hashCode()
        val now = System.currentTimeMillis()
        // Re-send only on change, or every 250 ms as a refresh
        if (!force && key == lastByte[motor] && now - lastSent[motor] < 250) return
        val t = System.nanoTime()
        ble.write(WRITE, hex, withResponse = force)
        val ms = (System.nanoTime() - t) / 1_000_000
        if (ms > 40) Log.w(TAG, "slow motor write: $ms ms (motor $motor, force=$force)")
        lastByte[motor] = key
        lastSent[motor] = now
    }

    companion object {
        private const val TAG = "DroidDepotDriver"
        const val NAME = "DROID"            // what the droid advertises as
        const val MANUFACTURER_ID = 0x0183  // Disney, in the droid's advertisement
        private const val WRITE = "09b600b1-3e42-41fc-b474-e9c0c8f0c801"
        private const val NOTIFY = "09b600b0-3e42-41fc-b474-e9c0c8f0c801"
        private val HANDSHAKE = listOf("222001", "222001", "222001", "27420f4444001f00", "27420f4444001802")
    }
}
