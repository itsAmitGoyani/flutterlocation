package com.lyokone.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Hands the system wakes to the Dart side (Android, AutoLNK fork).
 *
 * A wake is persisted first, then a job ([WakeWorker]) delivers the queue. A
 * running job keeps the process out of the cached state, so the Dart side
 * can finish its upload before Android freezes the process again. The job
 * delivers to the engine whose Dart side listens (`listenForWakes`): the
 * app's own engine while it lives, else a headless engine that this hub
 * starts. It never starts a headless engine beside an Activity-hosted one.
 *
 * Main thread, except [enqueue].
 */
internal object WakeHub {
    private const val TAG = "WakeHub"
    private const val PREFS_NAME = "flutter_location_prefs"
    private const val KEY_QUEUE = "wake_queue"
    private const val MAX_QUEUE = 16
    private const val UNIQUE_WORK = "lyokone_location_wake"

    /** A wake older than this is dropped: its news is stale, and a newer wake follows. */
    private const val MAX_AGE_MS = 15 * 60 * 1000L

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    /** Every attached plugin instance: one per engine. */
    private val plugins = mutableSetOf<LocationPlugin>()

    /** The instance whose Dart side takes the wakes. */
    private var listener: LocationPlugin? = null

    /** The id of the wake the listener is on, or null. */
    private var inFlight: String? = null

    /** The jobs that wait for the queue to empty. */
    private val waiters = mutableListOf<() -> Unit>()

    private var appContext: Context? = null

    @JvmStatic
    fun attach(
        plugin: LocationPlugin,
        context: Context,
    ) {
        appContext = context.applicationContext
        plugins.add(plugin)
    }

    @JvmStatic
    fun detach(plugin: LocationPlugin) {
        plugins.remove(plugin)
        if (listener === plugin) {
            listener = null
            // Its answer can never come back.
            inFlight = null
        }
        releaseWaitersIfNobodyCanTake()
    }

    @JvmStatic
    fun listen(plugin: LocationPlugin) {
        listener = plugin
        appContext?.let { drain(it) }
    }

    @JvmStatic
    fun unlisten(plugin: LocationPlugin) {
        if (listener !== plugin) return
        listener = null
        inFlight = null
        releaseWaitersIfNobodyCanTake()
    }

    /** Any thread: persists the wake and schedules the job that delivers it. */
    @JvmStatic
    fun enqueue(
        context: Context,
        wake: Map<String, Any?>,
    ) {
        val app = context.applicationContext
        synchronized(lock) {
            val queue = readQueue(app)
            queue.put(JSONObject(wake + ("id" to UUID.randomUUID().toString()) + ("queuedAt" to System.currentTimeMillis())))
            while (queue.length() > MAX_QUEUE) queue.remove(0)
            writeQueue(app, queue)
        }
        try {
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequest.Builder(WakeWorker::class.java).build(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "The wake job could not be scheduled.", e)
        }
    }

    /** From the job: delivers the queue; [done] runs once it is empty or nobody can take it. */
    fun drainForJob(
        context: Context,
        done: () -> Unit,
    ) {
        appContext = context.applicationContext
        waiters.add(done)
        drain(context.applicationContext)
    }

    /** The job stopped waiting; the next job delivers what is left. */
    fun abandon(done: () -> Unit) {
        waiters.remove(done)
    }

    /** The headless engine went, maybe before its Dart side listened. */
    fun onHeadlessGone() {
        releaseWaitersIfNobodyCanTake()
    }

    private fun drain(context: Context) {
        if (inFlight != null) return
        val next = synchronized(lock) { firstOf(pruneStale(context)) }
        if (next == null) {
            finishWaiters()
            return
        }
        val target = listener
        if (target == null) {
            // Nothing waits: the next job, or the next listener, delivers.
            if (waiters.isEmpty()) return
            // The app's own engine listens once its Dart side started the share.
            if (plugins.any { it.activityHosted() }) return
            // A headless engine listens as soon as its Dart side is up.
            if (HeadlessLocationEngine.isRunning) return
            val handle = FlutterLocationService.callbackHandle(context)
            if (handle == 0L || !HeadlessLocationEngine.startIfNone(context, handle)) {
                Log.w(TAG, "No Dart side can take the wakes: dropping them.")
                synchronized(lock) { writeQueue(context, JSONArray()) }
                finishWaiters()
            }
            return
        }
        val id = next.optString("id")
        inFlight = id
        target.deliverWake(
            toMap(next),
            object : MethodChannel.Result {
                override fun success(result: Any?) = delivered(context, id)

                override fun error(
                    errorCode: String,
                    errorMessage: String?,
                    errorDetails: Any?,
                ) {
                    // Not retried: the next wake carries fresher data anyway.
                    Log.w(TAG, "The wake failed on the Dart side: $errorCode $errorMessage")
                    delivered(context, id)
                }

                override fun notImplemented() = delivered(context, id)
            },
        )
    }

    private fun delivered(
        context: Context,
        id: String,
    ) {
        synchronized(lock) {
            val queue = readQueue(context)
            val kept = JSONArray()
            for (i in 0 until queue.length()) {
                val item = queue.optJSONObject(i) ?: continue
                if (item.optString("id") != id) kept.put(item)
            }
            writeQueue(context, kept)
        }
        if (inFlight == id) inFlight = null
        drain(context)
    }

    private fun releaseWaitersIfNobodyCanTake() {
        if (listener != null || HeadlessLocationEngine.isRunning || plugins.any { it.activityHosted() }) return
        finishWaiters()
    }

    private fun finishWaiters() {
        if (waiters.isEmpty()) return
        val all = waiters.toList()
        waiters.clear()
        all.forEach { it() }
    }

    private fun firstOf(queue: JSONArray): JSONObject? = if (queue.length() == 0) null else queue.optJSONObject(0)

    /** Under [lock]. The queue without the wakes older than [MAX_AGE_MS]. */
    private fun pruneStale(context: Context): JSONArray {
        val queue = readQueue(context)
        val oldest = System.currentTimeMillis() - MAX_AGE_MS
        val kept = JSONArray()
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            if (item.optLong("queuedAt", 0L) >= oldest) kept.put(item)
        }
        if (kept.length() != queue.length()) writeQueue(context, kept)
        return kept
    }

    private fun toMap(item: JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        val keys = item.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "id" || key == "queuedAt") continue
            out[key] = item.opt(key)?.takeUnless { it == JSONObject.NULL }
        }
        return out
    }

    private fun readQueue(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_QUEUE, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: JSONException) {
            JSONArray()
        }
    }

    private fun writeQueue(
        context: Context,
        queue: JSONArray,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_QUEUE, queue.toString()).commit()
    }
}
