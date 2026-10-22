package voltra.events

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** An event as it sits in the queue: [seq] orders it, [eventId] identifies it to JavaScript. */
data class VoltraQueuedEvent(
    val eventId: String,
    val seq: Long,
    val event: VoltraEvent,
) {
    /**
     * The event as JavaScript receives it: the persisted fields plus `eventId`, `seq` and
     * [delivery], with an interaction's flattened reply fields rebuilt as a nested `reply` object.
     */
    fun toJavaScriptMap(delivery: String): Map<String, Any?> {
        val persisted = event.toMap()
        val replyKeys = setOf(VoltraEvent.KEY_REPLY_KEY, VoltraEvent.KEY_REPLY_TEXT, VoltraEvent.KEY_REPLY_SOURCE)
        return buildMap {
            persisted.filterKeys { it !in replyKeys }.forEach { (key, value) -> put(key, value) }
            if (event is VoltraEvent.Interaction && event.replyKey != null) {
                put(
                    "reply",
                    mapOf(
                        "key" to event.replyKey,
                        "text" to event.replyText,
                        "source" to event.replySource,
                    ),
                )
            }
            put("eventId", eventId)
            put("seq", seq.toDouble())
            put("delivery", delivery)
        }
    }
}

/**
 * The persisted FIFO behind [VoltraEventBus] (ADR 0014).
 *
 * Every write uses `commit()`, not `apply()`: a broadcast receiver's process can be reclaimed as soon
 * as `onReceive` returns, so the event must be on disk before the caller goes on. Ordering is by a
 * persisted monotonic `seq`, never by wall clock. The queue keeps at most [MAX_EVENTS] entries,
 * dropping the oldest, and prunes entries older than [MAX_AGE_MS] on every write and every pop. An
 * entry that cannot be parsed is skipped without discarding the rest.
 *
 * Not thread-safe on its own; [VoltraEventBus] serialises every call.
 */
class VoltraEventQueue(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "VoltraEventQueue"
        const val PREFS_NAME = "voltra_event_queue"
        private const val KEY_EVENTS = "events"
        private const val KEY_NEXT_SEQ = "nextSeq"
        private const val KEY_EVENT_ID = "eventId"
        private const val KEY_SEQ = "seq"
        const val MAX_EVENTS = 200
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persists [event] and returns it with its assigned `seq` and `eventId`. */
    fun enqueue(event: VoltraEvent): VoltraQueuedEvent {
        val seq = prefs.getLong(KEY_NEXT_SEQ, 1L)
        val queued = VoltraQueuedEvent(eventId = UUID.randomUUID().toString(), seq = seq, event = event)
        val entries = prune(read()) + queued
        val overflow = entries.size - MAX_EVENTS
        if (overflow > 0) {
            Log.w(TAG, "Event queue is full; dropping the $overflow oldest event(s)")
        }
        write(entries.drop(overflow.coerceAtLeast(0)), nextSeq = seq + 1)
        return queued
    }

    /**
     * Removes and returns the oldest event whose type is in [types], or null when there is none.
     * Events of other types keep their place.
     */
    fun popFirst(types: Set<String>): VoltraQueuedEvent? {
        val entries = prune(read())
        val first = entries.firstOrNull { it.event.type in types }
        write(if (first == null) entries else entries - first)
        return first
    }

    /** Every queued event, oldest first, without removing anything. */
    fun peekAll(): List<VoltraQueuedEvent> = prune(read())

    private fun prune(entries: List<VoltraQueuedEvent>): List<VoltraQueuedEvent> {
        val cutoff = clock() - MAX_AGE_MS
        return entries.filter { it.event.timestamp >= cutoff }
    }

    private fun read(): List<VoltraQueuedEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        val array =
            try {
                JSONArray(raw)
            } catch (error: JSONException) {
                Log.w(TAG, "Event queue is unreadable; starting empty", error)
                return emptyList()
            }
        val entries = mutableListOf<VoltraQueuedEvent>()
        for (index in 0 until array.length()) {
            val entry = parseEntry(array.opt(index))
            if (entry == null) {
                Log.w(TAG, "Skipping an unreadable queued event at index $index")
                continue
            }
            entries += entry
        }
        return entries.sortedBy { it.seq }
    }

    private fun parseEntry(value: Any?): VoltraQueuedEvent? {
        val json = value as? JSONObject ?: return null
        val eventId = json.optString(KEY_EVENT_ID).takeIf { it.isNotEmpty() } ?: return null
        if (!json.has(KEY_SEQ)) return null
        val seq = json.optLong(KEY_SEQ)
        val map = json.keys().asSequence().associateWith { key -> json.opt(key) }
        val event = VoltraEvent.fromMap(map) ?: return null
        return VoltraQueuedEvent(eventId = eventId, seq = seq, event = event)
    }

    private fun write(
        entries: List<VoltraQueuedEvent>,
        nextSeq: Long? = null,
    ) {
        val array = JSONArray()
        entries.forEach { entry ->
            val json = JSONObject()
            json.put(KEY_EVENT_ID, entry.eventId)
            json.put(KEY_SEQ, entry.seq)
            entry.event.toMap().forEach { (key, value) -> json.put(key, value) }
            array.put(json)
        }
        val editor = prefs.edit().putString(KEY_EVENTS, array.toString())
        nextSeq?.let { editor.putLong(KEY_NEXT_SEQ, it) }
        if (!editor.commit()) {
            Log.e(TAG, "Failed to persist the event queue")
        }
    }
}
