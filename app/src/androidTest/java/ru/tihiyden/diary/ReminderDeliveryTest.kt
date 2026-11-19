package ru.tihiyden.diary

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun refresh() {
        val done = CountDownLatch(1)
        ReminderScheduler.refresh(context) { done.countDown() }
        assertTrue(done.await(8, TimeUnit.SECONDS))
    }

    private fun awaitRemoved(vararg tags: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val until = SystemClock.elapsedRealtime() + 10_000
        while (manager.activeNotifications.any { it.tag in tags } && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(100)
        }
        assertFalse(manager.activeNotifications.any { it.tag in tags })
    }

    @Test fun advanceAlarmUsesStartEvenWhenDeadlineHasAnotherDateAndTime() {
        assumeTrue(ReminderScheduler.canNotify(context) && ReminderScheduler.canScheduleExactly(context))
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = ReminderScheduler.readSettings(context)
        val startsAt = LocalDateTime.now().plusSeconds(6)
        val deadline = startsAt.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1).plusHours(1)
        val task = DayEntry(id = UUID.randomUUID().toString(), title = "Проверка напоминания",
            date = deadline.toLocalDate(), time = deadline.toLocalTime().toString(),
            dueDate = deadline.toLocalDate().plusDays(3), dueTime = LocalTime.of(23, 45))
        val startOnly = DayEntry(title = "Задача со временем начала", date = deadline.toLocalDate(), time = deadline.toLocalTime().toString())
        fun posted(entry: DayEntry) = manager.activeNotifications.singleOrNull { it.tag == reminderKey(entry.id, ReminderPhase.ADVANCE) }

        DiaryStore(context).use { store ->
            try {
                store.save(task)
                store.save(startOnly)
                ReminderScheduler.saveSettings(context, ReminderSettings(tasks = true))
                refresh()
                val limit = SystemClock.elapsedRealtime() + 85_000
                while ((posted(task) == null || posted(startOnly) == null) && SystemClock.elapsedRealtime() < limit) SystemClock.sleep(250)
                val postedDeadline = requireNotNull(posted(task)).notification.extras.getCharSequence("android.text").toString()
                val postedStart = requireNotNull(posted(startOnly)).notification.extras.getCharSequence("android.text").toString()
                assertTrue(postedDeadline.startsWith("Начало") && postedDeadline.endsWith(deadline.toLocalTime().toString()))
                assertTrue(postedStart.startsWith("Начало") && postedStart.endsWith(startOnly.time))
                assertTrue(!LocalDateTime.now().isBefore(deadline.minusHours(1)))
                store.save(task.copy(done = true))
                store.save(startOnly.copy(done = true))
                refresh()
                awaitRemoved(reminderKey(task.id, ReminderPhase.ADVANCE), reminderKey(startOnly.id, ReminderPhase.ADVANCE))
                assertNull(posted(task))
                assertNull(posted(startOnly))
                store.save(task)
                store.save(startOnly)
                refresh()
                assertNull(posted(task))
                assertNull(posted(startOnly))
            } finally {
                store.deleteEntry(task.id)
                store.deleteEntry(startOnly.id)
                ReminderScheduler.saveSettings(context, settings)
                refresh()
            }
        }
    }

    @Test fun startNotificationArrivesAfterAdvanceAndBothRemainIndependent() {
        assumeTrue(ReminderScheduler.canNotify(context) && ReminderScheduler.canScheduleExactly(context))
        val settings = ReminderScheduler.readSettings(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val start = LocalDateTime.now().plusSeconds(6).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val task = DayEntry(title = "Начало задачи", date = start.toLocalDate(), time = start.toLocalTime().toString(),
            dueDate = start.toLocalDate().plusDays(2))
        fun posted(phase: ReminderPhase) = manager.activeNotifications.singleOrNull { it.tag == reminderKey(task.id, phase) }
        DiaryStore(context).use { store ->
            try {
                store.save(task)
                ReminderScheduler.saveSettings(context, ReminderSettings(tasks = true))
                refresh()
                assertNotNull(posted(ReminderPhase.ADVANCE))
                assertNull(posted(ReminderPhase.START))
                val until = SystemClock.elapsedRealtime() + 85_000
                while (posted(ReminderPhase.START) == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(250)
                val notification = requireNotNull(posted(ReminderPhase.START)).notification
                assertTrue(notification.extras.getCharSequence("android.text").toString().startsWith("Пора начинать"))
                assertTrue(!LocalDateTime.now().isBefore(start))
                assertNotNull(posted(ReminderPhase.ADVANCE))
                refresh()
                assertEquals(2, manager.activeNotifications.count { it.tag?.startsWith("task:${task.id}:") == true })
                store.save(task.copy(done = true))
                refresh()
                awaitRemoved(reminderKey(task.id, ReminderPhase.START), reminderKey(task.id, ReminderPhase.ADVANCE))
                assertNull(posted(ReminderPhase.START))
                assertNull(posted(ReminderPhase.ADVANCE))
                store.save(task)
                refresh()
                assertNull(posted(ReminderPhase.START))
            } finally {
                store.deleteEntry(task.id)
                ReminderScheduler.saveSettings(context, settings)
                refresh()
            }
        }
    }

    @Test fun lateAlarmPostsOnlyTheStartPhaseForPreviouslyScheduledTask() {
        assumeTrue(ReminderScheduler.canNotify(context))
        val settings = ReminderScheduler.readSettings(context)
        val deadline = LocalDateTime.now().minusMinutes(15).truncatedTo(ChronoUnit.MINUTES)
        val task = DayEntry(title = "Просроченное напоминание", date = deadline.toLocalDate(),
            time = deadline.toLocalTime().toString(), dueDate = deadline.toLocalDate().plusDays(2))
        val older = task.copy(id = UUID.randomUUID().toString(), time = deadline.minusMinutes(5).toLocalTime().toString(),
            date = deadline.minusMinutes(5).toLocalDate(), dueDate = deadline.minusMinutes(5).toLocalDate())
        val manager = context.getSystemService(NotificationManager::class.java)
        DiaryStore(context).use { store ->
            try {
                ReminderScheduler.saveSettings(context, ReminderSettings(tasks = true))
                refresh()
                store.save(task)
                store.save(older)
                val trigger = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val preferences = context.getSharedPreferences("reminders", android.content.Context.MODE_PRIVATE)
                assertTrue(preferences.edit().putLong("armedTask", trigger).commit())
                refresh()
                val posted = manager.activeNotifications.single { it.tag == reminderKey(task.id, ReminderPhase.START) }
                assertTrue(posted.notification.extras.getCharSequence("android.text").toString().startsWith("Пора начинать"))
                assertFalse(manager.activeNotifications.any { it.tag == reminderKey(task.id, ReminderPhase.ADVANCE) })
                assertFalse(manager.activeNotifications.any { it.tag?.startsWith("task:${older.id}:") == true })
            } finally {
                store.deleteEntry(task.id)
                store.deleteEntry(older.id)
                ReminderScheduler.saveSettings(context, settings)
                refresh()
            }
        }
    }
}
