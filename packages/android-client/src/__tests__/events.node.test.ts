const mockPlatform = { OS: 'android' as string }
const mockRegisterHeadlessTask = jest.fn()

jest.mock('react-native', () => ({
  Platform: mockPlatform,
  AppRegistry: { registerHeadlessTask: (...args: unknown[]) => mockRegisterHeadlessTask(...args) },
}))

jest.mock('../native/NativeVoltraAndroid.js', () => ({
  getNativeVoltraAndroid: jest.fn(),
}))

import { getNativeVoltraAndroid, type Spec } from '../native/NativeVoltraAndroid.js'

type EventsModule = typeof import('../events.js')

const mockedGetNativeVoltraAndroid = jest.mocked(getNativeVoltraAndroid)

const interaction = (overrides: Record<string, unknown> = {}) => ({
  type: 'interaction',
  source: 'workout-1',
  timestamp: 1790000000000,
  identifier: 'pause',
  payload: '',
  notificationId: 'workout-1',
  revision: 7,
  eventId: 'e-1',
  seq: 1,
  delivery: 'live',
  ...overrides,
})

const dismissal = (overrides: Record<string, unknown> = {}) => ({
  type: 'notificationDismissed',
  source: 'order-123',
  timestamp: 1790000000000,
  notificationId: 'order-123',
  eventId: 'e-2',
  seq: 2,
  delivery: 'replay',
  ...overrides,
})

describe('Android events', () => {
  let events: EventsModule
  let emit: (event: unknown) => void
  let setEventListenerActive: jest.Mock
  let nativeRemove: jest.Mock
  let onVoltraEvent: jest.Mock
  let warn: jest.SpyInstance
  let error: jest.SpyInstance

  beforeEach(() => {
    jest.isolateModules(() => {
      events = require('../events.js')
    })
    mockPlatform.OS = 'android'
    setEventListenerActive = jest.fn()
    nativeRemove = jest.fn()
    onVoltraEvent = jest.fn((listener: (event: unknown) => void) => {
      emit = listener
      return { remove: nativeRemove }
    })
    mockedGetNativeVoltraAndroid.mockReturnValue({ onVoltraEvent, setEventListenerActive } as unknown as Spec)
    warn = jest.spyOn(console, 'warn').mockImplementation(() => {})
    error = jest.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    warn.mockRestore()
    error.mockRestore()
    mockRegisterHeadlessTask.mockReset()
  })

  describe('addVoltraListener', () => {
    it('subscribes to the emitter before activating the type', () => {
      events.addVoltraListener('interaction', () => {})

      expect(onVoltraEvent).toHaveBeenCalledTimes(1)
      expect(setEventListenerActive).toHaveBeenCalledWith('interaction', true)
      expect(onVoltraEvent.mock.invocationCallOrder[0]).toBeLessThan(setEventListenerActive.mock.invocationCallOrder[0])
      expect(warn).not.toHaveBeenCalled()
    })

    it('activates each type on its first listener and deactivates it on its last', () => {
      const first = events.addVoltraListener('interaction', () => {})
      const second = events.addVoltraListener('interaction', () => {})
      const dismissed = events.addVoltraListener('notificationDismissed', () => {})

      expect(setEventListenerActive.mock.calls).toEqual([
        ['interaction', true],
        ['notificationDismissed', true],
      ])

      first.remove()
      first.remove()
      expect(setEventListenerActive).toHaveBeenCalledTimes(2)
      second.remove()
      expect(setEventListenerActive).toHaveBeenLastCalledWith('interaction', false)
      expect(nativeRemove).not.toHaveBeenCalled()

      dismissed.remove()
      expect(setEventListenerActive).toHaveBeenLastCalledWith('notificationDismissed', false)
      expect(nativeRemove).toHaveBeenCalledTimes(1)
    })

    it('counts the same function registered twice as two registrations', () => {
      const listener = jest.fn()
      const first = events.addVoltraListener('interaction', listener)
      events.addVoltraListener('interaction', listener)

      first.remove()

      expect(setEventListenerActive).not.toHaveBeenCalledWith('interaction', false)
      emit(interaction())
      expect(listener).toHaveBeenCalledTimes(1)
    })

    it('delivers each event to every listener of its type only', () => {
      const pressA = jest.fn()
      const pressB = jest.fn()
      const dismissed = jest.fn()
      events.addVoltraListener('interaction', pressA)
      events.addVoltraListener('interaction', pressB)
      events.addVoltraListener('notificationDismissed', dismissed)

      emit(interaction())
      emit(dismissal())

      expect(pressA).toHaveBeenCalledWith(interaction())
      expect(pressB).toHaveBeenCalledWith(interaction())
      expect(dismissed).toHaveBeenCalledWith(dismissal())
      expect(pressA).toHaveBeenCalledTimes(1)
    })

    it('delivers replayed events in the order the native queue emits them', () => {
      const received: number[] = []
      events.addVoltraListener('interaction', (event) => received.push(event.seq))

      emit(interaction({ seq: 1, delivery: 'replay' }))
      emit(interaction({ seq: 2, delivery: 'replay' }))
      emit(interaction({ seq: 3, delivery: 'live' }))

      expect(received).toEqual([1, 2, 3])
    })

    it('keeps delivering after a listener throws or rejects', async () => {
      const after = jest.fn()
      events.addVoltraListener('interaction', () => {
        throw new Error('boom')
      })
      events.addVoltraListener('interaction', (async () => {
        throw new Error('async boom')
      }) as unknown as () => void)
      events.addVoltraListener('interaction', after)

      emit(interaction({ seq: 1 }))
      emit(interaction({ seq: 2 }))
      await new Promise((resolve) => setTimeout(resolve, 0))

      expect(after).toHaveBeenCalledTimes(2)
      expect(error).toHaveBeenCalledTimes(4)
    })

    it('drops a malformed native event with a warning', () => {
      const listener = jest.fn()
      events.addVoltraListener('interaction', listener)

      emit({ type: 'interaction', source: 'x' })

      expect(listener).not.toHaveBeenCalled()
      expect(warn).toHaveBeenCalledTimes(1)
    })

    it('warns and returns a no-op for iOS-only names on Android', () => {
      const subscription = events.addVoltraListener('stateChange', () => {})

      subscription.remove()
      expect(onVoltraEvent).not.toHaveBeenCalled()
      expect(warn).toHaveBeenCalledWith(expect.stringContaining('only supported on iOS'))
    })

    it('warns and returns a no-op for the Android-only names on iOS', () => {
      mockPlatform.OS = 'ios'

      for (const name of ['interaction', 'notificationDismissed', 'notificationTapped'] as const) {
        events.addVoltraListener(name, () => {}).remove()
      }

      expect(onVoltraEvent).not.toHaveBeenCalled()
      expect(warn).toHaveBeenCalledTimes(3)
      expect(warn).toHaveBeenCalledWith(expect.stringContaining('only supported on Android'))
    })

    it('warns and returns a no-op when the native build has no event channel', () => {
      mockedGetNativeVoltraAndroid.mockReturnValue({} as unknown as Spec)

      events.addVoltraListener('interaction', () => {}).remove()

      expect(warn).toHaveBeenCalledWith(expect.stringContaining('newer native build'))
    })

    it('warns and returns a no-op when the native module is unavailable', () => {
      mockedGetNativeVoltraAndroid.mockImplementation(() => {
        throw new Error('NativeVoltraAndroid is not available')
      })

      expect(() => events.addVoltraListener('interaction', () => {}).remove()).not.toThrow()
      expect(warn).toHaveBeenCalledTimes(1)
    })
  })

  describe('registerInteractionHandler', () => {
    it('activates interaction delivery and unregisters with the returned function', () => {
      const handler = jest.fn()
      const unregister = events.registerInteractionHandler(handler)

      emit(interaction())
      unregister()

      expect(handler).toHaveBeenCalledWith(interaction())
      expect(setEventListenerActive.mock.calls).toEqual([
        ['interaction', true],
        ['interaction', false],
      ])
    })
  })

  describe('type guards', () => {
    it('accept the documented shapes', () => {
      expect(events.isVoltraInteractionEvent(interaction())).toBe(true)
      expect(
        events.isVoltraInteractionEvent(interaction({ reply: { key: 'tip', text: '15%', source: 'choice' } }))
      ).toBe(true)
      expect(events.isVoltraNotificationDismissedEvent(dismissal())).toBe(true)
      expect(events.isVoltraNotificationTappedEvent(dismissal({ type: 'notificationTapped' }))).toBe(true)
    })

    it('reject other types and incomplete events', () => {
      expect(events.isVoltraInteractionEvent(dismissal())).toBe(false)
      expect(events.isVoltraInteractionEvent(interaction({ revision: '7' }))).toBe(false)
      expect(events.isVoltraInteractionEvent(interaction({ delivery: 'later' }))).toBe(false)
      expect(
        events.isVoltraInteractionEvent(interaction({ reply: { key: 'tip', text: '15%', source: 'voice' } }))
      ).toBe(false)
      expect(events.isVoltraNotificationDismissedEvent(dismissal({ notificationId: undefined }))).toBe(false)
      expect(events.isVoltraNotificationTappedEvent(dismissal())).toBe(false)
      expect(events.isVoltraInteractionEvent(null)).toBe(false)
    })
  })

  describe('headless task', () => {
    it('is registered once, on Android only', () => {
      events.registerVoltraHeadlessTask()
      events.registerVoltraHeadlessTask()

      expect(mockRegisterHeadlessTask).toHaveBeenCalledTimes(1)
      expect(mockRegisterHeadlessTask).toHaveBeenCalledWith('VoltraEvents', expect.any(Function))
      const provider = mockRegisterHeadlessTask.mock.calls[0][1] as () => unknown
      expect(provider()).toBe(events.runVoltraHeadlessTask)
    })

    it('is not registered on iOS', () => {
      mockPlatform.OS = 'ios'

      events.registerVoltraHeadlessTask()

      expect(mockRegisterHeadlessTask).not.toHaveBeenCalled()
    })

    it('resolves only after the listeners it woke have finished', async () => {
      let finish!: () => void
      const work = new Promise<void>((resolve) => {
        finish = resolve
      })
      events.registerInteractionHandler(() => work)
      emit(interaction())

      let settled = false
      const task = events.runVoltraHeadlessTask().then(() => {
        settled = true
      })
      await new Promise((resolve) => setTimeout(resolve, 5))
      expect(settled).toBe(false)

      finish()
      await task
      expect(settled).toBe(true)
      expect(warn).not.toHaveBeenCalled()
    })

    it('warns when no interaction listener was registered at module scope', async () => {
      await events.runVoltraHeadlessTask()

      expect(warn).toHaveBeenCalledWith(expect.stringContaining("no 'interaction' listener"))
    })
  })
})
