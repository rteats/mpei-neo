package com.rteats.mpeineo.data

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rteats.mpeineo.MainActivity
import com.rteats.mpeineo.R
import java.util.concurrent.TimeUnit

/**
 * Background mail notifications are opt-in. Store only an IMAP UID checkpoint,
 * never a message body, cookie, password, or entire inbox in preferences.
 * IMAP UID and UIDVALIDITY together identify messages within one mailbox.
 */
class MailNotificationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("mail_notifications_v1", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    var intervalMinutes: Int
        get() = prefs.getInt("interval_minutes", 30).takeIf { it in ALLOWED_INTERVALS } ?: 30
        set(value) {
            require(value in ALLOWED_INTERVALS)
            prefs.edit().putInt("interval_minutes", value).apply()
        }

    @Synchronized
    fun checkpoint(account: String, messages: List<MailSummary>): List<MailSummary> {
        if (messages.isEmpty()) return emptyList()
        val validity = messages.first().uidValidity
        val highest = messages.maxOf { it.uid }
        val storedAccount = prefs.getString("account", null)
        val storedValidity = prefs.getLong("uid_validity", -1L)
        val lastUid = prefs.getLong("last_uid", -1L)

        // First successful poll or mailbox reset establishes baseline.
        val newMessages = if (storedAccount == account &&
            storedValidity == validity && lastUid >= 0L
        ) mailNewUids(messages, lastUid, validity)
        else emptyList()

        prefs.edit()
            .putString("account", account)
            .putLong("uid_validity", validity)
            .putLong("last_uid", maxOf(if (storedValidity == validity && storedAccount == account) lastUid else -1L, highest))
            .commit()
        return newMessages.sortedBy { it.uid }
    }

    fun clearAccount() {
        prefs.edit()
            .putBoolean("enabled", false)
            .remove("account")
            .remove("uid_validity")
            .remove("last_uid")
            .commit()
    }

    companion object {
        val ALLOWED_INTERVALS = setOf(15, 30, 60)
    }
}

object MailNotificationScheduler {
    private const val UNIQUE_WORK = "mpei_imap_notification_poll"
    const val CHANNEL_ID = "mpei_mail_new_messages"

    fun setEnabled(context: Context, value: Boolean) {
        val prefs = MailNotificationPreferences(context)
        prefs.enabled = value
        if (value) schedule(context)
        else WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    fun changeInterval(context: Context, intervalMinutes: Int) {
        MailNotificationPreferences(context).intervalMinutes = intervalMinutes
        if (MailNotificationPreferences(context).enabled) schedule(context)
    }

    fun stopAndClear(context: Context) {
        MailNotificationPreferences(context).clearAccount()
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    private fun schedule(context: Context) {
        val minutes = MailNotificationPreferences(context).intervalMinutes.toLong()
        val request = PeriodicWorkRequestBuilder<MailNotificationWorker>(
            minutes, TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Новые письма МЭИ",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Уведомления о новых входящих письмах"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }

    @Suppress("MissingPermission")
    internal fun show(context: Context, newMessages: List<MailSummary>) {
        if (newMessages.isEmpty() || !canNotify(context)) return
        ensureChannel(context)
        val latest = newMessages.last()
        val pending = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_MAIL, true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val count = newMessages.size
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_mail)
            .setContentTitle(if (count == 1) "Новое письмо МЭИ" else "Новых писем МЭИ: " + count)
            .setContentText(latest.sender + " · " + latest.subject)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (count == 1) latest.sender + " · " + latest.subject
                    else "Последнее: " + latest.sender + " · " + latest.subject,
                ),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup("mpei_mail")
            .build()
        NotificationManagerCompat.from(context).notify(9821, notification)
    }
}

class MailNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = MailNotificationPreferences(applicationContext)
        if (!prefs.enabled) return Result.success()
        val repository = MailRepository(applicationContext)
        val credentials = repository.credentials.load() ?: return Result.success()

        return try {
            // Headers only; READ_ONLY + PEEK does not mark messages as seen.
            val messages = repository.listInbox(credentials, maxMessages = 60)
            val stillEnabled = MailNotificationPreferences(applicationContext).enabled
            val stillAccount = repository.credentials.load()?.username == credentials.username
            if (stillEnabled && stillAccount) {
                val newMessages = prefs.checkpoint(credentials.username, messages)
                MailNotificationScheduler.show(applicationContext, newMessages)
            }
            Result.success()
        } catch (_: Exception) {
            // Keep the prior UID checkpoint so transient IMAP/TLS errors never
            // generate false notifications. WorkManager will retry later.
            Result.retry()
        }
    }
}

/** Pure UID delta for deterministic tests and worker checkpointing. */
internal fun mailNewUids(
    messages: List<MailSummary>,
    lastUid: Long,
    uidValidity: Long,
): List<MailSummary> =
    messages.filter { it.uidValidity == uidValidity && it.uid > lastUid }
        .sortedBy { it.uid }
