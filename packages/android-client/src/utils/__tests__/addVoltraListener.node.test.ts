jest.mock('react-native', () => ({
  Platform: { OS: 'android' },
  AppRegistry: { registerHeadlessTask: jest.fn() },
}))

import { Platform } from 'react-native'

import { addVoltraListener } from '../../events.js'

describe('addVoltraListener (Android client)', () => {
  afterEach(() => {
    jest.restoreAllMocks()
    ;(Platform as { OS: string }).OS = 'android'
  })

  // The Android events themselves are covered in src/__tests__/events.node.test.ts (ADR 0014).
  it('warns and returns a no-op subscription for an iOS-only event on every platform', () => {
    for (const os of ['android', 'ios']) {
      ;(Platform as { OS: string }).OS = os
      const warn = jest.spyOn(console, 'warn').mockImplementation(() => {})

      const subscription = addVoltraListener('stateChange', () => {})

      expect(warn).toHaveBeenCalledTimes(1)
      expect(warn).toHaveBeenCalledWith(
        "[Voltra] Event 'stateChange' is only supported on iOS. Returning no-op subscription."
      )
      expect(typeof subscription.remove).toBe('function')
      expect(() => subscription.remove()).not.toThrow()
      warn.mockRestore()
    }
  })

  it('warns and returns a no-op subscription for an Android event on iOS', () => {
    ;(Platform as { OS: string }).OS = 'ios'
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {})

    const subscription = addVoltraListener('interaction', () => {})

    expect(warn).toHaveBeenCalledWith(
      "[Voltra] Event 'interaction' is only supported on Android. Returning no-op subscription."
    )
    expect(() => subscription.remove()).not.toThrow()
  })
})
