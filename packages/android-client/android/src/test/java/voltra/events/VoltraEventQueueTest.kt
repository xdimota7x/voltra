package voltra.events

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoltraEventQueueTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs by lazy { context.getSharedPreferences(VoltraEventQueue.PREFS_NAME, Context.MODE_PRIVATE) }
    private var now = 1_790_000_000_000L

    private fun queue() = VoltraEventQueue(context) { now }

    private fun press(
        identifier: String,
        timestamp: Long = now,
    ) = VoltraEvent.Interaction("workout-1", timestamp, identifier, "", "workout-1", 1)

    private fun dismissal(id: String) = VoltraEvent.NotificationDismissed(id, now, id)

    private val allTypes =
        setOf(
            VoltraEvent.TYPE_INTERACTION,
            VoltraEvent.TYPE_NOTIFICATION_DISMISSED,
            VoltraEvent.TYPE_NOTIFICATION_TAPPED,
        )

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `events pop in enqueue order with increasing seq`() {
        val first = queue().enqueue(press("pause"))
        val second = queue().enqueue(press("pause"))
        val third = queue().enqueue(dismissal("workout-1"))

        assertEquals(listOf(first.seq + 1, first.seq + 2), listOf(second.seq, third.seq))
        assertEquals(first, queue().popFirst(allTypes))
        assertEquals(second, queue().popFirst(allTypes))
        assertEquals(third, queue().popFirst(allTypes))
        assertNull(queue().popFirst(allTypes))
    }

    @Test
    fun `two presses of the same action are two events with distinct ids`() {
        val first = queue().enqueue(press("pause"))
        val second = queue().enqueue(press("pause"))

        assertEquals(2, queue().peekAll().size)
        assert(first.eventId != second.eventId)
    }

    @Test
    fun `an event is on disk when enqueue returns`() {
        queue().enqueue(press("pause"))

        // A fresh instance reads only what was committed.
        assertEquals(1, JSONArray(prefs.getString("events", null)).length())
        assertEquals(listOf("pause"), queue().peekAll().map { (it.event as VoltraEvent.Interaction).identifier })
    }

    @Test
    fun `seq keeps increasing after the queue is emptied`() {
        val first = queue().enqueue(press("pause"))
        queue().popFirst(allTypes)

        val next = queue().enqueue(press("pause"))

        assertEquals(first.seq + 1, next.seq)
    }

    @Test
    fun `popFirst takes only the requested types and leaves the rest in place`() {
        val dismissed = queue().enqueue(dismissal("order-1"))
        val pressed = queue().enqueue(press("pause"))

        assertEquals(pressed, queue().popFirst(setOf(VoltraEvent.TYPE_INTERACTION)))
        assertEquals(listOf(dismissed), queue().peekAll())
    }

    @Test
    fun `the queue keeps the newest 200 events`() {
        repeat(VoltraEventQueue.MAX_EVENTS + 5) { index -> queue().enqueue(press("press-$index")) }

        val entries = queue().peekAll()

        assertEquals(VoltraEventQueue.MAX_EVENTS, entries.size)
        assertEquals("press-5", (entries.first().event as VoltraEvent.Interaction).identifier)
        assertEquals("press-204", (entries.last().event as VoltraEvent.Interaction).identifier)
    }

    @Test
    fun `events older than seven days are pruned on write and on pop`() {
        queue().enqueue(press("old", timestamp = now - VoltraEventQueue.MAX_AGE_MS - 1))
        queue().enqueue(press("recent"))
        now += 1

        assertEquals("recent", (queue().popFirst(allTypes)?.event as VoltraEvent.Interaction).identifier)
        assertNull(queue().popFirst(allTypes))

        queue().enqueue(press("aging"))
        now += VoltraEventQueue.MAX_AGE_MS + 1
        queue().enqueue(press("fresh"))
        assertEquals(listOf("fresh"), queue().peekAll().map { (it.event as VoltraEvent.Interaction).identifier })
    }

    @Test
    fun `an unreadable entry is skipped without losing the others`() {
        val good = queue().enqueue(press("pause"))
        val array = JSONArray(prefs.getString("events", null))
        array.put("not an object")
        array.put(JSONObject().put("eventId", "x").put("seq", 99).put("type", "interaction"))
        prefs.edit().putString("events", array.toString()).commit()

        assertEquals(listOf(good), queue().peekAll())
    }

    @Test
    fun `an unparsable queue file reads as empty and is replaced on the next write`() {
        prefs.edit().putString("events", "{not json").commit()

        assertEquals(emptyList<VoltraQueuedEvent>(), queue().peekAll())
        queue().enqueue(press("pause"))
        assertEquals(1, queue().peekAll().size)
    }
}
