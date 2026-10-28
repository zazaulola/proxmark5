# Proxmark5 Android GUI

A visual Android front-end for the Proxmark5, in the spirit of the Chameleon
apps. It talks to the device over a **USB OTG cable, without root and without
Termux**.

> Work in progress. The app is a front-end to one living client session: see
> "The app" below. Forms for the common card workflows come on top of the
> command tree.

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

## The app

The app is built around **one living client session**. Connecting starts it and it
lives until you leave it, the link is lost, or the client dies. The app never waits
for a command to finish: commands go into a queue, their output streams into the
log, and a long one (sniff, emulation, hardnested) just runs until you stop it.
Leaving by hand (*Connect → Выйти из сессии*) really leaves: nothing reconnects until
you ask or plug the cable in again. Closing the app ends the session too; keeping it
alive in the background (a foreground service) is not done yet.

```
 ┌─ terminal (log + input) ────────────┐  collapsed to a strip: status + last line
 ├─ ═════ slider ═════ ────────────────┤  drag to give the log any part of the screen
 │  section                            │
 └─ Device │ Functions │ Library ──────┘
```

- **Device**: *Connect* (USB works; BT/BLE are shown but need a wireless module the
  Proxmark5 doesn't have yet), *Metadata* (`hw version`), *Firmware* (config, build,
  flash), *User data* (`mem info`, and where the app keeps its files), *Status*
  (idle, progress of the running command, sniffer, emulation, and Stop).
- **Functions**: the client's own command tree (`prefs`, `analyse`, `data`, `hw`,
  `emv`, `hf`, `lf`, `mad`, `nfc`, `piv`, `smart`, `mqtt`, `reveng`, `script`,
  `trace`, `wiegand`). The tree is read from the client itself
  (`walkCommandsRecursive()`, see `nativeCommandTree`), so it always matches the
  build in the app and the connected device. Pick a command, add parameters, run.
  Search finds a command anywhere in the tree. "Определить карту" runs
  `hf search` and `lf search` and shows the card.
- **Library**: *Keys*, *Dumps* (what the client saved, sorted by file name) and
  *Logs* (terminal logs saved from the terminal's toolbar). Files can be shared
  or deleted.

## Modules

- **`app`** — the Android application (Kotlin + Jetpack Compose).
  - `core/` — the JNI bridge (`NativeClient`), the single client thread and
    live console (`ClientController`), the command queue and activity of the session
    (`CommandPump`), the saved files (`LibraryRepository`), and asset unpacking
    (`ResourceUnpacker`).
  - `usb/` — finding, permitting and opening the device (`UsbTransport`).
  - `firmware/` — firmware releases, builds on GitHub Actions and downloads
    (`FirmwareController`, `GitHubApi`).
  - `ui/` — the shell (`Pm3App`, `SplitShell`, `TerminalPanel`) and the three
    sections (`DeviceSection`, `FunctionsSection`, `LibrarySection`).
  - `src/main/cpp/` — `pm3_jni.c` and the CMake glue that builds
    `client/experimental_lib` (the `LIBPM3` client) plus the bridge into
    `libpm3jni.so`.
- **`parsers`** — a pure-JVM Kotlin module that turns the client's text output
  into typed data (ANSI colors, `hw version`, `hf`/`lf search`, a streaming
  terminal buffer, the command tree, what the session is busy with, library files). It has no Android dependency so it is unit-tested on the JVM.

The app bundles the client's `resources/`, `dictionaries/` (minus the large
`extras/` bundle and the hardnested benchmark data; the hardnested tables that
`hf mf autopwn` needs are shipped) and lua/cmd scripts as assets, unpacked into
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

The firmware on the Proxmark should match the client the app was built from — Device →
Metadata warns when it doesn't and leads to Device → Firmware.

## Firmware updates

Device → **Firmware** updates the Proxmark5 to a chosen version, from the
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
