# Proxmark5 Android GUI

A visual Android front-end for the Proxmark5, in the spirit of the Chameleon
apps. It talks to the device over a **USB OTG cable, without root and without
Termux**.

> Early work in progress. First version: see the device, read a card with one
> tap, and a full console. MIFARE/clone/emulation screens come next.

## How it works

The real `proxmark3` client is compiled as a shared library
(`libpm3jni.so`) and embedded in the app. It drives the Proxmark over the
`usb:` usbfs transport added for Termux (see `client/src/uart/usbfs_linux.c` and
`doc/termux_notes.md`). The difference on Android is where the USB file
descriptor comes from: the app asks for it through the standard Android USB
permission dialog (`UsbManager.openDevice(...).fileDescriptor`) and hands the
client a `usb:<fd>` port. No `termux-usb`, no bridge app, no `libusb`.

```
Compose UI  ──▶  ClientController (one client thread)  ──▶  JNI (pm3_jni.c)
                        ▲                                         │
                        │  stdout pipe (ANSI)                     ▼
                 TerminalBuffer  ◀───────────────────────  libpm3jni.so (client)
                                                                  │  usb:<fd>
                        UsbManager.openDevice ───────────────────▶ Proxmark5
```

## Modules

- **`app`** — the Android application (Kotlin + Jetpack Compose).
  - `core/` — the JNI bridge (`NativeClient`), the single client thread and
    live console (`ClientController`), and asset unpacking (`ResourceUnpacker`).
  - `usb/` — finding, permitting and opening the device (`UsbTransport`).
  - `ui/` — the three screens: device, read, terminal.
  - `src/main/cpp/` — `pm3_jni.c` and the CMake glue that builds
    `client/experimental_lib` (the `LIBPM3` client) plus the bridge into
    `libpm3jni.so`.
- **`parsers`** — a pure-JVM Kotlin module that turns the client's text output
  into typed data (ANSI colors, `hw version`, `hf`/`lf search`, a streaming
  terminal buffer). It has no Android dependency so it is unit-tested on the JVM.

The app bundles the client's `resources/`, `dictionaries/` (minus the large
`extras/` and hardnested tables) and lua/cmd scripts as assets, unpacked into
`$HOME/.proxmark3/` on first run so the client finds them.

## Building

The APK is built in CI (`.github/workflows/android-gui.yml`) because the Android
SDK/NDK are needed. To build locally you need the Android SDK with NDK
`27.1.12297006` and CMake `3.22.1`:

```
cd client/android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
```

The parser tests run on any JVM, no Android SDK:

```
./gradlew -Pparsers.only :parsers:test
```

## Install and use

1. Grab `proxmark5-android-debug.apk` from the `Android GUI` workflow run and
   install it (allow unknown sources).
2. Plug the Proxmark5 into the phone with an OTG adapter (cable on the button
   side, as for flashing).
3. Allow the USB access prompt. The app connects and shows the device.

The firmware on the Proxmark must match the client the app was built from — the
device screen warns when it doesn't. Flash with `pm3-termux-usb` or the
`Proxmark5 firmware` workflow artifacts for now; flashing from the app is a
planned screen.
