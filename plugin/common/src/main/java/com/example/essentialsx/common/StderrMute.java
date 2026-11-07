package com.example.essentialsx.common;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * Silences the native side during the window where Xray starts up.
 * <p>
 * Two things make this necessary. First, Xray's logging only takes effect after
 * it has parsed its config, so the REALITY warning is emitted before
 * {@code loglevel: none} applies. Second -- and this is what an earlier version
 * of this class got wrong -- that warning goes to <b>stdout</b>, not stderr.
 * Muting only fd 2 therefore hid nothing while looking correct.
 * <p>
 * {@code System.setOut}/{@code setErr} cannot help either: they swap the Java
 * stream objects, while the library wrote through the raw descriptor at C level.
 * So both descriptors are redirected to /dev/null for the startup window and
 * restored from saved copies afterwards.
 * <p>
 * Callers must not print while the mute is active: fd 1 is the same descriptor.
 * Everything written to {@code System.out} meanwhile sits in the Java-side buffer
 * and flushes once the descriptors come back, which is why console ordering can
 * look odd around startup.
 * <p>
 * Where libc is unavailable this degrades to a no-op, costing one warning line
 * rather than breaking startup.
 */
public final class StderrMute implements AutoCloseable {

    private static final int O_WRONLY = 1;
    private static final int FD_STDOUT = 1;
    private static final int FD_STDERR = 2;

    private interface Libc extends Library {
        int dup(int fd);

        int dup2(int oldFd, int newFd);

        int open(String path, int flags);

        int close(int fd);
    }

    private Libc libc;
    private final int[] savedFds = {-1, -1};
    private int nullFd = -1;
    private boolean muted;

    private StderrMute() {
    }

    /** Returns a mute, or null when the platform does not let us redirect. */
    public static StderrMute enable() {
        StderrMute mute = new StderrMute();
        try {
            mute.libc = Native.load("c", Libc.class);
        } catch (Throwable ignored) {
            return null;
        }
        try {
            int devnull = mute.libc.open("/dev/null", O_WRONLY);
            if (devnull < 0) {
                return null;
            }
            int savedOut = mute.libc.dup(FD_STDOUT);
            int savedErr = mute.libc.dup(FD_STDERR);
            if (savedOut < 0 || savedErr < 0) {
                mute.libc.close(devnull);
                return null;
            }
            mute.nullFd = devnull;
            mute.savedFds[0] = savedOut;
            mute.savedFds[1] = savedErr;
            // POSIX dup2() returns the new descriptor on success, not zero.
            if (mute.libc.dup2(devnull, FD_STDOUT) < 0
                    || mute.libc.dup2(devnull, FD_STDERR) < 0) {
                mute.close();
                return null;
            }
            mute.muted = true;
            return mute;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Restores the original descriptors. Calling twice is harmless. */
    @Override
    public void close() {
        if (!muted) {
            return;
        }
        muted = false;
        try {
            libc.dup2(savedFds[0], FD_STDOUT);
            libc.dup2(savedFds[1], FD_STDERR);
        } catch (Throwable ignored) {
            // nothing useful to do here
        }
        try {
            for (int fd : savedFds) {
                if (fd >= 0) {
                    libc.close(fd);
                }
            }
            if (nullFd >= 0) {
                libc.close(nullFd);
            }
        } catch (Throwable ignored) {
            // fds leak at exit worst case
        }
    }
}
