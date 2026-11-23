package io.agentmesh.control.admin

import android.content.Context
import io.agentmesh.control.App

/** Shared admin objects (one set per process). */
class AdminGraph private constructor(ctx: Context) {
    private val app = ctx.applicationContext as App
    val store = AdminStore(app, app.profiles)
    val api = AdminApi(store)

    companion object {
        @Volatile private var instance: AdminGraph? = null
        fun get(ctx: Context): AdminGraph =
            instance ?: synchronized(this) { instance ?: AdminGraph(ctx).also { instance = it } }
    }
}
