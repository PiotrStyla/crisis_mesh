package dev.crisismesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One ordered sequence of acknowledged 20-byte ATT writes per connection.
 * The last write response follows the receive COMMIT (or durable duplicate lookup).
 * Stable discovery IDs are not authenticated identities. No background-service guarantee.
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(context: Context, private val nodeId: String) : MeshTransport {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = manager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var advertising: AdvertiseCallback? = null
    private var scan: ScanCallback? = null
    private var stopScanTask: Runnable? = null
    private var generation = 0
    private val sends = HashSet<BluetoothGatt>()
    // Preserve LE address metadata supplied by the scanner, not just the MAC.
    private class Endpoint(var device: BluetoothDevice, var seenAt: Long)
    private val scannedDevices = LinkedHashMap<String, Endpoint>(64, 0.75f, true)
    private var discovering = false
    private var discoveryTask: Runnable? = null
    private val identity = nodeId.chunked(2).map { it.toInt(16).toByte() }.toByteArray().also {
        require(it.size == 8) { "Invalid node identity" }
    }

    @Synchronized
    fun reachablePeers(): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        return scannedDevices.filterValues { now - it.seenAt < 120_000 }.keys
    }

    @Synchronized
    override fun listen(port: Int, onPacket: (ByteArray) -> Unit, onStatus: (String) -> Unit) {
        close()
        val token = generation
        val incoming = HashMap<String, MeshFrames.Assembler>()
        try {
            val bt = adapter
            check(bt != null && bt.isEnabled) { "Bluetooth niedostępny lub wyłączony" }
            val le = bt.bluetoothLeAdvertiser ?: error("Reklamowanie BLE niedostępne")
            val callback = object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                    synchronized(this@BleMeshTransport) {
                        if (token != generation) return
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            close()
                            onStatus("Błąd dodawania usługi GATT: $status")
                            return
                        }
                        val advert = object : AdvertiseCallback() {
                            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                                synchronized(this@BleMeshTransport) {
                                    if (token == generation) onStatus("Nasłuch BLE · reklamowanie włączone")
                                }
                            }
                            override fun onStartFailure(errorCode: Int) {
                                synchronized(this@BleMeshTransport) {
                                    if (token == generation) {
                                        close()
                                        onStatus("Błąd reklamowania BLE: $errorCode")
                                    }
                                }
                            }
                        }
                        advertising = advert
                        try {
                            le.startAdvertising(
                                AdvertiseSettings.Builder().setConnectable(true)
                                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(),
                                AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
                                AdvertiseData.Builder().addServiceData(ParcelUuid(SERVICE_UUID), identity).build(),
                                advert,
                            )
                        } catch (e: Exception) {
                            close()
                            onStatus("Błąd startu BLE: ${e.message}")
                        }
                    }
                }

                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    synchronized(this@BleMeshTransport) {
                        if (newState != BluetoothProfile.STATE_CONNECTED) incoming.remove(device.address)
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
                ) {
                    synchronized(this@BleMeshTransport) {
                        if (token != generation) return
                        var result = BluetoothGatt.GATT_SUCCESS
                        try {
                            require(characteristic.uuid == FRAME_UUID && !preparedWrite && offset == 0) {
                                "Unsupported write"
                            }
                            require(value.size in 1..MeshFrames.ATT_CHUNK) { "Invalid fragment size" }
                            require(incoming.containsKey(device.address) || incoming.size < 8) { "Too many peers" }
                            incoming.getOrPut(device.address) { MeshFrames.Assembler() }.feed(value).forEach(onPacket)
                        } catch (e: Exception) {
                            incoming.remove(device.address)
                            result = BluetoothGatt.GATT_FAILURE
                            onStatus("Odrzucono fragment BLE: ${e.message}")
                        }
                        if (responseNeeded) server?.sendResponse(device, requestId, result, 0, null)
                    }
                }
            }
            server = manager.openGattServer(context, callback) ?: error("GATT server niedostępny")
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(
                FRAME_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE,
            ))
            check(server!!.addService(service)) { "Nie można dodać usługi GATT" }
        } catch (e: Exception) {
            close()
            onStatus("Błąd nasłuchu BLE: ${e.message}")
        }
    }

    override fun send(address: String, payload: ByteArray, onComplete: (Result<Unit>) -> Unit) {
        if (!NODE_ADDRESS.matches(address)) {
            onComplete(Result.failure(IllegalArgumentException("Nieprawidłowy identyfikator węzła BLE")))
            return
        }
        val token = synchronized(this) { generation }
        Thread({
            try {
                sendBlocking(address, payload, token)
                onComplete(Result.success(Unit))
            } catch (e: Exception) {
                onComplete(Result.failure(e))
            }
        }, "mesh-ble-send").apply { isDaemon = true; start() }
    }

    private fun sendBlocking(address: String, payload: ByteArray, token: Int) {
        // Serialize sends from this node; the peripheral has one ordered stream per central.
        synchronized(sendLock) {
            val bt = adapter
            check(bt != null && bt.isEnabled) { "Bluetooth niedostępny lub wyłączony" }
            val events = LinkedBlockingQueue<Pair<String, Int>>()
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    events.offer((if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected") to status)
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    events.offer("services" to status)
                }
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    events.offer("written" to status)
                }
            }
            val gatt = synchronized(this) {
                check(token == generation) { "Transport został zamknięty" }
                check(sends.size < 8) { "Too many connections" }
                val endpoint = scannedDevices[address]
                check(endpoint != null && android.os.SystemClock.elapsedRealtime() - endpoint.seenAt < 120_000) {
                    "Węzeł poza aktualnym skanem; oczekiwanie na ponowne wykrycie"
                }
                val device = endpoint.device
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?.also { sends.add(it) } ?: error("Nie można połączyć GATT")
            }
            fun await(expected: String) {
                val event = events.poll(15, TimeUnit.SECONDS) ?: error("Timeout BLE: $expected")
                check(event.first == expected && event.second == BluetoothGatt.GATT_SUCCESS) {
                    "GATT ${event.first}: ${event.second}, oczekiwano $expected"
                }
                synchronized(this) { check(token == generation) { "Transport został zamknięty" } }
            }
            try {
                await("connected")
                check(gatt.discoverServices()) { "Nie można rozpocząć wykrywania usług" }
                await("services")
                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(FRAME_UUID)
                    ?: error("Brak usługi Crisis Mesh")
                for (chunk in MeshFrames.chunks(MeshFrames.frame(payload), MeshFrames.ATT_CHUNK)) {
                    val accepted = if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = chunk
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    }
                    check(accepted) { "Nie można zapisać fragmentu BLE" }
                    await("written")
                }
            } finally {
                synchronized(this) { sends.remove(gatt) }
                runCatching { gatt.disconnect() }
                gatt.close()
            }
        }
    }
    private val sendLock = Any()

    /** Periodic foreground discovery; rotating radio addresses resolve to stable peer keys. */
    @Synchronized
    fun startDiscovery(onDevice: (String) -> Unit, onStatus: (String) -> Unit) {
        if (discovering) return
        discovering = true
        val cycle = object : Runnable {
            override fun run() {
                synchronized(this@BleMeshTransport) {
                    if (!discovering) return
                    scanOnce(onDevice, onStatus)
                    handler.postDelayed(this, 40_000)
                }
            }
        }
        discoveryTask = cycle
        handler.post(cycle)
    }

    @Synchronized
    private fun scanOnce(onDevice: (String) -> Unit, onStatus: (String) -> Unit) {
        stopScan()
        try {
            val bt = adapter
            check(bt != null && bt.isEnabled) { "Bluetooth niedostępny lub wyłączony" }
            val scanner = bt.bluetoothLeScanner ?: error("Skanowanie BLE niedostępne")
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    synchronized(this@BleMeshTransport) {
                        if (scan === this) {
                            val idBytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                            if (idBytes.size != 8) return
                            val id = buildString(16) {
                                for (byte in idBytes) {
                                    val value = byte.toInt() and 255
                                    append(HEX[value ushr 4]); append(HEX[value and 15])
                                }
                            }
                            if (id == nodeId) return
                            val peer = "node:$id"
                            val previous = scannedDevices[peer]
                            val changed = previous == null || previous.device.address != result.device.address
                            if (previous == null) {
                                if (scannedDevices.size >= 64) scannedDevices.remove(scannedDevices.keys.first())
                                scannedDevices[peer] = Endpoint(result.device, android.os.SystemClock.elapsedRealtime())
                            } else {
                                previous.device = result.device
                                previous.seenAt = android.os.SystemClock.elapsedRealtime()
                            }
                            if (changed) onDevice(peer)
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    synchronized(this@BleMeshTransport) {
                        if (scan === this) {
                            stopScan()
                            onStatus("Błąd skanowania BLE: $errorCode")
                        }
                    }
                }
            }
            scan = callback
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            stopScanTask = Runnable {
                synchronized(this) {
                    if (scan === callback) {
                        stopScan()
                        onStatus("Skanowanie BLE zakończone")
                    }
                }
            }.also { handler.postDelayed(it, 10_000) }
            onStatus("Skanowanie BLE · 10 sekund")
        } catch (e: Exception) {
            stopScan()
            onStatus("Błąd skanowania BLE: ${e.message}")
        }
    }

    @Synchronized
    private fun stopScan() {
        stopScanTask?.let { handler.removeCallbacks(it) }
        stopScanTask = null
        scan?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scan = null
    }

    @Synchronized
    override fun close() {
        generation++
        discovering = false
        discoveryTask?.let { handler.removeCallbacks(it) }
        discoveryTask = null
        stopScan()
        scannedDevices.clear()
        advertising?.let { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertising = null
        runCatching { server?.close() }
        server = null
        sends.forEach { runCatching { it.disconnect(); it.close() } }
        sends.clear()
    }

    companion object {
        private val NODE_ADDRESS = Regex("node:[0-9a-f]{16}")
        private const val HEX = "0123456789abcdef"
        val SERVICE_UUID: UUID = UUID.fromString("d9e0b821-484c-4a01-9ada-b8700e4182a1")
        val FRAME_UUID: UUID = UUID.fromString("d9e0b822-484c-4a01-9ada-b8700e4182a1")
    }
}
