# Proxmark 3 on Android
<a id="top"></a>

## Table of Contents
- [Proxmark 3 on Android](#proxmark-3-on-android)
  - [Table of Contents](#table-of-contents)
  - [Requirements](#requirements)
  - [Notes](#notes)
  - [Setup](#setup)
    - [Setting up Termux](#setting-up-termux)
    - [Install Proxmark3 package which follows tagged releases](#install-proxmark3-package-which-follows-tagged-releases)
    - [Optional: Building Proxmark3 client from source](#optional-building-proxmark3-client-from-source)
  - [PC-like method](#pc-like-method)
    - [Specific requirements](#specific-requirements)
    - [USB\_ACM](#usb_acm)
      - [Enable the driver](#enable-the-driver)
      - [Building the kernel](#building-the-kernel)
      - [Flashing the kernel](#flashing-the-kernel)
      - [Testing](#testing)
      - [Troubleshooting](#troubleshooting)
  - [TCP/UDP bridge method](#tcpudp-bridge-method)
    - [USB connection](#usb-connection)
      - [USB-UART Bridge Application for TCP to USB bridging](#usb-uart-bridge-application-for-tcp-to-usb-bridging)
      - [USB-UART Bridge Application for UDP to USB bridging](#usb-uart-bridge-application-for-udp-to-usb-bridging)
    - [Bluetooth connection](#bluetooth-connection)
      - [BT-UART Bridge Application for TCP to BT bridging](#bt-uart-bridge-application-for-tcp-to-bt-bridging)
      - [BLE bridging for the Proxmark5 BWM](#ble-bridging-for-the-proxmark5-bwm)
    - [TCP connection](#tcp-connection)
    - [UDP connection](#udp-connection)
    - [Troubleshooting](#troubleshooting-1)
      - [BTADDON Missing in Firmware of PM3](#btaddon-missing-in-firmware-of-pm3)
  - [Compiling and Flashing a Proxmark3 Firmware from non-root Android](#compiling-and-flashing-a-proxmark3-firmware-from-non-root-android)
    - [Compiling the Proxmark3 Firmware](#compiling-the-proxmark3-firmware)
    - [Flashing the Proxmark3 Firmware](#flashing-the-proxmark3-firmware)
  - [Flashing a Proxmark5 from the phone, no PC and no root](#flashing-a-proxmark5-from-the-phone-no-pc-and-no-root)
    - [What you need](#what-you-need)
    - [Get the firmware](#get-the-firmware)
    - [First flash of a new Proxmark5](#first-flash-of-a-new-proxmark5)
    - [Later updates](#later-updates)
    - [Recovery over DFU](#recovery-over-dfu)
    - [Troubleshooting the USB link](#troubleshooting-the-usb-link)
## Requirements
^[Top](#top)

- Android phone
- [F-Droid](https://f-droid.org/)
- [Termux](https://f-droid.org/en/packages/com.termux/)
- [Proxmark3](https://www.proxmark.com/proxmark-3-hardware/proxmark-3-rdv4)
- [Blueshark Standalone Module](https://www.proxmark.com/proxmark-news/proxmark3-blueshark-bluetooth-released) **(ONLY if using Bluetooth)**
- [Proxmark with BTADDON compiled Firmware](https://github.com/RfidResearchGroup/proxmark3/blob/master/doc/md/Use_of_Proxmark/4_Advanced-compilation-parameters.md#platform_extras) **(ONLY if using Bluetooth)**


## Notes
^[Top](#top)
From official Proxmark3 wiki:
 > In any case, you would need a USB-C to A or USB-OTG cable to connect Proxmark3 to your Android device. Some Android devices may not supply enough power (USB-OTG = 100mA), and need a USB Y-cable and external battery, otherwise they will get strange failures.
ref : https://github.com/Proxmark/proxmark3/wiki/android

## Setup
^[Top](#top)

### Setting up Termux
^[Top](#top)

Use [F-Droid](https://f-droid.org/) to install [Termux](https://f-droid.org/en/packages/com.termux/) and start it.

It is recommended to use the F-Droid version of Termux as it will be the latest. The [Play Store version](https://play.google.com/store/apps/details?id=com.termux) is not maintained (as stated in the description: "Updates over Google Play [are] currently halted due to technical reasons").

### Install Proxmark3 package which follows tagged releases
^[Top](#top)

Run the following commands:
```
pkg install proxmark3
```
### Optional: Building Proxmark3 client from source
```
pkg install make clang readline libc++ git binutils libgd
git clone https://github.com/RfidResearchGroup/proxmark3.git
cd proxmark3
make clean && make host
```
Testing:
```
make host/check
```

## PC-like method
^[Top](#top)

### Specific requirements
^[Top](#top)

- Kernel with one of:
    - USB_ACM driver
    - module loading enabled
    - published sources
- Root

termux shell:
```
pkg install tsu
```

### USB_ACM
^[Top](#top)

You need the `USB_ACM` driver enabled and working to communicate with the Proxmark3. To see if it's working, run `tsudo ls /dev/tty*` and it should list `/dev/ttyACM0` (or similar). If you see this, congratulations, skip this step!

#### Enable the driver
^[Top](#top)

If  your kernel has module loading enabled, you should be able to build the module separately and load it on your system without any changes. Otherwise, grab your kernel sources and edit your build config to include `CONFIG_USB_ACM=y`. On the tested kernel, this was under: `android_kernel_oneplus_msm8998/arch/arm64/configs/omni_oneplus5_defconfig`

#### Building the kernel
^[Top](#top)

If using a custom kernel, refer to the build instructions provided by its maintainer. Otherwise, follow the standard Linux kernel build procedure

#### Flashing the kernel
^[Top](#top)

You can flash the kernel however it suits you. On the tested device, this was achieved using [TWRP](https://twrp.me/), the most popular custom recovery

#### Testing
^[Top](#top)

Open Termux and start the Proxmark3 client:
```
tsudo proxmark3/client/proxmark3 /dev/ttyACM0
```
Everything should work just like if it was your PC!

#### Troubleshooting
^[Top](#top)

- `dmesg | grep usb` - useful debug info
- `/proc/config.gz` - contains your kernel's build configuration. Look for `CONFIG_USB_ACM`, which should be enabled

## TCP/UDP bridge method
^[Top](#top)

Termux doesn't come with usb serial neither bluetooth serial drivers.
However, it is fully integrated with phone's network, so we need to talk to the proxmark using serial to TCP/UDP sockets (carried out by other android apps).

```
|Client in Termux| <--TCP/UDP--> |Bridge App| <--USB/Bluetooth--> |Proxmark3| 
```

### USB connection
^[Top](#top)

#### USB-UART Bridge Application for TCP to USB bridging
^[Top](#top)

Install [this free TCPUART app](https://play.google.com/store/apps/details?id=com.hardcodedjoy.tcpuart) on the Play Store

The app lets you choose the baudrate. Default value (115 200 baud) is fine.
Plug the PM3 in and click connect.
Set the toggle in server mode and choose a random port not used by system (client default port is 18888) and start the server.

Alternatively, use the [paid version of the BT/USB/TCP Bridge app](https://play.google.com/store/apps/details?id=masar.bluetoothbridge.pro) which includes USB bridge as well.

In this app, select TCP server as 'Device A' and choose an unused port (client default port is 18888).
Choose your registered PM3 device as 'Device B' -> 'Connect to USB device'.
Ensure 'Retransmission' is set to 'both ways'.
It is possible to record the config as autostart, cf 'Settings' -> 'Autostart setting'.

#### USB-UART Bridge Application for UDP to USB bridging
^[Top](#top)

Install [this free SerialPipe app](https://f-droid.org/packages/io.github.wh201906.serialpipe/) on [F-Droid](https://f-droid.org/).
You can download the apk on this website without installing F-Droid.

The app lets you choose the baudrate. Default value (115 200 baud) is fine.
Plug the PM3 in and click `Connect`.
Choose a random port not used by system (client default port is 18888) and click `Start Server`.

Note: This app uses foreground service to keep the connection alive, so you can safely put it in the background without suspending the transmission. However, you will see a dummy notification in the status bar which is required for foreground service.

### Bluetooth connection
^[Top](#top)

#### BT-UART Bridge Application for TCP to BT bridging
^[Top](#top)

Install [this free app](https://play.google.com/store/apps/details?id=masar.bb) or [the paid version](https://play.google.com/store/apps/details?id=masar.bluetoothbridge.pro) (which includes usb bridge)

You need to pair the proxmark3 in the Android settings.
In the app, select TCP server as 'Device A' and choose an unused port (client default port is 18888).
Choose your registered PM3 device as 'Device B' -> 'Connect to classic Bluetooth device'.
Ensure 'Retransmission' is set to 'both ways'.
It is possible to record the config as autostart, cf 'Settings' -> 'Autostart setting'.

#### BLE bridging for the Proxmark5 BWM
^[Top](#top)

The Proxmark5 Battery Wireless Module (BWM) uses BLE, not classic Bluetooth.
The [paid version of the BT/USB/TCP Bridge app](https://play.google.com/store/apps/details?id=masar.bluetoothbridge.pro) handles it (the free version works with 10 minute time limit, can be reset by restarting the app):

In the app, select TCP server as 'Device A', port 18888 to use the client default port.
Choose 'Device B' -> 'Connect to BLE device' -> `Proxmark5`.
When asked for the characteristic, choose: service UUID starting with `0000ae86`, characteristic starting with `0000ae88`, for RX+TX.
No pairing is needed. Then connect from Termux as in [TCP connection](#tcp-connection) using port 18888.

See [PM5-BWM-USAGE.md](md/PM5_Start_Here/PM5-BWM-USAGE.md) for the BWM firmware build flag and the WiFi alternative, which needs no bridge app at all.

### TCP connection
^[Top](#top)

Start a new session, then:
```
proxmark3 tcp:localhost:<chosenPort>
```
Alternatively, if you have made the client in the git repo:
```
./client/proxmark3 tcp:localhost:<chosenPort>
```
If the last colon and the chosen port are missing, the client will use `18888` as the default port.

### UDP connection
^[Top](#top)

Start a new session, then:
```
proxmark3 udp:localhost:<chosenPort>
```
Alternatively, if you have made the client in the git repo:
```
./client/proxmark3 udp:localhost:<chosenPort>
```
If the last colon and the chosen port are missing, the client will use `18888` as the default port.  

You can also specify the outbound port for UDP connections, which might be required for some UDP to USB/BT bridge app as the target port of it.  
The format is
```
proxmark3 udp:localhost:<chosenPort>,bind=:<outboundPort>
```
Some examples:
```
# The bridge app listens on Port 12345, and the client listens on Port 12355
proxmark3 udp:localhost:12345,bind=:12355

# 127.0.0.1 is also a valid local address
proxmark3 udp:127.0.0.1:12345,bind=:12355

# The bridge app listens on the default port 18888, and the client listens on Port 12355
proxmark3 udp:127.0.0.1,bind=:12355

# OutboundPort is randomly picked by the system, which requires the "UDP server" mode for the bridge app
proxmark3 udp:localhost:12345
```


### Troubleshooting
^[Top](#top)

#### BTADDON Missing in Firmware of PM3
^[Top](#top)

1. Phone and pm3 are connected, blue led is on and *not* blinking
2. BTUART Tool TCP Server at Port 18888 
3. Using proxmark3 in termux shows the following error message:
```
$ proxmark3 tcp:localhost:18888

[=] Session log /data/data/com.termux/files/home/.suroot /.proxmark3/log_20210519.txt

[=] Loading Preferences...

[+] loaded from JSON file /data/data/com.termux/files/ho me/.suroot/.proxmark3/preferences.json

Using UART port tcp:localhost:18888

[!!] ERROR: cannot communicate with the Proxmark3
```
Solution:  

Make sure you have installed a firmware with BTADDON compiled. 
See: https://github.com/RfidResearchGroup/proxmark3/blob/master/doc/md/Use_of_Proxmark/4_Advanced-compilation-parameters.md#platform_extras  

## Compiling and Flashing a Proxmark3 Firmware from non-root Android

READ ME:
* If you can compile and flash your device from a PC, do it! It's probably much confortable than following this method.
* Flashing is possible only via USB-UART, *not* via BT-UART
* Avoid flashing the Bootloader from non-root Android as the connection is probably less stable than with pure USB and you don't want to brick your device...

### Compiling the Proxmark3 Firmware

Assuming we're using the Github repo sources as explained above.

```
pkg install make clang readline libc++ git binutils
cd
git clone https://github.com/RfidResearchGroup/proxmark3.git
cd proxmark3
make clean && make -j client
```

Termux doesn't have the ARM cross-compiler, so we'll install a Debian within Termux.

```
$ pkg install proot-distro
$ proot-distro install debian
$ proot-distro login debian --termux-home
```
At this point we should be on a Debian root prompt in the user directory. We install only the requirements to compile the Proxmark3 firmware.
```
# apt-get update
# apt-get install -y --no-install-recommends make gcc g++ libc6-dev gcc-arm-none-eabi libnewlib-dev
# cd proxmark3
# make -j fullimage
# exit
```
At this point we're back to the Termux prompt.

### Flashing the Proxmark3 Firmware

Plug the Proxmark3 while pressing the button, to enter into bootloader mode manually.

Activate the USB-UART to TCP Bridge with one of the applications as explained above.

```
cd proxmark3
./client/proxmark3 tcp:localhost:<chosenPort> --flash --image armsrc/obj/fullimage.elf
```
Once the Proxmark3 has rebooted, reconnect it to the bridge in the app.
The freshly flashed device is now ready to be used.

```
./client/proxmark3 tcp:localhost:<chosenPort>
```

## Flashing a Proxmark5 from the phone, no PC and no root
^[Top](#top)

The client can talk to the Proxmark over USB itself, using the file descriptor that Termux:API's
`termux-usb` hands out once you allow access in the Android prompt. No root, no kernel driver, no
bridge app, and no library beyond what the client already needs. `pm3-termux-usb` wraps it; the
`pm3`, `pm3-flash*` scripts use it on their own when they run in Termux.

Unlike the TCP bridge apps, this survives the reboot into the bootloader: the device then shows up
as a new USB device, the client exits with code 3, and `pm3-termux-usb` waits for it, asks for
permission again (tap OK a second time) and carries on.

### What you need
^[Top](#top)

- Termux **and** Termux:API, both from F-Droid (the Play Store builds don't match each other).
- An OTG adapter or a USB-C to USB-C cable. Use the Proxmark5 USB port on the **button side**.
- The client from this repository (the `proxmark3` Termux package doesn't have the `usb:` port yet):
  ```
  pkg install termux-api git make clang readline libc++ binutils libgd
  git clone https://github.com/zazaulola/proxmark5.git
  cd proxmark5
  make -j client
  ```

### Get the firmware
^[Top](#top)

Every push builds the Proxmark5 images in GitHub Actions (workflow "Proxmark5 firmware"). Open the
latest run of the branch you want in the phone's browser, download `proxmark5-firmware-pm5-bwm`
(with the Battery/Wireless Module) or `proxmark5-firmware-pm5` (without), and unpack it in Termux:
```
termux-setup-storage                 # once, gives Termux access to Download/
mkdir -p ~/pm5fw && cd ~/pm5fw
unzip ~/storage/downloads/proxmark5-firmware-pm5-bwm.zip
cat VERSION.txt && sha256sum -c SHA256SUMS
cd ~/proxmark5
```
It contains `bootrom.elf`, `fullimage.elf` and `recovery.bin`. Building them in Termux is possible
too, see [Compiling the Proxmark3 Firmware](#compiling-the-proxmark3-firmware), with `PLATFORM=PM5`.

### First flash of a new Proxmark5
^[Top](#top)

Same steps as [Getting Started with the Proxmark5](md/PM5_Start_Here/Getting_Started_With_PM5.md),
with the phone instead of a PC. If a BWM is fitted, remove it for this first flash.

1. Put the Proxmark5 into *Boot Mode*: plug it into the phone while holding the button for about
   4 seconds, until LEDs B and D are on. (If they go on and then off, that is DFU mode: unplug and
   try again.)
2. Flash the bootloader:
   ```
   ./pm3-termux-usb --flash --unlock-bootloader --image ~/pm5fw/bootrom.elf
   ```
3. Unplug, enter *Boot Mode* again, flash the main firmware:
   ```
   ./pm3-termux-usb --flash --image ~/pm5fw/fullimage.elf
   ```
4. Unplug, plug in normally, check it:
   ```
   ./pm3-termux-usb -c "hw version"
   ```

### Later updates
^[Top](#top)

Once the new bootloader is in, no button is needed: the client sends the device to its bootloader
and `pm3-termux-usb` follows it.
```
./pm3-termux-usb --flash --image ~/pm5fw/fullimage.elf
./pm3-flash ~/pm5fw/fullimage.elf   # same, through the pm3 helper scripts
./pm3                            # the client, port detected automatically
```
A Proxmark5 with a BWM-capable bootloader can also be flashed over WiFi or BLE, see
[PM5-BWM-USAGE.md](md/PM5_Start_Here/PM5-BWM-USAGE.md).

### Recovery over DFU
^[Top](#top)

If the Proxmark5 doesn't enter Boot Mode or the flasher gets no answer, rewrite everything through
the MCU's own DFU loader. This replaces `dfu-util`, which can't open USB devices on a non-root
phone.

1. Press and hold the button while plugging the Proxmark5 in, until the LEDs go off. Keep holding.
2. Write the recovery image (bootloader and firmware in one file):
   ```
   ./pm3-termux-usb --dfu ~/pm5fw/recovery.bin
   ```
   Only the flash pages the image covers are erased. The image is read back and compared before
   the loader starts it.
3. Release the button, unplug and plug back in. Then continue with the
   [first flash](#first-flash-of-a-new-proxmark5) to get the latest bootloader and firmware.

`--dfu` takes a raw `.bin`; `--dfuaddr` changes the address (default `0x08000000`).

### Troubleshooting the USB link
^[Top](#top)

- No device found: check the cable and OTG adapter; `termux-usb -l` must list a `/dev/bus/usb/...`
  path. Some phones only supply power through OTG when "OTG" is turned on in the settings.
- Several USB devices (a hub): choose one with `./pm3-termux-usb --device /dev/bus/usb/001/002`.
- The permission prompt shows up after each reboot of the Proxmark: that is Android's rule for a
  newly attached device. Tap OK.
- By hand, without the script: `termux-usb -r -e ./myscript /dev/bus/usb/001/002`. termux-usb runs
  `./myscript` with the descriptor as its argument; there run `./client/proxmark3 -p usb:$1`, or
  `-p usb:` to take it from `$TERMUX_USB_FD`. `usbfd:` is the same as `usb:`.
- On a Linux PC or a rooted phone the same code works with a device node:
  `./client/proxmark3 -p usb:/dev/bus/usb/001/002` (no `cdc_acm` needed).
