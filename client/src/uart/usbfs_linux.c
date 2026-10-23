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
// Userspace USB transport over Linux usbfs - see usbfs_linux.h.
//-----------------------------------------------------------------------------

#include "usbfs_linux.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ui.h"        // PrintAndLogEx

#define USBFS_PORT_ALIAS "usbfd:"

bool usbfs_is_port(const char *port_name) {
    return (port_name != NULL) &&
           ((strncmp(port_name, USBFS_PORT_PREFIX, strlen(USBFS_PORT_PREFIX)) == 0) ||
            (strncmp(port_name, USBFS_PORT_ALIAS, strlen(USBFS_PORT_ALIAS)) == 0));
}

#if defined(__linux__)

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

#define USBFS_MAX_IFACES      8
#define USBFS_MAX_EPS         4
#define USBFS_RX_URBS         16          // bulk IN reads kept in flight
#define USBFS_RX_BUF_SIZE     (64 * 1024) // bytes received but not yet consumed
#define USBFS_TX_CHUNK        (16 * 1024) // usbfs caps a single bulk transfer
#define USBFS_TX_TIMEOUT_MS   3000
#define USBFS_CTRL_TIMEOUT_MS 1000

// CDC class requests
#define CDC_SET_LINE_CODING        0x20
#define CDC_SET_CONTROL_LINE_STATE 0x22

typedef struct {
    uint8_t  number;
    uint8_t  alt;
    uint8_t  cls;
    uint8_t  subcls;
    uint8_t  num_eps;
    uint8_t  ep_addr[USBFS_MAX_EPS];
    uint8_t  ep_attr[USBFS_MAX_EPS];
    uint16_t ep_maxp[USBFS_MAX_EPS];
} usbfs_iface_t;

struct usbfs_dev {
    int      fd;
    bool     owns_fd;      // false for a descriptor inherited from termux-usb: never close it
    uint16_t vid;
    uint16_t pid;
    uint8_t  desc[4096];
    size_t   desc_len;

    usbfs_iface_t ifaces[USBFS_MAX_IFACES];
    uint8_t  num_ifaces;
    uint32_t claimed;      // bitmask of claimed interface numbers

    // CDC data pipe
    bool     cdc_running;
    int      ctrl_if;
    uint8_t  ep_in;
    uint8_t  ep_out;
    uint16_t maxp_in;
    struct usbdevfs_urb urbs[USBFS_RX_URBS];
    uint8_t *urb_bufs;
    uint8_t  rx[USBFS_RX_BUF_SIZE];
    size_t   rx_head;      // next byte to hand out
    size_t   rx_tail;      // next free byte
};

bool usbfs_supported(void) {
    return true;
}

static int parse_descriptors(usbfs_dev_t *dev) {
    uint8_t *raw = dev->desc;

    // Reading a usbfs node yields the device descriptor followed by every
    // configuration descriptor, as cached by the kernel. No bus traffic.
    if (lseek(dev->fd, 0, SEEK_SET) < 0) {
        return -errno;
    }
    ssize_t n = read(dev->fd, raw, sizeof(dev->desc));
    if (n < USB_DT_DEVICE_SIZE) {
        return (n < 0) ? -errno : -EIO;
    }
    if (raw[1] != USB_DT_DEVICE) {
        return -EIO;
    }

    dev->desc_len = (size_t)n;
    dev->vid = (uint16_t)(raw[8] | (raw[9] << 8));
    dev->pid = (uint16_t)(raw[10] | (raw[11] << 8));

    // Walk the first configuration only, Proxmarks and DFU loaders have one.
    size_t pos = raw[0];
    bool in_config = false;
    usbfs_iface_t *cur = NULL;
    dev->num_ifaces = 0;

    while (pos + 2 <= (size_t)n) {
        uint8_t len = raw[pos];
        uint8_t type = raw[pos + 1];
        if (len < 2 || pos + len > (size_t)n) {
            break;
        }

        if (type == USB_DT_CONFIG) {
            if (in_config) {
                break;
            }
            in_config = true;
        } else if (type == USB_DT_INTERFACE && len >= USB_DT_INTERFACE_SIZE) {
            cur = NULL;
            if (dev->num_ifaces < USBFS_MAX_IFACES) {
                cur = &dev->ifaces[dev->num_ifaces++];
                memset(cur, 0, sizeof(*cur));
                cur->number = raw[pos + 2];
                cur->alt = raw[pos + 3];
                cur->cls = raw[pos + 5];
                cur->subcls = raw[pos + 6];
            }
        } else if (type == USB_DT_ENDPOINT && len >= USB_DT_ENDPOINT_SIZE && cur != NULL) {
            if (cur->num_eps < USBFS_MAX_EPS) {
                cur->ep_addr[cur->num_eps] = raw[pos + 2];
                cur->ep_attr[cur->num_eps] = raw[pos + 3];
                cur->ep_maxp[cur->num_eps] = (uint16_t)((raw[pos + 4] | (raw[pos + 5] << 8)) & 0x7FF);
                cur->num_eps++;
            }
        }
        pos += len;
    }
    return 0;
}

usbfs_dev_t *usbfs_open(const char *port_name, bool silent) {
    if (usbfs_is_port(port_name) == false) {
        return NULL;
    }
    const char *arg = strchr(port_name, ':') + 1;

    usbfs_dev_t *dev = calloc(1, sizeof(usbfs_dev_t));
    if (dev == NULL) {
        return NULL;
    }
    dev->fd = -1;
    dev->ctrl_if = -1;

    if (arg[0] == '/') {
        dev->fd = open(arg, O_RDWR | O_CLOEXEC);
        dev->owns_fd = true;
        if (dev->fd < 0 && silent == false) {
            PrintAndLogEx(ERR, "error: cannot open " _YELLOW_("%s") " (%s)", arg, strerror(errno));
        }
    } else {
        if (arg[0] == '\0') {
            arg = getenv("TERMUX_USB_FD");
            if (arg == NULL) {
                if (silent == false) {
                    PrintAndLogEx(ERR, "error: " _YELLOW_("usb:") " needs a descriptor number, a /dev/bus/usb path or $TERMUX_USB_FD");
                }
                free(dev);
                return NULL;
            }
        }
        char *end = NULL;
        long fd = strtol(arg, &end, 10);
        if (end == arg || *end != '\0' || fd < 0 || fd > 65535 || fcntl((int)fd, F_GETFD) < 0) {
            if (silent == false) {
                PrintAndLogEx(ERR, "error: " _YELLOW_("%s") " is not an open USB descriptor", arg);
                PrintAndLogEx(HINT, "Hint: in Termux start the client with " _YELLOW_("pm3-termux-usb") ", see doc/termux_notes.md");
            }
            free(dev);
            return NULL;
        }
        dev->fd = (int)fd;
        dev->owns_fd = false;
    }

    if (dev->fd < 0) {
        free(dev);
        return NULL;
    }

    int res = parse_descriptors(dev);
    if (res < 0) {
        if (silent == false) {
            PrintAndLogEx(ERR, "error: cannot read USB descriptors (%s)", strerror(-res));
        }
        usbfs_close(dev);
        return NULL;
    }
    PrintAndLogEx(DEBUG, "usbfs: device %04x:%04x, %u interface(s)", dev->vid, dev->pid, dev->num_ifaces);
    return dev;
}

uint16_t usbfs_vid(const usbfs_dev_t *dev) {
    return dev->vid;
}

uint16_t usbfs_pid(const usbfs_dev_t *dev) {
    return dev->pid;
}

const uint8_t *usbfs_descriptors(const usbfs_dev_t *dev, size_t *len) {
    *len = dev->desc_len;
    return dev->desc;
}

int usbfs_find_interface(const usbfs_dev_t *dev, uint8_t cls, uint8_t subcls) {
    for (int i = 0; i < dev->num_ifaces; i++) {
        if (dev->ifaces[i].alt == 0 && dev->ifaces[i].cls == cls && dev->ifaces[i].subcls == subcls) {
            return dev->ifaces[i].number;
        }
    }
    return -1;
}

int usbfs_claim(usbfs_dev_t *dev, uint8_t ifnum) {
    // Kick out a kernel driver (cdc_acm) if one is bound. Failure only means
    // there was none, which is the usual case on Android.
    struct usbdevfs_ioctl cmd = {
        .ifno = ifnum,
        .ioctl_code = USBDEVFS_DISCONNECT,
        .data = NULL,
    };
    ioctl(dev->fd, USBDEVFS_IOCTL, &cmd);

    unsigned int n = ifnum;
    if (ioctl(dev->fd, USBDEVFS_CLAIMINTERFACE, &n) < 0) {
        return -errno;
    }
    if (ifnum < 32) {
        dev->claimed |= (1U << ifnum);
    }
    return 0;
}

int usbfs_release(usbfs_dev_t *dev, uint8_t ifnum) {
    unsigned int n = ifnum;
    if (ifnum < 32) {
        dev->claimed &= ~(1U << ifnum);
    }
    return (ioctl(dev->fd, USBDEVFS_RELEASEINTERFACE, &n) < 0) ? -errno : 0;
}

int usbfs_control(usbfs_dev_t *dev, uint8_t request_type, uint8_t request, uint16_t value,
                  uint16_t index, uint8_t *data, uint16_t length, uint32_t timeout_ms) {
    struct usbdevfs_ctrltransfer ctrl = {
        .bRequestType = request_type,
        .bRequest = request,
        .wValue = value,
        .wIndex = index,
        .wLength = length,
        .timeout = timeout_ms,
        .data = data,
    };
    int res = ioctl(dev->fd, USBDEVFS_CONTROL, &ctrl);
    return (res < 0) ? -errno : res;
}

static int submit_rx_urb(usbfs_dev_t *dev, int i) {
    struct usbdevfs_urb *urb = &dev->urbs[i];
    memset(urb, 0, sizeof(*urb));
    urb->type = USBDEVFS_URB_TYPE_BULK;
    urb->endpoint = dev->ep_in;
    urb->buffer = dev->urb_bufs + ((size_t)i * dev->maxp_in);
    urb->buffer_length = dev->maxp_in;
    urb->usercontext = (void *)(intptr_t)i;
    return (ioctl(dev->fd, USBDEVFS_SUBMITURB, urb) < 0) ? -errno : 0;
}

int usbfs_cdc_start(usbfs_dev_t *dev, bool silent) {
    const usbfs_iface_t *data_if = NULL;

    // Prefer the CDC data interface, fall back to anything with a bulk pair.
    for (int pass = 0; pass < 2 && data_if == NULL; pass++) {
        for (int i = 0; i < dev->num_ifaces && data_if == NULL; i++) {
            const usbfs_iface_t *it = &dev->ifaces[i];
            if (it->alt != 0 || (pass == 0 && it->cls != USB_CLASS_CDC_DATA)) {
                continue;
            }
            uint8_t in = 0, out = 0;
            uint16_t maxp = 0;
            for (int e = 0; e < it->num_eps; e++) {
                if ((it->ep_attr[e] & USB_ENDPOINT_XFERTYPE_MASK) != USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (it->ep_addr[e] & USB_DIR_IN) {
                    in = it->ep_addr[e];
                    maxp = it->ep_maxp[e];
                } else {
                    out = it->ep_addr[e];
                }
            }
            if (in && out) {
                data_if = it;
                dev->ep_in = in;
                dev->ep_out = out;
                dev->maxp_in = (maxp != 0) ? maxp : 64;
            }
        }
    }

    if (data_if == NULL) {
        if (silent == false) {
            PrintAndLogEx(ERR, "error: USB device %04x:%04x has no serial (CDC) interface", dev->vid, dev->pid);
        }
        return -ENODEV;
    }

    dev->ctrl_if = usbfs_find_interface(dev, USB_CLASS_COMM, 2 /* ACM */);
    if (dev->ctrl_if >= 0) {
        usbfs_claim(dev, dev->ctrl_if);
    }

    int res = usbfs_claim(dev, data_if->number);
    if (res < 0) {
        if (silent == false) {
            PrintAndLogEx(ERR, "error: cannot claim USB interface %u (%s)", data_if->number, strerror(-res));
        }
        return res;
    }

    // What the kernel cdc_acm driver would do on open: 115200 8N1, raise DTR/RTS.
    // The Proxmark ignores the values, so failures are not fatal.
    uint16_t ctrl_index = (dev->ctrl_if >= 0) ? (uint16_t)dev->ctrl_if : 0;
    uint8_t line_coding[7] = { 0x00, 0xC2, 0x01, 0x00, 0, 0, 8 };
    usbfs_control(dev, USB_TYPE_CLASS | USB_RECIP_INTERFACE | USB_DIR_OUT, CDC_SET_LINE_CODING,
                  0, ctrl_index, line_coding, sizeof(line_coding), USBFS_CTRL_TIMEOUT_MS);
    usbfs_control(dev, USB_TYPE_CLASS | USB_RECIP_INTERFACE | USB_DIR_OUT, CDC_SET_CONTROL_LINE_STATE,
                  0x0003, ctrl_index, NULL, 0, USBFS_CTRL_TIMEOUT_MS);

    // One packet per URB: a URB completes on every packet, so no byte waits
    // on a half filled buffer and a timeout never has to cancel a transfer.
    dev->urb_bufs = calloc(USBFS_RX_URBS, dev->maxp_in);
    if (dev->urb_bufs == NULL) {
        return -ENOMEM;
    }
    for (int i = 0; i < USBFS_RX_URBS; i++) {
        res = submit_rx_urb(dev, i);
        if (res < 0) {
            if (silent == false) {
                PrintAndLogEx(ERR, "error: USB read request failed (%s)", strerror(-res));
            }
            return res;
        }
    }

    dev->rx_head = 0;
    dev->rx_tail = 0;
    dev->cdc_running = true;
    PrintAndLogEx(DEBUG, "usbfs: CDC on interface %u, IN 0x%02x (%u) OUT 0x%02x",
                  data_if->number, dev->ep_in, dev->maxp_in, dev->ep_out);
    return 0;
}

// Collect every completed read and queue it again.
// Returns the number of bytes added, or negative if the device is gone.
static int reap_rx(usbfs_dev_t *dev) {
    int added = 0;
    for (;;) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(dev->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
            if (errno == EAGAIN) {
                return added;
            }
            if (errno == EINTR) {
                continue;
            }
            return -errno;
        }

        int i = (int)(intptr_t)urb->usercontext;

        if (urb->status == -EPIPE) {
            unsigned int ep = dev->ep_in;
            ioctl(dev->fd, USBDEVFS_CLEAR_HALT, &ep);
        } else if (urb->status == -ESHUTDOWN || urb->status == -ENODEV || urb->status == -EPROTO) {
            return -ENODEV;
        } else if (urb->status == 0 && urb->actual_length > 0) {
            // Compact before appending when the tail would run off the end.
            size_t len = (size_t)urb->actual_length;
            if (dev->rx_tail + len > sizeof(dev->rx)) {
                memmove(dev->rx, dev->rx + dev->rx_head, dev->rx_tail - dev->rx_head);
                dev->rx_tail -= dev->rx_head;
                dev->rx_head = 0;
            }
            if (dev->rx_tail + len > sizeof(dev->rx)) {
                PrintAndLogEx(WARNING, "usbfs: receive buffer overflow, %zu bytes dropped", len);
            } else {
                memcpy(dev->rx + dev->rx_tail, urb->buffer, len);
                dev->rx_tail += len;
                added += (int)len;
            }
        }

        int res = submit_rx_urb(dev, i);
        if (res < 0) {
            return res;
        }
    }
}

int usbfs_cdc_recv(usbfs_dev_t *dev, uint8_t *buf, size_t maxlen, size_t *got, int timeout_ms, int wake_fd) {
    *got = 0;
    if (dev->cdc_running == false) {
        return -ENODEV;
    }

    for (;;) {
        size_t avail = dev->rx_tail - dev->rx_head;
        if (avail > 0) {
            size_t n = MIN(avail, maxlen - *got);
            memcpy(buf + *got, dev->rx + dev->rx_head, n);
            dev->rx_head += n;
            *got += n;
            if (dev->rx_head == dev->rx_tail) {
                dev->rx_head = 0;
                dev->rx_tail = 0;
            }
            if (*got == maxlen) {
                return 0;
            }
        }

        int res = reap_rx(dev);
        if (res < 0) {
            return res;
        }
        if (res > 0) {
            continue;
        }

        // Nothing queued: sleep until a read completes (usbfs flags that as
        // writable), the device disappears, or someone wants to send.
        struct pollfd pfd[2] = {
            { .fd = dev->fd, .events = POLLOUT },
            { .fd = wake_fd, .events = POLLIN },
        };
        bool watch_wake = (wake_fd >= 0) && (*got == 0);
        res = poll(pfd, watch_wake ? 2 : 1, timeout_ms);
        if (res < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -errno;
        }
        if (res == 0) {
            return 0; // timeout, *got may be partial
        }
        if (pfd[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            return -ENODEV;
        }
        if (watch_wake && (pfd[1].revents & POLLIN)) {
            uint8_t drain[64];
            while (read(wake_fd, drain, sizeof(drain)) > 0) { };
            if ((pfd[0].revents & POLLOUT) == 0) {
                return 0;
            }
        }
    }
}

int usbfs_cdc_send(usbfs_dev_t *dev, const uint8_t *buf, size_t len) {
    size_t pos = 0;
    while (pos < len) {
        struct usbdevfs_bulktransfer bulk = {
            .ep = dev->ep_out,
            .len = (unsigned int)MIN(len - pos, USBFS_TX_CHUNK),
            .timeout = USBFS_TX_TIMEOUT_MS,
            .data = (void *)(buf + pos),
        };
        int res = ioctl(dev->fd, USBDEVFS_BULK, &bulk);
        if (res < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -errno;
        }
        if (res == 0) {
            return -EIO;
        }
        pos += (size_t)res;
    }
    return 0;
}

void usbfs_close(usbfs_dev_t *dev) {
    if (dev == NULL) {
        return;
    }

    if (dev->fd >= 0 && dev->cdc_running) {
        for (int i = 0; i < USBFS_RX_URBS; i++) {
            ioctl(dev->fd, USBDEVFS_DISCARDURB, &dev->urbs[i]);
        }
        // Cancelled URBs still come back through the reap queue and must be
        // collected before their buffers go away.
        for (int pending = USBFS_RX_URBS, tries = 0; pending > 0 && tries < 100; tries++) {
            struct usbdevfs_urb *urb = NULL;
            if (ioctl(dev->fd, USBDEVFS_REAPURBNDELAY, &urb) == 0) {
                pending--;
                continue;
            }
            if (errno != EAGAIN) {
                break;
            }
            usleep(10000);
        }
    }
    dev->cdc_running = false;

    if (dev->fd >= 0) {
        for (unsigned int i = 0; i < 32; i++) {
            if (dev->claimed & (1U << i)) {
                usbfs_release(dev, (uint8_t)i);
            }
        }
        if (dev->owns_fd) {
            close(dev->fd);
        }
    }
    free(dev->urb_bufs);
    free(dev);
}

#else // __linux__

bool usbfs_supported(void) {
    return false;
}

usbfs_dev_t *usbfs_open(const char *port_name, bool silent) {
    (void)port_name;
    if (silent == false) {
        PrintAndLogEx(ERR, "Sorry, " _YELLOW_("usb:") " ports are only available on Linux and Android");
    }
    return NULL;
}

uint16_t usbfs_vid(const usbfs_dev_t *dev) {
    (void)dev;
    return 0;
}

uint16_t usbfs_pid(const usbfs_dev_t *dev) {
    (void)dev;
    return 0;
}

int usbfs_cdc_start(usbfs_dev_t *dev, bool silent) {
    (void)dev;
    (void)silent;
    return -1;
}

int usbfs_cdc_recv(usbfs_dev_t *dev, uint8_t *buf, size_t maxlen, size_t *got, int timeout_ms, int wake_fd) {
    (void)dev;
    (void)buf;
    (void)maxlen;
    (void)timeout_ms;
    (void)wake_fd;
    *got = 0;
    return -1;
}

int usbfs_cdc_send(usbfs_dev_t *dev, const uint8_t *buf, size_t len) {
    (void)dev;
    (void)buf;
    (void)len;
    return -1;
}

int usbfs_control(usbfs_dev_t *dev, uint8_t request_type, uint8_t request, uint16_t value,
                  uint16_t index, uint8_t *data, uint16_t length, uint32_t timeout_ms) {
    (void)dev;
    (void)request_type;
    (void)request;
    (void)value;
    (void)index;
    (void)data;
    (void)length;
    (void)timeout_ms;
    return -1;
}

int usbfs_claim(usbfs_dev_t *dev, uint8_t ifnum) {
    (void)dev;
    (void)ifnum;
    return -1;
}

int usbfs_release(usbfs_dev_t *dev, uint8_t ifnum) {
    (void)dev;
    (void)ifnum;
    return -1;
}

const uint8_t *usbfs_descriptors(const usbfs_dev_t *dev, size_t *len) {
    (void)dev;
    *len = 0;
    return NULL;
}

int usbfs_find_interface(const usbfs_dev_t *dev, uint8_t cls, uint8_t subcls) {
    (void)dev;
    (void)cls;
    (void)subcls;
    return -1;
}

void usbfs_close(usbfs_dev_t *dev) {
    (void)dev;
}

#endif // __linux__
