package dev.droidlink

import android.content.Context
import org.json.JSONObject

/** The droid's saved settings (name and voice). Persist across restarts. */
class Settings(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("droidlink", Context.MODE_PRIVATE)

    var name: String
        get() = prefs.getString("name", DEFAULT_NAME)!!
        set(v) = prefs.edit().putString("name", v.trim().take(30).ifEmpty { DEFAULT_NAME }).commit().let {}

    /** TTS voice name, or null for the default male voice. */
    var voice: String?
        get() = prefs.getString("voice", null)
        set(v) = prefs.edit().putString("voice", v).commit().let {}

    var pitch: Float
        get() = prefs.getFloat("pitch", 0.8f)
        set(v) = prefs.edit().putFloat("pitch", v.coerceIn(0.3f, 2.0f)).commit().let {}

    var rate: Float
        get() = prefs.getFloat("rate", 1.0f)
        set(v) = prefs.edit().putFloat("rate", v.coerceIn(0.5f, 2.0f)).commit().let {}

    var robot: Boolean
        get() = prefs.getBoolean("robot", true)
        set(v) = prefs.edit().putBoolean("robot", v).commit().let {}

    /** Flashlight: "auto" (on when dark), "on" or "off". */
    var torchMode: String
        get() = prefs.getString("torch_mode", "auto")!!
        set(v) = prefs.edit().putString("torch_mode", v).commit().let {}

    /** In auto mode, how often (seconds) to check whether the light is still needed. */
    var torchRecheckS: Int
        get() = prefs.getInt("torch_recheck_s", 10)
        set(v) = prefs.edit().putInt("torch_recheck_s", v.coerceIn(2, 30)).commit().let {}

    // Motor calibration (0..1 power). Defaults are for an R-unit carrying the phone.
    var turnPower: Float
        get() = prefs.getFloat("turn_power", 0.45f)
        set(v) = prefs.edit().putFloat("turn_power", v.coerceIn(0.1f, 1f)).commit().let {}
    var drivePower: Float
        get() = prefs.getFloat("drive_power", 0.6f)
        set(v) = prefs.edit().putFloat("drive_power", v.coerceIn(0.1f, 1f)).commit().let {}
    var minPower: Float
        get() = prefs.getFloat("min_power", 0.28f)
        set(v) = prefs.edit().putFloat("min_power", v.coerceIn(0f, 1f)).commit().let {}
    /** Value sent in the motor command's ramp field (300 = what the droid's own app sends). */
    var rampMs: Int
        get() = prefs.getInt("ramp_ms", 100)
        set(v) = prefs.edit().putInt("ramp_ms", v.coerceIn(0, 2000)).commit().let {}

    /** In a turn, start easing off the power within this many degrees of the target. */
    var easeDeg: Float
        get() = prefs.getFloat("ease_deg", 45f)
        set(v) = prefs.edit().putFloat("ease_deg", v.coerceIn(1f, 180f)).commit().let {}

    // Head (dome) motor calibration
    var headPower: Float
        get() = prefs.getFloat("head_power", 0.75f)
        set(v) = prefs.edit().putFloat("head_power", v.coerceIn(0.1f, 1f)).commit().let {}
    var headMinPower: Float
        get() = prefs.getFloat("head_min_power", 0.7f)
        set(v) = prefs.edit().putFloat("head_min_power", v.coerceIn(0f, 1f)).commit().let {}
    var headCoastS: Float
        get() = prefs.getFloat("head_coast_s", 0.25f)
        set(v) = prefs.edit().putFloat("head_coast_s", v.coerceIn(0f, 1f)).commit().let {}
    /** Head power for canned gestures (the dome without the phone on it). */
    var headGesturePower: Float
        get() = prefs.getFloat("head_gesture_power", 0.55f)
        set(v) = prefs.edit().putFloat("head_gesture_power", v.coerceIn(0.1f, 1f)).commit().let {}

    /** Half the head's range of motion, degrees (0 = not calibrated yet). */
    var headHalfRange: Float
        get() = prefs.getFloat("head_half_range", 0f)
        set(v) = prefs.edit().putFloat("head_half_range", v.coerceIn(0f, 180f)).commit().let {}

    /** +1 if a positive head command turns the head right (clockwise from above), else -1. */
    var headSign: Int
        get() = prefs.getInt("head_sign", -1)
        set(v) = prefs.edit().putInt("head_sign", if (v < 0) -1 else 1).commit().let {}

    /** Turns stop this many seconds of spinning early, because the droid coasts. */
    var coastS: Float
        get() = prefs.getFloat("coast_s", 0.25f)
        set(v) = prefs.edit().putFloat("coast_s", v.coerceIn(0f, 1f)).commit().let {}

    /** How far the droid drives per second at drivePower (measure and set this). */
    var cmPerSecond: Float
        get() = prefs.getFloat("cm_per_second", 15f)
        set(v) = prefs.edit().putFloat("cm_per_second", v.coerceIn(1f, 200f)).commit().let {}

    /**
     * Connect to the droid as soon as the app starts. Off by default: while the
     * phone is connected, the droid's handheld remote can't control it.
     */
    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", false)
        set(v) = prefs.edit().putBoolean("auto_connect", v).commit().let {}

    /** BLE address of the paired droid. */
    var droidAddress: String?
        get() = prefs.getString("droid_address", null)
        set(v) = prefs.edit().putString("droid_address", v).commit().let {}

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("voice", voice ?: JSONObject.NULL)
        .put("pitch", pitch)
        .put("rate", rate)
        .put("robot", robot)
        .put("torch_mode", torchMode)
        .put("torch_recheck_s", torchRecheckS)
        .put("droid_address", droidAddress ?: JSONObject.NULL)
        .put("auto_connect", autoConnect)

    companion object { const val DEFAULT_NAME = "Droid" }
}
