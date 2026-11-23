package io.agentmesh.control.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agentmesh.control.BuildConfig
import io.agentmesh.control.data.ClientStatus
import io.agentmesh.control.data.ConnectProfile
import io.agentmesh.control.vpn.ExitVpnService
import io.agentmesh.control.vpn.VpnStatus
import java.text.DateFormat
import java.util.Date

private val Muted = Color(0xFF8A8F98)
private val Good = Color(0xFF16A34A)

@Composable
internal fun AgentMeshTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme(primary = Color(0xFF3949AB))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMeshApp(
    vm: AppViewModel, onScan: () -> Unit, onPickImage: () -> Unit, onConnect: (ConnectProfile) -> Unit,
    embedded: Boolean = false,
) {
    val st by vm.state.collectAsState()
    val vpn by ExitVpnService.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(st.message) {
        st.message?.let {
            snackbar.showSnackbar(it)
            vm.toast(null)
        }
    }
    LaunchedEffect(vpn) { if (vpn is VpnStatus.Connected || vpn is VpnStatus.Idle) vm.refresh() }
    // Keep "connections left" fresh while the screen is open and after each connect / disconnect.
    LaunchedEffect(st.profiles.size, vpn::class) { vm.refreshAccess() }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            vm.refreshAccess()
        }
    }

    AgentMeshTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { if (!embedded) TopAppBar(title = { Text("AgentMesh") }) },
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusCard(vpn, st.exitIp, st.checkingIp, onCheckIp = vm::checkExitIp, onStop = vm::disconnect,
                    onRequestMore = { id -> st.profiles.firstOrNull { it.id == id }?.let(vm::openRequest) })

                if (st.profiles.isEmpty()) {
                    WelcomeCard(onAdd = { vm.showAdd(true) })
                } else {
                    Text("Saved servers", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    st.profiles.forEach { p ->
                        val active = (vpn as? VpnStatus.Connected)?.deviceName?.let { it == p.deviceName || it == p.name } == true
                        ProfileCard(p, active, st.access[p.id], p.id in st.requesting,
                            onConnect = { onConnect(p) }, onEdit = { vm.edit(p) }, onRequestMore = { vm.openRequest(p) })
                    }
                    OutlinedButton(onClick = { vm.showAdd(true) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("+ Connect to remote server")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!embedded && st.personName.isNotBlank()) {
                    TextButton(onClick = { vm.askName(true) }) { Text("Your name: ${st.personName}  ·  Change", fontSize = 12.sp) }
                }
                Text("AgentMesh v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
            }
        }

        if (!embedded && st.askName) {
            NameDialog(initial = st.personName, required = st.personName.isBlank(), onDismiss = { vm.askName(false) }, onSave = vm::saveName)
        }
        st.requestFor?.let { p ->
            RequestMoreDialog(p, onDismiss = { vm.openRequest(null) }, onSend = { msg -> vm.requestMore(p, msg); vm.openRequest(null) })
        }
        if (st.showAdd) AddServerDialog(busy = st.pairing, onDismiss = { vm.showAdd(false) }, onScan = onScan, onPickImage = onPickImage, onSubmit = { text ->
            vm.submitCodeOrLink(text, onConnect)
        })
        st.editing?.let { p ->
            EditServerDialog(p, onDismiss = { vm.edit(null) }, onSave = { name, server, link -> vm.saveEdit(p, name, server, link) },
                onDelete = { vm.delete(p) })
        }
    }
}

/** Asked once, the first time the app opens: the name shown with each request. */
@Composable
private fun NameDialog(initial: String, required: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        title = { Text(if (required) "Welcome! What is your name?" else "Your name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AgentMesh uses this name to recognise your device when you ask to connect.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Continue") } },
        dismissButton = { if (!required) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asking for more connections. Writing a message is optional. */
@Composable
private fun RequestMoreDialog(p: ConnectProfile, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request more connections") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ask for more connections on ${p.name}. You will be told here as soon as they are added.", fontSize = 14.sp)
                OutlinedTextField(message, { message = it.take(200) }, label = { Text("Message (optional)") }, minLines = 2,
                    supportingText = { Text("You can send without writing anything.") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSend(message) }) { Text("Send request") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WelcomeCard(onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Use another device's internet", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Text(
                "Connect this phone to a computer running AgentMesh. Your internet will go out through that computer and websites will see its IP address — on mobile data or any Wi-Fi.",
                fontSize = 14.sp,
            )
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Connect to remote server", fontSize = 16.sp)
            }
            Text("You need the QR code or 6-digit pairing code from the AgentMesh dashboard → Connect a phone.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProfileCard(
    p: ConnectProfile, active: Boolean, access: ClientStatus?, requesting: Boolean,
    onConnect: () -> Unit, onEdit: () -> Unit, onRequestMore: () -> Unit,
) {
    val noneLeft = access?.status == "approved" && access.connectsLeft == 0
    val blocked = access?.status == "denied" || access?.status == "blocked"
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !noneLeft && !blocked, onClick = onConnect),
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                val via = p.deviceName.takeIf { it.isNotBlank() && it != p.name }?.let { "via $it · " } ?: ""
                Text(via + p.serverUrl.removePrefix("https://"), color = Muted, fontSize = 12.sp, maxLines = 1)
                if (p.lastConnectedAt > 0) {
                    Text("Last connected " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.lastConnectedAt)),
                        color = Muted, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            if (active) Text("Connected", color = Good, fontWeight = FontWeight.SemiBold)
            else Button(onClick = onConnect, enabled = !noneLeft && !blocked) { Text("Connect") }
        }
        AccessLine(access, requesting, onRequestMore)
    }
}

/** "4 of 5 connections left", with a button to ask the owner for more. */
@Composable
private fun AccessLine(access: ClientStatus?, requesting: Boolean, onRequestMore: () -> Unit) {
    val a = access ?: return
    val bad = MaterialTheme.colorScheme.error
    val (text, color) = when {
        a.status == "pending" -> "You are in the queue. AgentMesh will notice your device soon and connect it. Please wait patiently." to Muted
        a.status == "denied" || a.status == "blocked" -> "AgentMesh has not allowed this device." to bad
        a.status == "approved" && a.maxConnects != null && a.connectsLeft == 0 ->
            (if (a.moreRequested) "Your limit is reached. You are in the queue. AgentMesh will let you know here as soon as more connections are added. Please wait patiently." else "Your limit is reached. Request more connections to continue.") to bad
        a.status == "approved" && a.maxConnects != null ->
            "${a.connectsLeft} of ${a.maxConnects} connections left" to (if ((a.connectsLeft ?: 0) <= 1) bad else Good)
        a.status == "approved" -> "Unlimited connections" to Muted
        else -> return
    }
    Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (a.status == "approved" && a.maxConnects != null && !a.moreRequested) {
            TextButton(onClick = onRequestMore, enabled = !requesting) { Text(if (requesting) "Sending…" else "Request more") }
        }
    }
}

@Composable
private fun StatusCard(
    vpn: VpnStatus, exitIp: String?, checking: Boolean, onCheckIp: () -> Unit, onStop: () -> Unit,
    onRequestMore: (String) -> Unit = {},
) {
    val (title, detail) = when (vpn) {
        VpnStatus.Idle -> return
        is VpnStatus.Connecting -> "Connecting to ${vpn.deviceName}…" to null
        is VpnStatus.WaitingApproval -> "You are in the queue" to
            "AgentMesh will notice your device soon and connect it. Please wait patiently."
        is VpnStatus.WaitingExitNode ->
            (if (vpn.wasConnected) "Disconnected: exit node offline" else "Waiting for an exit node…") to
                "Please wait. AgentMesh connects you automatically as soon as an exit node comes online."
        is VpnStatus.Reconnecting -> "Reconnecting to ${vpn.deviceName} (attempt ${vpn.attempt})…" to vpn.reason
        is VpnStatus.Connected -> "Connected to AgentMesh exit node ${vpn.deviceName}" to
            "↑ ${ExitVpnService.human(vpn.bytesUp)}   ↓ ${ExitVpnService.human(vpn.bytesDown)}"
        is VpnStatus.Failed -> "Disconnected" to vpn.message
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            detail?.let { Text(it, fontSize = 13.sp) }
            if (vpn is VpnStatus.Connecting || vpn is VpnStatus.Reconnecting || vpn is VpnStatus.WaitingApproval || vpn is VpnStatus.WaitingExitNode) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vpn is VpnStatus.Connected) {
                val left by ExitVpnService.connectsLeft.collectAsState()
                val maxC by ExitVpnService.connectsMax.collectAsState()
                left?.let { Text("Connections left: $it" + (maxC?.let { m -> " of $m" } ?: ""), fontSize = 13.sp) }
                Text(if (exitIp != null) "Your IP now: $exitIp" else "Tap 'Check my IP' to see the IP websites see.", fontSize = 14.sp,
                    fontWeight = if (exitIp != null) FontWeight.SemiBold else FontWeight.Normal)
            }
            if (vpn is VpnStatus.Failed && vpn.code == "connect_limit_reached" && vpn.profileId.isNotBlank()) {
                Button(onClick = { onRequestMore(vpn.profileId) }) { Text("Request more connections") }
            }
            if (vpn !is VpnStatus.Failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vpn is VpnStatus.Connected) OutlinedButton(onClick = onCheckIp, enabled = !checking) { Text(if (checking) "Checking…" else "Check my IP") }
                    Button(onClick = onStop) { Text("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun AddServerDialog(busy: Boolean, onDismiss: () -> Unit, onScan: () -> Unit, onPickImage: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to remote server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Scan QR code") }
                OutlinedButton(onClick = onPickImage, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Choose QR code from gallery")
                }
                Text("Tip: take a screenshot of the QR code, then choose it here.", color = Muted, fontSize = 12.sp)
                Text("or enter the 6-digit pairing code (or paste the link):", color = Muted, fontSize = 13.sp)
                OutlinedTextField(text, { text = it }, placeholder = { Text("123 456") }, singleLine = true, enabled = !busy,
                    textStyle = LocalTextStyle.current.copy(fontSize = 22.sp, letterSpacing = 4.sp),
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Get the code from the dashboard → Connect a phone → Pairing code.", color = Muted, fontSize = 12.sp)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSubmit(text.trim()) }, enabled = text.isNotBlank() && !busy) { Text(if (busy) "Pairing…" else "Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditServerDialog(
    p: ConnectProfile, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit, onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(p.name) }
    var server by remember { mutableStateOf(p.serverUrl) }
    var link by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit saved server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true,
                    supportingText = { Text("IP, domain or https URL. Updated automatically when it changes.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(link, { link = it }, label = { Text("New connect link (optional)") }, minLines = 2,
                    supportingText = { Text("Paste a new link if the old one was revoked.") }, modifier = Modifier.fillMaxWidth())
                if (confirmDelete) {
                    Text("Delete '${p.name}' from this phone?", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(name, server, link) }) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                Text(if (confirmDelete) "Yes, delete" else "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
