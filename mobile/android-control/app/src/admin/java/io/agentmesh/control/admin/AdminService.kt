package io.agentmesh.control.admin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.agentmesh.control.MainActivity
import io.agentmesh.control.data.ApiException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Keeps a live connection to the server and shows a notification when a phone
 * asks to connect (with Approve / Deny buttons). It reconnects by itself, signing
 * in again with the saved login, so alerts keep coming after the server, the
 * tunnel or this phone restarts.
 */
class AdminService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AdminGraph.get(this).store.account() == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground("Starting…")
        if (job?.isActive != true) job = scope.launch { watch() }
        return START_STICKY
    }

    /**
     * Checks for new requests every few seconds. (A live event stream would be quicker, but free
     * Cloudflare quick tunnels do not carry streams, so plain checks are what works everywhere.)
     */
    private suspend fun watch() {
        val g = AdminGraph.get(this)
        val notified = mutableSetOf<String>() // id + time of the request, so each one alerts once
        var failures = 0
        while (scope.isActive) {
            if (g.store.account() == null) { stopSelf(); return }
            try {
                val current = g.api.clients("requests").associateBy { it.id + (it.moreRequestedAt ?: "") }
                for ((key, c) in current) if (notified.add(key)) notifyAny(this, c, g.store.defaultAllowance)
                // Answered elsewhere (another admin phone, or the dashboard): remove its alert.
                notified.filter { it !in current }.forEach { notified.remove(it); cancelRequest(this, it.take(UUID_LEN)) }
                failures = 0
                status("Watching for connection requests", true)
            } catch (e: ApiException) {
                failures++
                status(if (e.status == 401) "Sign-in failed: ${e.message}" else "Server said: ${e.message}", false)
            } catch (e: IOException) {
                failures++
                status("Server not reachable, retrying…", false)
            } catch (e: Exception) {
                failures++
                status("Retrying after an error…", false)
            }
            delay(if (failures == 0) CHECK_EVERY_MS else BACKOFF_MS[minOf(failures - 1, BACKOFF_MS.lastIndex)])
        }
    }

    private fun status(text: String, ok: Boolean) {
        _live.value = LiveAlerts(ok, text)
        notificationManager(this).notify(FOREGROUND_ID, foregroundNotification(text))
    }

    private fun foregroundNotification(text: String): Notification {
        val nm = notificationManager(this)
        if (nm.getNotificationChannel(CH_WATCH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Admin connection", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_WATCH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AgentMesh Admin")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun goForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, foregroundNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, foregroundNotification(text))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EVENT_REQUEST = "access.request"
        const val EVENT_UPDATED = "access.updated"
        const val EVENT_MORE = "access.more"
        private const val CH_WATCH = "admin_watch"
        private const val CH_REQUESTS = "admin_requests_v3"
        private const val FOREGROUND_ID = 21
        private const val CHECK_EVERY_MS = 5_000L
        private val BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L, 30_000L)
        private const val UUID_LEN = 36

        data class LiveAlerts(val ok: Boolean, val text: String)
        private val _live = MutableStateFlow(LiveAlerts(false, "Starting…"))
        /** Whether the alert check is working right now (shown in the app). */
        val live: StateFlow<LiveAlerts> = _live

        /** A test alert, to check this phone's sound, volume and Do Not Disturb settings. */
        fun notifyTest(ctx: Context) {
            val nm = notificationManager(ctx)
            ensureRequestChannel(nm)
            nm.notify(TEST_ID, Notification.Builder(ctx, CH_REQUESTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Test alert")
                .setContentText("If you heard a sound, request alerts will sound like this.")
                .setAutoCancel(true).build())
            ring(ctx)
        }


        /** Says what would keep a request alert silent on this phone (shown in the app). */
        fun soundProblems(ctx: Context): List<String> {
            val out = mutableListOf<String>()
            val nm = notificationManager(ctx)
            if (!nm.areNotificationsEnabled()) out += "Notifications are turned off for this app. Turn them on in the phone's app settings."
            ensureRequestChannel(nm)
            val ch = nm.getNotificationChannel(CH_REQUESTS)
            if (ch != null) {
                if (ch.importance == NotificationManager.IMPORTANCE_NONE) out += "The 'Connection requests' alerts are switched off. Tap Sound settings and turn them on."
                else if (ch.importance < NotificationManager.IMPORTANCE_DEFAULT || ch.sound == null) out += "'Connection requests' is set to silent. Tap Sound settings and choose Alert with a sound."
            }
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) out += "The phone is on silent or vibrate (check the side slider)."
            if (am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION) == 0) out += "The notification volume is zero. Raise it in the volume settings."
            if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) out += "Do Not Disturb is on."
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) out += "Battery is restricting the app. Tap Keep running."
            return out
        }

        /** Opens this phone's settings for the request alerts (sound, importance). */
        fun channelSettings(ctx: Context) {
            ensureRequestChannel(notificationManager(ctx))
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH_REQUESTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        /** Asks to be exempt from battery saving, which on many phones stops background alerts. */
        fun allowBackground(ctx: Context) {
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        private const val TEST_ID = 22

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AdminService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AdminService::class.java))
        }

        private fun notificationManager(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

        private fun requestId(id: String) = 1000 + (id.hashCode() and 0x3fffffff) % 1_000_000

        fun cancelRequest(ctx: Context, id: String) = notificationManager(ctx).cancel(requestId(id))


        private var lastRing: android.media.Ringtone? = null
        /** Plays the phone's notification sound directly, so it is heard even if the channel was set silent earlier. */
        private fun ring(ctx: Context) {
            runCatching {
                lastRing?.stop()
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val r = RingtoneManager.getRingtone(ctx.applicationContext, uri)
                // Alarm usage: heard on silent / vibrate and through Do Not Disturb, and not dropped by OEM notification settings.
                r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                r.play(); lastRing = r
            }
            runCatching {
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
            }
        }

        /** The requests channel plays the phone's notification sound and vibrates, so a new request is heard. */
        private fun ensureRequestChannel(nm: NotificationManager) {
            if (nm.getNotificationChannel(CH_REQUESTS) != null) return
            val ch = NotificationChannel(CH_REQUESTS, "Connection requests", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
                description = "A phone asks to connect, or asks for more connections"
            }
            nm.createNotificationChannel(ch)
        }

        fun notifyAny(ctx: Context, c: ClientInfo, allowance: Int) =
            if (c.wantsMore) notifyMore(ctx, c, allowance) else notifyRequest(ctx, c, allowance)

        /** "<phone> used all its connections and asks for more" with Give / Not now buttons. */
        fun notifyMore(ctx: Context, c: ClientInfo, allowance: Int) {
            val nm = notificationManager(ctx)
            ensureRequestChannel(nm)
            val nid = requestId(c.id)
            val give = if (allowance > 0) allowance else 5
            fun action(op: String, code: Int) = PendingIntent.getBroadcast(
                ctx, nid * 4 + code,
                Intent(ctx, AccessActionReceiver::class.java).putExtra("id", c.id).putExtra("op", op).putExtra("allowance", give),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(ctx, nid * 4 + 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val used = c.maxConnects?.let { "used ${c.connectsUsed} of $it" } ?: "used ${c.connectsUsed}"
            val n = Notification.Builder(ctx, CH_REQUESTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("${c.title} asks for more connections")
                .setContentText("Device: ${c.device} · $used")
                .setStyle(Notification.BigTextStyle().bigText("${c.title} · device ${c.device}\n$used on ${c.deviceName.ifBlank { "your server" }}.\n" + c.moreMessage.ifBlank { "No message." } + "\nGive $give more, or open the app to choose another number."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Give $give more", action("add", 0)).build())
                .addAction(Notification.Action.Builder(null, "Not now", action("dismiss", 1)).build())
                .build()
            nm.notify(nid, n)
            ring(ctx)
        }

        /** "<phone> wants to connect" with Approve and Deny buttons. */
        fun notifyRequest(ctx: Context, c: ClientInfo, allowance: Int) {
            val nm = notificationManager(ctx)
            ensureRequestChannel(nm)
            val nid = requestId(c.id)
            fun action(op: String, code: Int) = PendingIntent.getBroadcast(
                ctx, nid * 4 + code,
                Intent(ctx, AccessActionReceiver::class.java).putExtra("id", c.id).putExtra("op", op).putExtra("allowance", allowance),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(ctx, nid * 4 + 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val limit = if (allowance > 0) "$allowance connections" else "unlimited"
            val n = Notification.Builder(ctx, CH_REQUESTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("${c.title} wants to connect")
                .setContentText("Device: ${c.device} · ${c.ip}")
                .setStyle(Notification.BigTextStyle().bigText("${c.title} wants to use ${c.deviceName.ifBlank { "your server" }}\nDevice: ${c.device}\n${c.ip} · ${c.keyLabel}\nApprove gives $limit; open the app to choose another number."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Approve ($limit)", action("approve", 0)).build())
                .addAction(Notification.Action.Builder(null, "Deny", action("deny", 1)).build())
                .build()
            nm.notify(nid, n)
            ring(ctx)
        }
    }
}

/** Handles the Approve / Deny buttons on a request notification. */
class AccessActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val op = intent.getStringExtra("op") ?: return
        val allowance = intent.getIntExtra("allowance", 5)
        val pending = goAsync()
        val api = AdminGraph.get(context).api
        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching {
                when (op) {
                    "approve" -> api.approve(id, allowance.takeIf { it > 0 })
                    "add" -> api.addMore(id, allowance)
                    "dismiss" -> api.dismiss(id)
                    else -> api.deny(id)
                }
            }.isSuccess
            if (ok) AdminService.cancelRequest(context, id)
            pending.finish()
        }
    }
}

/** Starts the alert service again after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AdminGraph.get(context).store.account() != null) {
            runCatching { AdminService.start(context) }
        }
    }
}
