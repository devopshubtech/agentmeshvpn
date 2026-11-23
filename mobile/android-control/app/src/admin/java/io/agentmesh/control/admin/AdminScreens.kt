package io.agentmesh.control.admin

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.agentmesh.control.data.QrImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.agentmesh.control.data.ConnectProfile
import io.agentmesh.control.ui.AgentMeshApp
import io.agentmesh.control.ui.AppViewModel
import kotlinx.coroutines.delay

private val Muted = Color(0xFF8A8F98)
private val Good = Color(0xFF16A34A)
private val Bad = Color(0xFFDC2626)

/** The numbers an owner can pick. 0 means unlimited. */
private val ALLOWANCES = listOf(2, 3, 4, 5, 10, 20, 0)
private fun allowanceText(n: Int) = if (n > 0) "$n" else "Unlimited"

@Composable
fun AdminRoot(vm: AppViewModel, onScan: () -> Unit, onPickImage: () -> Unit, onConnect: (ConnectProfile) -> Unit) {
    val avm: AdminViewModel = viewModel()
    val ui by avm.state.collectAsState()
    val ctx = LocalContext.current

    // Make sure the alert service runs whenever the owner is signed in.
    LaunchedEffect(ui.signedIn) {
        if (ui.signedIn) {
            runCatching { AdminService.start(ctx) }
            // Many phones stop background apps to save battery, which would silence alerts: ask once.
            val prefs = ctx.getSharedPreferences("agentmesh_admin_ui", android.content.Context.MODE_PRIVATE)
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            if (!prefs.getBoolean("asked_battery", false) && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                prefs.edit().putBoolean("asked_battery", true).apply()
                AdminService.allowBackground(ctx)
            }
        }
    }
    // Keep the lists fresh while the app is open.
    LaunchedEffect(ui.signedIn) {
        while (ui.signedIn) {
            avm.refresh()
            delay(4000)
        }
    }

    if (!ui.signedIn) {
        LoginScreen(ui, onSignIn = { server, email, pw, r -> avm.signIn(server, email, pw, r) })
    } else {
        AdminHome(ui, avm, vm, onScan, onPickImage, onConnect)
    }
}

/** Two actions: scan an AgentMesh QR code with the camera, or pick a picture of one from the gallery. */
@Composable
private fun rememberQrActions(onLink: (AdminLink) -> Unit, onProblem: (String) -> Unit): Pair<() -> Unit, () -> Unit> {
    val ctx = LocalContext.current
    fun handle(text: String?) {
        val link = text?.let(AdminLinks::parse)
        if (link == null) onProblem("That is not an AgentMesh QR code. Use the QR code from the dashboard (Connect a phone) or from an invite.") else onLink(link)
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { handle(it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u ->
            val text = runCatching { QrImage.read(ctx.contentResolver, u) }.getOrNull()
            if (text == null) onProblem("No QR code found in that picture. Choose a clear screenshot of the QR code.") else handle(text)
        }
    }
    return Pair(
        {
            scan.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan an AgentMesh QR code")
                    .setBeepEnabled(false).setOrientationLocked(false)
            )
        },
        { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}

@Composable
private fun LoginScreen(ui: AdminUi, onSignIn: (String, String, String, String) -> Unit) {
    var server by rememberSaveable { mutableStateOf(ui.server) }
    var email by rememberSaveable { mutableStateOf(ui.email) }
    var rendezvous by rememberSaveable { mutableStateOf("") }
    var scanned by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    val (scan, pick) = rememberQrActions(
        onLink = { l ->
            if (l.server.isNotBlank()) server = l.server
            if (l.email.isNotBlank()) email = l.email
            rendezvous = l.rendezvous
            scanned = true
            problem = null
        },
        onProblem = { problem = it },
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("AgentMesh Admin", fontWeight = FontWeight.Bold, fontSize = 28.sp)
        Spacer(Modifier.height(6.dp))
        Text("Sign in with the admin account of your AgentMesh server. It is saved on this phone, so you stay signed in.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = scan, enabled = !ui.busy, modifier = Modifier.weight(1f)) { Text("Scan QR code") }
            OutlinedButton(onClick = pick, enabled = !ui.busy, modifier = Modifier.weight(1f)) { Text("From gallery") }
        }
        Text(
            if (scanned) "QR code read. The app will follow the server if its address changes." else
                "Scan the QR code from the dashboard (Connect a phone) so the app can find the server even when its address changes.",
            color = if (scanned) Good else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        problem?.let { Text(it, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        OutlinedTextField(server, { server = it; scanned = false; rendezvous = "" }, label = { Text("Server address") }, singleLine = true,
            supportingText = { Text("Filled in by the QR code. Leave empty to find it automatically.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email / username") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        ui.loginError?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 8.dp)) }
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSignIn(server, email, password, rendezvous) }, enabled = !ui.busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Sign in") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminHome(
    ui: AdminUi, avm: AdminViewModel, vm: AppViewModel,
    onScan: () -> Unit, onPickImage: () -> Unit, onConnect: (ConnectProfile) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); avm.toast(null) } }
    // A new request jumps the owner to the Requests tab.
    LaunchedEffect(ui.pending.size) { if (ui.pending.isNotEmpty()) tab = 0 }

    val titles = listOf(if (ui.pending.isNotEmpty()) "Requests (${ui.pending.size})" else "Requests", "Phones", "Servers", "Connect")
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text("AgentMesh Admin") }, actions = {
                var confirm by remember { mutableStateOf(false) }
                TextButton(onClick = { if (confirm) avm.signOut() else confirm = true }) { Text(if (confirm) "Tap again to sign out" else "Sign out") }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp, maxLines = 1) }) }
            }
            ui.loadError?.let {
                Text("Cannot reach the server: $it", color = Bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> RequestsTab(ui, avm)
                1 -> PhonesTab(ui, avm)
                2 -> ServersTab(ui, avm, vm, onConnect)
                else -> AgentMeshApp(vm, onScan, onPickImage, onConnect, embedded = true)
            }
        }
    }
    ui.invite?.let { InviteDialog(it, onClose = avm::closeInvite) }
}

@Composable
private fun AllowanceChooser(selected: Int, onSelect: (Int) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    val custom = selected > 0 && selected !in ALLOWANCES
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ALLOWANCES.forEach { n ->
            FilterChip(selected = selected == n, onClick = { onSelect(n) }, label = { Text(allowanceText(n)) })
        }
        FilterChip(selected = custom, onClick = { typing = true }, label = { Text(if (custom) "Custom: $selected" else "Custom…") })
    }
    if (typing) {
        var text by remember { mutableStateOf(if (custom) selected.toString() else "") }
        val n = text.toIntOrNull()
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("How many connections?") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
                    singleLine = true, label = { Text("Number of connections") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = { TextButton(enabled = n != null && n > 0, onClick = { onSelect(n!!); typing = false }) { Text("Use") } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------ requests

@Composable
private fun RequestsTab(ui: AdminUi, avm: AdminViewModel) {
    val ctx = LocalContext.current
    val live by AdminService.live.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text((if (live.ok) "● Alerts on: " else "○ Alerts not working: ") + live.text,
                    color = if (live.ok) Good else Bad, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { AdminService.notifyTest(ctx) }) { Text("Test alert") }
                    OutlinedButton(onClick = { AdminService.channelSettings(ctx) }) { Text("Sound settings") }
                    OutlinedButton(onClick = { AdminService.allowBackground(ctx) }) { Text("Keep running") }
                }
                var problems by remember { mutableStateOf(AdminService.soundProblems(ctx)) }
                LaunchedEffect(live) { problems = AdminService.soundProblems(ctx) }
                if (problems.isEmpty()) Text("Sound check: the phone is set to play the alert sound.", fontSize = 12.sp, color = Good)
                else problems.forEach { Text("• $it", fontSize = 12.sp, color = Bad) }
                Text("No sound? Raise the notification volume, turn off Do Not Disturb, and tap Keep running so the phone does not stop alerts to save battery.",
                    fontSize = 12.sp, color = Muted)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connections given when you tap Approve in a notification", fontSize = 13.sp, color = Muted)
                AllowanceChooser(ui.defaultAllowance, avm::setDefaultAllowance)
            }
        }
        if (ui.pending.isEmpty()) {
            Text("Nobody is waiting. When a phone tries to connect, or asks for more connections, you get a notification here.", color = Muted)
        }
        ui.pending.forEach { c -> RequestCard(c, ui.defaultAllowance, avm) }
    }
}

@Composable
private fun RequestCard(c: ClientInfo, defaultAllowance: Int, avm: AdminViewModel) {
    if (c.wantsMore) return MoreCard(c, defaultAllowance, avm)
    var allowance by remember(c.id) { mutableIntStateOf(defaultAllowance) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${c.title} wants to connect", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("Device: ${c.device}", fontSize = 13.sp)
            Text("Server: ${c.deviceName.ifBlank { "-" }}   ·   IP ${c.ip.ifBlank { "-" }}", fontSize = 13.sp)
            if (c.keyLabel.isNotBlank()) Text("Link: ${c.keyLabel}", fontSize = 12.sp, color = Muted)
            Text("How many times may it connect?", fontSize = 13.sp)
            AllowanceChooser(allowance) { allowance = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { avm.approve(c, allowance) }) { Text("Approve") }
                OutlinedButton(onClick = { avm.deny(c) }) { Text("Deny") }
                TextButton(onClick = { avm.block(c) }) { Text("Block", color = Bad) }
            }
        }
    }
}

/** An approved phone that used its connections and asks for more. */
@Composable
private fun MoreCard(c: ClientInfo, defaultAllowance: Int, avm: AdminViewModel) {
    // "Unlimited" makes no sense for "give more": start from 5 in that case.
    var n by remember(c.id) { mutableIntStateOf(if (defaultAllowance > 0) defaultAllowance else 5) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${c.title} asks for more connections", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            val used = c.maxConnects?.let { "Used ${c.connectsUsed} of $it" } ?: "Used ${c.connectsUsed}"
            Text("Device: ${c.device}", fontSize = 13.sp)
            Text("$used   ·   Server: ${c.deviceName.ifBlank { "-" }}", fontSize = 13.sp)
            if (c.moreMessage.isNotBlank()) Text("\"${c.moreMessage}\"", fontSize = 13.sp)
            Text("How many more may it have?", fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5, 10, 20).forEach { v -> FilterChip(selected = n == v, onClick = { n = v }, label = { Text("+$v") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { avm.addMore(c, n) }) { Text("Give $n more") }
                OutlinedButton(onClick = { avm.dismiss(c) }) { Text("Not now") }
                TextButton(onClick = { avm.block(c) }) { Text("Block", color = Bad) }
            }
        }
    }
}

// ------------------------------------------------------------ phones

private fun ago(iso: String?): String? {
    val t = iso?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: return null
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PhonesTab(ui: AdminUi, avm: AdminViewModel) {
    var editing by remember { mutableStateOf<ClientInfo?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 = all, 1 = online, 2 = offline
    val all = ui.phones
    val onlineCount = all.count { it.online }
    // Online phones first, then by name.
    val shown = all.filter { filter == 0 || (filter == 1) == it.online }
        .sortedWith(compareByDescending<ClientInfo> { it.online }.thenBy { it.title.lowercase() })
    val mine = shown.filter { it.isMine }
    val others = shown.filter { !it.isMine }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connected now: $onlineCount of ${all.size}", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = if (onlineCount > 0) Good else Muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("All (${all.size})") })
            FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Online ($onlineCount)") })
            FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Offline (${all.size - onlineCount})") })
        }
        if (all.isEmpty()) Text("No phones yet. They appear here after they ask to connect.", color = Muted)
        else if (shown.isEmpty()) Text(if (filter == 1) "No phone is online right now." else "No phone is offline.", color = Muted)
        if (mine.isNotEmpty()) {
            SectionTitle("My phone")
            mine.forEach { PhoneCard(it, ui, avm) { c -> editing = c } }
        }
        if (others.isNotEmpty()) {
            SectionTitle("Other phones (${others.count { it.online }} online of ${others.size})")
            others.forEach { PhoneCard(it, ui, avm) { c -> editing = c } }
        }
    }
    editing?.let { c ->
        var n by remember(c.id) { mutableIntStateOf(c.maxConnects ?: 0) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Connections for ${c.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Starts a fresh count from now.", color = Muted, fontSize = 13.sp)
                    AllowanceChooser(n) { n = it }
                }
            },
            confirmButton = { Button(onClick = { avm.approve(c, n); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PhoneCard(c: ClientInfo, ui: AdminUi, avm: AdminViewModel, onEdit: (ClientInfo) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    if (c.personName.isNotBlank()) Text(c.device, fontSize = 12.sp, color = Muted)
                }
                // Online = connected to AgentMesh right now (its VPN is up).
                Text(if (c.online) "● Online" else "○ Offline", color = if (c.online) Good else Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            val since = if (c.online) ago(c.onlineSince)?.let { "Online since $it" } ?: "Online now"
            else (ago(c.offlineSince) ?: ago(c.lastConnectAt))?.let { "Offline since $it" } ?: "Never connected"
            Text(since, fontSize = 13.sp, color = if (c.online) Good else Muted, fontWeight = FontWeight.Medium)
            val access = when (c.status) {
                "approved" -> "Allowed" to Good
                "blocked" -> "Blocked" to Bad
                else -> "Denied" to Bad
            }
            Text(
                access.first + (if (c.online) "   ·   via ${c.deviceName.ifBlank { "-" }}" else "") + "   ·   IP ${c.ip.ifBlank { "-" }}",
                fontSize = 12.sp, color = if (c.status == "approved") Muted else access.second,
            )
            if (c.status == "approved") {
                val limit = c.maxConnects?.let { "used ${c.connectsUsed} of $it" } ?: "unlimited (used ${c.connectsUsed})"
                Text("Connections: $limit", fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (c.status == "approved") {
                    TextButton(onClick = { onEdit(c) }) { Text("Change limit") }
                    TextButton(onClick = { avm.block(c) }) { Text("Block", color = Bad) }
                } else {
                    TextButton(onClick = { avm.approve(c, ui.defaultAllowance) }) { Text("Allow again") }
                }
                TextButton(onClick = { avm.forget(c) }) { Text("Remove") }
            }
        }
    }
}

// ------------------------------------------------------------ servers

@Composable
private fun ServersTab(ui: AdminUi, avm: AdminViewModel, vm: AppViewModel, onConnect: (ConnectProfile) -> Unit) {
    var inviteFor by remember { mutableStateOf<NodeInfo?>(null) }
    val exits = ui.nodes.filter { it.canExit }
    val online = exits.count { it.online }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Exit nodes: $online of ${exits.size} online", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("Phones use the internet of these computers. If one goes off, phones move to another online one by themselves.", color = Muted, fontSize = 13.sp)
        if (ui.nodes.isEmpty()) Text("No computers yet. Install the AgentMesh agent on a Mac, Windows PC or Linux machine.", color = Muted)
        ui.nodes.forEach { n ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.name.ifBlank { n.hostname }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(if (n.online) "Online" else "Offline", color = if (n.online) Good else Bad, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Text("${n.platform}${n.lastIp?.let { " · $it" } ?: ""}", fontSize = 12.sp, color = Muted)
                    if (!n.canExit) {
                        Text("Exit node is not enabled on this computer.", fontSize = 12.sp, color = Bad)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = n.online && !ui.busy, onClick = {
                                avm.connectThisPhone(n) { p -> vm.refresh(); onConnect(p) }
                            }) { Text("Connect this phone") }
                            OutlinedButton(enabled = !ui.busy, onClick = { inviteFor = n }) { Text("Invite a phone") }
                        }
                    }
                }
            }
        }
        ServerAddressCard(ui, avm)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New invitations", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ask me before a phone can connect", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = ui.requireApproval, onCheckedChange = avm::setRequireApproval)
                }
                Text("Starting connections per phone", fontSize = 13.sp, color = Muted)
                AllowanceChooser(ui.defaultAllowance, avm::setDefaultAllowance)
            }
        }
    }
    inviteFor?.let { n ->
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { inviteFor = null },
            title = { Text("Invite a phone to ${n.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("Who is it for? (optional)") }, singleLine = true)
                    Text(
                        (if (ui.requireApproval) "You approve each phone. " else "Phones connect without asking. ") +
                            "Each phone starts with ${if (ui.defaultAllowance > 0) "${ui.defaultAllowance} connections" else "unlimited connections"}.",
                        fontSize = 13.sp, color = Muted,
                    )
                }
            },
            confirmButton = { Button(onClick = { avm.createInvite(n, label); inviteFor = null }) { Text("Create QR and code") } },
            dismissButton = { TextButton(onClick = { inviteFor = null }) { Text("Cancel") } },
        )
    }
}

/** Where this app signs in, and a way to follow the server to a new address by scanning its QR code. */
@Composable
private fun ServerAddressCard(ui: AdminUi, avm: AdminViewModel) {
    val (scan, pick) = rememberQrActions(onLink = avm::relink, onProblem = avm::toast)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Server address", fontWeight = FontWeight.SemiBold)
            Text(ui.server.removePrefix("https://").ifBlank { "-" }, fontSize = 14.sp)
            Text(
                if (ui.autoUpdate) "Follows the server automatically if its address changes."
                else "Saved from what you typed. If the address changes, scan the QR code again.",
                fontSize = 12.sp, color = if (ui.autoUpdate) Good else Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = scan, enabled = !ui.busy) { Text("Scan QR code") }
                OutlinedButton(onClick = pick, enabled = !ui.busy) { Text("From gallery") }
            }
            Text("Your username and password stay saved.", fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun InviteDialog(i: InviteUi, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Invite to ${i.invite.nodeName}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(i.qr.asImageBitmap(), contentDescription = "QR code", filterQuality = FilterQuality.None, modifier = Modifier.size(240.dp))
                Text("Scan it in the AgentMesh app, or share the link.", fontSize = 13.sp, color = Muted)
                i.invite.code?.let {
                    Text("or type this code (15 minutes)", fontSize = 13.sp, color = Muted)
                    Text(it.chunked(3).joinToString(" "), fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, i.invite.link), "Share link"))
            }) { Text("Share link") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}
