package dev.xxemail.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.xxemail.MainActivity
import dev.xxemail.R
import dev.xxemail.data.db.ThreadEntity

/**
 * New-mail notifications. Grouped per account. No push service involved;
 * these fire from SyncWorker polls.
 */
object Notifier {

    const val CHANNEL_MAIL = "new_mail"
    private const val GROUP_PREFIX = "dev.xxemail.mail."

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, "New mail", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications for newly received messages"
            },
        )
    }

    fun notifyNewMail(context: Context, accountEmail: String, newThreads: List<ThreadEntity>) {
        if (newThreads.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notifier = NotificationManagerCompat.from(context)
        val groupKey = GROUP_PREFIX + accountEmail

        // Tapping opens the account mailbox — ideally the exact thread (see MainActivity extras).
        fun mailIntent(threadId: String?): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ACCOUNT, accountEmail)
                .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        newThreads.take(MAX_INDIVIDUAL).forEach { thread ->
            val contentIntent = PendingIntent.getActivity(
                context,
                ("mail-" + accountEmail + "-" + thread.id).hashCode(),
                mailIntent(thread.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(thread.fromName.ifBlank { thread.fromAddress })
                .setContentText(thread.subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText(thread.subject + "\n" + thread.snippet))
                .setGroup(groupKey)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            notifier.notify(("mail-" + accountEmail + "-" + thread.id).hashCode(), notification)
        }

        if (newThreads.size > 1) {
            val summaryIntent = PendingIntent.getActivity(
                context,
                ("summary-" + accountEmail).hashCode(),
                mailIntent(null),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val summary = NotificationCompat.Builder(context, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(accountEmail)
                .setContentText(newThreads.size.toString() + " new messages")
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(summaryIntent)
                .build()
            notifier.notify(("summary-" + accountEmail).hashCode(), summary)
        }
    }

    private const val MAX_INDIVIDUAL = 5
}
