# ADR 0014: Android native-to-JavaScript event channel

Status: Accepted

The channel for [#314](https://github.com/callstackincubator/voltra/issues/314) and
[#316](https://github.com/callstackincubator/voltra/issues/316).

Amends [ADR 0012](0012-android-ongoing-notification-dismissal-state.md): a dismissal is now also
reported as a public, queued event. The internal "ended" signal it introduced stays as it is.

## Introduction

Android has no way to tell the app's JavaScript that something happened while it was not watching.
`addVoltraListener` warns "only supported on iOS" for every event name, the old Kotlin event bus was
removed in #354 because it could never hold an event, and the one native-to-JavaScript signal that
exists, `onOngoingNotificationEnded` (ADR 0012), is internal, unqueued and only reaches a running
app.

Two issues need such a channel: #314 (Dynamic Widget taps reaching the app) and #316 (ongoing
notification action presses, direct replies, dismissals and taps reaching the app). #316 is the
stricter one. It requires that an action press made while the app process is dead is persisted
before anything else happens, survives until a listener takes it, and can run JavaScript without an
Activity being started.

This ADR records the channel both issues share. It follows the queue rules #314 specifies and adds
the headless delivery #314 left as a seam and #316 requires. Producers of events, the receivers that
turn a press into an event, are not part of it.

## Context

- A notification action or a widget tap arrives in a `BroadcastReceiver`. The process can be
  reclaimed as soon as `onReceive` returns, so the event must be on disk before that.
- The app may be in one of four states when that happens: in the foreground with JavaScript
  running; in the background with JavaScript running; alive without a React instance (the process
  was started only for the broadcast); or still loading its bundle.
- React Native's `HeadlessJsTaskService` can run JavaScript without an Activity. It crashes by
  default if the app is in the foreground, needs `acquireWakeLockNow()` before a receiver returns,
  and on Android 8+ has to be started as a foreground service. Android 12+ lists "the user performs
  an action on a UI element related to your app ... notification, widget" as an exemption from the
  background foreground-service start restriction.
- The Android 12 trampoline rule forbids starting an Activity from a receiver or service used by a
  notification, so nothing in the channel may start one.
- iOS (ADR 0011) queues interactions natively and lets JavaScript pull them. #314 specifies a push
  model gated by a listener flag instead, in the style of the iOS render-failure listener. The
  Android channel follows #314.

## Decision

### One persisted queue for every Android event

`voltra.events.VoltraEventQueue` stores events in the `voltra_event_queue` SharedPreferences file as
one JSON array, written with `commit()` so the write is finished before `send` returns. Each event
gets a persisted monotonic `seq` and a random `eventId`; order is by `seq`, never by wall clock. The
queue keeps at most 200 events (dropping the oldest with a warning) and prunes events older than
7 days on every write and every pop. An entry that cannot be parsed is skipped; a file that cannot be
parsed at all reads as empty and is replaced by the next write.

All event types share the queue: `interaction`, `notificationDismissed` and `notificationTapped` from
#316, and later the widget events of #314. #314 proposed one file per event kind; #316 requires one
replay rule for all of them, and a shared queue is the simplest way to keep a single order.

`VoltraEvent` is a sealed class whose subclasses persist as flat maps of primitives. Nested
JavaScript fields, such as an interaction's `reply`, are flattened for storage and rebuilt when the
event is emitted.

### Push to an active listener, per event type

`VoltraEventBus.send` persists the event first. Then, under one lock:

- If a React instance is attached and JavaScript has a listener for the event's type, the bus
  drains the queue for the active types, oldest first, and emits each event through the codegen
  emitter `onVoltraEvent`. The event just sent is marked `delivery: 'live'`, earlier ones
  `'replay'`.
- Otherwise the event stays queued and is handed to the delivery policy (below).

JavaScript turns a type on with `setEventListenerActive(type, true)` when its first listener for
that type registers, and off when the last one is removed. Turning a type on drains every queued
event of that type. Events of types nobody listens to keep their place, so a `notificationDismissed`
listener cannot consume interactions meant for a listener that registers a moment later.

Sending, draining and emitting share one lock, so an event sent while a replay is running is emitted
after the replayed ones. An event leaves the queue immediately before it is emitted. Delivery is at
most once: the only loss window is a process death between that pop and the JavaScript callback.
Exactly-once delivery would need a JavaScript acknowledgement, which was rejected for tap events in
#314 and is not worth the API for the events here either.

The module attaches itself to the bus in `initialize()` and detaches in `invalidate()`. Attaching
resets every type to inactive, because listeners belong to a JavaScript runtime and a new runtime has
none yet.

### Delivery policy: when to wake JavaScript

`InteractionDeliveryPolicy` is the seam #314 describes. The default, `HeadlessJsDeliveryPolicy`,
starts `VoltraHeadlessEventService` only when all of these hold:

- the event is an `interaction` (dismissals and taps never wake JavaScript; they wait for the next
  launch);
- no React instance with the Voltra module is attached in this process (an app whose JavaScript is
  alive drains the queue itself once its listener is registered);
- the app is not in the foreground (a headless task in a resumed app crashes).

`VoltraHeadlessEventService` is a `HeadlessJsTaskService`. The receiver calls
`acquireWakeLockNow()` and `startForegroundService` before it returns. The service promotes itself to
the foreground with type `shortService` on Android 14+, which needs no type-specific permission and
is stopped by the system after at most about three minutes. It shows a silent, low-importance
notification on its own `voltra_background_events` channel while it runs, because a foreground
service must.

The task, `VoltraEvents`, is registered by `@use-voltra/android-client` when the package is imported.
It does no work itself. Starting it loads the app's bundle, and the app's listener, registered at
module scope with `registerInteractionHandler` or `addVoltraListener`, activates delivery and drains
the queue. The task then waits until every promise those listeners returned has settled, so the
process stays up while they run, for example while they call `updateAndroidOngoingNotification`. If
no `interaction` listener is registered at module scope, the task warns and finishes, and the event
stays queued.

### JavaScript API

`addVoltraListener('interaction' | 'notificationDismissed' | 'notificationTapped', listener)` on
Android returns a real subscription. Every other event name keeps warning and returning a no-op, and
these three names do the same on iOS. `registerInteractionHandler(handler)` registers an `interaction`
listener at module scope and returns its unsubscribe function. Every event carries `eventId`, `seq`
and `delivery` besides its own fields. A listener that throws or rejects is logged and does not stop
delivery to the others.

The docs recommend one listener per type, registered in `index.js` before
`AppRegistry.registerComponent`, that fans out within the app: a listener added later by a screen
does not see events that were already delivered.

`VoltraView` on Android stops subscribing to `interaction`. Android views never emitted interaction
events, and a view's listener would now drain notification actions meant for the app's own listener.
Its `onInteraction` prop still warns, and its event type is now `VoltraViewInteractionEvent`.

### Rejected

- **A pull API like iOS (ADR 0011).** It avoids the listener flag but needs a JavaScript-side
  scheduler and an `AppState` trigger, and #314 already specifies the push model with the guarantees
  #316 needs.
- **One queue per event kind.** It would let a listener for one type drain while another waits, but
  the per-type activation above gives that within one queue and keeps one order.
- **Headless delivery for dismissals and taps.** Nothing in #316 needs JavaScript to run for them
  immediately, and every headless start shows a foreground-service notification.
- **Starting the headless task when the app is in the foreground.** React Native forbids it by
  default, and allowing it would run listeners twice over: once in the headless task and once in
  the app.

## Consequences

- Events produced while the app is not running are delivered, in order, to the first listener for
  their type once one is registered.
- An action press with no running app starts the app's JavaScript in the background and keeps it
  alive until the listeners finish. The user sees a short, silent "Handling notification action"
  notification while that happens.
- Apps that set `enableNotifications` get two more permissions, `FOREGROUND_SERVICE` and
  `WAKE_LOCK`, and the `VoltraHeadlessEventService` service in their manifest.
- A listener must be registered at module scope to receive headless deliveries; one registered in a
  component only sees events once that component mounts.
- `VoltraView`'s `onInteraction` callback type changes from `VoltraInteractionEvent` to
  `VoltraViewInteractionEvent` (the same fields as before), because `VoltraInteractionEvent` now
  describes Android notification actions.
- #314 can add widget taps by producing `VoltraEvent` subclasses; the queue, the policy seam and the
  JavaScript API are already in place.
