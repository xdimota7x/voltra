package voltra.events

/**
 * An event Voltra reports from native code to the app's JavaScript (ADR 0014).
 *
 * Every event is written to [VoltraEventQueue] before anything tries to deliver it, so it survives
 * process death. [toMap] is the persisted form: flat, primitives only, because it is stored as one
 * JSON object per event. Nested JavaScript fields such as an interaction's `reply` are flattened
 * here and rebuilt by [VoltraQueuedEvent.toJavaScriptMap].
 */
sealed class VoltraEvent {
    abstract val type: String
    abstract val source: String

    /** Epoch milliseconds, taken when native code observed the event. */
    abstract val timestamp: Long

    abstract fun toMap(): Map<String, Any?>

    /**
     * An action button on an ongoing notification was pressed (ADR 0014). Mirrors the iOS
     * `interaction` event: [identifier] is the action's `actionId`, [payload] its `payload` or `""`.
     */
    data class Interaction(
        override val source: String,
        override val timestamp: Long,
        val identifier: String,
        val payload: String,
        val notificationId: String,
        /** The notification revision the pressed button belonged to. */
        val revision: Int,
        val replyKey: String? = null,
        val replyText: String? = null,
        /** `"freeForm"` or `"choice"`; set together with [replyKey] and [replyText]. */
        val replySource: String? = null,
    ) : VoltraEvent() {
        override val type: String = TYPE_INTERACTION

        override fun toMap(): Map<String, Any?> =
            buildMap {
                put(KEY_TYPE, type)
                put(KEY_SOURCE, source)
                put(KEY_TIMESTAMP, timestamp)
                put(KEY_IDENTIFIER, identifier)
                put(KEY_PAYLOAD, payload)
                put(KEY_NOTIFICATION_ID, notificationId)
                put(KEY_REVISION, revision)
                if (replyKey != null && replyText != null && replySource != null) {
                    put(KEY_REPLY_KEY, replyKey)
                    put(KEY_REPLY_TEXT, replyText)
                    put(KEY_REPLY_SOURCE, replySource)
                }
            }
    }

    /** The user swiped an ongoing notification away, or its timeout removed it. */
    data class NotificationDismissed(
        override val source: String,
        override val timestamp: Long,
        val notificationId: String,
    ) : VoltraEvent() {
        override val type: String = TYPE_NOTIFICATION_DISMISSED

        override fun toMap(): Map<String, Any?> =
            mapOf(
                KEY_TYPE to type,
                KEY_SOURCE to source,
                KEY_TIMESTAMP to timestamp,
                KEY_NOTIFICATION_ID to notificationId,
            )
    }

    /** The user tapped the body of an ongoing notification and the app's Activity received it. */
    data class NotificationTapped(
        override val source: String,
        override val timestamp: Long,
        val notificationId: String,
    ) : VoltraEvent() {
        override val type: String = TYPE_NOTIFICATION_TAPPED

        override fun toMap(): Map<String, Any?> =
            mapOf(
                KEY_TYPE to type,
                KEY_SOURCE to source,
                KEY_TIMESTAMP to timestamp,
                KEY_NOTIFICATION_ID to notificationId,
            )
    }

    companion object {
        const val TYPE_INTERACTION = "interaction"
        const val TYPE_NOTIFICATION_DISMISSED = "notificationDismissed"
        const val TYPE_NOTIFICATION_TAPPED = "notificationTapped"

        internal const val KEY_TYPE = "type"
        internal const val KEY_SOURCE = "source"
        internal const val KEY_TIMESTAMP = "timestamp"
        internal const val KEY_IDENTIFIER = "identifier"
        internal const val KEY_PAYLOAD = "payload"
        internal const val KEY_NOTIFICATION_ID = "notificationId"
        internal const val KEY_REVISION = "revision"
        internal const val KEY_REPLY_KEY = "replyKey"
        internal const val KEY_REPLY_TEXT = "replyText"
        internal const val KEY_REPLY_SOURCE = "replySource"

        /**
         * Reads an event back from its persisted map, or returns null when the map is not a
         * complete event of a known type. A null entry is skipped by the queue, never fatal.
         */
        fun fromMap(map: Map<String, Any?>): VoltraEvent? {
            val source = map[KEY_SOURCE] as? String ?: return null
            val timestamp = (map[KEY_TIMESTAMP] as? Number)?.toLong() ?: return null
            return when (map[KEY_TYPE]) {
                TYPE_INTERACTION -> {
                    val replyKey = map[KEY_REPLY_KEY] as? String
                    val replyText = map[KEY_REPLY_TEXT] as? String
                    val replySource = map[KEY_REPLY_SOURCE] as? String
                    val hasReply = replyKey != null && replyText != null && replySource != null
                    Interaction(
                        source = source,
                        timestamp = timestamp,
                        identifier = map[KEY_IDENTIFIER] as? String ?: return null,
                        payload = map[KEY_PAYLOAD] as? String ?: "",
                        notificationId = map[KEY_NOTIFICATION_ID] as? String ?: return null,
                        revision = (map[KEY_REVISION] as? Number)?.toInt() ?: return null,
                        replyKey = replyKey.takeIf { hasReply },
                        replyText = replyText.takeIf { hasReply },
                        replySource = replySource.takeIf { hasReply },
                    )
                }

                TYPE_NOTIFICATION_DISMISSED -> {
                    NotificationDismissed(
                        source = source,
                        timestamp = timestamp,
                        notificationId = map[KEY_NOTIFICATION_ID] as? String ?: return null,
                    )
                }

                TYPE_NOTIFICATION_TAPPED -> {
                    NotificationTapped(
                        source = source,
                        timestamp = timestamp,
                        notificationId = map[KEY_NOTIFICATION_ID] as? String ?: return null,
                    )
                }

                else -> {
                    null
                }
            }
        }
    }
}
