package ru.tihiyden.diary

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

internal const val OPEN_REMINDER = "ru.tihiyden.diary.OPEN_REMINDER"
internal const val REMINDER_TASK_ID = "taskId"
internal const val REMINDER_MEDIA_ID = "mediaId"
private const val TASK_CHANNEL = "task_deadlines"
private const val WEEKEND_CHANNEL = "weekend_suggestion"
private const val TASK_TAG = "task:"
private const val DISPATCH_REMINDERS = "ru.tihiyden.diary.DISPATCH_REMINDERS"

internal object ReminderScheduler {
    private val worker = Executors.newSingleThreadExecutor()
    private fun preferences(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun readSettings(context: Context): ReminderSettings = with(preferences(context)) {
        ReminderSettings(getBoolean("tasks", false), getInt("leadHours", 1).coerceIn(1, 2), getBoolean("weekend", false))
    }

    fun saveSettings(context: Context, value: ReminderSettings) {
        preferences(context).edit().putBoolean("tasks", value.tasks).putInt("leadHours", value.leadHours)
            .putBoolean("weekend", value.weekend).apply()
        refresh(context)
    }

    fun canNotify(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun canScheduleExactly(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun refresh(context: Context, finished: () -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            try { reconcile(app) }
            catch (e: Exception) { Log.w("ReminderScheduler", "Could not update reminders", e) }
            finally { finished() }
        }
    }

    private fun reconcile(context: Context) {
        val settings = readSettings(context)
        val prefs = preferences(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val tasksAlarm = alarmIntent(context, 1)
        val weekendAlarm = alarmIntent(context, 2)
        if (!settings.tasks && !settings.weekend || !notifications.areNotificationsEnabled()) {
            alarms.cancel(tasksAlarm)
            alarms.cancel(weekendAlarm)
            notifications.cancelAll()
            return
        }

        notifications.createNotificationChannels(listOf(
            NotificationChannel(TASK_CHANNEL, "Сроки задач", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(WEEKEND_CHANNEL, "Совет на выходные", NotificationManager.IMPORTANCE_DEFAULT),
        ))
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val data = DiaryStore(context).use { it.read() }
        val tasksEnabled = settings.tasks && notifications.getNotificationChannel(TASK_CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
        val weekendEnabled = settings.weekend && notifications.getNotificationChannel(WEEKEND_CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
        val taskPlans = taskReminders(data.entries, now, zone, settings.leadHours)
        val active = data.entries.filter { it.kind == EntryKind.TASK && !it.done && it.dueDate != null }.associateBy { TASK_TAG + it.id }
        notifications.activeNotifications.filter { it.tag?.startsWith(TASK_TAG) == true }.forEach { posted ->
            val deadline = active[posted.tag]?.deadline?.toString()
            if (!tasksEnabled || deadline == null || prefs.getString(posted.tag, null) != deadline) {
                notifications.cancel(posted.tag, posted.id)
            }
        }

        val pending = taskPlans.filter { reminder ->
            !reminder.task.done && prefs.getString(TASK_TAG + reminder.task.id, null) != reminder.deadlineKey
        }
        if (tasksEnabled) {
            val dateFormat = DateTimeFormatter.ofPattern("d MMMM 'в' HH:mm", Russian)
            pending.filter { it.trigger <= now }.forEach { reminder ->
                val task = reminder.task
                val tag = TASK_TAG + task.id
                val body = "Срок · " + reminder.deadline.atZone(zone).format(dateFormat)
                val tap = openIntent(context, REMINDER_TASK_ID, task.id)
                if (post(context, notifications, TASK_CHANNEL, tag, task.title, body, tap)) {
                    prefs.edit().putString(tag, reminder.deadlineKey).commit()
                }
            }
        }
        val futureTask = pending.asSequence().filter { it.trigger > now }.minOfOrNull { it.trigger }
        setAlarm(alarms, tasksAlarm, futureTask.takeIf { tasksEnabled }, exact = canScheduleExactly(context))

        val choices = if (weekendEnabled) weekendSuggestions(data.media) else emptyList()
        if (!weekendEnabled) notifications.cancel(WEEKEND_CHANNEL, 1)
        val lastWeekend = prefs.getString("lastWeekend", null)
        val today = now.atZone(zone).toLocalDate()
        var saturday = weekendReminderDate(today, lastWeekend)
        var weekendTime = saturday.atTime(11, 0).atZone(zone).toInstant()
        if (choices.isNotEmpty() && weekendTime <= now) {
            val choice = choices.random()
            val kind = when (choice.kind) {
                MediaKind.BOOK -> "Книга"
                MediaKind.MOVIE -> "Фильм"
                MediaKind.SERIES -> "Сериал"
            }
            if (post(context, notifications, WEEKEND_CHANNEL, WEEKEND_CHANNEL, "Идея на выходные",
                    "$kind · ${choice.title}", openIntent(context, REMINDER_MEDIA_ID, choice.id))) {
                prefs.edit().putString("lastWeekend", saturday.toString()).commit()
            }
            saturday = saturday.plusWeeks(1)
            weekendTime = saturday.atTime(11, 0).atZone(zone).toInstant()
        }
        setAlarm(alarms, weekendAlarm, weekendTime.takeIf { choices.isNotEmpty() }, exact = false)

        val validKeys = data.entries.filter { it.dueDate != null }.mapTo(hashSetOf()) { TASK_TAG + it.id }
        val obsolete = prefs.all.keys.filter { it.startsWith(TASK_TAG) && it !in validKeys }
        if (obsolete.isNotEmpty()) prefs.edit().apply { obsolete.forEach { remove(it) } }.apply()
    }

    private fun alarmIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode, Intent(context, ReminderReceiver::class.java).setAction(DISPATCH_REMINDERS),
            PendingIntent.FLAG_IMMUTABLE)

    private fun setAlarm(manager: AlarmManager, intent: PendingIntent, at: Instant?, exact: Boolean) {
        if (at == null || at > Instant.ofEpochMilli(Long.MAX_VALUE)) {
            manager.cancel(intent)
            return
        }
        if (exact) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
                return
            } catch (_: SecurityException) {
                // Access can change between checking it and scheduling the alarm.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
    }

    private fun openIntent(context: Context, field: String, id: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_REMINDER)
            .setData(android.net.Uri.Builder().scheme("tihiyden").authority("reminder").appendPath(field).appendPath(id).build())
            .putExtra(field, id)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(context: Context, manager: NotificationManager, channel: String, tag: String, title: String, body: String, tap: PendingIntent): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return try {
            manager.notify(tag, 1, NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DISPATCH_REMINDERS, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val result = goAsync()
                ReminderScheduler.refresh(context) { result.finish() }
            }
        }
    }
}
