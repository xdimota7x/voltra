package voltra.events

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class HeadlessJsDeliveryPolicyTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var starts = 0

    private fun policy(foreground: Boolean = false) =
        HeadlessJsDeliveryPolicy(isAppInForeground = { foreground }, startHeadless = { starts += 1 })

    private fun queued(event: VoltraEvent) = VoltraQueuedEvent("e-1", 1, event)

    private val press = VoltraEvent.Interaction("workout-1", 1, "pause", "", "workout-1", 1)

    @Test
    fun `a press with no React instance in a background process starts headless delivery`() {
        policy().onUndelivered(context, queued(press), jsAttached = false)

        assertEquals(1, starts)
    }

    @Test
    fun `a press while JavaScript is alive waits for its listener`() {
        policy().onUndelivered(context, queued(press), jsAttached = true)

        assertEquals(0, starts)
    }

    @Test
    fun `a press while the app is in the foreground never starts a headless task`() {
        policy(foreground = true).onUndelivered(context, queued(press), jsAttached = false)

        assertEquals(0, starts)
    }

    @Test
    fun `dismissals and taps only wait in the queue`() {
        policy().onUndelivered(context, queued(VoltraEvent.NotificationDismissed("a", 1, "a")), jsAttached = false)
        policy().onUndelivered(context, queued(VoltraEvent.NotificationTapped("a", 1, "a")), jsAttached = false)

        assertEquals(0, starts)
    }

    @Test
    fun `a failing service start leaves the event queued without throwing`() {
        HeadlessJsDeliveryPolicy(
            isAppInForeground = { false },
            startHeadless = { throw IllegalStateException("not allowed") },
        ).onUndelivered(context, queued(press), jsAttached = false)
    }

    @Test
    @Config(sdk = [34])
    fun `the default start launches the headless service as a foreground service`() {
        VoltraHeadlessEventService.start(context)

        val intent = shadowOf(context).nextStartedService
        assertNotNull(intent)
        assertEquals(VoltraHeadlessEventService::class.java.name, intent.component?.className)
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
