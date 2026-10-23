//-----------------------------------------------------------------------------
// Copyright (C) Proxmark3 contributors. See AUTHORS.md for details.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// See LICENSE.txt for the text of the license.
//-----------------------------------------------------------------------------
// Userspace USB transport over Linux usbfs (/dev/bus/usb/BBB/DDD).
//
// Talks to the Proxmark CDC-ACM interface directly with usbfs ioctls, so the
// kernel cdc_acm driver (/dev/ttyACM*) is not needed. Its main use is Android:
// a non-root Termux has no /dev/ttyACM*, but `termux-usb` hands the process an
// already-opened usbfs file descriptor after the user taps "allow".
//
// Port name forms understood by the client (see uart_posix.c):
//   usb:<n>               inherited usbfs file descriptor number <n>
//   usb:                  descriptor taken from $TERMUX_USB_FD
//   usb:/dev/bus/usb/B/D  open the usbfs node itself (desktop Linux, rooted Android)
// "usbfd:" is accepted as an alias of "usb:".
//
// The same handle also drives the MCU ROM DFU loader (see dfuse.h).
//-----------------------------------------------------------------------------

#ifndef __USBFS_LINUX_H
#define __USBFS_LINUX_H

#include "common.h"

#define USBFS_PORT_PREFIX     "usb:"

// Artery AT32 ROM DFU loader (Proxmark5 entered with the button held at plug-in)
#define PM5_DFU_VID           0x2E3C
#define PM5_DFU_PID           0xDF11

typedef struct usbfs_dev usbfs_dev_t;

// True if the port name is a usb: (or usbfd:) port.
bool usbfs_is_port(const char *port_name);

// True if this client can talk usbfs at all (Linux / Android builds).
bool usbfs_supported(void);

// Open a device from a port name of the forms above (prefix included).
// Does not claim any interface. Returns NULL on error (already reported unless silent).
usbfs_dev_t *usbfs_open(const char *port_name, bool silent);

// Vendor / product id of the opened device.
uint16_t usbfs_vid(const usbfs_dev_t *dev);
uint16_t usbfs_pid(const usbfs_dev_t *dev);

// Find the CDC data interface, claim it (and its control interface), and
// start the bulk IN pipeline. Returns 0 on success.
int usbfs_cdc_start(usbfs_dev_t *dev, bool silent);

// CDC data transfer, same contract as ble_recv / ble_send:
// recv waits up to timeout_ms for the first byte, then returns what is in hand.
// wake_fd (may be -1) is watched while nothing has been received yet.
// Returns 0 on success (got may be 0 on timeout), negative if the device is gone.
int usbfs_cdc_recv(usbfs_dev_t *dev, uint8_t *buf, size_t maxlen, size_t *got, int timeout_ms, int wake_fd);
int usbfs_cdc_send(usbfs_dev_t *dev, const uint8_t *buf, size_t len);

// Raw control transfer on EP0. Returns bytes transferred, or negative errno.
int usbfs_control(usbfs_dev_t *dev, uint8_t request_type, uint8_t request, uint16_t value,
                  uint16_t index, uint8_t *data, uint16_t length, uint32_t timeout_ms);

// Claim / release a plain interface (used by DFU). Returns 0 on success.
int usbfs_claim(usbfs_dev_t *dev, uint8_t ifnum);
int usbfs_release(usbfs_dev_t *dev, uint8_t ifnum);

// Raw device + configuration descriptors as read at open time.
const uint8_t *usbfs_descriptors(const usbfs_dev_t *dev, size_t *len);

// Find the first interface with this class/subclass. Returns its number, or -1.
int usbfs_find_interface(const usbfs_dev_t *dev, uint8_t cls, uint8_t subcls);

void usbfs_close(usbfs_dev_t *dev);

#endif // __USBFS_LINUX_H
