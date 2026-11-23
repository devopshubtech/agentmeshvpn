package io.agentmesh.control.admin

import android.content.Context
import io.agentmesh.control.data.ProfileStore

/**
 * The owner's saved sign-in. The password is encrypted with the Android Keystore.
 * [rendezvous] is an address that always tells the server's current URL (it comes
 * from the QR code), so the app follows the server when its domain changes.
 * [autoFind] means the owner left the address empty: use the app's built-in one.
 */
data class Account(
    val serverUrl: String, val email: String, val password: String,
    val rendezvous: String = "", val autoFind: Boolean = false,
)

class AdminStore(context: Context, private val crypto: ProfileStore) {
    private val prefs = context.getSharedPreferences("agentmesh_admin", Context.MODE_PRIVATE)

    fun account(): Account? {
        val server = prefs.getString("server", null) ?: return null
        val email = prefs.getString("email", null) ?: return null
        val enc = prefs.getString("password", null) ?: return null
        val pw = runCatching { crypto.decrypt(enc) }.getOrNull() ?: return null
        return Account(server, email, pw, prefs.getString("rendezvous", "").orEmpty(), prefs.getBoolean("auto_find", false))
    }

    fun save(a: Account) {
        prefs.edit().putString("server", a.serverUrl).putString("email", a.email)
            .putString("password", crypto.encrypt(a.password))
            .putString("rendezvous", a.rendezvous).putBoolean("auto_find", a.autoFind).apply()
    }

    fun updateServer(url: String) = prefs.edit().putString("server", url).apply()

    fun clear() = prefs.edit().clear().apply()

    /** Connections a phone gets when approved with one tap (0 = unlimited). */
    var defaultAllowance: Int
        get() = prefs.getInt("default_allowance", 5)
        set(v) = prefs.edit().putInt("default_allowance", v).apply()

    /** Should new invitations ask for approval? */
    var requireApproval: Boolean
        get() = prefs.getBoolean("require_approval", true)
        set(v) = prefs.edit().putBoolean("require_approval", v).apply()
}
