package com.lyokone.location

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * The job that starts [LocationWatchService] again after the user removed
 * the app's task (AutoLNK 4.10). The system runs it in a fresh process, about
 * 20 s later on MIUI (measured on Life360, which comes back the same way).
 * The service reads the grant and the switch at that start.
 */
class WatchRestartWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val started = LocationWatchService.startIfWanted(applicationContext)
        Log.i(TAG, if (started) "The watch is back." else "The watch is not wanted now.")
        return Result.success()
    }

    private companion object {
        const val TAG = "WatchRestartWorker"
    }
}
