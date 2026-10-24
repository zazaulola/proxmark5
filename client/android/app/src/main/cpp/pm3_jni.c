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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "proxmark3.h"
#include "cmdmain.h"
#include "comms.h"
#include "commonutil.h"
#include "fileutils.h"
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
