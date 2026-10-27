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
// Minimal DfuSe writer for the Proxmark5 MCU ROM loader (AT32, 2e3c:df11).
//
// Does what `dfu-util -d 2e3c:df11 -a 0 -s <addr>:leave -D <file.bin>` does,
// over a usb: port, so a Proxmark5 can be recovered from a non-root Android
// phone where neither dfu-util nor libusb can open the device by themselves.
// Only the pages covered by the image are erased, the image is read back and
// compared, then the loader is asked to start the new firmware.
//-----------------------------------------------------------------------------

#ifndef __DFUSE_H
#define __DFUSE_H

#include "common.h"

#define DFUSE_DEFAULT_ADDRESS 0x08000000

// port: a usb: port name (see uart/usbfs_linux.h). filename: raw binary image.
int dfuse_flash_file(const char *port, const char *filename, uint32_t address);

#endif // __DFUSE_H
