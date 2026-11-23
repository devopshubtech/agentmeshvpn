package io.agentmesh.control.admin

import io.agentmesh.control.BuildConfig
import io.agentmesh.control.data.ApiException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The label on the link the admin makes for their own phone; the Phones tab uses it to find "My phone". */
const val MY_PHONE_LABEL = "Admin phone"

/** A phone that asked to connect (or already did). */
@Serializable
data class ClientInfo(
    val id: String,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("key_label") val keyLabel: String = "",
    val name: String = "",
    val ip: String = "",
    val status: String = "pending",
    @SerialName("max_connects") val maxConnects: Int? = null,
    @SerialName("connects_used") val connectsUsed: Int = 0,
    @SerialName("connects_left") val connectsLeft: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("last_connect_at") val lastConnectAt: String? = null,
    @SerialName("active_sessions") val activeSessions: Int = 0,
    @SerialName("more_requested_at") val moreRequestedAt: String? = null,
    @SerialName("more_message") val moreMessage: String = "",
    /** The name the person typed when they first opened the app. */
    @SerialName("person_name") val personName: String = "",
    /** When the phone's current connection started (null = not connected now). */
    @SerialName("online_since") val onlineSince: String? = null,
    /** When its last connection ended (null while connected, or if it never connected). */
    @SerialName("offline_since") val offlineSince: String? = null,
) {
    /** The person's name, or the device name if they gave none. */
    val title: String get() = personName.ifBlank { name.ifBlank { "Unnamed phone" } }

    /** The device itself (for example "Android Google Pixel 8"). */
    val device: String get() = name.ifBlank { "unknown device" }

    /** This is the admin's own phone (made with "Connect this phone"). */
    val isMine: Boolean get() = keyLabel == MY_PHONE_LABEL

    /** Connected to AgentMesh right now (its VPN is up through one of the servers). */
    val online: Boolean get() = activeSessions > 0

    /** An approved phone asking for more connections (as opposed to a brand-new phone). */
    val wantsMore: Boolean get() = status == "approved" && moreRequestedAt != null
}

/** A computer (Mac, Windows, Linux) running the AgentMesh agent. */
@Serializable
data class NodeInfo(
    val id: String,
    val name: String = "",
    val hostname: String = "",
    val status: String = "",
    val connectivity: String = "",
    val platform: String = "",
    val capabilities: List<String> = emptyList(),
    @SerialName("last_ip") val lastIp: String? = null,
) {
    val online: Boolean get() = status == "active" && connectivity == "online"
    val canExit: Boolean get() = "exit_node" in capabilities
}

data class Invite(val link: String, val code: String?, val codeExpiresAt: String?, val nodeName: String)

/**
 * Talks to the AgentMesh control API as the owner. It signs in with the saved
 * email and password whenever it needs a token, so the app keeps working after
 * the phone, the server or the tunnel restarts. If the saved address stops
 * answering, the current one is looked up at the rendezvous address.
 */
class AdminApi(private val store: AdminStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val streamHttp = http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    private fun account(): Account = store.account() ?: throw ApiException(401, "not_signed_in", "Sign in first")

    // ------------------------------------------------------------ sign in

    /**
     * Checks the sign-in and saves it. Returns the working server address.
     * [rendezvous] (from a QR code) lets the app find the server again if its domain changes.
     */
    fun signIn(serverInput: String, email: String, password: String, rendezvous: String = ""): String {
        val candidates = linkedSetOf<String>()
        if (serverInput.isNotBlank()) candidates.add(normalize(serverInput) ?: throw ApiException(400, "bad_server", "That server address is not valid"))
        val autoFind = serverInput.isBlank() && rendezvous.isBlank()
        when {
            rendezvous.isNotBlank() -> resolve(rendezvous)?.let(candidates::add)
            autoFind -> resolveDefault()?.let(candidates::add)
        }
        if (candidates.isEmpty()) throw IOException("Enter the server address, or scan the QR code (and check your internet connection).")
        var last: Exception? = null
        for (server in candidates) {
            try {
                loginAt(server, email.trim(), password)
                store.save(Account(server, email.trim(), password, rendezvous, autoFind))
                return server
            } catch (e: ApiException) {
                if (e.status in 400..499) throw e
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Could not reach the server")
    }

    /**
     * Points the saved sign-in at another server address (for example after the domain changed),
     * keeping the saved username and password. Checks that the login works there first.
     */
    fun relink(serverInput: String, rendezvous: String): String {
        val a = account()
        val server = (if (serverInput.isNotBlank()) normalize(serverInput) else null)
            ?: rendezvous.takeIf { it.isNotBlank() }?.let { resolve(it) }
            ?: throw IOException("Could not find the server from that QR code")
        loginAt(server, a.email, a.password)
        store.save(a.copy(serverUrl = server, rendezvous = rendezvous.ifBlank { a.rendezvous }, autoFind = false))
        return server
    }

    private fun loginAt(server: String, email: String, password: String) {
        val body = buildJsonObject { put("email", email); put("password", password); put("client", "mobile") }.toString().toRequestBody(jsonType)
        val o = execute(Request.Builder().url("$server/v1/auth/login").post(body))
        token = o["access_token"]!!.jsonPrimitive.content
        tokenAt = System.currentTimeMillis()
    }

    private fun ensureToken(forceNew: Boolean = false): String {
        val t = token
        // Access tokens last 10 minutes; renew a little early.
        if (!forceNew && t != null && System.currentTimeMillis() - tokenAt < 8 * 60_000) return t
        val a = account()
        try {
            loginAt(a.serverUrl, a.email, a.password)
        } catch (e: IOException) {
            // The server may have moved (free tunnel restarted, domain changed): follow it using the
            // address from the QR code, and keep the saved username and password.
            val fresh = resolveCurrent()
            if (fresh == null || fresh == a.serverUrl) throw e
            loginAt(fresh, a.email, a.password)
            store.updateServer(fresh)
        }
        return token!!
    }

    // ------------------------------------------------------------ requests

    private fun call(method: String, path: String, body: JsonObject? = null): JsonObject {
        var relogin = false
        var relocated = false
        while (true) {
            try {
                val tok = ensureToken(relogin) // may move to the server's new address
                val a = account()
                val req = Request.Builder().url(a.serverUrl + path)
                    .header("Authorization", "Bearer $tok")
                val rb = (body?.toString() ?: "").toRequestBody(jsonType)
                when (method) {
                    "GET" -> req.get()
                    "DELETE" -> req.delete()
                    else -> req.method(method, rb)
                }
                return execute(req)
            } catch (e: ApiException) {
                if (e.status == 401 && !relogin) { relogin = true; continue }
                throw e
            } catch (e: IOException) {
                // The address may have changed (free tunnel restarted): look it up once.
                val fresh = if (relocated) null else resolveCurrent()
                relocated = true
                if (fresh == null || fresh == store.account()?.serverUrl) throw e
                store.updateServer(fresh)
                token = null
            }
        }
    }

    private fun execute(req: Request.Builder): JsonObject {
        http.newCall(req.header("User-Agent", "AgentMesh-Admin").build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val err = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
                val msg = err?.get("message")?.jsonPrimitive?.content
                if (msg == null && r.code >= 500) throw IOException("server unreachable (HTTP ${r.code})")
                throw ApiException(r.code, err?.get("code")?.jsonPrimitive?.content ?: "http_${r.code}", msg ?: "HTTP ${r.code}")
            }
            if (text.isBlank()) return JsonObject(emptyMap())
            return json.parseToJsonElement(text).jsonObject
        }
    }

    // ------------------------------------------------------------ data

    fun nodes(): List<NodeInfo> =
        call("GET", "/v1/devices?limit=200")["items"]?.let { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(NodeInfo.serializer()), it) }
            .orEmpty().filter { it.status != "revoked" }

    fun clients(status: String = ""): List<ClientInfo> =
        call("GET", "/v1/access" + if (status.isNotEmpty()) "?status=$status" else "")["items"]
            ?.let { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(ClientInfo.serializer()), it) }.orEmpty()

    /** [maxConnects] null = unlimited. */
    fun approve(id: String, maxConnects: Int?, name: String? = null) {
        call("POST", "/v1/access/$id/approve", buildJsonObject {
            if (maxConnects == null) put("max_connects", JsonNull) else put("max_connects", maxConnects)
            if (name != null) put("name", name)
        })
    }

    /** Gives a phone [n] more connections on top of what it has (the count used so far is kept). */
    fun addMore(id: String, n: Int) {
        call("POST", "/v1/access/$id/add", buildJsonObject { put("add_connects", n) })
    }

    /** Clears a phone's request for more without changing its allowance. */
    fun dismiss(id: String) { call("POST", "/v1/access/$id/dismiss") }

    fun deny(id: String) { call("POST", "/v1/access/$id/deny") }
    fun block(id: String) { call("POST", "/v1/access/$id/block") }
    fun forget(id: String) { call("DELETE", "/v1/access/$id") }

    /**
     * Makes a QR link and a 6-digit code for [node]. New phones wait for approval
     * when [requireApproval], and start with [maxConnects] connections (null = unlimited).
     */
    fun invite(node: NodeInfo, label: String, requireApproval: Boolean, maxConnects: Int?): Invite {
        fun rule(b: kotlinx.serialization.json.JsonObjectBuilder) {
            b.put("require_approval", requireApproval)
            if (maxConnects != null) b.put("default_max_connects", maxConnects)
        }
        val key = call("POST", "/v1/devices/${node.id}/connect-keys", buildJsonObject { put("label", label); rule(this) })
        val pair = call("POST", "/v1/devices/${node.id}/pair-codes", buildJsonObject { put("label", label); put("expires_in_s", 900); rule(this) })
        return Invite(
            link = key["link"]!!.jsonPrimitive.content,
            code = pair["code"]?.jsonPrimitive?.content, codeExpiresAt = pair["expires_at"]?.jsonPrimitive?.content,
            nodeName = node.name,
        )
    }

    /** A private link for the owner's own phone: no approval, no limit. Returns the link. */
    fun selfLink(node: NodeInfo): String =
        call("POST", "/v1/devices/${node.id}/connect-keys", buildJsonObject {
            put("label", MY_PHONE_LABEL); put("require_approval", false)
        })["link"]!!.jsonPrimitive.content

    // ------------------------------------------------------------ live events

    /**
     * Follows the server's event stream and calls [onEvent] with (event, data).
     * Blocks until the stream ends; throws if it cannot be opened.
     */
    fun stream(onEvent: (String, String) -> Unit) {
        val tok = ensureToken(true) // signs in again; follows the server if its address changed
        val a = account()
        val req = Request.Builder().url(a.serverUrl + "/v1/events")
            .header("Authorization", "Bearer $tok").header("Accept", "text/event-stream")
            .header("User-Agent", "AgentMesh-Admin").build()
        streamHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                if (r.code >= 500) throw IOException("server unreachable (HTTP ${r.code})")
                throw ApiException(r.code, "http_${r.code}", "Events stream refused (HTTP ${r.code})")
            }
            val src = r.body!!.source()
            var event = "message"
            val data = StringBuilder()
            while (true) {
                val line = src.readUtf8Line() ?: return
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) onEvent(event, data.toString())
                        event = "message"; data.clear()
                    }
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trim()) }
                }
            }
        }
    }

    // ------------------------------------------------------------ addresses

    /** The server's current address, looked up where this sign-in says to (QR code address, or the built-in one). */
    private fun resolveCurrent(): String? {
        val a = store.account() ?: return null
        return when {
            a.rendezvous.isNotBlank() -> resolve(a.rendezvous)
            a.autoFind -> resolveDefault()
            else -> null
        }
    }

    /** Looks up the server's current address at the app's built-in rendezvous address. */
    fun resolveDefault(): String? = BuildConfig.DEFAULT_RENDEZVOUS.takeIf { it.isNotBlank() }?.let { resolve(it) }

    /** Reads {"url": ...} directly or from a GitHub gist API response. */
    fun resolve(rendezvous: String): String? = runCatching {
        http.newCall(Request.Builder().url(rendezvous).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val root = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            val doc = root["files"]?.jsonObject?.get("agentmesh-endpoint.json")?.jsonObject?.get("content")
                ?.jsonPrimitive?.content?.let { json.parseToJsonElement(it).jsonObject } ?: root
            doc["url"]?.jsonPrimitive?.content?.trimEnd('/')?.takeIf { it.startsWith("https://") }
        }
    }.getOrNull()

    private fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.startsWith("http://", ignoreCase = true)) return null
        if (s.startsWith("https://", ignoreCase = true)) s = s.substring(8)
        val hostPort = s.substringBefore('/')
        if (hostPort.isBlank() || hostPort.contains(' ')) return null
        return "https://$hostPort"
    }
}
