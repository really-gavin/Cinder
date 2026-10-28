package app.cinder.wallet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Warnings that someone is guessing this card's SPP somewhere else (the website, say). */
object Alerts {
    private const val CHANNEL = "alerts"
    private const val JOB = 41

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB) != null) return
        js.schedule(JobInfo.Builder(JOB, ComponentName(ctx, AlertJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).build())
    }

    fun notify(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Card warnings", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, WalletActivity::class.java).putExtra("alert", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(7, Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Someone is trying to open your card")
            .setContentText("Tap to lock it.").setStyle(Notification.BigTextStyle().bigText(text + " Tap to lock the card."))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class AlertJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try {
                val w = Wallet(this)
                if (w.hasCard) w.fetchAlerts().lastOrNull()?.let { Alerts.notify(this, it) }
            } catch (_: Exception) { }
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { if (Wallet(ctx).hasCard) Alerts.schedule(ctx) }
}
