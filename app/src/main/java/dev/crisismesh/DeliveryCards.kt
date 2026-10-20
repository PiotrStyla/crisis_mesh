package dev.crisismesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun Modifier.label(name: String) = semantics { contentDescription = name }

@Composable
fun LocalAccessCard(board: LocalBoardServer, hotspot: LocalHotspot) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tablica wyłączona. Włączenie udostępnia skróty opublikowanych raportów osobom w sieci; bez prywatnych notatek.") }
    var credentials by remember { mutableStateOf("") }
    var links by remember { mutableStateOf(emptyList<String>()) }
    var pendingHotspot by remember { mutableStateOf(false) }
    val startBoard: () -> Unit = {
        runCatching {
            val port = board.start()
            links = LocalBoardServer.addresses().map { "http://$it:$port/" }
            running = true
            status = "Tablica działa w bieżącej sieci. Nie wyświetla automatycznych powiadomień na obcych telefonach."
        }.onFailure { status = "Nie uruchomiono tablicy: ${it.message}" }
    }
    val startHotspot: () -> Unit = {
        credentials = ""
        startBoard()
        // LocalHotspot dispatches all callbacks on the main looper. Do not requeue stale state.
        if (running) hotspot.start({ ssid, password, addresses ->
            credentials = "Sieć: $ssid\nHasło: $password"
            links = addresses.map { "http://$it:8080/" }
            status = "Hotspot lokalny działa bez internetu. Połącz drugi telefon z podaną siecią, zaakceptuj pozostanie bez internetu i otwórz osiągalny adres poniżej. Lista obejmuje adresy lokalnych interfejsów; nie każdy należy do hotspotu."
        }, { text -> credentials = ""; status = text })
    }
    val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingHotspot && permissions.all { permission ->
                androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }) startHotspot()
        else status = "Brak uprawnienia do hotspotu. Na Androidzie 12 i starszym sprawdź też włączenie lokalizacji w ustawieniach."
        pendingHotspot = false
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dostęp bez aplikacji", style = MaterialTheme.typography.titleMedium)
            Text("Publiczna, tylko do odczytu, nieszyfrowana tablica HTTP. Udostępnia do 100 ostatnich opublikowanych raportów. Szkice i prywatne notatki pozostają na urządzeniu.", fontSize = 12.sp)
            Button(onClick = startBoard, enabled = !running, modifier = Modifier.fillMaxWidth().label("board-start")) {
                Text("Udostępnij tablicę w obecnej sieci")
            }
            Button(onClick = {
                pendingHotspot = true
                if (permissions.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                    pendingHotspot = false; startHotspot()
                } else permissionLauncher.launch(permissions)
            }, modifier = Modifier.fillMaxWidth().label("hotspot-start")) { Text("Uruchom lokalny hotspot i tablicę") }
            if (credentials.isNotEmpty()) Text(credentials, modifier = Modifier.label("hotspot-credentials"))
            links.forEach { Text(it, modifier = Modifier.label("board-url")) }
            Text(status, fontSize = 12.sp, modifier = Modifier.label("board-status"))
            if (running) TextButton(onClick = {
                hotspot.close(); board.close(); running = false; credentials = ""; links = emptyList()
                status = "Tablica i hotspot wyłączone."
            }, modifier = Modifier.label("board-stop")) { Text("Wyłącz udostępnianie") }
        }
    }
}

@Composable
fun ContactCard(numbers: List<String>, onSave: (String) -> Unit) {
    var text by remember(numbers) { mutableStateOf(numbers.joinToString(",")) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ustalone kontakty SOS", style = MaterialTheme.typography.titleMedium)
            Text("Opcjonalnie do 5 numerów. Przekazanie wybranego raportu otwiera edytor SMS; wysyłkę zatwierdzasz osobno. Wymaga sieci komórkowej i może kosztować. To nie jest integracja z 112 ani automatyczna bramka.", fontSize = 12.sp)
            OutlinedTextField(text, { text = it.take(128) }, label = { Text("Numery po przecinku") },
                modifier = Modifier.fillMaxWidth().label("contact-numbers"))
            Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth().label("contact-save")) { Text("Zapisz kontakty") }
        }
    }
}
