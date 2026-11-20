package app.backstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Notify me when this person goes live." Starred streamers are checked in the
 * background about every 15 minutes (the tightest Android allows for this kind of
 * job), and a notification goes out when one of their streams flips to "live".
 */
object LiveWatch {
    private const val WORK = "live-watch"
    private const val CHANNEL = "live"

    private fun prefs(c: Context) = c.getSharedPreferences("watch", 0)

    fun stars(c: Context): Set<String> = HashSet(prefs(c).getStringSet("stars", emptySet()) ?: emptySet())

    /** Stars or unstars a streamer; returns whether they are starred now. */
    fun toggle(c: Context, host: String): Boolean {
        val stars = HashSet(stars(c))
        val starred = if (stars.remove(host)) false else stars.add(host)
        prefs(c).edit().putStringSet("stars", stars).apply()
        schedule(c)
        return starred
    }

    fun schedule(c: Context) {
        val work = WorkManager.getInstance(c)
        if (stars(c).isEmpty()) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequest.Builder(LiveWatchWorker::class.java, 15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun check(c: Context) {
        val stars = stars(c)
        if (stars.isEmpty()) return
        val who = JSONArray(stars)
        val filters = listOf(
            JSONObject().put("kinds", JSONArray().put(30311)).put("authors", who).put("limit", 50),
            JSONObject().put("kinds", JSONArray().put(30311)).put("#p", who).put("limit", 50),
        )
        val events = Nostr.fetch(OkHttpClient(), Nostr.DEFAULT_RELAYS, filters, patient = true)
        if (events.isEmpty()) return // offline or relays down: keep what we knew

        val newest = Discover.newestBy(events) { "30311:${it.optString("pubkey")}:${Nostr.tag(it, "d") ?: ""}" }
        val liveNow = newest.filter { (_, e) -> Nostr.tag(e, "status") == "live" && Nostr.host(e) in stars }

        // Notify only for streams that weren't live last time we looked, and only if the
        // switch is recent, so starring someone doesn't announce a stream stuck on "live" for days.
        val known = prefs(c).getStringSet("known", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis() / 1000
        for ((key, e) in liveNow) {
            if (key in known || now - e.optLong("created_at") > 2 * 3600) continue
            val title = (Nostr.tag(e, "title") ?: Nostr.tag(e, "summary")).takeUnless { it.isNullOrBlank() } ?: "A stream you starred"
            notify(c, key, title)
        }
        prefs(c).edit().putStringSet("known", HashSet(liveNow.keys)).apply()
    }

    private fun notify(c: Context, key: String, title: String) {
        val manager = c.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Stream went live", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            c, key.hashCode(),
            Intent(c, MainActivity::class.java).putExtra("play", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Live now")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(key.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notifications are switched off for the app.
        }
    }
}

class LiveWatchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            LiveWatch.check(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }
}
