# compose-windows-touch

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)

Multi-touch and pen input for Compose Desktop on Windows.

```kotlin
// Once per window, after it has an HWND.
val touch = WindowsTouchInput.create(window) { message, error -> log.warn(message, error) }

// In the window procedure of the frame and of the Skiko canvas child, before anything else.
if (touch?.handleMessage(message, wParam) == true) return 0

// Around each window's content: Compose Desktop's 18 dp touch slop is too large for a finger.
ProvideTouchViewConfiguration { App() }
```

AWT ignores `WM_POINTER`, so Windows turns every touch into mouse input and Compose sees a single mouse
pointer: no pinch, no pressure, no stylus. This library reads the pointer messages and hands them to the
Compose scene directly. Fingers arrive as `PointerType.Touch` with every contact present in each event, pens as
`Stylus` or `Eraser` with normalized pressure, and a mouse click stays a mouse click.

```kotlin
dependencies {
    implementation("dev.nihildigit:compose-windows-touch:0.1.0")
}
```

## Hooking the window

The library does not subclass windows. An application that draws its own title bar already replaces the window
procedure, and two subclassers overwrite each other, so you call `handleMessage` from yours. `WM_POINTER*`
goes to the window under the finger, which is the Skiko canvas child, so hook both it and the frame. Return `0`
when it answers `true`, and leave every other message alone.

Touches that land on the non-client area (title bar, resize border) are not handled here. The system turns them
into mouse messages, which keeps window dragging and caption buttons working.

`handleMessage` must run on the thread that owns the window procedure.

## Behavior worth knowing

- Consecutive `MOVE` events that queue up behind a busy event dispatch thread are merged, with the older
  samples kept as `HistoricalChange`, so a lagging UI does not replay stale positions after the finger stops
  and fling velocity stays correct.
- Compose Desktop hard-codes an 18 dp touch slop. `ProvideTouchViewConfiguration` replaces it with Android's
  8 dp. The mouse slop is a fixed fraction of the touch slop and stays below one physical pixel either way.
- After the mouse has been used, the first touch would be read as a mouse press and dropped. The bridge ends
  the mouse hover before the first contact so that touch registers.

## Requirements and limits

- Windows 10 or later, x64 and ARM64. On other systems `WindowsTouchInput.create` returns `null`.
- JDK 22 or later, for `java.lang.foreign`.
- Compose Multiplatform 1.12 Desktop. Compose has no public API for injecting pointer events, so the bridge
  reaches the scene through reflection (`composePanel`, `_composeContainer`, `mediator`, and the ten-parameter
  `sendPointerEvent` overload). When a Compose upgrade changes those, the lookup fails once, `onError` is called,
  and input falls back to AWT's mouse path. Keep the members under R8 or ProGuard shrinking.

## License

MIT.
