package voltra.events

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoltraEventTest {
    private val context = RuntimeEnvironment.getApplication()

    private val reply =
        VoltraEvent.Interaction(
            source = "order-123",
            timestamp = 1_790_000_000_000,
            identifier = "tip",
            payload = "{\"route\":\"orders/123\",\"note\":\"say \\\"hi\\\"\"}",
            notificationId = "order-123",
            revision = 3,
            replyKey = "tip",
            replyText = "15%\nthanks for the \"fast\" ride / see you",
            replySource = "choice",
        )

    @Before
    fun clearQueue() {
        context
            .getSharedPreferences(
                VoltraEventQueue.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            ).edit()
            .clear()
            .commit()
    }

    @Test
    fun `every event type round-trips through toMap and fromMap`() {
        val events =
            listOf(
                VoltraEvent.Interaction("workout-1", 1_790_000_000_000, "pause", "", "workout-1", 7),
                reply,
                VoltraEvent.NotificationDismissed("order-123", 1_790_000_000_001, "order-123"),
                VoltraEvent.NotificationTapped("order-123", 1_790_000_000_002, "order-123"),
            )

        events.forEach { event -> assertEquals(event, VoltraEvent.fromMap(event.toMap())) }
    }

    @Test
    fun `an interaction with a multi-line reply and an escaped payload round-trips through the persisted JSON`() {
        val persisted = JSONObject(reply.toMap()).toString()

        // Pinned: org.json escapes '/' as '\/' and quotes as '\"'; a newline becomes '\n'.
        assertEquals(
            """{"type":"interaction","source":"order-123","timestamp":1790000000000,"identifier":"tip",""" +
                """"payload":"{\"route\":\"orders\/123\",\"note\":\"say \\\"hi\\\"\"}",""" +
                """"notificationId":"order-123","revision":3,"replyKey":"tip",""" +
                """"replyText":"15%\nthanks for the \"fast\" ride \/ see you","replySource":"choice"}""",
            persisted,
        )
        val json = JSONObject(persisted)
        val map = json.keys().asSequence().associateWith { json.get(it) }
        assertEquals(reply, VoltraEvent.fromMap(map))
    }

    @Test
    fun `an interaction survives the queue unchanged`() {
        // A current timestamp: the queue prunes anything older than seven days.
        val recent = reply.copy(timestamp = System.currentTimeMillis())
        VoltraEventQueue(context).enqueue(recent)

        val popped = VoltraEventQueue(context).popFirst(setOf(VoltraEvent.TYPE_INTERACTION))

        assertEquals(recent, popped?.event)
    }

    @Test
    fun `a plain press has no reply fields`() {
        val press = VoltraEvent.Interaction("workout-1", 1, "pause", "", "workout-1", 7)

        val map = press.toMap()

        assertFalse(map.containsKey("replyKey"))
        assertFalse(map.containsKey("replyText"))
        assertFalse(map.containsKey("replySource"))
    }

    @Test
    fun `a partial reply is dropped rather than half-read`() {
        val map = reply.toMap() - "replySource"

        val event = VoltraEvent.fromMap(map) as VoltraEvent.Interaction

        assertNull(event.replyKey)
        assertNull(event.replyText)
        assertNull(event.replySource)
    }

    @Test
    fun `an unknown type or a missing required field reads as null`() {
        assertNull(VoltraEvent.fromMap(mapOf("type" to "somethingNew", "source" to "a", "timestamp" to 1L)))
        assertNull(VoltraEvent.fromMap(VoltraEvent.NotificationDismissed("a", 1, "a").toMap() - "notificationId"))
        assertNull(VoltraEvent.fromMap(VoltraEvent.Interaction("a", 1, "pause", "", "a", 1).toMap() - "revision"))
    }

    @Test
    fun `the JavaScript map nests the reply and carries the queue fields`() {
        val queued = VoltraQueuedEvent(eventId = "e-1", seq = 4, event = reply)

        val map = queued.toJavaScriptMap(VoltraEventBus.DELIVERY_REPLAY)

        assertEquals(mapOf("key" to "tip", "text" to reply.replyText, "source" to "choice"), map["reply"])
        assertFalse(map.containsKey("replyKey"))
        assertEquals("e-1", map["eventId"])
        assertEquals(4.0, map["seq"])
        assertEquals("replay", map["delivery"])
        assertEquals("interaction", map["type"])
        assertEquals(3, map["revision"])
    }
}
