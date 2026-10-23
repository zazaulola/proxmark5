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
// USB-CDC over an already opened usbfs file descriptor (libusb), see header.
//-----------------------------------------------------------------------------
#ifdef HAVE_LIBUSB

#define _DEFAULT_SOURCE

#include "usbfd_posix.h"

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <libusb.h>

#include "ui.h"

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

#define USBFD_XFER_SIZE      4096   // multiple of any bulk wMaxPacketSize
#define USBFD_POLL_MS        200    // how often the pumps look at the stop flag
#define USBFD_TX_TIMEOUT_MS  3000

// CDC ACM class requests
#define CDC_SET_LINE_CODING         0x20
#define CDC_SET_CONTROL_LINE_STATE  0x22

struct usbfd_conn {
    libusb_context *ctx;
    libusb_device_handle *h;
    int comm_if;              // CDC communication interface, -1 if none
    int data_if;              // CDC data interface
    uint8_t ep_in;
    uint8_t ep_out;
    int app_fd;               // handed to uart_posix.c
    int dev_fd;               // our end of the socketpair
    volatile bool running;
    bool rx_started;
    bool tx_started;
    pthread_t rx_thread;
    pthread_t tx_thread;
};

int usbfd_parse(const char *arg) {
    if (arg == NULL || *arg == '\0') {
        arg = getenv(USBFD_ENV_VAR);
        if (arg == NULL || *arg == '\0') {
            return -1;
        }
    }
    char *end = NULL;
    long v = strtol(arg, &end, 10);
    if (end == arg || *end != '\0' || v < 0 || v > 65535) {
        return -1;
    }
    return (int)v;
}

// Device -> client. Ends the link (EOF on app_fd) when the device goes away.
static void *usbfd_rx_loop(void *arg) {
    usbfd_conn_t *c = (usbfd_conn_t *)arg;
    uint8_t buf[USBFD_XFER_SIZE];

    while (c->running) {
        int got = 0;
        int r = libusb_bulk_transfer(c->h, c->ep_in, buf, sizeof(buf), &got, USBFD_POLL_MS);

        if (r == LIBUSB_ERROR_PIPE) {
            libusb_clear_halt(c->h, c->ep_in);
            continue;
        }
        if (r != 0 && r != LIBUSB_ERROR_TIMEOUT && r != LIBUSB_ERROR_INTERRUPTED) {
            break;   // NO_DEVICE, IO, ...
        }

        int off = 0;
        while (off < got) {
            ssize_t w = send(c->dev_fd, buf + off, got - off, MSG_NOSIGNAL);
            if (w < 0) {
                if (errno == EINTR) {
                    continue;
                }
                goto out;   // client side closed
            }
            off += (int)w;
        }
    }
out:
    shutdown(c->dev_fd, SHUT_RDWR);
    return NULL;
}

// Client -> device.
static void *usbfd_tx_loop(void *arg) {
    usbfd_conn_t *c = (usbfd_conn_t *)arg;
    uint8_t buf[USBFD_XFER_SIZE];

    while (c->running) {
        struct pollfd pfd = { .fd = c->dev_fd, .events = POLLIN };
        int pr = poll(&pfd, 1, USBFD_POLL_MS);
        if (pr == 0 || (pr < 0 && errno == EINTR)) {
            continue;
        }
        if (pr < 0) {
            break;
        }

        ssize_t n = recv(c->dev_fd, buf, sizeof(buf), MSG_DONTWAIT);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
            continue;
        }
        if (n <= 0) {
            break;   // client side closed, or RX loop shut the socket down
        }

        int off = 0;
        while (off < n && c->running) {
            int sent = 0;
            int r = libusb_bulk_transfer(c->h, c->ep_out, buf + off, (int)n - off, &sent, USBFD_TX_TIMEOUT_MS);
            off += sent;
            if (r == LIBUSB_ERROR_PIPE) {
                libusb_clear_halt(c->h, c->ep_out);
                continue;
            }
            if (r != 0 && r != LIBUSB_ERROR_TIMEOUT) {
                goto out;
            }
        }
    }
out:
    shutdown(c->dev_fd, SHUT_RDWR);
    return NULL;
}

// Find the CDC data interface (bulk IN + bulk OUT) and, if present, the
// communication interface that controls it.
static bool usbfd_find_endpoints(usbfd_conn_t *c) {
    libusb_device *dev = libusb_get_device(c->h);
    struct libusb_config_descriptor *cfg = NULL;
    if (libusb_get_active_config_descriptor(dev, &cfg) != 0) {
        if (libusb_get_config_descriptor(dev, 0, &cfg) != 0) {
            return false;
        }
    }

    c->comm_if = -1;
    c->data_if = -1;
    for (int i = 0; i < cfg->bNumInterfaces; i++) {
        if (cfg->interface[i].num_altsetting < 1) {
            continue;
        }
        const struct libusb_interface_descriptor *id = &cfg->interface[i].altsetting[0];

        if (id->bInterfaceClass == LIBUSB_CLASS_COMM && c->comm_if < 0) {
            c->comm_if = id->bInterfaceNumber;
            continue;
        }

        uint8_t in = 0, out = 0;
        for (int e = 0; e < id->bNumEndpoints; e++) {
            const struct libusb_endpoint_descriptor *ep = &id->endpoint[e];
            if ((ep->bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) != LIBUSB_TRANSFER_TYPE_BULK) {
                continue;
            }
            if (ep->bEndpointAddress & LIBUSB_ENDPOINT_IN) {
                in = ep->bEndpointAddress;
            } else {
                out = ep->bEndpointAddress;
            }
        }
        if (in && out && c->data_if < 0) {
            c->data_if = id->bInterfaceNumber;
            c->ep_in = in;
            c->ep_out = out;
        }
    }
    libusb_free_config_descriptor(cfg);
    return c->data_if >= 0;
}

static void usbfd_release(usbfd_conn_t *c) {
    if (c->h) {
        if (c->data_if >= 0) {
            libusb_release_interface(c->h, c->data_if);
        }
        if (c->comm_if >= 0) {
            libusb_release_interface(c->h, c->comm_if);
        }
        // For a wrapped device libusb_close() leaves the usbfs fd open.
        libusb_close(c->h);
    }
    if (c->ctx) {
        libusb_exit(c->ctx);
    }
    if (c->dev_fd >= 0) {
        close(c->dev_fd);
    }
    if (c->app_fd >= 0) {
        close(c->app_fd);
    }
    free(c);
}

usbfd_conn_t *usbfd_open(int sys_fd, int *app_fd, bool silent) {
    usbfd_conn_t *c = calloc(1, sizeof(usbfd_conn_t));
    if (c == NULL) {
        return NULL;
    }
    c->comm_if = -1;
    c->data_if = -1;
    c->app_fd = -1;
    c->dev_fd = -1;

#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000108)
    // No bus enumeration: on Android an app may only touch the fd it was given.
    libusb_set_option(NULL, LIBUSB_OPTION_WEAK_AUTHORITY);
#endif

    int r = libusb_init(&c->ctx);
    if (r != 0) {
        if (silent == false) {
            PrintAndLogEx(ERR, "usbfd: libusb init failed: %s", libusb_error_name(r));
        }
        c->ctx = NULL;
        usbfd_release(c);
        return NULL;
    }

    r = libusb_wrap_sys_device(c->ctx, (intptr_t)sys_fd, &c->h);
    if (r != 0) {
        if (silent == false) {
            PrintAndLogEx(ERR, "usbfd: cannot use fd %d as a USB device: %s", sys_fd, libusb_error_name(r));
            PrintAndLogEx(HINT, "Hint: start the client through " _YELLOW_("termux-usb -e") ", e.g. with " _YELLOW_("pm3-termux-usb"));
        }
        c->h = NULL;
        usbfd_release(c);
        return NULL;
    }

    if (usbfd_find_endpoints(c) == false) {
        if (silent == false) {
            PrintAndLogEx(ERR, "usbfd: no CDC data interface on this device");
        }
        usbfd_release(c);
        return NULL;
    }

    // A kernel cdc_acm driver can only be bound on rooted devices; try anyway.
    libusb_set_auto_detach_kernel_driver(c->h, 1);

    if (c->comm_if >= 0 && libusb_claim_interface(c->h, c->comm_if) != 0) {
        c->comm_if = -1;   // not fatal, only needed for the control requests
    }
    r = libusb_claim_interface(c->h, c->data_if);
    if (r != 0) {
        if (silent == false) {
            PrintAndLogEx(ERR, "usbfd: cannot claim interface %d: %s", c->data_if, libusb_error_name(r));
        }
        c->data_if = -1;
        usbfd_release(c);
        return NULL;
    }

    if (c->comm_if >= 0) {
        // 115200 8N1 (ignored by the Proxmark, but some stacks want it), then DTR | RTS.
        uint8_t lc[7] = { 0x00, 0xC2, 0x01, 0x00, 0, 0, 8 };
        libusb_control_transfer(c->h, LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
                                CDC_SET_LINE_CODING, 0, c->comm_if, lc, sizeof(lc), 1000);
        libusb_control_transfer(c->h, LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
                                CDC_SET_CONTROL_LINE_STATE, 0x03, c->comm_if, NULL, 0, 1000);
    }

    // Drop whatever an earlier session left in the IN pipe.
    uint8_t junk[USBFD_XFER_SIZE];
    int got = 0;
    while (libusb_bulk_transfer(c->h, c->ep_in, junk, sizeof(junk), &got, 50) == 0 && got > 0) { };

    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        usbfd_release(c);
        return NULL;
    }
    c->app_fd = sv[0];
    c->dev_fd = sv[1];

    c->running = true;
    if (pthread_create(&c->rx_thread, NULL, usbfd_rx_loop, c) == 0) {
        c->rx_started = true;
    }
    if (pthread_create(&c->tx_thread, NULL, usbfd_tx_loop, c) == 0) {
        c->tx_started = true;
    }
    if (c->rx_started == false || c->tx_started == false) {
        usbfd_close(c);
        return NULL;
    }

    *app_fd = c->app_fd;
    return c;
}

void usbfd_close(usbfd_conn_t *c) {
    if (c == NULL) {
        return;
    }
    c->running = false;
    // Closing our side unblocks a pump stuck in send()/poll().
    if (c->app_fd >= 0) {
        close(c->app_fd);
        c->app_fd = -1;
    }
    if (c->rx_started) {
        pthread_join(c->rx_thread, NULL);
    }
    if (c->tx_started) {
        pthread_join(c->tx_thread, NULL);
    }
    usbfd_release(c);
}

#endif // HAVE_LIBUSB
