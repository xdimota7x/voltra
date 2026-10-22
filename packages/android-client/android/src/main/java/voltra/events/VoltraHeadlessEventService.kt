package voltra.events

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import voltra.R

/**
 * Wakes JavaScript for an event that arrived while no React instance was alive (ADR 0014).
 *
 * The task itself does no work: starting it loads the app's bundle, whose module-scope Voltra
 * listener activates delivery and drains the queue. The task then waits until the listeners have
 * finished, so the process stays up while they run.
 *
 * Started from a broadcast receiver with `startForegroundService`. A user's press on a notification
 * action is an exemption from the Android 12 background foreground-service start restriction. The
 * service promotes itself with the `shortService` type, which needs no type-specific permission and
 * is stopped by the system after about three minutes at most.
 */
class VoltraHeadlessEventService : HeadlessJsTaskService() {
    companion object {
        private const val TAG = "VoltraHeadlessEvents"

        /** Registered by `@use-voltra/android-client` with `AppRegistry.registerHeadlessTask`. */
        const val TASK_NAME = "VoltraEvents"
        const val CHANNEL_ID = "voltra_background_events"
        const val FOREGROUND_NOTIFICATION_ID = 0x566F6C74
        private const val TASK_TIMEOUT_MS = 60_000L

        /**
         * Starts the service. Must be called from `BroadcastReceiver.onReceive`: the wake lock is
         * taken before the receiver returns, as React Native's headless docs require.
         */
        fun start(context: Context) {
            acquireWakeLockNow(context)
            ContextCompat.startForegroundService(context, Intent(context, VoltraHeadlessEventService::class.java))
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        promoteToForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig =
        HeadlessJsTaskConfig(
            TASK_NAME,
            Arguments.createMap(),
            TASK_TIMEOUT_MS,
            // React Native refuses a headless task in a resumed app; the delivery policy only
            // starts this service when the app is not in the foreground.
            false,
        )

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Short foreground service timed out; stopping")
        stopSelf()
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voltra_background_events_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(applicationInfo.icon)
                .setContentTitle(getString(R.string.voltra_background_events_notification_title))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                0
            }
        ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification, type)
    }
}

/**
 * The default [InteractionDeliveryPolicy] (ADR 0014): an `interaction` that finds no live React
 * instance and an app that is not in the foreground starts [VoltraHeadlessEventService]. Everything
 * else waits in the queue for the next listener: dismissals and taps never wake JavaScript, and an
 * app whose JavaScript is alive drains the queue itself once its listener is registered.
 */
class HeadlessJsDeliveryPolicy(
    private val isAppInForeground: () -> Boolean = ::isProcessInForeground,
    private val startHeadless: (Context) -> Unit = VoltraHeadlessEventService::start,
) : InteractionDeliveryPolicy {
    private companion object {
        private const val TAG = "VoltraEventBus"

        fun isProcessInForeground(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    override fun onUndelivered(
        context: Context,
        event: VoltraQueuedEvent,
        jsAttached: Boolean,
    ) {
        if (event.event !is VoltraEvent.Interaction) return
        if (jsAttached) return
        if (isAppInForeground()) {
            Log.d(TAG, "App is in the foreground without a Voltra module; ${event.eventId} stays queued")
            return
        }
        try {
            startHeadless(context)
        } catch (error: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException: the event stays queued for the next launch.
            Log.w(TAG, "Could not start headless delivery for ${event.eventId}", error)
        }
    }
}
