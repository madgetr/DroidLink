package dev.droidlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Start/stop the bridge, and a simple settings panel: droid name and voice. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var nameField: EditText
    private lateinit var voiceSpinner: Spinner
    private lateinit var pitchLabel: TextView
    private lateinit var rateLabel: TextView
    private lateinit var recheckLabel: TextView
    private lateinit var droidStatus: TextView
    private lateinit var droidButton: Button
    @Volatile private var droidBusy = false
    private val droidPoll = object : Runnable {
        override fun run() { refreshDroid(); ui.postDelayed(this, 2_000) }
    }
    private var voiceIds: List<String> = emptyList()
    private val ui = Handler(Looper.getMainLooper())

    // The first three are required; microphone and notifications are optional
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildUi())
        // Start right away if we already have permission, so a relaunch "just works"
        if (hasPermissions() && !BridgeService.running) startBridge()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        loadVoices()
        ui.post(droidPoll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(droidPoll)
    }

    private fun refreshDroid() {
        val d = BridgeService.droidLink
        when {
            droidBusy -> { droidStatus.text = "Working..."; droidButton.isEnabled = false }
            d == null -> { droidStatus.text = "Start the bridge first"; droidButton.isEnabled = false; droidButton.text = "Connect" }
            d.connected -> { droidStatus.text = "Connected to the droid"; droidButton.isEnabled = true; droidButton.text = "Disconnect (use remote)" }
            else -> { droidStatus.text = "Not connected (the remote works)"; droidButton.isEnabled = true; droidButton.text = "Connect" }
        }
    }

    private fun onDroidButton() {
        val svc = BridgeService.service ?: return
        val d = BridgeService.droidLink ?: return
        droidBusy = true
        refreshDroid()
        thread {   // BLE scanning/connecting takes a few seconds; keep it off the UI thread
            val error = try {
                if (d.connected) d.disconnect() else svc.connectDroid(null)
                null
            } catch (e: Exception) {
                e.message
            }
            ui.post {
                droidBusy = false
                refreshDroid()
                error?.let { Toast.makeText(this, "Droid: $it", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        title = TextView(this).apply {
            textSize = 32f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        col.addView(title); col.addView(status); col.addView(toggle)

        // --- droid connection (the handheld remote only works while disconnected)
        col.addView(header("Droid"))
        droidStatus = TextView(this).apply { textSize = 16f }
        droidButton = Button(this).apply { setOnClickListener { onDroidButton() } }
        col.addView(droidStatus)
        col.addView(droidButton)
        col.addView(Switch(this).apply {
            text = "Connect automatically when the app starts"; textSize = 14f
            isChecked = settings.autoConnect
            setOnCheckedChangeListener { _, on -> settings.autoConnect = on }
        })
        col.addView(TextView(this).apply {
            text = "While connected, the droid's handheld remote won't work."
            textSize = 13f
        })

        // --- name
        col.addView(header("Droid name"))
        nameField = EditText(this).apply {
            setText(settings.name)
            textSize = 24f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { saveName(); true } else false
            }
        }
        col.addView(nameField)
        col.addView(Button(this).apply { text = "Save name"; setOnClickListener { saveName() } })

        // --- voice
        col.addView(header("Voice"))
        voiceSpinner = Spinner(this)
        col.addView(voiceSpinner)

        pitchLabel = TextView(this)
        col.addView(pitchLabel)
        col.addView(slider(settings.pitch, 0.3f, 1.5f) { v ->
            settings.pitch = v; updateLabels()
        })
        rateLabel = TextView(this)
        col.addView(rateLabel)
        col.addView(slider(settings.rate, 0.5f, 1.6f) { v ->
            settings.rate = v; updateLabels()
        })
        col.addView(Switch(this).apply {
            text = "Robot effect"; textSize = 16f; isChecked = settings.robot
            setOnCheckedChangeListener { _, on -> settings.robot = on }
        })
        col.addView(Button(this).apply { text = "Test voice"; setOnClickListener { testVoice() } })

        // --- flashlight
        col.addView(header("Flashlight"))
        val modes = listOf("auto" to "Auto (on when it's dark)", "on" to "Always on", "off" to "Off")
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, modes.map { it.second })
            setSelection(modes.indexOfFirst { it.first == settings.torchMode }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val mode = modes[pos].first
                    if (mode == settings.torchMode) return
                    val cam = BridgeService.cameraHub
                    if (cam == null) { settings.torchMode = mode; return }
                    thread {   // switching the light talks to the camera; keep it off the UI thread
                        try { cam.setTorchMode(mode) } catch (e: Exception) {
                            ui.post { Toast.makeText(this@MainActivity, "Flashlight: ${e.message}", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
        recheckLabel = TextView(this)
        col.addView(recheckLabel)
        col.addView(SeekBar(this).apply {
            max = 28   // 2..30 s
            progress = settings.torchRecheckS - 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { settings.torchRecheckS = p + 2; updateLabels() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })

        updateLabels()
        return ScrollView(this).apply { addView(col) }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun slider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit) =
        SeekBar(this).apply {
            this.max = 100
            progress = ((value - min) / (max - min) * 100).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(min + (max - min) * p / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }

    private fun updateLabels() {
        title.text = settings.name
        pitchLabel.text = "Pitch: %.2f  (lower = deeper)".format(settings.pitch)
        rateLabel.text = "Speed: %.2f".format(settings.rate)
        recheckLabel.text = "Auto: check if the light is still needed every ${settings.torchRecheckS} s"
    }

    private fun saveName() {
        settings.name = nameField.text.toString()
        nameField.setText(settings.name)
        updateLabels()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(nameField.windowToken, 0)
        Toast.makeText(this, "Hello, ${settings.name}!", Toast.LENGTH_SHORT).show()
        testVoice()
    }

    /** Fill the voice list once the bridge's speech engine is ready. */
    private fun loadVoices(attempt: Int = 0) {
        val v = BridgeService.voice
        if (v == null || !v.ready) {
            if (attempt < 20) ui.postDelayed({ loadVoices(attempt + 1) }, 500)
            return
        }
        val choices = v.voiceChoices()
        voiceIds = choices.map { it.first }
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            choices.map { it.second })
        voiceIds.indexOf(v.currentVoice).takeIf { it >= 0 }?.let { voiceSpinner.setSelection(it) }
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val name = voiceIds[pos]
                if (name != v.currentVoice) v.configure(name, null, null, null)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun testVoice() {
        val v = BridgeService.voice
        if (v == null || !v.ready) {
            Toast.makeText(this, "Start the bridge first", Toast.LENGTH_SHORT).show()
            return
        }
        val line = "Hello! I am ${settings.name}."
        thread {
            try {
                v.chirp("greeting", null)
                v.speak(line)
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "Voice error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ------------------------------------------------------------ service

    private fun onToggle() {
        if (BridgeService.running) {
            stopService(Intent(this, BridgeService::class.java))
            BridgeService.running = false
            refresh()
        } else if (hasPermissions()) {
            startBridge()
        } else {
            requestPermissions(permissions, REQUEST_PERMS)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasPermissions()) startBridge() else {
            status.text = "Bluetooth and camera permissions are required."
        }
    }

    private fun startBridge() {
        startForegroundService(Intent(this, BridgeService::class.java))
        BridgeService.running = true
        refresh()
        loadVoices()
    }

    private fun hasPermissions() = permissions.take(3).all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        // Never let a status line crash the screen
        val ip = try { getSystemService(WifiManager::class.java).connectionInfo.ipAddress } catch (e: Exception) { 0 }
        val addr = if (ip == 0) "localhost" else
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        status.text = if (BridgeService.running) "Running at http://$addr:${BridgeService.PORT}" else "Stopped"
        toggle.text = if (BridgeService.running) "Stop" else "Start"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_PERMS = 1 }
}
