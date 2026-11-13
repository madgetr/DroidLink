package dev.droidlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** One screen: grant permissions, start/stop the bridge service, show its address. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    // The first three are required; notifications are optional
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = "DroidLink"; textSize = 28f; gravity = Gravity.CENTER })
            addView(status)
            addView(toggle)
        })
        // Start right away if we already have permission, so a relaunch "just works"
        if (hasPermissions() && !BridgeService.running) startBridge()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

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
    }

    private fun hasPermissions() = permissions.take(3).all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        val ip = getSystemService(WifiManager::class.java).connectionInfo.ipAddress
        val addr = if (ip == 0) "localhost" else
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        status.text = if (BridgeService.running) "Running\nhttp://$addr:${BridgeService.PORT}" else "Stopped"
        toggle.text = if (BridgeService.running) "Stop" else "Start"
    }

    companion object { private const val REQUEST_PERMS = 1 }
}
