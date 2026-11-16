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
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun alarmPostsBeforeDeadlineAndDoesNotRepeatAfterReopeningTask() {
        assumeTrue(ReminderScheduler.canNotify(context) && ReminderScheduler.canScheduleExactly(context))
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = ReminderScheduler.readSettings(context)
        val startsAt = LocalDateTime.now().plusSeconds(6)
        val deadline = startsAt.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1).plusHours(1)
        val task = DayEntry(id = UUID.randomUUID().toString(), title = "Проверка напоминания",
            date = deadline.toLocalDate(), dueDate = deadline.toLocalDate(), dueTime = deadline.toLocalTime())
        val tag = "task:${task.id}"
        fun refresh() {
            val done = CountDownLatch(1)
            ReminderScheduler.refresh(context) { done.countDown() }
            assertTrue(done.await(8, TimeUnit.SECONDS))
        }
        fun posted() = manager.activeNotifications.singleOrNull { it.tag == tag }

        DiaryStore(context).use { store ->
            try {
                store.save(task)
                ReminderScheduler.saveSettings(context, ReminderSettings(tasks = true))
                refresh()
                val limit = SystemClock.elapsedRealtime() + 85_000
                while (posted() == null && SystemClock.elapsedRealtime() < limit) SystemClock.sleep(250)
                assertNotNull(posted())
                assertTrue(posted()!!.notification.extras.getCharSequence("android.text").toString().contains(deadline.toLocalTime().toString()))
                assertTrue(!LocalDateTime.now().isBefore(deadline.minusHours(1)))
                store.save(task.copy(done = true))
                refresh()
                assertNull(posted())
                store.save(task)
                refresh()
                assertNull(posted())
            } finally {
                store.deleteEntry(task.id)
                ReminderScheduler.saveSettings(context, settings)
                refresh()
            }
        }
    }
}
