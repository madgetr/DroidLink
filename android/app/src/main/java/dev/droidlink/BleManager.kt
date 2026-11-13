package dev.droidlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Generic BLE central: scan, connect, inspect GATT, read/write characteristics,
 * and collect notifications. Android only allows one GATT operation in flight,
 * so every operation is serialized and waits for its callback.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity
class BleManager(private val ctx: Context) {

    private val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
    private val opLock = Any()
    @Volatile private var pending: CompletableFuture<Any?>? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var state = "disconnected"
    @Volatile private var mtu = 23
    private val notifications = ArrayDeque<JSONObject>()
    private var notifySeq = 0L

    // ---------------------------------------------------------------- scan

    /**
     * Scan for [seconds]. Android silently returns nothing for *unfiltered* scans
     * while the screen is off, so pass [exactName] and/or [manufacturerId] when
     * looking for something specific; those use real scan filters.
     */
    fun scan(seconds: Double, nameFilter: String?, exactName: String? = null,
             manufacturerId: Int? = null): JSONArray {
        val scanner = adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth is off")
        respectScanBudget()
        val found = ConcurrentHashMap<String, JSONObject>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val rec = r.scanRecord
                val o = JSONObject()
                    .put("address", r.device.address)
                    .put("name", rec?.deviceName ?: r.device.name ?: JSONObject.NULL)
                    .put("rssi", r.rssi)
                rec?.serviceUuids?.let { uuids -> o.put("service_uuids", JSONArray(uuids.map { it.toString() })) }
                val mfr = rec?.manufacturerSpecificData
                if (mfr != null && mfr.size() > 0) {
                    val m = JSONObject()
                    for (i in 0 until mfr.size()) m.put("0x%04x".format(mfr.keyAt(i)), mfr.valueAt(i).toHex())
                    o.put("manufacturer_data", m)
                }
                found[r.device.address] = o
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Several filters = match ANY of them, in a single scan
        val filters = buildList {
            exactName?.let { add(ScanFilter.Builder().setDeviceName(it).build()) }
            manufacturerId?.let { add(ScanFilter.Builder().setManufacturerData(it, ByteArray(0)).build()) }
        }
        scanner.startScan(filters.ifEmpty { null }, settings, cb)
        try {
            Thread.sleep((seconds * 1000).toLong())
        } finally {
            scanner.stopScan(cb)
        }
        val list = found.values
            .filter { nameFilter.isNullOrEmpty() || it.optString("name").contains(nameFilter, ignoreCase = true) }
            .sortedByDescending { it.getInt("rssi") }
        return JSONArray(list)
    }

    /**
     * Android silently returns *empty* scans for an app that starts more than 5
     * scans in 30 s. Wait if we're about to hit that, instead of getting nothing.
     */
    private val scanStarts = ArrayDeque<Long>()
    private fun respectScanBudget() = synchronized(scanStarts) {
        val now = System.currentTimeMillis()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > SCAN_WINDOW_MS) scanStarts.removeFirst()
        if (scanStarts.size >= MAX_SCANS_PER_WINDOW) {
            val wait = SCAN_WINDOW_MS - (now - scanStarts.first()) + 500
            Log.i(TAG, "scan budget used up, waiting ${wait} ms")
            Thread.sleep(wait)
            scanStarts.removeFirst()
        }
        scanStarts.addLast(System.currentTimeMillis())
    }

    // ---------------------------------------------------------- connection

    fun connect(address: String): JSONObject {
        disconnect()
        val device = adapter?.getRemoteDevice(address.uppercase())
            ?: throw IllegalStateException("Bluetooth is off")
        state = "connecting"
        op(15_000) {
            gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
        op(10_000) { gatt!!.discoverServices() }
        // A bigger MTU lets longer commands go out in one packet; not fatal if refused.
        try { op(5_000) { gatt!!.requestMtu(185) } } catch (e: Exception) { Log.w(TAG, "MTU request failed", e) }
        state = "connected"
        return status().put("services", services())
    }

    fun disconnect() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        state = "disconnected"
    }

    fun isConnected() = state == "connected" && gatt != null

    fun status(): JSONObject = JSONObject()
        .put("state", state)
        .put("address", gatt?.device?.address ?: JSONObject.NULL)
        .put("mtu", mtu)

    // ---------------------------------------------------------------- GATT

    fun services(): JSONArray {
        val g = requireGatt()
        return JSONArray(g.services.map { s ->
            JSONObject()
                .put("uuid", s.uuid.toString())
                .put("characteristics", JSONArray(s.characteristics.map { c ->
                    JSONObject().put("uuid", c.uuid.toString()).put("properties", JSONArray(props(c.properties)))
                }))
        })
    }

    fun write(charUuid: String, hex: String, withResponse: Boolean): JSONObject {
        val g = requireGatt()
        val c = findChar(charUuid)
        val bytes = hex.hexToBytes()
        if (withResponse) {
            op(5_000) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            }
        } else {
            // Fire-and-forget (motor updates): don't wait for a callback, which
            // can stall the control loop for up to a second. Just retry briefly
            // if the BLE stack is still busy with the previous packet.
            synchronized(opLock) {
                var rc = -1
                for (attempt in 0 until 40) {
                    rc = g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    if (rc == BluetoothStatusCodes.SUCCESS) break
                    Thread.sleep(5)
                }
                if (rc != BluetoothStatusCodes.SUCCESS) throw IllegalStateException("BLE write failed ($rc)")
            }
        }
        return JSONObject().put("written", bytes.size).put("char", c.uuid.toString())
    }

    fun read(charUuid: String): JSONObject {
        val g = requireGatt()
        val c = findChar(charUuid)
        val value = op(5_000) { g.readCharacteristic(c) } as ByteArray
        return JSONObject().put("char", c.uuid.toString()).put("hex", value.toHex())
    }

    fun setNotify(charUuid: String, enable: Boolean): JSONObject {
        val g = requireGatt()
        val c = findChar(charUuid)
        g.setCharacteristicNotification(c, enable)
        val cccd = c.getDescriptor(CCCD) ?: throw IllegalArgumentException("characteristic has no CCCD")
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        op(5_000) { g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS }
        return JSONObject().put("char", c.uuid.toString()).put("notify", enable)
    }

    /** Notifications received since sequence number [since] (oldest first). */
    fun notifications(since: Long): JSONObject = synchronized(notifications) {
        JSONObject()
            .put("next", notifySeq)
            .put("items", JSONArray(notifications.filter { it.getLong("seq") >= since }))
    }

    // ----------------------------------------------------------- internals

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                pending?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state = "disconnected"
                pending?.completeExceptionally(IllegalStateException("disconnected (status $status)"))
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status, true)

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            finish(status, newMtu)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(status, true)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(status, value)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(status, true)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            synchronized(notifications) {
                notifications.addLast(JSONObject()
                    .put("seq", notifySeq++)
                    .put("ts", System.currentTimeMillis() / 1000.0)
                    .put("char", c.uuid.toString())
                    .put("hex", value.toHex()))
                while (notifications.size > MAX_NOTIFICATIONS) notifications.removeFirst()
            }
        }
    }

    private fun finish(status: Int, result: Any?) {
        if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(result)
        else pending?.completeExceptionally(IllegalStateException("GATT error $status"))
    }

    /** Start one GATT operation and block until its callback completes it. */
    private fun op(timeoutMs: Long, start: () -> Boolean): Any? = synchronized(opLock) {
        val f = CompletableFuture<Any?>()
        pending = f
        try {
            // The stack refuses new operations while earlier packets (e.g. fire-and-
            // forget motor writes) are still queued; wait briefly and retry.
            var started = start()
            var tries = 0
            while (!started && tries++ < 40) {
                Thread.sleep(5)
                started = start()
            }
            if (!started) throw IllegalStateException("BLE operation could not be started")
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            pending = null
        }
    }

    private fun requireGatt(): BluetoothGatt =
        gatt?.takeIf { state == "connected" } ?: throw IllegalArgumentException("not connected")

    private fun findChar(uuid: String): BluetoothGattCharacteristic {
        val want = parseUuid(uuid)
        for (s in requireGatt().services) {
            s.getCharacteristic(want)?.let { return it }
        }
        throw IllegalArgumentException("characteristic $uuid not found")
    }

    private fun props(p: Int) = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write_no_response")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }

    companion object {
        private const val TAG = "BleManager"
        private const val MAX_NOTIFICATIONS = 500
        private const val MAX_SCANS_PER_WINDOW = 4     // Android's limit is 5 per 30 s
        private const val SCAN_WINDOW_MS = 30_000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Accept full UUIDs or 16-bit short forms like "ffe1". */
        fun parseUuid(s: String): UUID =
            if (s.length == 4) UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb") else UUID.fromString(s)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "").replace(":", "")
    require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "bad hex: $this" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
