package com.lyokone.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The job that delivers the queued wakes ([WakeHub]). No foreground service,
 * so no notification: a job is enough to keep the process runnable while the
 * Dart side sends one point. Bounded by [TIME_LIMIT_S]: a Dart side that
 * never answers leaves its wakes for the next job.
 */
class WakeWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val latch = CountDownLatch(1)
        val done: () -> Unit = { latch.countDown() }
        val main = Handler(Looper.getMainLooper())
        main.post { WakeHub.drainForJob(applicationContext, done) }
        if (!latch.await(TIME_LIMIT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "The wakes did not finish in time; the next job delivers the rest.")
            main.post { WakeHub.abandon(done) }
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "WakeWorker"
        const val TIME_LIMIT_S = 60L
    }
}
