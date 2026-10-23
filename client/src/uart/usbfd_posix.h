//-----------------------------------------------------------------------------
// Copyright (C) Proxmark3 contributors. See AUTHORS.md for details.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// See LICENSE.txt for the text of the license.
//-----------------------------------------------------------------------------
// USB-CDC over an already opened usbfs file descriptor (libusb).
//
// Android does not give apps /dev/ttyACM*, but Termux:API's `termux-usb` hands
// a program a file descriptor for the USB device once the user grants access.
// This driver wraps that fd with libusb, claims the CDC interfaces and pumps the
// bulk endpoints into one end of a socketpair, so uart_posix.c can keep using
// its plain select()/read()/write() path on the other end.
//-----------------------------------------------------------------------------
#ifndef USBFD_POSIX_H__
#define USBFD_POSIX_H__

#ifdef HAVE_LIBUSB

#include <stdbool.h>
#include <stdint.h>

// Env var termux-usb sets for the program it starts (newer termux-api), and
// the variable our own wrapper script exports. Used when the port is "usbfd:"
// with no number.
#define USBFD_ENV_VAR "TERMUX_USB_FD"

typedef struct usbfd_conn usbfd_conn_t;

// Parse the part after "usbfd:" (a decimal fd, or empty for $TERMUX_USB_FD).
// Returns the fd or -1.
int usbfd_parse(const char *arg);

// Wrap sys_fd, claim the CDC interfaces, raise DTR/RTS and start the pump
// threads. On success returns the connection and stores in *app_fd the socket
// the caller reads and writes. Returns NULL on failure (already reported unless
// silent). The usbfs fd itself is never closed, so the port can be reopened.
usbfd_conn_t *usbfd_open(int sys_fd, int *app_fd, bool silent);

// Stop the threads, release the interfaces and close the caller's socket.
void usbfd_close(usbfd_conn_t *c);

#endif // HAVE_LIBUSB
#endif // USBFD_POSIX_H__
