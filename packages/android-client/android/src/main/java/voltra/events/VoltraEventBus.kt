package voltra.events

import android.content.Context
import android.util.Log

/** Where [VoltraEventBus] emits events: the live TurboModule of the current React instance. */
fun interface VoltraEventSink {
    /** [delivery] is `"live"` for an event emitted as it was sent, `"replay"` for one drained from the queue. */
    fun emit(
        event: VoltraQueuedEvent,
        delivery: String,
    )
}

/**
 * Decides what happens to an event that was persisted but could not be emitted, because no
 * JavaScript listener for its type is active in this process (ADR 0014). The queue already holds the
 * event, so a policy only chooses whether to wake JavaScript; doing nothing leaves the event for the
 * next listener.
 */
fun interface InteractionDeliveryPolicy {
    /** [jsAttached] is true while a React instance with the Voltra module is alive in this process. */
    fun onUndelivered(
        context: Context,
        event: VoltraQueuedEvent,
        jsAttached: Boolean,
    )
}

/**
 * The Android native-to-JavaScript event channel (ADR 0014).
 *
 * [send] persists first, then emits the event straight away when a listener for its type is active,
 * otherwise hands it to [deliveryPolicy]. JavaScript turns a type on with [setListenerActive], which
 * drains every queued event of that type in `seq` order. Emitting and draining hold the same lock,
 * so an event sent while a drain runs is emitted after the replayed ones. An event leaves the queue
 * immediately before it is emitted: delivery is at most once.
 *
 * Process-wide because events are produced by broadcast receivers that have no reference to the
 * TurboModule; the module attaches itself while it is alive.
 */
object VoltraEventBus {
    private const val TAG = "VoltraEventBus"
    const val DELIVERY_LIVE = "live"
    const val DELIVERY_REPLAY = "replay"

    private val lock = Any()
    private var sink: VoltraEventSink? = null
    private val activeTypes = mutableSetOf<String>()

    @Volatile
    var deliveryPolicy: InteractionDeliveryPolicy = HeadlessJsDeliveryPolicy()

    /** Persists [event] (synchronously, before returning) and delivers it if a listener is active. */
    fun send(
        context: Context,
        event: VoltraEvent,
    ) {
        val undelivered: VoltraQueuedEvent?
        val jsAttached: Boolean
        synchronized(lock) {
            val queue = VoltraEventQueue(context)
            val queued = queue.enqueue(event)
            jsAttached = sink != null
            if (jsAttached && event.type in activeTypes) {
                drainLocked(queue, liveEventId = queued.eventId)
                undelivered = null
            } else {
                undelivered = queued
            }
        }
        undelivered?.let { queued ->
            try {
                deliveryPolicy.onUndelivered(context, queued, jsAttached)
            } catch (error: Exception) {
                Log.e(TAG, "Delivery policy failed for ${queued.event.type}; the event stays queued", error)
            }
        }
    }

    /**
     * Turns emission of [type] on or off for the attached React instance. Turning it on drains every
     * queued event of that type. Ignored while no instance is attached.
     */
    fun setListenerActive(
        context: Context,
        type: String,
        active: Boolean,
    ) {
        synchronized(lock) {
            if (!active) {
                activeTypes.remove(type)
                return
            }
            if (sink == null) {
                Log.w(TAG, "Ignoring listener activation for $type: no React instance is attached")
                return
            }
            activeTypes.add(type)
            drainLocked(VoltraEventQueue(context), liveEventId = null)
        }
    }

    /** Called by the module when its React instance starts. Listeners start inactive. */
    fun attach(sink: VoltraEventSink) {
        synchronized(lock) {
            this.sink = sink
            activeTypes.clear()
        }
    }

    /** Called by the module when its React instance is torn down. */
    fun detach(sink: VoltraEventSink) {
        synchronized(lock) {
            if (this.sink !== sink) return
            this.sink = null
            activeTypes.clear()
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            sink = null
            activeTypes.clear()
            deliveryPolicy = HeadlessJsDeliveryPolicy()
        }
    }

    private fun drainLocked(
        queue: VoltraEventQueue,
        liveEventId: String?,
    ) {
        val target = sink ?: return
        while (true) {
            val next = queue.popFirst(activeTypes.toSet()) ?: return
            val delivery = if (next.eventId == liveEventId) DELIVERY_LIVE else DELIVERY_REPLAY
            try {
                target.emit(next, delivery)
            } catch (error: Exception) {
                // At most once: the event already left the queue and is not put back.
                Log.e(TAG, "Failed to emit ${next.event.type} ${next.eventId}", error)
            }
        }
    }
}
