package dev.bookah.calendar

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Brings the phone's copy up to date in the background, about every 15 minutes while the phone has a network (the Nextcloud server
 * is only reachable on the tailnet: off it, the run simply reports offline and keeps what is cached). It does nothing when nobody
 * is signed in. Whether HyperOS lets this run on time is measured on the phone, not assumed.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = (applicationContext as BookahApp).graph
        val session = graph.sessions.load() ?: return@withContext Result.success()
        graph.syncLock.withLock { graph.sync.sync(graph.apiFactory(session)) }
        graph.rescheduleReminders()      // the events may have changed while the app was closed
        graph.feedLock.withLock {
            val feeds = graph.feeds.load()
            if (feeds.isNotEmpty()) graph.feedSync.sync(feeds)
        }
        Result.success()
    }

    companion object {
        private const val NAME = "refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
