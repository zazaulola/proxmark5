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
  - `firmware/` — firmware releases, builds on GitHub Actions and downloads
    (`FirmwareController`, `GitHubApi`).
  - `ui/` — the screens: device, read, firmware, terminal.
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

The firmware on the Proxmark should match the client the app was built from — the
device screen warns when it doesn't and leads to the firmware screen.

## Firmware updates

The **Прошивка** (firmware) tab updates the Proxmark5 to a chosen version, from the
phone only.

Firmware can't be compiled on a phone, and upstream releases
(RfidResearchGroup/proxmark3) carry source code only, so the app works with
prebuilt images:

```
 app: pick version + options ──▶ GitHub Actions "Proxmark5 firmware release"
                                   (make PLATFORM=PM5 bootrom fullimage recovery)
                                                │
 app: list releases ◀── pm5-fw-<version> release of this repo: <variant>.fullimage.elf,
          │                 <variant>.bootrom.elf, <variant>.recovery.bin, <variant>.json
          ▼
 download + SHA-256 check ──▶ flasher (flash_pm3 over usb:<fd>) ──▶ Proxmark5
```

- **Ready firmware**: the `pm5-fw-*` releases of the repository (no login
  needed), plus the firmware **bundled in the APK**: CI builds it from the same
  tree as the app's client, so it always matches and works offline. Each card
  says whether its ARM source hash matches the client (`✓`).
- **Build a version**: upstream release (v4.23346 is the first with PM5) or
  `master`, or any ref of the fork; a standalone mode; the PM5 extras (BWM,
  low-battery switches); modules to leave out. The app starts the workflow
  through the GitHub API, follows the run and offers to flash the result. This
  needs a fine-grained token for the repository with *Actions: read and write*,
  stored only on the phone. A build that already exists is found without one.
  The workflow file has to be on the repository's default branch for the API
  to start it; the daily schedule builds each new upstream release by itself.
- **Flashing** follows the device through its reboots: the firmware drops it
  into the bootloader (a new USB device, so Android asks for access again), the
  flasher writes there, then it boots the new firmware (one more prompt to
  reconnect). Updating the bootloader too is an option.
- **DFU recovery**: in AT32 DFU mode (2e3c:df11) the app writes a
  `recovery.bin` (bundled, from a release, or a file) through the MCU loader.
- A local `.elf` can be flashed too.
