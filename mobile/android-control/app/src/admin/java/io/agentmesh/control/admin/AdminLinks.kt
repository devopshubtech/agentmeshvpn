package io.agentmesh.control.admin

import android.net.Uri
import io.agentmesh.control.data.Links

/** What an AgentMesh QR code tells the admin app: where the server is, and how to find it again. */
data class AdminLink(val server: String, val rendezvous: String, val email: String)

object AdminLinks {
    /**
     * Accepts every AgentMesh QR code / link:
     *   agentmesh://admin?u=<server>&r=<rendezvous>&e=<email>   (admin sign-in code)
     *   the "Connect a phone" QR code or link from the dashboard / an invite (server + rendezvous)
     */
    fun parse(raw: String): AdminLink? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val uri = runCatching { Uri.parse(s) }.getOrNull()
        if (uri?.scheme.equals("agentmesh", ignoreCase = true) && uri?.host.equals("admin", ignoreCase = true)) {
            val u = uri!!.getQueryParameter("u")?.trimEnd('/').orEmpty()
            val r = uri.getQueryParameter("r").orEmpty()
            if (!u.startsWith("https://") && r.isBlank()) return null
            return AdminLink(u.takeIf { it.startsWith("https://") }.orEmpty(), r, uri.getQueryParameter("e").orEmpty())
        }
        val link = Links.parse(s) ?: return null
        return AdminLink(link.serverUrl, link.rendezvousUrl, "")
    }
}
