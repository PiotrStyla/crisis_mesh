package dev.crisismesh

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** User-enabled local network, not internet tethering and not a captive portal. */
class LocalHotspot(context: Context) : AutoCloseable {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var generation = 0
    private var addressTask: Runnable? = null

    @SuppressLint("MissingPermission")
    @Synchronized fun start(onReady: (String, String, List<String>) -> Unit, onStatus: (String) -> Unit) {
        close()
        val token = generation
        onStatus("Uruchamianie lokalnego hotspotu…")
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(current: WifiManager.LocalOnlyHotspotReservation) {
                    synchronized(this@LocalHotspot) {
                        if (token != generation) { current.close(); return }
                        reservation = current
                        val ssid: String
                        val password: String
                        if (Build.VERSION.SDK_INT >= 30) {
                            ssid = current.softApConfiguration.ssid ?: ""
                            password = current.softApConfiguration.passphrase ?: ""
                        } else {
                            @Suppress("DEPRECATION") val config = current.wifiConfiguration
                            ssid = config?.SSID?.removeSurrounding("\"") ?: ""
                            password = config?.preSharedKey?.removeSurrounding("\"") ?: ""
                        }
                        addressTask = Runnable {
                            synchronized(this@LocalHotspot) {
                                if (token == generation) onReady(ssid, password, LocalBoardServer.addresses())
                            }
                        }.also { handler.postDelayed(it, 1500) }
                    }
                }
                override fun onStopped() {
                    synchronized(this@LocalHotspot) {
                        if (token == generation) {
                            generation++
                            addressTask?.let { handler.removeCallbacks(it) }; addressTask = null
                            reservation = null
                            onStatus("Hotspot został zatrzymany przez system.")
                        }
                    }
                }
                override fun onFailed(reason: Int) {
                    synchronized(this@LocalHotspot) {
                        if (token == generation) onStatus(when (reason) {
                            ERROR_NO_CHANNEL -> "Brak dostępnego kanału Wi-Fi dla hotspotu."
                            ERROR_INCOMPATIBLE_MODE -> "Hotspot koliduje z obecnym trybem Wi-Fi. Zmień połączenie w ustawieniach telefonu."
                            ERROR_TETHERING_DISALLOWED -> "System lub administrator nie pozwala uruchomić hotspotu."
                            else -> "System nie uruchomił hotspotu (kod $reason). Tablica może nadal działać w istniejącej sieci LAN."
                        })
                    }
                }
            }, handler)
        } catch (e: Exception) { onStatus("Nie można uruchomić hotspotu: ${e.message}") }
    }

    @Synchronized override fun close() {
        generation++
        addressTask?.let { handler.removeCallbacks(it) }; addressTask = null
        runCatching { reservation?.close() }; reservation = null
    }
}
