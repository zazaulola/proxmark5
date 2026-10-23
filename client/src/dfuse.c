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
// Minimal DfuSe writer - see dfuse.h.
// Protocol references: USB DFU 1.1 and ST UM0391 (DfuSe extensions), the same
// subset dfu-util uses for "-s <address>:leave".
//-----------------------------------------------------------------------------

#include "dfuse.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include "ui.h"
#include "util_posix.h"      // msleep
#include "fileutils.h"
#include "pm3_cmd.h"
#include "uart/usbfs_linux.h"

// DFU class requests
#define DFU_DNLOAD       1
#define DFU_UPLOAD       2
#define DFU_GETSTATUS    3
#define DFU_CLRSTATUS    4
#define DFU_ABORT        6

// DFU states
#define STATE_DFU_IDLE           2
#define STATE_DFU_DNLOAD_SYNC    3
#define STATE_DFU_DNBUSY         4
#define STATE_DFU_DNLOAD_IDLE    5
#define STATE_DFU_MANIFEST_SYNC  6
#define STATE_DFU_MANIFEST       7
#define STATE_DFU_UPLOAD_IDLE    9
#define STATE_DFU_ERROR          10

// DfuSe commands, sent as a download to block 0
#define DFUSE_SET_ADDRESS        0x21
#define DFUSE_ERASE_PAGE         0x41

#define DFU_REQ_OUT   0x21   // class, interface, host to device
#define DFU_REQ_IN    0xA1   // class, interface, device to host

#define DFU_CTRL_TIMEOUT_MS   5000
#define DFU_BUSY_BUDGET_MS    20000
#define DFUSE_MAX_SEGMENTS    32

typedef struct {
    uint32_t start;
    uint32_t page_size;
    uint32_t pages;
    uint8_t  type;           // bit0 readable, bit1 erasable, bit2 writable
} dfuse_segment_t;

typedef struct {
    usbfs_dev_t *dev;
    uint8_t  ifnum;
    uint8_t  iface_string;
    uint16_t xfer_size;
    char     layout[256];
    dfuse_segment_t segs[DFUSE_MAX_SEGMENTS];
    int      num_segs;
} dfuse_t;

typedef struct {
    uint8_t  status;
    uint32_t poll_ms;
    uint8_t  state;
} dfu_status_t;

static const char *dfu_state_name(uint8_t state) {
    switch (state) {
        case STATE_DFU_IDLE:
            return "dfuIDLE";
        case STATE_DFU_DNLOAD_SYNC:
            return "dfuDNLOAD-SYNC";
        case STATE_DFU_DNBUSY:
            return "dfuDNBUSY";
        case STATE_DFU_DNLOAD_IDLE:
            return "dfuDNLOAD-IDLE";
        case STATE_DFU_MANIFEST_SYNC:
            return "dfuMANIFEST-SYNC";
        case STATE_DFU_MANIFEST:
            return "dfuMANIFEST";
        case STATE_DFU_UPLOAD_IDLE:
            return "dfuUPLOAD-IDLE";
        case STATE_DFU_ERROR:
            return "dfuERROR";
        default:
            return "unknown";
    }
}

// Locate the DFU interface (alt 0) and its functional descriptor.
static int dfuse_find_interface(dfuse_t *d) {
    size_t len = 0;
    const uint8_t *raw = usbfs_descriptors(d->dev, &len);
    bool found = false;
    bool in_dfu_if = false;

    for (size_t pos = 0; pos + 2 <= len;) {
        uint8_t dlen = raw[pos];
        uint8_t type = raw[pos + 1];
        if (dlen < 2 || pos + dlen > len) {
            break;
        }
        if (type == 0x04 && dlen >= 9) {
            // interface: class 0xFE (application specific), subclass 0x01 (DFU)
            in_dfu_if = (raw[pos + 5] == 0xFE && raw[pos + 6] == 0x01);
            if (in_dfu_if && found == false && raw[pos + 3] == 0) {
                d->ifnum = raw[pos + 2];
                d->iface_string = raw[pos + 8];
                found = true;
            }
        } else if (type == 0x21 && dlen >= 7 && in_dfu_if) {
            d->xfer_size = (uint16_t)(raw[pos + 5] | (raw[pos + 6] << 8));
        }
        pos += dlen;
    }
    if (found == false) {
        return PM3_EDEVNOTSUPP;
    }
    if (d->xfer_size == 0 || d->xfer_size > 4096) {
        d->xfer_size = 2048;
    }
    return PM3_SUCCESS;
}

static int dfuse_read_layout(dfuse_t *d) {
    if (d->iface_string == 0) {
        return PM3_EDEVNOTSUPP;
    }
    uint8_t buf[255] = {0};
    int res = usbfs_control(d->dev, 0x80, 0x06 /* GET_DESCRIPTOR */, (uint16_t)(0x0300 | d->iface_string),
                            0x0409, buf, sizeof(buf), DFU_CTRL_TIMEOUT_MS);
    if (res < 2 || buf[1] != 0x03) {
        return PM3_EIO;
    }
    int n = MIN(res, buf[0]);
    int j = 0;
    for (int i = 2; i + 1 < n && j < (int)sizeof(d->layout) - 1; i += 2) {
        d->layout[j++] = (buf[i + 1] == 0) ? (char)buf[i] : '?';
    }
    d->layout[j] = '\0';
    return PM3_SUCCESS;
}

// "@Internal Flash  /0x08000000/0256*04Kg" - possibly several /addr/segs groups,
// segments separated by commas: <count>*<size><unit B|K|M| ><type a..g>
static int dfuse_parse_layout(dfuse_t *d) {
    char copy[sizeof(d->layout)];
    strncpy(copy, d->layout, sizeof(copy) - 1);
    copy[sizeof(copy) - 1] = '\0';

    char *save = NULL;
    char *tok = strtok_r(copy, "/", &save);   // memory name
    if (tok == NULL || tok[0] != '@') {
        return PM3_ESOFT;
    }

    d->num_segs = 0;
    while ((tok = strtok_r(NULL, "/", &save)) != NULL) {
        uint32_t addr = (uint32_t)strtoul(tok, NULL, 0);
        char *segs = strtok_r(NULL, "/", &save);
        if (segs == NULL) {
            break;
        }
        char *segsave = NULL;
        for (char *s = strtok_r(segs, ",", &segsave); s != NULL; s = strtok_r(NULL, ",", &segsave)) {
            unsigned int count = 0, size = 0;
            char unit = ' ', type = 'g';
            if (sscanf(s, "%u*%u%c%c", &count, &size, &unit, &type) < 3) {
                return PM3_ESOFT;
            }
            if (unit == 'K') {
                size *= 1024;
            } else if (unit == 'M') {
                size *= 1024 * 1024;
            } else if (unit >= 'a' && unit <= 'g') {
                type = unit;   // no unit letter, bytes
            }
            if (d->num_segs == DFUSE_MAX_SEGMENTS || count == 0 || size == 0) {
                return PM3_ESOFT;
            }
            dfuse_segment_t *seg = &d->segs[d->num_segs++];
            seg->start = addr;
            seg->page_size = size;
            seg->pages = count;
            seg->type = (uint8_t)(type & 7);
            addr += count * size;
        }
    }
    return (d->num_segs > 0) ? PM3_SUCCESS : PM3_ESOFT;
}

static const dfuse_segment_t *dfuse_segment_at(const dfuse_t *d, uint32_t addr) {
    for (int i = 0; i < d->num_segs; i++) {
        const dfuse_segment_t *s = &d->segs[i];
        if (addr >= s->start && (addr - s->start) < (uint64_t)s->page_size * s->pages) {
            return s;
        }
    }
    return NULL;
}

static int dfu_get_status(dfuse_t *d, dfu_status_t *st) {
    uint8_t buf[6] = {0};
    int res = usbfs_control(d->dev, DFU_REQ_IN, DFU_GETSTATUS, 0, d->ifnum, buf, sizeof(buf), DFU_CTRL_TIMEOUT_MS);
    if (res < 6) {
        return PM3_EIO;
    }
    st->status = buf[0];
    st->poll_ms = (uint32_t)(buf[1] | (buf[2] << 8) | (buf[3] << 16));
    st->state = buf[4];
    return PM3_SUCCESS;
}

static int dfu_simple(dfuse_t *d, uint8_t request) {
    return (usbfs_control(d->dev, DFU_REQ_OUT, request, 0, d->ifnum, NULL, 0, DFU_CTRL_TIMEOUT_MS) < 0) ? PM3_EIO : PM3_SUCCESS;
}

// Wait for the end of a download request and check it went fine.
static int dfu_wait_idle(dfuse_t *d, const char *what, uint32_t address) {
    uint64_t deadline = msclock() + DFU_BUSY_BUDGET_MS;
    dfu_status_t st;
    for (;;) {
        if (dfu_get_status(d, &st) != PM3_SUCCESS) {
            PrintAndLogEx(ERR, "\nDFU: no status after %s at 0x%08x", what, address);
            return PM3_EIO;
        }
        if (st.status != 0) {
            PrintAndLogEx(ERR, "\nDFU: %s at 0x%08x failed, status %u, state %s", what, address, st.status, dfu_state_name(st.state));
            return PM3_EFLASH;
        }
        if (st.state != STATE_DFU_DNBUSY && st.state != STATE_DFU_DNLOAD_SYNC) {
            return PM3_SUCCESS;
        }
        if (msclock() > deadline) {
            PrintAndLogEx(ERR, "\nDFU: %s at 0x%08x timed out", what, address);
            return PM3_ETIMEOUT;
        }
        msleep(MAX(st.poll_ms, 1));
    }
}

static int dfuse_command(dfuse_t *d, uint8_t cmd, uint32_t address) {
    uint8_t buf[5] = { cmd, address & 0xFF, (address >> 8) & 0xFF, (address >> 16) & 0xFF, (address >> 24) & 0xFF };
    if (usbfs_control(d->dev, DFU_REQ_OUT, DFU_DNLOAD, 0, d->ifnum, buf, sizeof(buf), DFU_CTRL_TIMEOUT_MS) < 0) {
        PrintAndLogEx(ERR, "\nDFU: command 0x%02x at 0x%08x not accepted", cmd, address);
        return PM3_EIO;
    }
    return dfu_wait_idle(d, (cmd == DFUSE_ERASE_PAGE) ? "erase" : "set address", address);
}

static int dfu_reset_state(dfuse_t *d) {
    dfu_status_t st;
    for (int i = 0; i < 4; i++) {
        if (dfu_get_status(d, &st) != PM3_SUCCESS) {
            return PM3_EIO;
        }
        if (st.state == STATE_DFU_IDLE) {
            return PM3_SUCCESS;
        }
        dfu_simple(d, (st.state == STATE_DFU_ERROR) ? DFU_CLRSTATUS : DFU_ABORT);
    }
    PrintAndLogEx(ERR, "DFU: loader stuck in state %s", dfu_state_name(st.state));
    return PM3_EFATAL;
}

static void dfuse_progress(const char *what, uint32_t done, uint32_t total) {
    static const char *last_what = NULL;
    static unsigned int last_pct = 0;
    unsigned int pct = (total == 0) ? 100 : (unsigned int)((uint64_t)done * 100 / total);
    // redraw on every 10%, a slow phone terminal chokes on more
    if (what != last_what || pct / 10 != last_pct / 10 || pct == 100) {
        PrintAndLogEx(INPLACE, "%s %3u%%", what, pct);
        last_what = what;
        last_pct = pct;
    }
}

static int dfuse_erase(dfuse_t *d, uint32_t address, uint32_t len) {
    uint32_t end = address + len;
    uint32_t addr = address;
    while (addr < end) {
        const dfuse_segment_t *seg = dfuse_segment_at(d, addr);
        uint32_t page = addr - ((addr - seg->start) % seg->page_size);
        if (seg->type & 2) {
            int res = dfuse_command(d, DFUSE_ERASE_PAGE, page);
            if (res != PM3_SUCCESS) {
                return res;
            }
        }
        addr = page + seg->page_size;
        dfuse_progress("Erasing ", MIN(addr, end) - address, len);
    }
    PrintAndLogEx(NORMAL, "");
    return PM3_SUCCESS;
}

static int dfuse_write(dfuse_t *d, uint32_t address, const uint8_t *data, uint32_t len) {
    for (uint32_t off = 0; off < len; off += d->xfer_size) {
        uint16_t n = (uint16_t)MIN(d->xfer_size, len - off);
        int res = dfuse_command(d, DFUSE_SET_ADDRESS, address + off);
        if (res != PM3_SUCCESS) {
            return res;
        }
        // block 2 lands at the address pointer
        if (usbfs_control(d->dev, DFU_REQ_OUT, DFU_DNLOAD, 2, d->ifnum, (uint8_t *)data + off, n, DFU_CTRL_TIMEOUT_MS) < 0) {
            PrintAndLogEx(ERR, "\nDFU: write at 0x%08x not accepted", address + off);
            return PM3_EIO;
        }
        res = dfu_wait_idle(d, "write", address + off);
        if (res != PM3_SUCCESS) {
            return res;
        }
        dfuse_progress("Writing ", off + n, len);
    }
    PrintAndLogEx(NORMAL, "");
    return PM3_SUCCESS;
}

static int dfuse_verify(dfuse_t *d, uint32_t address, const uint8_t *data, uint32_t len) {
    uint8_t *buf = malloc(d->xfer_size);
    if (buf == NULL) {
        return PM3_EMALLOC;
    }

    int res = dfuse_command(d, DFUSE_SET_ADDRESS, address);
    if (res == PM3_SUCCESS) {
        dfu_simple(d, DFU_ABORT);
    }

    for (uint32_t off = 0, block = 2; res == PM3_SUCCESS && off < len; off += d->xfer_size, block++) {
        uint16_t n = (uint16_t)MIN(d->xfer_size, len - off);
        // always ask for full blocks: block numbers are in units of xfer_size
        int got = usbfs_control(d->dev, DFU_REQ_IN, DFU_UPLOAD, (uint16_t)block, d->ifnum, buf, d->xfer_size, DFU_CTRL_TIMEOUT_MS);
        if (got < n) {
            PrintAndLogEx(NORMAL, "");
            PrintAndLogEx(WARNING, "DFU: this loader does not read back, verification skipped");
            res = PM3_ENOTIMPL;
            break;
        }
        if (memcmp(buf, data + off, n) != 0) {
            PrintAndLogEx(NORMAL, "");
            PrintAndLogEx(ERR, "DFU: " _RED_("verification failed") " around 0x%08x", address + off);
            res = PM3_EFLASH;
            break;
        }
        dfuse_progress("Verifying", off + n, len);
    }
    if (res == PM3_SUCCESS) {
        PrintAndLogEx(NORMAL, "");
    }
    free(buf);
    dfu_reset_state(d);
    return res;
}

static void dfuse_leave(dfuse_t *d, uint32_t address) {
    if (dfuse_command(d, DFUSE_SET_ADDRESS, address) != PM3_SUCCESS) {
        return;
    }
    // zero length download, then the status request makes the loader jump
    usbfs_control(d->dev, DFU_REQ_OUT, DFU_DNLOAD, 2, d->ifnum, NULL, 0, DFU_CTRL_TIMEOUT_MS);
    dfu_status_t st;
    dfu_get_status(d, &st);
}

int dfuse_flash_file(const char *port, const char *filename, uint32_t address) {

    if (usbfs_is_port(port) == false) {
        PrintAndLogEx(ERR, "--dfu works on a " _YELLOW_("usb:") " port, e.g. " _YELLOW_("usb:/dev/bus/usb/001/005") " or through termux-usb");
        PrintAndLogEx(HINT, "Hint: on a PC simply use " _YELLOW_("dfu-util -d 2e3c:df11 -a 0 -s 0x%08x:leave -D %s"), address, filename);
        return PM3_EINVARG;
    }
    if (usbfs_supported() == false) {
        usbfs_open(port, false);   // prints why
        return PM3_ENOTIMPL;
    }

    uint8_t *image = NULL;
    size_t image_len = 0;
    if (loadFile_safeEx(filename, "", (void **)&image, &image_len, false) != PM3_SUCCESS || image_len == 0) {
        PrintAndLogEx(ERR, "Cannot read image " _YELLOW_("%s"), filename);
        free(image);
        return PM3_EFILE;
    }
    if (image_len >= 4 && memcmp(image, "\x7f" "ELF", 4) == 0) {
        PrintAndLogEx(ERR, _YELLOW_("%s") " is an ELF file, --dfu takes a raw .bin (e.g. recovery/recovery.bin)", filename);
        free(image);
        return PM3_EINVARG;
    }

    // the loader programs whole words, pad the tail with erased flash
    uint32_t len = (uint32_t)((image_len + 3) & ~(size_t)3);
    if (len != image_len) {
        uint8_t *tmp = realloc(image, len);
        if (tmp == NULL) {
            free(image);
            return PM3_EMALLOC;
        }
        image = tmp;
        memset(image + image_len, 0xFF, len - image_len);
    }

    dfuse_t d;
    memset(&d, 0, sizeof(d));
    int res = PM3_EIO;

    d.dev = usbfs_open(port, false);
    if (d.dev == NULL) {
        free(image);
        return PM3_EIO;
    }

    PrintAndLogEx(INFO, "USB device " _YELLOW_("%04x:%04x"), usbfs_vid(d.dev), usbfs_pid(d.dev));
    if (dfuse_find_interface(&d) != PM3_SUCCESS) {
        PrintAndLogEx(ERR, "This device has no DFU interface");
        if (usbfs_vid(d.dev) == 0x9AC4) {
            PrintAndLogEx(HINT, "Hint: it runs the Proxmark bootloader or firmware, use " _YELLOW_("--flash") " instead");
        } else {
            PrintAndLogEx(HINT, "Hint: unplug, then plug it back in while holding the button until the LEDs go off");
        }
        res = PM3_EDEVNOTSUPP;
        goto out;
    }
    if (usbfs_vid(d.dev) != PM5_DFU_VID || usbfs_pid(d.dev) != PM5_DFU_PID) {
        PrintAndLogEx(WARNING, "Not the Proxmark5 MCU loader (%04x:%04x), continuing anyway", PM5_DFU_VID, PM5_DFU_PID);
    }

    if (usbfs_claim(d.dev, d.ifnum) != 0) {
        PrintAndLogEx(ERR, "Cannot claim the DFU interface");
        goto out;
    }

    if (dfuse_read_layout(&d) != PM3_SUCCESS || dfuse_parse_layout(&d) != PM3_SUCCESS) {
        PrintAndLogEx(ERR, "Cannot read the flash layout of the loader (\"%s\")", d.layout);
        res = PM3_EDEVNOTSUPP;
        goto out;
    }
    PrintAndLogEx(INFO, "Loader memory " _YELLOW_("%s") ", transfer size %u", d.layout, d.xfer_size);

    // the whole image has to fit into erasable, writable flash
    for (uint32_t a = address; a < address + len;) {
        const dfuse_segment_t *seg = dfuse_segment_at(&d, a);
        if (seg == NULL || (seg->type & 6) != 6) {
            PrintAndLogEx(ERR, "Image does not fit: 0x%08x is not writable flash", a);
            res = PM3_EOUTOFBOUND;
            goto out;
        }
        a = seg->start + seg->page_size * seg->pages;
    }

    res = dfu_reset_state(&d);
    if (res != PM3_SUCCESS) {
        goto out;
    }

    PrintAndLogEx(SUCCESS, "Writing " _YELLOW_("%s") " (%zu bytes) at 0x%08x", filename, image_len, address);
    PrintAndLogEx(INFO, "Keep the button pressed and the cable still until it's done");

    res = dfuse_erase(&d, address, len);
    if (res == PM3_SUCCESS) {
        res = dfuse_write(&d, address, image, len);
    }
    if (res == PM3_SUCCESS) {
        res = dfuse_verify(&d, address, image, len);
        if (res == PM3_ENOTIMPL) {
            res = PM3_SUCCESS;
        }
    }

    if (res == PM3_SUCCESS) {
        dfuse_leave(&d, address);
        PrintAndLogEx(SUCCESS, _GREEN_("DFU write done") ", release the button, unplug and plug the Proxmark5 back in");
    } else {
        PrintAndLogEx(ERR, "DFU write " _RED_("failed") ". Nothing outside the image was touched, re-enter DFU mode and retry");
    }

out:
    usbfs_close(d.dev);
    free(image);
    return res;
}
