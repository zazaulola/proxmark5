package io.github.zazaulola.proxmark5.ipc;

import io.github.zazaulola.proxmark5.ipc.IPm3Listener;

/**
 * The live pm3 session, owned by the service in process :pm3. Everything but attach and snapshot is
 * oneway and must not be answered by blocking: handlers only queue work. The session is started with
 * startForegroundService (see Pm3Ipc), not through this interface.
 * Contract: /mnt/project-files/design/pm3-process-split.md
 */
interface IPm3Service {
    /** Registers [listener] and returns the current snapshot as JSON (parsers: SessionSnapshot). */
    String attach(IPm3Listener listener);
    oneway void detach(IPm3Listener listener);

    /** The current snapshot as JSON. Cheap: reads a ready string and waits for nothing. */
    String snapshot();

    /** Runs a client command line; the result comes back as onResult(id, ...). */
    oneway void submit(long id, String cmd, boolean grab);

    /** Asks for the command tree; it comes back as onTree(id, ...). */
    oneway void commandTree(long id);

    /** Stop: drop the queue, end the running command (Enter + BREAK). Nothing while flashing. */
    oneway void stop();

    /** Leave the session: the Stop pipeline, then close the link. Nothing while flashing. */
    oneway void disconnect();

    /** Kill the client process (the user's "restart the client"). Nothing while flashing. */
    oneway void restartClient(String reason);

    /** Flashes ELF images on the device named [deviceName]; closes the session first. */
    oneway void flash(long id, String deviceName, in String[] files, boolean unlockBootloader, boolean force);

    /** Writes a raw image through the PM5 DFU loader. */
    oneway void dfuFlash(long id, String deviceName, String image);

    /** The UI is done with its chain of flash calls (success, failure or cancel). */
    oneway void flashEnd();

    /** A line of the app's own in the log. */
    oneway void note(String text);

    /** Empties the log file. */
    oneway void clearLog();
}
