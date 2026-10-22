---
'@use-voltra/android-client': minor
---

Android now has an event channel from native code to JavaScript. `addVoltraListener` returns a working subscription on Android for `interaction`, `notificationDismissed` and `notificationTapped`, and the new `registerInteractionHandler` registers an `interaction` handler at module scope. Events that happen while the app is not running are kept on the device and delivered, in order, to the first listener for their type. An `interaction` that arrives while the app is not running starts the app's JavaScript in the background, without opening the app.

With `enableNotifications`, the Expo plugin now also adds the `FOREGROUND_SERVICE` and `WAKE_LOCK` permissions and a background service used for that delivery.

On Android, `VoltraView` no longer subscribes to interaction events, which Android views never emitted. Its `onInteraction` callback is now typed with `VoltraViewInteractionEvent`, which has the same fields as before.
