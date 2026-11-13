package dev.droidlink

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

fun now() = System.currentTimeMillis() / 1000.0

/**
 * Listens to the phone's sensors and keeps the latest reading of each.
 * The JSON shape matches the original Termux /sensors endpoint.
 */
class SensorHub(private val ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(SensorManager::class.java)

    private val types = mapOf(
        Sensor.TYPE_ACCELEROMETER to "accelerometer",
        Sensor.TYPE_GYROSCOPE to "gyroscope",
        Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
        Sensor.TYPE_GRAVITY to "gravity",
        Sensor.TYPE_LINEAR_ACCELERATION to "linear_acceleration",
        Sensor.TYPE_ROTATION_VECTOR to "rotation_vector",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "game_rotation_vector",
        Sensor.TYPE_LIGHT to "light",
        Sensor.TYPE_PRESSURE to "pressure",
        Sensor.TYPE_PROXIMITY to "proximity",
        Sensor.TYPE_STEP_COUNTER to "step_counter",
    )

    class Reading(val ts: Double, val values: FloatArray)

    private val latest = ConcurrentHashMap<String, Reading>()

    /** Camera-relative pose, updated from the rotation vector. */
    @Volatile var azimuth = Double.NaN; private set   // compass heading the back camera faces, 0-360
    @Volatile var pitch = Double.NaN; private set     // camera elevation, + = looking up
    @Volatile var roll = Double.NaN; private set      // sideways tilt of the phone
    @Volatile var poseTs = 0.0; private set

    /**
     * Gyro-based heading in degrees, continuous (keeps counting past 360) and
     * immune to magnets like the droid's motors. Its zero point is arbitrary, so
     * use it for relative turns, and the compass [azimuth] for absolute heading.
     */
    @Volatile var yaw = Double.NaN; private set
    @Volatile var yawTs = 0.0; private set
    private var lastRawYaw = Double.NaN

    fun start() {
        for ((type, _) in types) {
            sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val name = types[e.sensor.type] ?: return
        latest[name] = Reading(now(), e.values.clone())
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) updatePose(e.values)
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) updateYaw(e.values)
    }

    private fun updateYaw(rv: FloatArray) {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, rv)
        // Same camera-forward direction as the compass heading (see updatePose)
        val raw = (Math.toDegrees(atan2(-r[2].toDouble(), -r[5].toDouble())) + 360) % 360
        yaw = if (lastRawYaw.isNaN() || yaw.isNaN()) raw
              else yaw + ((raw - lastRawYaw + 540) % 360 - 180)   // unwrap
        lastRawYaw = raw
        yawTs = now()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updatePose(rv: FloatArray) {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, rv)
        // r maps device axes -> world (East, North, Up). The back camera looks along
        // device -Z, so its world direction is minus the third column of r. Using the
        // camera direction keeps the heading right in portrait AND landscape mounts.
        val east = -r[2].toDouble()
        val north = -r[5].toDouble()
        val up = -r[8].toDouble()
        azimuth = (Math.toDegrees(atan2(east, north)) + 360) % 360
        pitch = Math.toDegrees(asin(up.coerceIn(-1.0, 1.0)))
        roll = Math.toDegrees(asin(r[6].toDouble().coerceIn(-1.0, 1.0)))
        poseTs = now()
    }

    /** Ambient light in lux, or null if the light sensor hasn't reported yet. */
    fun lux(): Float? = latest["light"]?.values?.get(0)

    /** Magnitude of linear acceleration (gravity removed), m/s^2. */
    fun linearAccel(): Double {
        val v = latest["linear_acceleration"]?.values ?: return 0.0
        return sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
    }

    fun snapshot(): JSONObject {
        val t = now()
        val sensors = JSONObject()
        for ((name, rd) in latest) {
            sensors.put(name, format(name, rd).put("ts", rd.ts).put("age_s", round2(t - rd.ts)))
        }
        if (!azimuth.isNaN()) {
            sensors.put("orientation", JSONObject()
                .put("ts", poseTs).put("azimuth", azimuth).put("pitch", pitch).put("roll", roll)
                .put("yaw", if (yaw.isNaN()) JSONObject.NULL else yaw)
                .put("unit", "deg").put("age_s", round2(t - poseTs)))
        }
        return JSONObject()
            .put("ts", t)
            .put("sensors", sensors)
            .put("battery", battery())
            .put("error", JSONObject.NULL)
    }

    fun one(name: String): JSONObject? = when (name) {
        "battery" -> battery()
        else -> snapshot().getJSONObject("sensors").optJSONObject(name)
    }

    private fun format(name: String, rd: Reading): JSONObject {
        val v = rd.values
        val o = JSONObject()
        fun xyz(unit: String) = o.put("x", v[0]).put("y", v[1]).put("z", v[2]).put("unit", unit)
        when (name) {
            "accelerometer", "gravity", "linear_acceleration" -> xyz("m/s^2")
            "gyroscope" -> xyz("rad/s")
            "magnetometer" -> xyz("uT")
            "rotation_vector" -> o.put("x", v[0]).put("y", v[1]).put("z", v[2]).put("w", v.getOrElse(3) { 0f })
            "light" -> o.put("value", v[0]).put("unit", "lux")
            "pressure" -> o.put("value", v[0]).put("unit", "hPa")
            "proximity" -> o.put("value", v[0]).put("unit", "cm")
            "step_counter" -> o.put("value", v[0].toInt()).put("unit", "steps")
        }
        return o
    }

    private fun battery(): JSONObject {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return JSONObject()
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
            else -> "UNPLUGGED"
        }
        return JSONObject()
            .put("percentage", if (level >= 0) level * 100 / scale else JSONObject.NULL)
            .put("status", status)
            .put("plugged", plugged)
            .put("temperature", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            .put("voltage", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
    }

    private fun round2(d: Double) = Math.round(d * 100) / 100.0
}
