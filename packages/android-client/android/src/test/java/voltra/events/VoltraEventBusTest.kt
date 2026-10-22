package voltra.events

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VoltraEventBusTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private data class Emitted(
        val event: VoltraEvent,
        val delivery: String,
    )

    private val emitted = mutableListOf<Emitted>()
    private val sink = VoltraEventSink { queued, delivery -> emitted += Emitted(queued.event, delivery) }

    private data class Undelivered(
        val event: VoltraEvent,
        val jsAttached: Boolean,
    )

    private val undelivered = mutableListOf<Undelivered>()

    private fun press(identifier: String) =
        VoltraEvent.Interaction("workout-1", System.currentTimeMillis(), identifier, "", "workout-1", 1)

    private fun dismissal() = VoltraEvent.NotificationDismissed("order-1", System.currentTimeMillis(), "order-1")

    private fun queued() = VoltraEventQueue(context).peekAll().map { it.event }

    @Before
    fun setUp() {
        context
            .getSharedPreferences(VoltraEventQueue.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        VoltraEventBus.resetForTests()
        VoltraEventBus.deliveryPolicy =
            InteractionDeliveryPolicy { _, event, jsAttached -> undelivered += Undelivered(event.event, jsAttached) }
    }

    @After
    fun tearDown() {
        VoltraEventBus.resetForTests()
    }

    @Test
    fun `with no React instance an event is persisted and handed to the policy`() {
        val event = press("pause")

        VoltraEventBus.send(context, event)

        assertEquals(listOf(event), queued())
        assertEquals(listOf(Undelivered(event, jsAttached = false)), undelivered)
    }

    @Test
    fun `an attached instance without an active listener emits nothing`() {
        VoltraEventBus.attach(sink)
        val event = press("pause")

        VoltraEventBus.send(context, event)

        assertTrue(emitted.isEmpty())
        assertEquals(listOf(event), queued())
        assertEquals(listOf(Undelivered(event, jsAttached = true)), undelivered)
    }

    @Test
    fun `activating a type replays its queued events in order and empties them from the queue`() {
        val first = press("pause")
        val second = press("resume")
        VoltraEventBus.send(context, first)
        VoltraEventBus.send(context, second)
        VoltraEventBus.attach(sink)

        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)

        assertEquals(listOf(Emitted(first, "replay"), Emitted(second, "replay")), emitted)
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `an active listener receives a new event live, without the policy`() {
        VoltraEventBus.attach(sink)
        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)
        val event = press("pause")

        VoltraEventBus.send(context, event)

        assertEquals(listOf(Emitted(event, "live")), emitted)
        assertTrue(undelivered.isEmpty())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `events of a type nobody listens to stay queued`() {
        val dismissed = dismissal()
        val pressed = press("pause")
        VoltraEventBus.send(context, dismissed)
        VoltraEventBus.send(context, pressed)
        VoltraEventBus.attach(sink)

        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)

        assertEquals(listOf(Emitted(pressed, "replay")), emitted)
        assertEquals(listOf<VoltraEvent>(dismissed), queued())
    }

    @Test
    fun `an event sent during a replay is emitted after the replayed ones`() {
        val first = press("one")
        val second = press("two")
        val live = press("live")
        VoltraEventBus.send(context, first)
        VoltraEventBus.send(context, second)
        var injected = false
        VoltraEventBus.attach { queued, delivery ->
            emitted += Emitted(queued.event, delivery)
            if (!injected) {
                injected = true
                VoltraEventBus.send(context, live)
            }
        }

        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)

        assertEquals(listOf(Emitted(first, "replay"), Emitted(second, "replay"), Emitted(live, "live")), emitted)
    }

    @Test
    fun `an event sent from another thread during a replay is emitted after the replayed ones`() {
        val first = press("one")
        val second = press("two")
        val live = press("live")
        VoltraEventBus.send(context, first)
        VoltraEventBus.send(context, second)
        var sender: Thread? = null
        VoltraEventBus.attach { queued, delivery ->
            synchronized(emitted) { emitted += Emitted(queued.event, delivery) }
            if (sender == null) {
                sender = Thread { VoltraEventBus.send(context, live) }.also { it.start() }
                // The sender blocks on the bus lock until this replay finishes.
                Thread.sleep(50)
            }
        }

        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)
        sender?.join(2_000)

        assertEquals(listOf(Emitted(first, "replay"), Emitted(second, "replay"), Emitted(live, "live")), emitted)
    }

    @Test
    fun `deactivating a type stops live emission and queues again`() {
        VoltraEventBus.attach(sink)
        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)
        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, false)
        val event = press("pause")

        VoltraEventBus.send(context, event)

        assertTrue(emitted.isEmpty())
        assertEquals(listOf(event), queued())
    }

    @Test
    fun `activation without an attached instance drains nothing`() {
        val event = press("pause")
        VoltraEventBus.send(context, event)

        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)

        assertTrue(emitted.isEmpty())
        assertEquals(listOf(event), queued())
    }

    @Test
    fun `detaching resets listeners so a new instance starts inactive`() {
        VoltraEventBus.attach(sink)
        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)
        VoltraEventBus.detach(sink)
        VoltraEventBus.attach(sink)
        val event = press("pause")

        VoltraEventBus.send(context, event)

        assertTrue(emitted.isEmpty())
        assertEquals(listOf(event), queued())
    }

    @Test
    fun `a stale instance detaching does not detach the current one`() {
        val stale = VoltraEventSink { _, _ -> }
        VoltraEventBus.attach(stale)
        VoltraEventBus.attach(sink)
        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)

        VoltraEventBus.detach(stale)
        VoltraEventBus.send(context, press("pause"))

        assertEquals(1, emitted.size)
    }

    @Test
    fun `an emit that throws loses that event only`() {
        val first = press("one")
        val second = press("two")
        VoltraEventBus.send(context, first)
        VoltraEventBus.send(context, second)
        VoltraEventBus.attach { queued, delivery ->
            if (queued.event == first) throw IllegalStateException("boom")
            emitted += Emitted(queued.event, delivery)
        }

        VoltraEventBus.setListenerActive(context, VoltraEvent.TYPE_INTERACTION, true)

        assertEquals(listOf(Emitted(second, "replay")), emitted)
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `send never starts an activity`() {
        VoltraEventBus.send(context, press("pause"))

        assertEquals(null, shadowOf(context).nextStartedActivity)
    }
}
