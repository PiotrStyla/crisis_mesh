// Offline SOS with automatic BLE discovery, explicit public board and user-confirmed SMS handoff.

package dev.crisismesh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

private val Amber = Color(0xFFF5B942)

/** Semantics label used by TalkBack and UI automation. */
private fun Modifier.cd(label: String): Modifier = semantics { contentDescription = label }
private val Ink = Color(0xFF0B0C0E)
private val Panel = Color(0xFF15171A)
private val Dim = Color(0xFF9AA0A6)

private val HAZARD_PL = mapOf(
    "unknown" to "nie ustalono", "fire" to "pożar",
    "flood" to "powódź", "collapse" to "zawalenie",
)
private val NEED_PL = mapOf(
    "unknown" to "nie ustalono", "rescue" to "ratunek",
    "medical" to "pomoc medyczna", "water" to "woda",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 250
    val opened = remember { runCatching { ReportStore(context) } }
    val store = opened.getOrNull()
    if (store == null) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Text("Nie można otworzyć historii. Stary plik nie został usunięty.\n${opened.exceptionOrNull()?.message}",
                    modifier = Modifier.padding(24.dp))
            }
        }
        return
    }
    val reports = remember { mutableStateListOf<StoredReport>().apply { addAll(store.load()) } }
    var people by remember { mutableStateOf<Int?>(null) }
    var children by remember { mutableStateOf<Int?>(null) }
    var hazard by remember { mutableStateOf("unknown") }
    var need by remember { mutableStateOf("unknown") }
    var note by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }
    var importPreview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<StoredReport?>(null) }
    var transportKind by remember { mutableStateOf(store.selectedTransport()) }
    var meshPeer by remember { mutableStateOf(store.peers(transportKind).joinToString(",")) }
    var meshStatus by remember { mutableStateOf("Kolejka offline gotowa") }
    var queueInfo by remember { mutableStateOf(store.queueSummary(transportKind)) }
    var networkJoined by remember { mutableStateOf(store.autoMeshEnabled()) }
    val board = remember(store) { LocalBoardServer { store.publicReports() } }
    val hotspot = remember { LocalHotspot(context) }
    val contacts = remember { ContactHandoff(context) }
    var contactNumbers by remember { mutableStateOf(contacts.contacts()) }
    var smsReport by remember { mutableStateOf<StoredReport?>(null) }
    val transport = remember(transportKind) {
        if (transportKind == "ble") BleMeshTransport(context, store.nodeId()) else TcpMeshTransport()
    }
    val mesh = remember(transport) {
        MeshNode(store, transport, transportKind) {
            transportKind != "ble" || blePermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
    // Bluetooth permissions are requested only when BLE is actually used.
    var pendingBleAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingBleAction
        pendingBleAction = null
        if (blePermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            } && action != null) action() else meshStatus = "Brak uprawnień Bluetooth"
    }
    val requireBle: ((() -> Unit)) -> Unit = { action ->
        val needed = blePermissions()
        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            action()
        } else {
            pendingBleAction = action
            permissionLauncher.launch(needed)
        }
    }
    val joinNetwork: () -> Unit = {
        requireBle {
            runCatching { mesh.joinNetwork() }
                .onFailure { meshStatus = "Nie dołączono do sieci: ${it.message}. Zapisane SOS pozostają w kolejce." }
        }
    }
    val refresh: () -> Unit = {
        reports.clear()
        reports.addAll(store.load())
        queueInfo = store.queueSummary(transportKind)
        networkJoined = store.autoMeshEnabled()
    }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // Worker-thread callbacks marshalled to the UI thread for every status.
    val onStatus: (String) -> Unit = { s -> mainHandler.post { meshStatus = s } }
    val onPacket: (ByteArray) -> Unit = { mainHandler.post { refresh() } }
    DisposableEffect(store) {
        onDispose { hotspot.close(); board.close(); store.close() }
    }
    DisposableEffect(transport) {
        val node = mesh
        node.start({ mainHandler.post { refresh() } }, onStatus)
        onDispose { node.close() }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Amber, onPrimary = Ink, background = Ink, surface = Panel,
        onBackground = Color.White, onSurface = Color.White,
        error = Color(0xFFFF6B5E), surfaceVariant = Panel, onSurfaceVariant = Dim,
    )) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (compact) 12.dp else 24.dp)
                    .padding(top = if (compact) 12.dp else 24.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Header()
                MeshCard(
                    transportKind = transportKind,
                    onTransport = { kind ->
                        pendingBleAction = null
                        store.selectTransport(kind)
                        transportKind = kind
                        meshPeer = store.peers(kind).joinToString(",")
                        queueInfo = store.queueSummary(kind)
                        meshStatus = if (kind == "ble") "Automatyczna sieć BLE" else "TCP — diagnostyka"
                    },
                    peer = meshPeer, onPeer = { meshPeer = it }, status = meshStatus,
                    joined = networkJoined, discovered = store.peers("ble").size,
                    onJoin = joinNetwork,
                    onLeave = { runCatching { mesh.leaveNetwork() }.onFailure { meshStatus = "Nie odłączono: ${it.message}" } },
                    onApply = {
                        runCatching { mesh.configurePeers(meshPeer) }
                            .onFailure { meshStatus = it.message ?: "Niepoprawny adres TCP" }
                    },
                    onListen = { mesh.listen(8765, onPacket, onStatus) },
                    queueInfo = queueInfo,
                )
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stepper("people", "Osoby łącznie", people, { people = it })
                        Stepper("children", "W tym dzieci", children, { children = it })
                        ChoiceRow("Zagrożenie", HAZARD_PL, hazard, { hazard = it })
                        ChoiceRow("Potrzeba", NEED_PL, need, { need = it })
                        OutlinedTextField(
                            note, { note = it.take(2000) },
                            label = { Text("Notatka — tylko lokalnie, nie wchodzi w pakiet") },
                            minLines = 2, maxLines = 4,
                            modifier = Modifier.fillMaxWidth().cd("note"),
                        )
                        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        Button(
                            onClick = {
                                var committed = false
                                try {
                                    val report = Codec.validate(people, children, hazard, need)
                                    val item = mesh.publishReport(report, note)
                                    committed = true
                                    refresh()
                                    lastSaved = "SOS zapisano w kolejce · ${item.id}"
                                    saveError = null
                                    note = ""
                                    if (transportKind == "ble" && !store.autoMeshEnabled()) {
                                        joinNetwork()
                                    }
                                } catch (e: Exception) {
                                    saveError = if (committed) "SOS zapisano; błąd odświeżenia: ${e.message}"
                                        else "Nie zapisano SOS: ${e.message ?: "błąd danych lub pamięci"}"
                                }
                            },
                            modifier = Modifier.fillMaxWidth().cd("save"),
                        ) { Text("Nadaj zgłoszenie SOS") }
                        lastSaved?.let {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = Amber, modifier = Modifier.cd("last-saved"))
                        }
                    }
                }

                ImportCard(
                    importText, { importText = it },
                    onImport = {
                        try {
                            val report = Codec.unpackText(importText)
                            val packet = String(Codec.pack(report), Charsets.US_ASCII)
                            importPreview = packet to Codec.render(report)
                            importError = null
                        } catch (e: Exception) {
                            importPreview = null
                            importError = "Pakiet odrzucony: ${e.message ?: "nieczytelny"}"
                        }
                    },
                    error = importError, preview = importPreview,
                    onCommit = { preview ->
                        val report = Codec.unpackText(preview.first)
                        store.add(report, "import", "")
                        refresh()
                        importText = ""
                        importPreview = null
                        lastSaved = "Zapisano · ${preview.first} · ${preview.first.toByteArray().size} B"
                    },
                )

                LocalAccessCard(board, hotspot)
                ContactCard(contactNumbers) { text ->
                    runCatching { contacts.save(text) }
                        .onSuccess { contactNumbers = it; meshStatus = "Kontakty zapisano lokalnie." }
                        .onFailure { meshStatus = it.message ?: "Nie zapisano kontaktów" }
                }

                Text("Historia (${reports.size})",
                    fontSize = 14.sp, color = Dim,
                    modifier = Modifier.padding(top = 4.dp))
                if (reports.isEmpty()) {
                    Text(
                        "Brak raportów. Pierwszy zgłoszenie utworzysz powyższym formularzem.",
                        fontSize = 13.sp, color = Dim, lineHeight = 18.sp,
                    )
                }
                reports.reversed().forEach { item ->
                    HistoryRow(
                        item,
                        onSend = {
                            runCatching { mesh.publish(Codec.pack(item.report), reportId = item.id) }
                                .onSuccess {
                                    if (transportKind == "ble" && !store.autoMeshEnabled()) joinNetwork()
                                }
                                .onFailure { meshStatus = it.message ?: "Błąd wysyłki" }
                        },
                        onShare = {
                            val packet = String(Codec.pack(item.report), Charsets.US_ASCII)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, packet)
                            }
                            context.startActivity(Intent.createChooser(send, "Udostępnij pakiet CM1"))
                        },
                        onContact = { smsReport = item },
                        onDelete = { confirmDelete = item },
                    )
                }
            }
        }

        smsReport?.let { item ->
            AlertDialog(
                onDismissRequest = { smsReport = null },
                title = { Text("Przekaż zgłoszenie kontaktowi") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(Codec.render(item.report), fontSize = 13.sp)
                        Text("Prywatna notatka nie zostanie dołączona. Wybór otworzy edytor SMS, nie wyśle wiadomości. Może obowiązywać opłata operatora.", fontSize = 12.sp)
                        if (contactNumbers.isEmpty()) Text("Najpierw zapisz odbiorcę w sekcji „Ustalone kontakty SOS”.")
                        contactNumbers.forEach { number ->
                            TextButton(onClick = {
                                runCatching { contacts.compose(number, item) }
                                    .onSuccess { meshStatus = "Otwarto edytor SMS. Wysłanie i odbiór nie są potwierdzone."; smsReport = null }
                                    .onFailure { meshStatus = "Nie otwarto SMS: ${it.message}"; smsReport = null }
                            }, modifier = Modifier.cd("contact-compose")) { Text(number) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { smsReport = null }) { Text("Zamknij") } },
            )
        }
        confirmDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Usunąć raport?") },
                text = { Text("Usuń z aktywnej historii i anuluj powiązane oczekujące wysyłki. Już dostarczonych kopii nie cofniesz. Stara kopia migracyjna JSON pozostaje nietknięta.") },
                confirmButton = {
                    TextButton(onClick = {
                        store.delete(item.id)
                        refresh()
                        confirmDelete = null
                    }) { Text("Usuń") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("Anuluj") }
                },
            )
        }
    }
}

@Composable
private fun Header() = Column(
    Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    Text("Crisis Mesh", fontSize = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 250) 18.sp else 22.sp,
        letterSpacing = 1.sp, textAlign = TextAlign.Center)
    if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 250) Text(
        "Bez serwera centralnego · TCP / BLE",
        fontSize = 12.sp, color = Dim, textAlign = TextAlign.Center,
        modifier = Modifier.cd("status"),
    )
}

@Composable
private fun Stepper(
    tag: String, label: String, value: Int?,
    onChange: (Int?) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 13.sp, color = Dim, modifier = Modifier.cd("label-$tag"))
        Text(
            if (value == null) "nie podano" else value.toString(),
            fontFamily = FontFamily.Monospace,
            fontSize = if (value == null) 13.sp else 18.sp,
            color = if (value == null) Dim else Color.White,
            maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().cd("$tag-value"),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(
                onClick = { onChange(if (value == null) 0 else (value - 1).coerceAtLeast(0)) },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                modifier = Modifier.cd("$tag-minus"),
            ) {
                Text("−", fontSize = 20.sp)
            }
            Spacer(Modifier.width(32.dp))
            TextButton(
                onClick = { onChange(if (value == null) 0 else (value + 1).coerceAtMost(254)) },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                modifier = Modifier.cd("$tag-plus"),
            ) {
                Text("+", fontSize = 20.sp)
            }
        }
        // Returning to "unknown" needs an explicit action once a number is set;
        // unknown never becomes a number implicitly.
        if (value != null) {
            TextButton(
                onClick = { onChange(null) },
                modifier = Modifier.align(Alignment.CenterHorizontally).cd("$tag-reset"),
            ) { Text("nie podano", fontSize = 12.sp, color = Dim) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(label: String, options: Map<String, String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, color = Dim)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (code, pl) ->
                FilterChip(
                    selected = code == selected,
                    onClick = { onSelect(code) },
                    label = { Text(pl, fontSize = 12.sp) },
                    modifier = Modifier.cd("choice-$code"),
                )
            }
        }
    }
}

@Composable
private fun MeshCard(
    transportKind: String, onTransport: (String) -> Unit,
    peer: String, onPeer: (String) -> Unit, status: String,
    joined: Boolean, discovered: Int, onJoin: () -> Unit, onLeave: () -> Unit,
    onApply: () -> Unit, onListen: () -> Unit, queueInfo: String,
) {
    val ble = transportKind == "ble"
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sieć · 4 przeskoki", fontSize = 14.sp, color = Amber)
            if (ble) {
                Button(onClick = onJoin, modifier = Modifier.fillMaxWidth().cd("mesh-join")) {
                    Text(if (joined) "Ponów dołączenie" else "Dołącz do sieci")
                }
                Text(if (joined) "Automatyczna sieć włączona · poznane węzły: $discovered"
                    else "Poza siecią. SOS można zapisać w kolejce przed dołączeniem.",
                    fontSize = 12.sp, modifier = Modifier.cd("network-state"))
                Text("Dołączenie uruchamia nasłuch i okresowe wykrywanie. Zgłoszenia są przekazywane do wykrytych węzłów bez wpisywania adresów. Brak szyfrowania i uwierzytelniania — prototyp do zaufanych prób.", fontSize = 12.sp, color = Dim)
                if (joined) TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().cd("mesh-leave")) {
                    Text("Odłącz od sieci")
                }
            } else {
                Text("TCP — diagnostyka w zaufanej sieci. To nie jest automatyczne wykrywanie BLE.", fontSize = 12.sp)
                OutlinedTextField(peer, onPeer, label = { Text("Sąsiedzi host:port, po przecinku") },
                    modifier = Modifier.fillMaxWidth().cd("mesh-peer"))
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth().cd("mesh-apply")) { Text("Zastosuj adresy TCP") }
                Button(onClick = onListen, modifier = Modifier.fillMaxWidth().cd("mesh-listen")) { Text("Nasłuchuj TCP :8765") }
                TextButton(onClick = onLeave, modifier = Modifier.cd("mesh-stop")) { Text("Zatrzymaj odbiór TCP") }
            }
            Text(queueInfo, fontSize = 12.sp, modifier = Modifier.cd("queue-status"))
            Text("Potwierdzenie węzła oznacza zapis danych, nie przyjęcie wezwania przez człowieka ani wysłanie służb. Brak takiego potwierdzenia pomocy.", fontSize = 12.sp, color = Dim)
            Text("Praca zależy od działającego procesu aplikacji. Retencja przekazywania: 24 h na tym węźle; historia pozostaje.", fontSize = 12.sp, color = Dim)
            Text(status, fontSize = 12.sp, color = Amber, modifier = Modifier.cd("mesh-status"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = ble, onClick = { onTransport("ble") },
                    label = { Text("BLE", fontSize = 12.sp) }, modifier = Modifier.cd("transport-ble"))
                FilterChip(selected = !ble, onClick = { onTransport("tcp") },
                    label = { Text("TCP · test", fontSize = 12.sp) }, modifier = Modifier.cd("transport-tcp"))
            }
        }
    }
}

/** Runtime permissions required by the BLE path; the TCP path needs none of these. */
private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun ImportCard(
    text: String, onText: (String) -> Unit, onImport: () -> Unit,
    error: String?, preview: Pair<String, String>?, onCommit: (Pair<String, String>) -> Unit,
) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import pakietu CM1", fontSize = 14.sp, color = Dim)
            OutlinedTextField(
                text, onText, singleLine = true,
                label = { Text("Wklej CM1|osoby|dzieci|zagrożenie|potrzeba") },
                modifier = Modifier.fillMaxWidth().cd("import-field"),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            Button(
                onClick = onImport, enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().cd("import"),
            ) { Text("Odczytaj pakiet") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            preview?.let { (packet, rendered) ->
                Text(packet, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Amber)
                Text(rendered, fontSize = 13.sp)
                Button(onClick = { onCommit(packet to rendered) },
                    modifier = Modifier.fillMaxWidth().cd("import-commit")) {
                    Text("Zapisz odczytany raport")
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(item: StoredReport, onSend: () -> Unit, onShare: () -> Unit, onContact: () -> Unit, onDelete: () -> Unit) {
    val packet = remember(item) { String(Codec.pack(item.report), Charsets.US_ASCII) }
    val stamp = remember(item) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAtMillis))
    }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$stamp · ${item.source}", fontSize = 11.sp, color = Dim)
            Text(Codec.render(item.report), fontSize = 14.sp)
            Text(packet, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Amber)
            if (item.note.isNotBlank()) Text(item.note, fontSize = 13.sp, color = Dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSend, modifier = Modifier.cd("send")) {
                    Text("Wyślij", fontSize = 13.sp)
                }
                TextButton(onClick = onShare, modifier = Modifier.cd("share")) {
                    Text("Udostępnij", fontSize = 13.sp)
                }
                TextButton(onClick = onContact, modifier = Modifier.cd("contact-report")) {
                    Text("Przekaż kontaktowi", fontSize = 13.sp)
                }
                TextButton(onClick = onDelete, modifier = Modifier.cd("delete")) {
                    Text("Usuń", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
