import { AppRegistry, Platform } from 'react-native'

import { getNativeVoltraAndroid } from './native/NativeVoltraAndroid.js'
import type { EventSubscription } from './types.js'

export type BasicVoltraEvent = {
  source: string
  timestamp: number
}

export type VoltraActivityState = 'active' | 'dismissed' | 'pending' | 'stale' | 'ended' | string
export type VoltraActivityTokenReceivedEvent = BasicVoltraEvent & {
  type: 'activityTokenReceived'
  activityName: string
  pushToken: string
}
export type VoltraActivityPushToStartTokenReceivedEvent = BasicVoltraEvent & {
  type: 'activityPushToStartTokenReceived'
  pushToStartToken: string
}
export type VoltraActivityUpdateEvent = BasicVoltraEvent & {
  type: 'stateChange'
  activityName: string
  activityState: VoltraActivityState
}

/**
 * How an Android event reached the listener: `'live'` when it was emitted as it happened, `'replay'`
 * when it waited in the native queue first (the app was not running, or no listener was registered).
 */
export type VoltraEventDelivery = 'live' | 'replay'

/** Fields every event from the Android event queue carries (ADR 0014). */
export type VoltraQueuedEventFields = {
  /** Unique per event; the same id is never delivered twice in one process. */
  eventId: string
  /** Monotonic per install; events are delivered in ascending `seq`. */
  seq: number
  delivery: VoltraEventDelivery
}

export type VoltraInteractionReply = {
  /** The `remoteInput.key` of the action. */
  key: string
  text: string
  /** `'freeForm'` when the user typed the text, `'choice'` when they picked one of the `choices`. */
  source: 'freeForm' | 'choice'
}

/** The user pressed an event action (`actionId`) on an Android ongoing notification. */
export type VoltraInteractionEvent = BasicVoltraEvent &
  VoltraQueuedEventFields & {
    type: 'interaction'
    /** The action's `actionId`. */
    identifier: string
    /** The action's `payload`, or `''`. */
    payload: string
    /** The ongoing notification that owned the action. */
    notificationId: string
    /** The notification revision the pressed button belonged to; compare it to ignore a stale press. */
    revision: number
    reply?: VoltraInteractionReply
  }

/** The user swiped an Android ongoing notification away, or its `timeoutMs` removed it. */
export type VoltraNotificationDismissedEvent = BasicVoltraEvent &
  VoltraQueuedEventFields & {
    type: 'notificationDismissed'
    notificationId: string
  }

/** The user tapped the body of an Android ongoing notification. */
export type VoltraNotificationTappedEvent = BasicVoltraEvent &
  VoltraQueuedEventFields & {
    type: 'notificationTapped'
    notificationId: string
  }

const noopSubscription: EventSubscription = {
  remove: () => {},
}

export type VoltraEventMap = {
  activityTokenReceived: VoltraActivityTokenReceivedEvent
  activityPushToStartTokenReceived: VoltraActivityPushToStartTokenReceivedEvent
  stateChange: VoltraActivityUpdateEvent
  interaction: VoltraInteractionEvent
  notificationDismissed: VoltraNotificationDismissedEvent
  notificationTapped: VoltraNotificationTappedEvent
}

const ANDROID_EVENT_TYPES = ['interaction', 'notificationDismissed', 'notificationTapped'] as const
type AndroidEventType = (typeof ANDROID_EVENT_TYPES)[number]
type AndroidEvent = VoltraEventMap[AndroidEventType]

/** Name of the headless task the native channel starts when an action press finds no running app. */
export const VOLTRA_HEADLESS_TASK_NAME = 'VoltraEvents'

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null

const hasQueuedEventFields = (value: Record<string, unknown>): boolean =>
  typeof value.source === 'string' &&
  typeof value.timestamp === 'number' &&
  typeof value.eventId === 'string' &&
  typeof value.seq === 'number' &&
  (value.delivery === 'live' || value.delivery === 'replay')

const isReply = (value: unknown): value is VoltraInteractionReply =>
  isRecord(value) &&
  typeof value.key === 'string' &&
  typeof value.text === 'string' &&
  (value.source === 'freeForm' || value.source === 'choice')

export function isVoltraInteractionEvent(value: unknown): value is VoltraInteractionEvent {
  return (
    isRecord(value) &&
    value.type === 'interaction' &&
    hasQueuedEventFields(value) &&
    typeof value.identifier === 'string' &&
    typeof value.payload === 'string' &&
    typeof value.notificationId === 'string' &&
    typeof value.revision === 'number' &&
    (value.reply === undefined || isReply(value.reply))
  )
}

export function isVoltraNotificationDismissedEvent(value: unknown): value is VoltraNotificationDismissedEvent {
  return (
    isRecord(value) &&
    value.type === 'notificationDismissed' &&
    hasQueuedEventFields(value) &&
    typeof value.notificationId === 'string'
  )
}

export function isVoltraNotificationTappedEvent(value: unknown): value is VoltraNotificationTappedEvent {
  return (
    isRecord(value) &&
    value.type === 'notificationTapped' &&
    hasQueuedEventFields(value) &&
    typeof value.notificationId === 'string'
  )
}

const isAndroidEvent = (value: unknown): value is AndroidEvent =>
  isVoltraInteractionEvent(value) || isVoltraNotificationDismissedEvent(value) || isVoltraNotificationTappedEvent(value)

const isAndroidEventType = (event: string): event is AndroidEventType =>
  (ANDROID_EVENT_TYPES as readonly string[]).includes(event)

// Registrations are entries, not functions, so the same function added twice counts twice.
type Registration = { listener: (event: AndroidEvent) => unknown }

const registrations = new Map<AndroidEventType, Set<Registration>>()
let nativeSubscription: EventSubscription | null = null
const pendingHandlers = new Set<Promise<unknown>>()

const dispatch = (raw: unknown) => {
  if (!isAndroidEvent(raw)) {
    console.warn('[Voltra] Ignoring a malformed event from the native event queue.', raw)
    return
  }
  const current = registrations.get(raw.type)
  if (!current) return
  // Snapshot: a listener that subscribes or unsubscribes during dispatch does not change who gets this event.
  for (const registration of [...current]) {
    try {
      const result = registration.listener(raw)
      if (result && typeof (result as Promise<unknown>).then === 'function') {
        const pending = Promise.resolve(result as Promise<unknown>).catch((error: unknown) => {
          console.error(`[Voltra] A '${raw.type}' listener rejected.`, error)
        })
        pendingHandlers.add(pending)
        void pending.finally(() => pendingHandlers.delete(pending))
      }
    } catch (error) {
      console.error(`[Voltra] A '${raw.type}' listener threw.`, error)
    }
  }
}

const subscribeAndroid = (event: AndroidEventType, listener: (event: AndroidEvent) => unknown): EventSubscription => {
  let nativeModule: ReturnType<typeof getNativeVoltraAndroid>
  try {
    nativeModule = getNativeVoltraAndroid()
  } catch {
    console.warn(`[Voltra] Event '${event}' needs the Voltra native module. Returning no-op subscription.`)
    return noopSubscription
  }
  if (typeof nativeModule.onVoltraEvent !== 'function' || typeof nativeModule.setEventListenerActive !== 'function') {
    console.warn(`[Voltra] Event '${event}' needs a newer native build. Returning no-op subscription.`)
    return noopSubscription
  }

  // Subscribe to the emitter before activating: activation drains the queue straight away.
  if (!nativeSubscription) {
    nativeSubscription = nativeModule.onVoltraEvent(dispatch)
  }
  const forType = registrations.get(event) ?? new Set<Registration>()
  registrations.set(event, forType)
  const registration: Registration = { listener }
  forType.add(registration)
  if (forType.size === 1) {
    nativeModule.setEventListenerActive(event, true)
  }

  let removed = false
  return {
    remove: () => {
      if (removed) return
      removed = true
      forType.delete(registration)
      if (forType.size === 0) {
        registrations.delete(event)
        nativeModule.setEventListenerActive(event, false)
      }
      if (registrations.size === 0 && nativeSubscription) {
        nativeSubscription.remove()
        nativeSubscription = null
      }
    },
  }
}

/**
 * Subscribes to a Voltra event.
 *
 * On Android, `interaction`, `notificationDismissed` and `notificationTapped` come from a native
 * queue: events that happened while the app was not running are replayed, in order, to the first
 * listener for their type, and each event is delivered once. Register one listener per type at
 * module scope (for example in `index.js`) and fan out from there; a listener added later by a
 * screen does not see events that were already delivered. Every other event name is iOS only and
 * returns a no-op subscription on Android.
 */
export function addVoltraListener<K extends keyof VoltraEventMap>(
  event: K,
  listener: (event: VoltraEventMap[K]) => void
): EventSubscription {
  if (Platform.OS === 'android' && isAndroidEventType(event)) {
    return subscribeAndroid(event, listener as (event: AndroidEvent) => unknown)
  }

  console.warn(
    isAndroidEventType(event)
      ? `[Voltra] Event '${event}' is only supported on Android. Returning no-op subscription.`
      : `[Voltra] Event '${event}' is only supported on iOS. Returning no-op subscription.`
  )
  return noopSubscription
}

/**
 * Registers a handler for `interaction` events at module scope, before `AppRegistry.registerComponent`,
 * so a press that starts the app (or runs it in the background) is handled without a screen
 * mounting. The handler may return a promise; Voltra keeps a background run alive until it settles.
 * Returns a function that unregisters it.
 */
export function registerInteractionHandler(
  handler: (event: VoltraInteractionEvent) => void | Promise<void>
): () => void {
  const subscription = addVoltraListener('interaction', handler)
  return () => subscription.remove()
}

/** Resolves once every listener promise started so far, including ones they start meanwhile, has settled. */
const waitForPendingHandlers = async () => {
  // Let events the native drain already emitted reach their listeners first.
  await new Promise<void>((resolve) => setTimeout(resolve, 0))
  while (pendingHandlers.size > 0) {
    await Promise.allSettled([...pendingHandlers])
  }
}

/**
 * The headless task the native channel starts for an action press while no app is running
 * (ADR 0014). Loading the bundle already registered the app's module-scope listeners, which drained
 * the queue; the task only keeps the process alive until their work is done.
 */
export const runVoltraHeadlessTask = async (): Promise<void> => {
  if (!registrations.has('interaction')) {
    console.warn(
      "[Voltra] A notification action started the app in the background, but no 'interaction' listener is registered at module scope. The event stays queued until one is."
    )
  }
  await waitForPendingHandlers()
}

let headlessTaskRegistered = false

/** Registers {@link runVoltraHeadlessTask} with React Native. Called once by the package entry point. */
export function registerVoltraHeadlessTask(): void {
  if (Platform.OS !== 'android' || headlessTaskRegistered) return
  headlessTaskRegistered = true
  AppRegistry.registerHeadlessTask(VOLTRA_HEADLESS_TASK_NAME, () => runVoltraHeadlessTask)
}
