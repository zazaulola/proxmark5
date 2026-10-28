package io.github.zazaulola.proxmark5.core

import io.github.zazaulola.proxmark5.session.ActivitySnapshot

/** What the live session is doing right now (idle, a command, a sniffer, an emulation). */
typealias Activity = ActivitySnapshot
