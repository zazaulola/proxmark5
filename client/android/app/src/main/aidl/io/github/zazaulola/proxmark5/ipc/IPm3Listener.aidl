package io.github.zazaulola.proxmark5.ipc;

/**
 * Calls from the service (process :pm3) to the UI. Every call is oneway, so the service never waits
 * for the UI; each is delivered in order but may be lost if the UI is gone, and the UI repairs the
 * state by reading IPm3Service.snapshot(). See /mnt/project-files/design/pm3-process-split.md.
 */
oneway interface IPm3Listener {
    /** Something changed in the snapshot: read IPm3Service.snapshot(). */
    void onChanged();

    /** Outcome of submit / flash / dfuFlash. [utf8] is the grabbed output, at most 96 KiB. */
    void onResult(long id, int code, in byte[] utf8);

    /** The client's command tree (the text io.github.zazaulola.proxmark5.parse.CommandTree reads), gzip compressed. */
    void onTree(long id, in byte[] gzipUtf8);
}
