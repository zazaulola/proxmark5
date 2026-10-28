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
descriptor comes from: the app asks for access through the standard Android USB
permission dialog, opens the device (`UsbManager.openDevice(...).fileDescriptor`) and
hands the client a `usb:<fd>` port. No `termux-usb`, no bridge app, no `libusb`.

The client runs in a **process of its own** (`:pm3`), inside a foreground service,
apart from the UI. The session, the running command and the log belong to that
process, so they survive the UI being closed, and a client that crashes or has to
be killed (the only way to stop `hf mf hardnested`) takes down only that process:
the UI shows why and starts a clean session.

```
UI process                                      process :pm3
───────────────────────────                     ─────────────────────────────────────────
MainActivity, Compose, AppViewModel             Pm3Service (thin shell: foreground, binder, notification)
Pm3Session (one per process)  ◄──── AIDL ────►  SessionHost (all the logic, one per process)
  TerminalBuffer ◄── tails the log file ──┐       ├─ pm3-host   every change of the session state
  StateFlow: link, activity, ready ...    │       ├─ pm3-client JNI, libpm3jni.so (the one client thread)
UsbTransport: find the device,            │       ├─ pm3-ctl    Enter, BREAK and the Stop timers
  ask for the permission                  │       ├─ pm3-events callbacks to the UI
FirmwareController, LibraryRepository     │       └─ UsbLink    opens the USB device itself ──▶ Proxmark5
                                          │
no_backup/pm3/out-<epoch>.log ◄───────────┘  stdout/stderr of the client (dup2) + notes of the service
```

- The service is the source of truth: the UI reads an immutable snapshot (link, client,
  running command) and gets a short "changed" call when it moves. Every call from the UI
  is `oneway`, so the UI never waits for the client.
- The service opens the USB device (the permission belongs to the app, not to a process);
  the UI only finds it and asks for the permission. A killed process closes the file
  descriptor, which frees the device.
- The client's output goes to a file, not a pipe: the last words of a client that died
  are not lost, and a new UI starts reading where the file is.

## The app

The app is built around **one living client session**. Connecting starts it and it
lives until you leave it, the link is lost, or the client dies. The app never waits
for a command to finish: commands go into a queue, their output streams into the
log, and a long one (sniff, emulation, hardnested) just runs until you stop it.
Leaving by hand (*Connect → Выйти из сессии*, or *Выйти* in the notification) really
leaves: nothing reconnects until you ask or plug the cable in again. Closing the app,
the screen going off or the app going to the background does not end the session: it
lives in the service, with the screen off too, until you leave it, the link is lost or
the client dies.

```
 ┌─ terminal (log + input) ────────────┐  collapsed to a strip: status + last line
 ├─ ═════ slider ═════ ────────────────┤  drag to give the log any part of the screen
 │  section                            │
 └─ Device │ Functions │ Library ──────┘
```

- **Device**: *Connect* (USB works; BT/BLE are shown but need a wireless module the
  Proxmark5 doesn't have yet), *Metadata* (`hw version`), *Firmware* (config, build,
  flash), *User data* (`mem info`, and where the app keeps its files), *Status*
  (idle, progress of the running command, sniffer, emulation, Stop, and the restart
  buttons below).
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

## The session service

- **Foreground service.** A session is started with `startForegroundService` from the
  visible app (connecting, a firmware write, a command that needs no device) and shows
  a notification while there is something to hold: an opening, open or closing session,
  a running command, a firmware write. When nothing is left the service stops itself
  after a few seconds. The notification has *Стоп* and *Выйти* buttons (none while a
  firmware is written) and a tap opens the app. A partial wake lock is held while a
  command runs, the session opens or a firmware is written, so the CPU stays on with the
  screen off.
- **Permissions** (`AndroidManifest.xml`): `FOREGROUND_SERVICE` and
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` (the service type), `CHANGE_NETWORK_STATE` (a
  documented prerequisite of that type on Android 14), `WAKE_LOCK`, and
  `POST_NOTIFICATIONS`, which the app asks for once at the first launch (Android 13+).
  Without the notification permission everything works the same, only the notification
  is not shown; the same Stop and leave buttons are in the app.
- **Stop** drops the queued commands and presses Enter in the client, plus a BREAK to
  the device for the loops that wait on it. A command that does not notice in about
  6 seconds is *stuck*: the Status screen then offers **Перезапустить клиент**, which
  kills the client process and opens a clean session (a card write that was in progress
  can be left unfinished, hence the question to the user). The few commands known to
  ignore Enter (`hf mf hardnested`, `hf mfdes chk`, `hf mfdes detect`) are restarted
  after a second and a half and the app connects again by itself, if it is visible and
  you have not left the session (a Stop from the notification with the app in the
  background only ends the session). Leaving the session while a command does not stop
  shows *Закрываю сессию…* and the **Принудительно** button, which does the same restart.
- **Firmware** is never interrupted: while it is written Stop, leaving, the restart
  buttons and the notification buttons do nothing. If the client process still dies
  under a write, the app says so, does not connect by itself, and points to the DFU
  recovery.
- **Where the log is.** The client's output and the service's own notes go to
  `no_backup/pm3/out-<epoch>.log` in the app's private storage (`noBackupFilesDir`,
  `/data/data/io.github.zazaulola.proxmark5/no_backup/pm3/`); the terminal in the app
  tails that file. It is emptied by the terminal's clear button, cut when it passes
  32 MiB, and the logs of earlier service lives are deleted at the next start except
  the newest one (it holds the last words of a client that died). The terminal's save
  button writes a copy to the Library → Logs shelf, which is the file to share.
- **Looking at it on a device:** `adb shell dumpsys activity services io.github.zazaulola.proxmark5`.
- **Not done yet.** `hf mf sim` without `-i` hands control back at once, so the status
  goes idle and the wake lock is released while the device still emulates. There is no
  *Готово* button for `hf mf sen/isen` and no answer to the questions the client asks on
  stdin (`hw usart`, `hf legic`). The process is not killed on exit, so a "clean client
  per session" only happens after a restart or a death of the process. `hf iclass
  tagsim` reads the keyboard in a blocking way: Stop works, but finishing from the
  device's button is not noticed. Saving `preferences` is not atomic upstream (a kill in
  the middle can reset the settings).

## Modules

- **`app`** — the Android application (Kotlin + Jetpack Compose).
  - `service/` — process `:pm3`: the foreground service (`Pm3Service`), the session
    and its state machine (`SessionHost`), the client thread and the command queue
    (`ClientEngine`), the USB device (`UsbLink`), the Stop thread (`ControlThread`),
    the callbacks to the UI (`Notifier`), the notification, the wake lock, the log file
    and the exit marker.
  - `remote/` — the UI side of the session (`Pm3Session`): binds the service, keeps
    the last snapshot, tails the log into the terminal, turns a dead service into a
    state the screens can show.
  - `ipc/` and `src/main/aidl/` — the binder interface (`IPm3Service`, `IPm3Listener`)
    and the names both processes share.
  - `core/` — the JNI bridge (`NativeClient`, loaded only by the service), the results
    of commands (`ExecResult`, `Activity`), the saved files (`LibraryRepository`), and
    asset unpacking (`ResourceUnpacker`).
  - `usb/` — finding the device and asking for its permission (`UsbTransport`); it
    never opens it.
  - `firmware/` — firmware releases, builds on GitHub Actions and downloads
    (`FirmwareController`, `GitHubApi`).
  - `ui/` — the view model (`AppViewModel`), the shell (`Pm3App`, `SplitShell`,
    `TerminalPanel`) and the three sections (`DeviceSection`, `FunctionsSection`,
    `LibrarySection`).
  - `src/main/cpp/` — `pm3_jni.c` and the CMake glue that builds
    `client/experimental_lib` (the `LIBPM3` client) plus the bridge into
    `libpm3jni.so`.
- **`parsers`** — a pure-JVM Kotlin module that turns the client's text output
  into typed data (ANSI colors, `hw version`, `hf`/`lf search`, a streaming
  terminal buffer, the command tree, what the session is busy with, library files) and the
  pure logic of the session (`session` package: link states, the snapshot the service
  publishes, the exit marker, the log tail, the Stop policy). It has no Android dependency
  so it is unit-tested on the JVM.

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
3. Allow the USB access prompt. The app connects and shows the device. On Android 13+
   it also asks once to show notifications: the session's notification (Stop, leave) is
   the only thing that needs it, the app works without.

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
