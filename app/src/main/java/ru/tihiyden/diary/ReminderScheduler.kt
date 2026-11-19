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
private const val DISPATCH_TEST = "ru.tihiyden.diary.DISPATCH_REMINDER_TEST"
private const val ARMED_TASK = "armedTask"

internal fun reminderKey(taskId: String, phase: ReminderPhase): String = "$TASK_TAG$taskId:${phase.name}"

internal object ReminderScheduler {
    private val worker = Executors.newSingleThreadExecutor()
    private fun preferences(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun readSettings(context: Context): ReminderSettings = with(preferences(context)) {
        ReminderSettings(getBoolean("tasks", false), getInt("leadHours", 1).coerceIn(1, 2), getBoolean("weekend", false))
    }

    fun saveSettings(context: Context, value: ReminderSettings) {
        preferences(context).edit().putBoolean("tasks", value.tasks).putInt("leadHours", value.leadHours)
            .putBoolean("weekend", value.weekend).apply {
                if (!value.tasks) remove(ARMED_TASK)
            }.apply()
        refresh(context)
    }

    fun canNotify(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun canScheduleExactly(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun taskChannelEnabled(context: Context): Boolean =
        channelEnabled(context.getSystemService(NotificationManager::class.java), TASK_CHANNEL)

    fun taskChannelSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, TASK_CHANNEL)

    fun scheduleTest(context: Context): Instant? {
        val notifications = context.getSystemService(NotificationManager::class.java)
        createChannels(notifications)
        if (!notifications.areNotificationsEnabled() || !channelEnabled(notifications, TASK_CHANNEL)) return null
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = Instant.now().plusSeconds(60)
        setAlarm(alarms, alarmIntent(context, 4, DISPATCH_TEST), at, exact = canScheduleExactly(context))
        return at
    }

    fun deliverTest(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!readSettings(context).tasks) return
        createChannels(manager)
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        post(context, manager, TASK_CHANNEL, "reminder-test", "Проверка уведомлений",
            "Телефон получил проверочное напоминание.", openApp)
    }

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
        val taskFallback = alarmIntent(context, 3)
        if (!settings.tasks && !settings.weekend || !notifications.areNotificationsEnabled()) {
            alarms.cancel(tasksAlarm)
            alarms.cancel(taskFallback)
            alarms.cancel(weekendAlarm)
            prefs.edit().remove(ARMED_TASK).apply()
            notifications.cancelAll()
            return
        }

        createChannels(notifications)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val data = DiaryStore(context).use { it.read() }
        val tasksEnabled = settings.tasks && channelEnabled(notifications, TASK_CHANNEL)
        val weekendEnabled = settings.weekend && channelEnabled(notifications, WEEKEND_CHANNEL)
        val armedAt = prefs.getLong(ARMED_TASK, 0).takeIf { it != 0L }?.let(Instant::ofEpochMilli)
        val taskPlans = taskReminders(data.entries, now, zone, settings.leadHours, armedAt)
        val timedTasks = data.entries.filter { it.reminderTarget != null }.flatMap { task ->
            ReminderPhase.entries.map { phase -> reminderKey(task.id, phase) to task }
        }.toMap()
        val legacyDelivery = prefs.edit()
        data.entries.forEach { task ->
            val previous = prefs.getString(TASK_TAG + task.id, null)
            if (previous != null && previous == task.reminderTarget?.toString()) {
                legacyDelivery.putString(reminderKey(task.id, ReminderPhase.ADVANCE), previous)
            }
        }
        legacyDelivery.apply()
        notifications.activeNotifications.filter { it.tag?.startsWith(TASK_TAG) == true }.forEach { posted ->
            val target = timedTasks[posted.tag]?.takeUnless { it.done }?.reminderTarget?.toString()
            if (!tasksEnabled || target == null || prefs.getString(posted.tag, null) != target) {
                notifications.cancel(posted.tag, posted.id)
            }
        }

        val pending = taskPlans.filter { reminder ->
            !reminder.task.done && prefs.getString(reminderKey(reminder.task.id, reminder.phase), null) != reminder.targetKey
        }
        if (tasksEnabled) {
            val dateFormat = DateTimeFormatter.ofPattern("d MMMM 'в' HH:mm", Russian)
            pending.filter { it.trigger <= now }.forEach { reminder ->
                val task = reminder.task
                val tag = reminderKey(task.id, reminder.phase)
                val label = if (reminder.phase == ReminderPhase.ADVANCE) "Начало" else "Пора начинать"
                val body = "$label · " + reminder.target.atZone(zone).format(dateFormat)
                val tap = openIntent(context, REMINDER_TASK_ID, task.id)
                if (post(context, notifications, TASK_CHANNEL, tag, task.title, body, tap)) {
                    prefs.edit().putString(tag, reminder.targetKey).commit()
                }
            }
        }
        val futureTask = pending.asSequence().filter { it.trigger > now }.minOfOrNull { it.trigger }
        val next = futureTask?.takeIf { tasksEnabled && it <= Instant.ofEpochMilli(Long.MAX_VALUE) }
        if (next == null) {
            alarms.cancel(tasksAlarm)
            alarms.cancel(taskFallback)
            prefs.edit().remove(ARMED_TASK).apply()
        } else {
            val exact = canScheduleExactly(context) && setExact(alarms, tasksAlarm, next)
            if (!exact) alarms.cancel(tasksAlarm)
            // An inexact alarm survives revocation of exact-alarm access. Posting either one reconciles both.
            val fallbackAt = if (exact) next.plusSeconds(10 * 60) else next
            setAlarm(alarms, taskFallback, fallbackAt, exact = false)
            prefs.edit().putLong(ARMED_TASK, next.toEpochMilli()).apply()
        }

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

        val obsolete = prefs.all.keys.filter { it.startsWith(TASK_TAG) && it !in timedTasks }
        if (obsolete.isNotEmpty()) prefs.edit().apply { obsolete.forEach { remove(it) } }.apply()
    }

    private fun createChannels(manager: NotificationManager) =
        manager.createNotificationChannels(listOf(
            NotificationChannel(TASK_CHANNEL, "Напоминания о задачах", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(WEEKEND_CHANNEL, "Совет на выходные", NotificationManager.IMPORTANCE_DEFAULT),
        ))

    private fun channelEnabled(manager: NotificationManager, channel: String): Boolean =
        manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun alarmIntent(context: Context, requestCode: Int, action: String = DISPATCH_REMINDERS): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode, Intent(context, ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE)

    private fun setAlarm(manager: AlarmManager, intent: PendingIntent, at: Instant?, exact: Boolean) {
        if (at == null || at > Instant.ofEpochMilli(Long.MAX_VALUE)) {
            manager.cancel(intent)
            return
        }
        if (exact && setExact(manager, intent, at)) return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
    }

    private fun setExact(manager: AlarmManager, intent: PendingIntent, at: Instant): Boolean = try {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        true
    } catch (_: SecurityException) {
        false
    }

    private fun openIntent(context: Context, field: String, id: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_REMINDER)
            .setData(android.net.Uri.Builder().scheme("tihiyden").authority("reminder").appendPath(field).appendPath(id).build())
            .putExtra(field, id)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(context: Context, manager: NotificationManager, channel: String, tag: String, title: String, body: String, tap: PendingIntent): Boolean {
        if (!manager.areNotificationsEnabled() || !channelEnabled(manager, channel)) return false
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
            DISPATCH_TEST -> ReminderScheduler.deliverTest(context)
            DISPATCH_REMINDERS, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val result = goAsync()
                ReminderScheduler.refresh(context) { result.finish() }
            }
        }
    }
}
