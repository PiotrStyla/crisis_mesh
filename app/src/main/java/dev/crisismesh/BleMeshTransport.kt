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
 * The last write response confirms frame reception, not storage or multi-hop delivery.
 * No pairing, encryption, background service or discovery identity is implied.
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(context: Context) : MeshTransport {
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

    override fun send(address: String, payload: ByteArray, onStatus: (String) -> Unit) {
        if (!MAC_ADDRESS.matches(address)) {
            onStatus("Podaj adres BLE jako AA:BB:CC:DD:EE:FF")
            return
        }
        val token = synchronized(this) { generation }
        Thread({
            try {
                sendBlocking(address, payload, token)
                onStatus("Ramka BLE odebrana przez $address · ${payload.size} B (nie potwierdza zapisu)")
            } catch (e: Exception) {
                onStatus("Błąd wysyłki BLE: ${e.message ?: e.javaClass.simpleName}")
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
                bt.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
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

    /** Only Crisis Mesh service advertisers; results expire with scan/address rotation. */
    @Synchronized
    fun startScan(onDevice: (String) -> Unit, onStatus: (String) -> Unit) {
        stopScan()
        try {
            val bt = adapter
            check(bt != null && bt.isEnabled) { "Bluetooth niedostępny lub wyłączony" }
            val scanner = bt.bluetoothLeScanner ?: error("Skanowanie BLE niedostępne")
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    synchronized(this@BleMeshTransport) {
                        if (scan === this) onDevice("Crisis Mesh|${result.device.address}")
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
    fun stopScan() {
        stopScanTask?.let { handler.removeCallbacks(it) }
        stopScanTask = null
        scan?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scan = null
    }

    @Synchronized
    override fun close() {
        generation++
        stopScan()
        advertising?.let { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertising = null
        runCatching { server?.close() }
        server = null
        sends.forEach { runCatching { it.disconnect(); it.close() } }
        sends.clear()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("d9e0b811-484c-4a01-9ada-b8700e4182a1")
        val FRAME_UUID: UUID = UUID.fromString("d9e0b812-484c-4a01-9ada-b8700e4182a1")
    }
}
