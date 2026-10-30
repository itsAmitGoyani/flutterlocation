package com.lyokone.location

import android.app.AppOpsManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * The resident watch of a sharing member's phone (AutoLNK 4.10, after
 * Life360): a started service with no notification and no location request.
 * Its one job is to keep this process alive at rest, so that
 * [ProvidersWatch] hears the location switch at once, and to read the grant
 * at each of its starts and at each change of the location app op
 * ([WakeGuard]): a revoke kills the process, so the start reads the loss;
 * the user's repair in Settings kills nothing, so the app-ops watch reads
 * the return within a second. Without it a dead app learns of the switch or
 * of a lost grant only at its next wake, up to the heartbeat interval away.
 *
 * It runs only while the wake sources are armed (the user shares on a
 * server) and while the user exempted the app from battery optimization:
 * Android stops the background services of an optimized app about a minute
 * after it goes idle, so the watch would not live there, and the own card
 * already asks for the "Unrestricted" setting.
 *
 * The ways it comes back after its process went: a sticky restart by the
 * system (stock Android, and after the kill that follows a permission
 * change), the job of [onTaskRemoved] (MIUI ends every process with the
 * task), the next wake event ([WakeReceiver]), the boot receiver and the
 * app's process start. The heartbeat is the floor, as before 4.10.
 */
class LocationWatchService : Service() {
    companion object {
        private const val TAG = "LocationWatchService"
        private const val RESTART_WORK = "lyokone_location_watch_restart"

        @Volatile
        private var running = false

        @JvmStatic
        val isRunning: Boolean
            get() = running

        /**
         * Whether the watch should run now: the wake sources are armed and
         * the user exempted the app from battery optimization.
         */
        @JvmStatic
        fun wanted(context: Context): Boolean {
            val app = context.applicationContext
            if (!WakeMonitor.isArmed(app)) return false
            val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            return power.isIgnoringBatteryOptimizations(app.packageName)
        }

        /**
         * Starts the watch when it is wanted and not running. Any thread.
         * Returns whether it runs, or was asked to run, afterwards. A start
         * the system refuses is logged; the next wake event asks again.
         */
        @JvmStatic
        fun startIfWanted(context: Context): Boolean {
            val app = context.applicationContext
            if (!wanted(app)) return false
            if (running) return true
            return try {
                app.startService(Intent(app, LocationWatchService::class.java))
                true
            } catch (e: Exception) {
                Log.w(TAG, "The watch could not start now.", e)
                false
            }
        }

        /** Stops the watch: the wake sources were disarmed. */
        @JvmStatic
        fun stop(context: Context) {
            val app = context.applicationContext
            try {
                app.stopService(Intent(app, LocationWatchService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "The watch could not be stopped.", e)
            }
        }

        /**
         * Expedited on Android 12+, like the wake jobs ([WakeHub]): a plain
         * job waited 92 s on MIUI, until another event had started the
         * process. Below Android 12 an expedited job needs a foreground
         * service and its notification, so the job stays plain there.
         */
        private fun scheduleRestart(app: Context) {
            try {
                val builder = OneTimeWorkRequest.Builder(WatchRestartWorker::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                WorkManager.getInstance(app).enqueueUniqueWork(RESTART_WORK, ExistingWorkPolicy.REPLACE, builder.build())
            } catch (e: Exception) {
                Log.w(TAG, "The restart job could not be scheduled.", e)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private var opsListener: AppOpsManager.OnOpChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Log.i(TAG, "Watch started.")
        ProvidersWatch.register(applicationContext)
        watchGrant()
    }

    /**
     * The location app op changes with every grant change of this app: a
     * denial, "While using" and "All the time" are its modes. The callback
     * comes on a binder thread, so the check runs on the main thread, where
     * every other wake path runs.
     */
    private fun watchGrant() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || opsListener != null) return
        val app = applicationContext
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return
        val listener =
            AppOpsManager.OnOpChangedListener { op, packageName ->
                if (packageName != app.packageName) return@OnOpChangedListener
                main.post {
                    if (!running) return@post
                    Log.i(TAG, "The location app op changed ($op): reading the grant.")
                    WakeGuard.check(app)
                }
            }
        try {
            ops.startWatchingMode(AppOpsManager.OPSTR_FINE_LOCATION, app.packageName, listener)
            opsListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "The location app op could not be watched.", e)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val app = applicationContext
        if (!wanted(app)) {
            Log.i(TAG, "The watch is not wanted any more: stopping.")
            stopSelf()
            return START_NOT_STICKY
        }
        // The state at every start. A null intent is the system's sticky
        // restart after a process kill: the one that follows a permission
        // change reads the lost grant here, within seconds of the change.
        Log.d(TAG, if (intent == null) "Sticky restart." else "Start.")
        WakeGuard.check(app)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user removed the app's task. MIUI ends every process of the
        // app with it, so a job brings the watch back in a fresh process.
        Log.i(TAG, "The task was removed: a job brings the watch back.")
        scheduleRestart(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        opsListener?.let {
            try {
                (applicationContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager)?.stopWatchingMode(it)
            } catch (e: Exception) {
                Log.w(TAG, "The location app op watch could not be stopped.", e)
            }
        }
        opsListener = null
        Log.i(TAG, "Watch stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
