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
// The client prints to stdout: stdout and stderr go to the session log file, which the
// service and the app read. stdin is a pipe, so that a "\n" written by nativeAbort() ends the
// command loops that poll kbd_enter_pressed().
//-----------------------------------------------------------------------------

// glibc declares pipe2() only under this (the desktop build of the bridge uses glibc)
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

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

// stdout and stderr go to the file at log_path, stdin comes from a pipe that nativeAbort() writes to.
// Returns false, with nothing left open, when any of it fails.
static bool setup_stdio(const char *log_path) {
    int log_fd = open(log_path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0600);
    if (log_fd < 0) {
        return false;
    }
    int in[2];
    if (pipe2(in, O_CLOEXEC) != 0) {
        close(log_fd);
        return false;
    }
    // Only the write end is non-blocking, so an abort can never stall its caller. The read end stays
    // blocking: the client reads it with fgets() at its prompts. kbd_enter_pressed() makes it
    // non-blocking for a moment and puts it back, which leaves the write end alone (another file description).
    int flags = fcntl(in[1], F_GETFL);
    bool ok = flags >= 0 && fcntl(in[1], F_SETFL, flags | O_NONBLOCK) == 0;
    if (ok) {
        fflush(stdout);
        fflush(stderr);
        ok = dup2(log_fd, STDOUT_FILENO) >= 0 && dup2(log_fd, STDERR_FILENO) >= 0 && dup2(in[0], STDIN_FILENO) >= 0;
        // kbd_enter_pressed() fails, and complains, when it can't read the flags of stdin
        ok = ok && fcntl(STDIN_FILENO, F_GETFL) >= 0;
    }
    if (log_fd > STDERR_FILENO) {
        close(log_fd);
    }
    if (in[0] > STDERR_FILENO) {
        close(in[0]);
    }
    if (ok) {
        g_stdin_wr = in[1];
    } else {
        close(in[1]);
    }
    return ok;
}

// Returns 0, or -1 when something failed. Only the first successful call sets things up.
// home: $HOME, where resources (dictionaries, scripts) are unpacked and read from.
// dumpdir: where saved dumps go (an app directory a file manager can reach); "" keeps HOME.
// logpath: the file the client's stdout and stderr are appended to.
JNIEXPORT jint JNICALL JNI_FN(nativeInit)(JNIEnv *env, jclass clazz, jstring jhome, jstring jdump, jstring jlog) {
    (void) clazz;
    // Before anything can print (pm3_init and preferences_* may). The client would otherwise append all it
    // prints to a log file under $HOME/.proxmark3/logs, opened at the first line, without rotation: on a
    // phone that grows for ever, and the session log is the app's own.
    g_printAndLog &= (uint8_t) ~PRINTANDLOG_LOG;
    if (g_stdin_wr >= 0) {
        return 0;
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

    const char *log_path = (*env)->GetStringUTFChars(env, jlog, NULL);
    if (log_path == NULL) {
        return -1;
    }
    bool stdio_ok = setup_stdio(log_path);
    (*env)->ReleaseStringUTFChars(env, jlog, log_path);
    if (!stdio_ok) {
        return -1;
    }
    // a write to a socket or pipe nobody reads must fail with EPIPE, not kill the process
    signal(SIGPIPE, SIG_IGN);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

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
    return 0;
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
        // With the communication thread gone nothing would send the command, and the pause is for it to get out
        if (!IsCommunicationThreadDead() && !IsCommunicationThreadExited()) {
            clearCommandBuffer();
            SendCommandNG(CMD_QUIT_SESSION, NULL, 0);
            msleep(100);
        }
        CloseProxmark(g_session.current_device);
    }
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsConnected)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_session.pm3_present ? JNI_TRUE : JNI_FALSE;
}

// State of the link without taking any lock, so any thread may ask while a command runs:
// 0 no device, 1 connected, 2 the device is gone. pm3_present stays set until nativeClose, but the
// communication thread has ended on its own, because it saw the port fail: nothing more reaches the
// device. That shows as the dead flag, and as the thread having exited while g_conn.run is still set
// (CloseProxmark() clears it before the thread ends, so a normal close is not taken for a loss).
JNIEXPORT jint JNICALL JNI_FN(nativeLinkState)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!__atomic_load_n(&g_session.pm3_present, __ATOMIC_SEQ_CST)) {
        return 0;
    }
    if (IsCommunicationThreadDead() ||
            (IsCommunicationThreadExited() && __atomic_load_n(&g_conn.run, __ATOMIC_SEQ_CST))) {
        return 2;
    }
    return 1;
}

// Asks the device to leave the loop it is in (CMD_BREAK_LOOP), like `hw break`. Meant to be
// called from another thread than the one running the command: commands that wait on the device
// without looking at the keyboard (most sim and sniff commands) only end this way. Does nothing
// when there is no live link, and never waits for one that is dead. It may wait for the USB write.
// Not while flashing: the bootloader only knows OLD frames, and the flasher matches each ACK to the last
// command sent.
JNIEXPORT void JNICALL JNI_FN(nativeBreak)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (__atomic_load_n(&g_session.pm3_present, __ATOMIC_SEQ_CST) && !IsCommunicationThreadDead() &&
            !__atomic_load_n(&g_conn.block_after_ACK, __ATOMIC_SEQ_CST)) {
        SendCommandNG(CMD_BREAK_LOOP, NULL, 0);
    }
}

// Runs one client command line. With grab, its output is also kept for nativeTakeGrabbed().
// An Enter that is already waiting on stdin ends the command at once: the caller runs nativeDrainStdin() first.
JNIEXPORT jint JNICALL JNI_FN(nativeExec)(JNIEnv *env, jclass clazz, jstring jcmd, jboolean grab) {
    (void) clazz;
    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    if (cmd == NULL) {
        return PM3_EMALLOC;
    }
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

// Same as pressing Enter in the console: ends loops that wait for it. The write end is
// non-blocking, so this can be called from any thread.
JNIEXPORT void JNICALL JNI_FN(nativeAbort)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_stdin_wr >= 0) {
        if (write(g_stdin_wr, "\n", 1) != 1) {
            // EAGAIN, the pipe is full: an Enter is already waiting, which is all an abort needs
        }
    }
}

// Throws away the Enters that were written and not read, such as an abort that came after the command
// it was meant for had ended. Runs on the client thread, right before nativeExec.
JNIEXPORT void JNICALL JNI_FN(nativeDrainStdin)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    // kbd_enter_pressed() reads everything there is and returns 1 if it saw a newline, or -1 when it can't
    // set the mode of stdin; it leaves stdin blocking. Ask again until a pass finds nothing: an Enter may
    // have come in while it was reading.
    while (kbd_enter_pressed() > 0) {
        // drop it
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

// Every exit() in the client library ends up here (-Wl,--wrap=exit, see CMakeLists.txt). A real exit() runs
// the atexit handlers and the static destructors while other threads, hardnested's workers for one, still use
// what is being destroyed, so how the process dies would depend on timing. Flush what the client printed
// (the app shows it after the process is gone), say so in logcat and leave at once. Never returns, as
// exit() does not: the callers were compiled to expect that.
void __wrap_exit(int status) {
    fflush(NULL);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_FATAL, "pm3", "the client called exit(%d)", status);
#endif
    _exit(status);
}
