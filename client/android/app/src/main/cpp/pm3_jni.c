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
// JNI bridge between the Android app and the client library (LIBPM3).
//
// The client prints to stdout: stdout and stderr go to a pipe the app reads.
// stdin is a pipe too, so that a "\n" written by Abort() ends the command loops
// that poll kbd_enter_pressed().
//-----------------------------------------------------------------------------

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "proxmark3.h"
#include "cmdmain.h"
#include "cmdparser.h"
#include "comms.h"
#include "commonutil.h"
#include "dfuse.h"
#include "fileutils.h"
#include "flash.h"
#include "preferences.h"
#include "ui.h"
#include "util.h"
#include "util_posix.h"
#include "usart_defs.h"

#define JNI_FN(name) Java_io_github_zazaulola_proxmark5_core_NativeClient_##name

static int g_stdin_wr = -1;
static int g_stdout_rd = -1;

// Returns the read end of the output pipe, or -1. Only the first call sets things up.
// home: $HOME, where resources (dictionaries, scripts) are unpacked and read from.
// dumpdir: where saved dumps go (an app directory a file manager can reach); "" keeps HOME.
JNIEXPORT jint JNICALL JNI_FN(nativeInit)(JNIEnv *env, jclass clazz, jstring jhome, jstring jdump) {
    (void) clazz;
    if (g_stdout_rd >= 0) {
        return g_stdout_rd;
    }

    const char *home = (*env)->GetStringUTFChars(env, jhome, NULL);
    if (home == NULL) {
        return -1;
    }
    setenv("HOME", home, 1);
    // dumps saved without a path land in the cwd, "/" is not writable
    int cwd_ok = chdir(home);
    (*env)->ReleaseStringUTFChars(env, jhome, home);
    if (cwd_ok != 0) {
        return -1;
    }

    int out[2], in[2];
    if (pipe(out) != 0) {
        return -1;
    }
    // A big stdout pipe so a burst of client output can't block the command thread (which
    // also drives the device) faster than the app drains it. Best effort: ignore failure.
    fcntl(out[1], F_SETPIPE_SZ, 1 << 20);
    if (pipe(in) != 0) {
        close(out[0]);
        close(out[1]);
        return -1;
    }
    fflush(stdout);
    fflush(stderr);
    dup2(out[1], STDOUT_FILENO);
    dup2(out[1], STDERR_FILENO);
    close(out[1]);
    dup2(in[0], STDIN_FILENO);
    close(in[0]);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    g_stdin_wr = in[1];
    g_stdout_rd = out[0];

    pm3_init();
    preferences_load();
    if (g_session.preferences_loaded == false) {
        preferences_save();
        g_session.preferences_loaded = true;
    }
    g_debugMode = g_session.client_debug_level;
    // the app renders ANSI colors itself
    g_session.supports_colors = true;
    g_session.emoji_mode = EMO_ALTTEXT;

    // Send dumps to a directory the user can actually reach. preferences_load()
    // resets spDump to $HOME (app-private), so override it here, every init.
    if (jdump != NULL) {
        const char *dump = (*env)->GetStringUTFChars(env, jdump, NULL);
        if (dump != NULL) {
            if (dump[0] != '\0') {
                setDefaultPath(spDump, dump);
            }
            (*env)->ReleaseStringUTFChars(env, jdump, dump);
        }
    }
    return g_stdout_rd;
}

// port: e.g. "usb:12" for a usbfs descriptor the app opened. Returns true when connected.
JNIEXPORT jboolean JNICALL JNI_FN(nativeOpen)(JNIEnv *env, jclass clazz, jstring jport) {
    (void) clazz;
    if (g_session.pm3_present) {
        return JNI_TRUE;
    }
    const char *port = (*env)->GetStringUTFChars(env, jport, NULL);
    if (port == NULL) {
        return JNI_FALSE;
    }
    OpenProxmark(&g_session.current_device, port, false, 20, false, USART_BAUD_RATE);
    (*env)->ReleaseStringUTFChars(env, jport, port);

    if (g_session.pm3_present && (TestProxmark(g_session.current_device) != PM3_SUCCESS)) {
        PrintAndLogEx(ERR, _RED_("ERROR:") " cannot communicate with the Proxmark3\n");
        CloseProxmark(g_session.current_device);
    }
    return g_session.pm3_present ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_session.pm3_present) {
        clearCommandBuffer();
        SendCommandNG(CMD_QUIT_SESSION, NULL, 0);
        msleep(100);
        CloseProxmark(g_session.current_device);
    }
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsConnected)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_session.pm3_present ? JNI_TRUE : JNI_FALSE;
}

// Runs one client command line. With grab, its output is also kept for nativeTakeGrabbed().
JNIEXPORT jint JNICALL JNI_FN(nativeExec)(JNIEnv *env, jclass clazz, jstring jcmd, jboolean grab) {
    (void) clazz;
    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    if (cmd == NULL) {
        return PM3_EMALLOC;
    }
    // drop a stale Abort() that came after the previous command ended
    kbd_enter_pressed();

    uint8_t prev = g_printAndLog;
    if (grab) {
        g_printAndLog |= PRINTANDLOG_GRAB;
    }
    int ret = CommandReceived(cmd);
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    g_printAndLog = prev;
    fflush(stdout);
    return ret;
}

// ANSI-free output grabbed since the last call, as UTF-8 bytes.
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeTakeGrabbed)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    size_t len = 0;
    if (g_grabbed_output.ptr != NULL) {
        len = g_grabbed_output.idx;
    }
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    if ((arr != NULL) && (len > 0)) {
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) g_grabbed_output.ptr);
    }
    g_grabbed_output.idx = 0;
    return arr;
}

// The command tree as text, built while the client walks its own command tables.
typedef struct {
    char *buf;
    size_t len;
    size_t cap;
} tree_buf_t;

static bool tree_reserve(tree_buf_t *b, size_t extra) {
    if (b->len + extra + 1 <= b->cap) {
        return true;
    }
    size_t cap = b->cap ? b->cap : 16384;
    while (cap < b->len + extra + 1) {
        cap *= 2;
    }
    char *tmp = realloc(b->buf, cap);
    if (tmp == NULL) {
        return false;
    }
    b->buf = tmp;
    b->cap = cap;
    return true;
}

static void tree_char(tree_buf_t *b, char c) {
    if (tree_reserve(b, 1)) {
        b->buf[b->len++] = c;
        b->buf[b->len] = '\0';
    }
}

// Tabs and newlines separate the fields and lines, so they can't be part of a text.
static void tree_text(tree_buf_t *b, const char *s) {
    if (s == NULL) {
        return;
    }
    size_t n = strlen(s);
    if (!tree_reserve(b, n)) {
        return;
    }
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        b->buf[b->len++] = (c == '\t' || c == '\n' || c == '\r') ? ' ' : c;
    }
    b->buf[b->len] = '\0';
}

// One line per command: for every level down to it "name TAB help TAB 1|0" (1 = available).
static void tree_visit(const command_t *const path[], size_t depth, void *ctx) {
    tree_buf_t *b = ctx;
    for (size_t i = 0; i < depth; i++) {
        if (i > 0) {
            tree_char(b, '\t');
        }
        tree_text(b, path[i]->Name);
        tree_char(b, '\t');
        tree_text(b, path[i]->Help);
        tree_char(b, '\t');
        tree_char(b, (path[i]->IsAvailable == NULL || path[i]->IsAvailable()) ? '1' : '0');
    }
    tree_char(b, '\n');
}

// Every command the client has, as UTF-8 text (see tree_visit). Availability reflects the
// connected device, so ask again after connecting or disconnecting.
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCommandTree)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    tree_buf_t b = {0};

    // some categories run their own parser on the walk token and would print a complaint
    uint8_t prev = g_printAndLog;
    g_printAndLog = 0;
    walkCommandsRecursive(getTopLevelCommandTable(), tree_visit, &b);
    g_printAndLog = prev;

    jsize len = (jsize) b.len;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if ((arr != NULL) && (len > 0)) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) b.buf);
    }
    free(b.buf);
    return arr;
}

// Same as pressing Enter in the console: ends loops that wait for it.
JNIEXPORT void JNICALL JNI_FN(nativeAbort)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_stdin_wr >= 0) {
        if (write(g_stdin_wr, "\n", 1) != 1) {
            // pipe full: an abort is already pending
        }
    }
}

JNIEXPORT jstring JNICALL JNI_FN(nativeClientVersion)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char temp[256] = {0};
    format_version_information_short(temp, sizeof(temp), &g_version_information);
    return (*env)->NewStringUTF(env, temp);
}

// sha256 prefix of the ARM sources the client was built from. A firmware image built
// from the same armsrc/common_arm tree carries the same value (tools/mkversion.sh),
// which is what the flasher compares before it refuses an image without --force.
JNIEXPORT jstring JNICALL JNI_FN(nativeClientArmSrc)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char armsrc[sizeof(g_version_information.armsrc) + 1] = {0};
    memcpy(armsrc, g_version_information.armsrc, sizeof(g_version_information.armsrc));
    return (*env)->NewStringUTF(env, armsrc);
}

// Flashes ELF images, like `pm3 --flash [--unlock-bootloader] [--force] --image ...`.
// Returns PM3_SUCCESS, a PM3_E* error, or FLASH_REOPEN_PORT: the device was sent to its
// bootloader, which is a new USB device, so open that one and call again with the same files.
JNIEXPORT jint JNICALL JNI_FN(nativeFlash)(JNIEnv *env, jclass clazz, jstring jport, jobjectArray jfiles,
                                           jboolean unlock_bl, jboolean force) {
    (void) clazz;
    jsize num = (*env)->GetArrayLength(env, jfiles);
    if ((num <= 0) || (num > FLASH_MAX_FILES)) {
        return PM3_EINVARG;
    }

    char *files[FLASH_MAX_FILES] = {0};
    char *port = NULL;
    int ret = PM3_EMALLOC;

    const char *cport = (*env)->GetStringUTFChars(env, jport, NULL);
    if (cport == NULL) {
        goto out;
    }
    // flash_pm3() wants a writable port name
    port = strdup(cport);
    (*env)->ReleaseStringUTFChars(env, jport, cport);
    if (port == NULL) {
        goto out;
    }

    for (jsize i = 0; i < num; i++) {
        jstring jfile = (jstring)(*env)->GetObjectArrayElement(env, jfiles, i);
        if (jfile == NULL) {
            ret = PM3_EINVARG;
            goto out;
        }
        const char *cfile = (*env)->GetStringUTFChars(env, jfile, NULL);
        if (cfile != NULL) {
            files[i] = strdup(cfile);
            (*env)->ReleaseStringUTFChars(env, jfile, cfile);
        }
        (*env)->DeleteLocalRef(env, jfile);
        if (files[i] == NULL) {
            goto out;
        }
    }

    // the flasher opens the port itself
    if (g_session.pm3_present) {
        CloseProxmark(g_session.current_device);
    }
    ret = flash_pm3(port, (uint8_t) num, (const char **) files, unlock_bl == JNI_TRUE, force == JNI_TRUE);
    fflush(stdout);

out:
    for (jsize i = 0; i < num; i++) {
        free(files[i]);
    }
    free(port);
    return ret;
}

// Writes a raw image (e.g. recovery.bin) through the PM5 AT32 ROM DFU loader, like `pm3 --dfu`.
JNIEXPORT jint JNICALL JNI_FN(nativeDfuFlash)(JNIEnv *env, jclass clazz, jstring jport, jstring jfile, jint address) {
    (void) clazz;
    const char *port = (*env)->GetStringUTFChars(env, jport, NULL);
    if (port == NULL) {
        return PM3_EMALLOC;
    }
    const char *file = (*env)->GetStringUTFChars(env, jfile, NULL);
    if (file == NULL) {
        (*env)->ReleaseStringUTFChars(env, jport, port);
        return PM3_EMALLOC;
    }
    int ret = dfuse_flash_file(port, file, (uint32_t) address);
    fflush(stdout);
    (*env)->ReleaseStringUTFChars(env, jfile, file);
    (*env)->ReleaseStringUTFChars(env, jport, port);
    return ret;
}
