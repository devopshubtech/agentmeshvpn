package io.agentmesh.control.admin

import android.app.Application
import android.graphics.Bitmap
import android.media.RingtoneManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.agentmesh.control.App
import io.agentmesh.control.data.ConnectLink
import io.agentmesh.control.data.ConnectProfile
import io.agentmesh.control.data.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InviteUi(val invite: Invite, val qr: Bitmap)

data class AdminUi(
    val signedIn: Boolean,
    val email: String = "",
    val server: String = "",
    val autoUpdate: Boolean = false,
    val busy: Boolean = false,
    val loginError: String? = null,
    val message: String? = null,
    val loadError: String? = null,
    val nodes: List<NodeInfo> = emptyList(),
    val pending: List<ClientInfo> = emptyList(),
    val phones: List<ClientInfo> = emptyList(),
    val invite: InviteUi? = null,
    val defaultAllowance: Int = 5,
    val requireApproval: Boolean = true,
)

class AdminViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AdminGraph.get(app)
    private val api = graph.api
    private val store = graph.store
    private val profiles = (app as App).profiles

    /** Requests already seen, so a new one (not the first load) plays the notification sound. */
    private var seenRequests: Set<String>? = null

    private fun playRequestSound() {
        runCatching {
            RingtoneManager.getRingtone(getApplication(), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<AdminUi> = _state

    private fun initial(): AdminUi {
        val a = store.account()
        return AdminUi(a != null, a?.email.orEmpty(), a?.serverUrl.orEmpty(), autoUpdate = a?.let { it.rendezvous.isNotBlank() || it.autoFind } == true,
            defaultAllowance = store.defaultAllowance, requireApproval = store.requireApproval)
    }

    fun toast(msg: String?) = _state.update { it.copy(message = msg) }

    fun signIn(server: String, email: String, password: String, rendezvous: String = "") {
        _state.update { it.copy(busy = true, loginError = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.signIn(server, email, password, rendezvous) } }
            r.onSuccess { url ->
                AdminService.start(getApplication())
                _state.update { it.copy(signedIn = true, busy = false, email = email.trim(), server = url, autoUpdate = store.account()?.let { a -> a.rendezvous.isNotBlank() || a.autoFind } == true) }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, loginError = e.message ?: "Could not sign in") }
            }
        }
    }

    /** A QR code was scanned while signed in: follow the server to its new address, keeping the saved login. */
    fun relink(link: AdminLink) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.relink(link.server, link.rendezvous) } }
            r.onSuccess { url -> _state.update { it.copy(busy = false, server = url, loadError = null, message = "Server updated: ${url.removePrefix("https://")}") } }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.message ?: "Could not use that QR code") } }
            refresh()
        }
    }

    fun signOut() {
        AdminService.stop(getApplication())
        store.clear()
        seenRequests = null
        _state.value = AdminUi(false)
    }

    /** Reloads servers and phones (also run every few seconds while the app is open). */
    fun refresh() {
        if (!_state.value.signedIn) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Triple(api.nodes(), api.clients("requests"), api.clients().filter { it.status != "pending" }) }
            }
            r.onSuccess { (nodes, pending, phones) ->
                val ids = pending.map { it.id + (it.moreRequestedAt ?: "") }.toSet()
                if (seenRequests?.let { seen -> ids.any { it !in seen } } == true) playRequestSound()
                seenRequests = ids
                _state.update { it.copy(nodes = nodes, pending = pending, phones = phones, loadError = null, server = store.account()?.serverUrl ?: it.server) }
            }.onFailure { e -> _state.update { it.copy(loadError = e.message ?: "Cannot reach the server") } }
        }
    }

    private fun act(done: String?, block: () -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            r.onFailure { e -> toast(e.message ?: "Failed") }.onSuccess { done?.let(::toast) }
            refresh()
        }
    }

    /** [max] 0 = unlimited. */
    fun approve(c: ClientInfo, max: Int) {
        act("Approved ${c.title}: ${if (max > 0) "$max connections" else "unlimited"}") {
            api.approve(c.id, max.takeIf { it > 0 })
            AdminService.cancelRequest(getApplication(), c.id)
        }
    }

    /** Gives a phone [n] more connections (what it used so far is kept). */
    fun addMore(c: ClientInfo, n: Int) = act("Gave ${c.title} $n more connections") {
        api.addMore(c.id, n); AdminService.cancelRequest(getApplication(), c.id)
    }

    fun dismiss(c: ClientInfo) = act("Dismissed") { api.dismiss(c.id); AdminService.cancelRequest(getApplication(), c.id) }

    fun deny(c: ClientInfo) = act("Denied ${c.title}") { api.deny(c.id); AdminService.cancelRequest(getApplication(), c.id) }
    fun block(c: ClientInfo) = act("Blocked ${c.title}") { api.block(c.id); AdminService.cancelRequest(getApplication(), c.id) }
    fun forget(c: ClientInfo) = act("Removed ${c.title}") { api.forget(c.id) }

    fun setDefaultAllowance(n: Int) {
        store.defaultAllowance = n
        _state.update { it.copy(defaultAllowance = n) }
    }

    fun setRequireApproval(v: Boolean) {
        store.requireApproval = v
        _state.update { it.copy(requireApproval = v) }
    }

    fun createInvite(node: NodeInfo, label: String) {
        val s = _state.value
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val inv = api.invite(node, label.ifBlank { "Phone invite" }, s.requireApproval, s.defaultAllowance.takeIf { it > 0 })
                    InviteUi(inv, qrBitmap(inv.link))
                }
            }
            r.onSuccess { i -> _state.update { it.copy(busy = false, invite = i) } }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.message ?: "Could not create the invite") } }
        }
    }

    fun closeInvite() = _state.update { it.copy(invite = null) }

    /** Makes a private, unlimited link for this admin phone and hands the saved server to [onReady]. */
    fun connectThisPhone(node: NodeInfo, onReady: (ConnectProfile) -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val link = Links.parse(api.selfLink(node)) ?: error("The server sent an invalid link")
                    val name = "Admin · ${node.name}"
                    profiles.all().filter { it.name == name }.forEach { profiles.delete(it.id) }
                    val server = store.account()?.serverUrl ?: link.serverUrl
                    profiles.import(ConnectLink(server, link.key, link.rendezvousUrl), name).let {
                        profiles.upsert(it.copy(name = name, deviceName = node.name)); profiles.get(it.id)!!
                    }
                }
            }
            _state.update { it.copy(busy = false) }
            r.onSuccess(onReady).onFailure { e -> toast(e.message ?: "Could not connect") }
        }
    }

    private fun qrBitmap(text: String, size: Int = 720): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
