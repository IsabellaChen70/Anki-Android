// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar

/**
 * Feature 2 (Vantage): a minimal due-cards reminder for the MCAT study cards.
 *
 * It deliberately REUSES AnkiDroid's existing infrastructure rather than building a
 * parallel notification system:
 *  - the Android [AlarmManager] scheduling primitive (the same one AnkiDroid's
 *    review-reminder [com.ichi2.anki.services.AlarmManagerService] uses), and
 *  - the shared [Channel.REVIEW_REMINDERS] notification channel.
 * It adds NO new notification channel and does NOT add a new scope type to
 * AnkiDroid's review-reminders subsystem (which is Global/per-deck only).
 *
 * On fire it reads the Vantage (mcat-tagged) due-card count -- the same due count
 * the Vantage dashboard shows for this MCAT collection -- and posts a notification
 * ONLY when at least one card is due. Honesty-first: it is silent (and clears any
 * stale reminder) when nothing is due, and the number is a real query, never an
 * estimate.
 */
class VantageDueReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val due = vantageDueCount()
                Timber.i("VantageDueReminder: mcat due=%d", due)
                postOrCancel(context, due)
            } catch (e: Exception) {
                Timber.w(e, "VantageDueReminder failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Broadcast action that fires the reminder (used by the scheduled alarm). */
        const val ACTION_FIRE = "com.ichi2.anki.VANTAGE_DUE_REMINDER"

        private const val NOTIF_TAG = "com.ichi2.anki.vantage_due_reminder"
        private const val NOTIF_ID = 0x7A6E
        private const val ALARM_HOUR = 18 // 6pm local, daily

        /**
         * Vantage's due-card count: the SAME formula the dashboard uses (review/day-learn
         * due today in queue 2/3, plus intraday learning in queue 1), scoped to the
         * mcat-tagged study cards. Pure read; changes no calculation.
         */
        suspend fun vantageDueCount(): Int =
            withCol {
                val today = sched.today
                val due =
                    db.queryScalar(
                        "select count() from cards c join notes n on c.nid = n.id " +
                            "where n.tags like '%mcat::%' and c.queue in (2,3) and c.due <= $today",
                    )
                val lrn =
                    db.queryScalar(
                        "select count() from cards c join notes n on c.nid = n.id " +
                            "where n.tags like '%mcat::%' and c.queue = 1",
                    )
                due + lrn
            }

        private fun postOrCancel(
            context: Context,
            due: Int,
        ) {
            val manager = context.getSystemService<NotificationManager>() ?: return
            // Reuse AnkiDroid's own channel setup so the shared REVIEW_REMINDERS
            // channel exists even when this receiver runs in a process spawned just
            // for the alarm/broadcast (idempotent; adds no new channel).
            setupNotificationChannels(context)
            if (due <= 0) {
                manager.cancel(NOTIF_TAG, NOTIF_ID) // nothing due -> stay silent
                return
            }
            val open =
                Intent(context, VantageDashboardActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    NOTIF_ID,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val text = "$due card${if (due == 1) "" else "s"} due to review today"
            val notification =
                NotificationCompat
                    .Builder(context, Channel.REVIEW_REMINDERS.id)
                    .setSmallIcon(R.drawable.ic_star_notify)
                    .setContentTitle("Vantage")
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .build()
            manager.notify(NOTIF_TAG, NOTIF_ID, notification)
        }

        /**
         * Schedule (idempotently) a daily inexact repeating alarm that fires this
         * receiver. Reuses the Android AlarmManager primitive; INEXACT + RTC so no
         * exact-alarm permission is needed. Called when the Vantage dashboard opens.
         */
        fun schedule(context: Context) {
            try {
                val am = context.getSystemService<AlarmManager>() ?: return
                val cal =
                    Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, ALARM_HOUR)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
                    }
                am.setInexactRepeating(
                    AlarmManager.RTC,
                    cal.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    fireIntent(context),
                )
                Timber.i("VantageDueReminder scheduled for %tR daily", cal)
            } catch (e: Exception) {
                Timber.w(e, "Failed to schedule VantageDueReminder")
            }
        }

        private fun fireIntent(context: Context): PendingIntent {
            val intent =
                Intent(context, VantageDueReminderReceiver::class.java).apply {
                    action = ACTION_FIRE
                }
            return PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
