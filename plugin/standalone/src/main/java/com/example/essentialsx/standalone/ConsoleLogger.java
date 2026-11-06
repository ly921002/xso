package com.example.essentialsx.standalone;

import com.example.essentialsx.common.PlatformLogger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Console logger for the standalone entry point.
 * <p>
 * Unlike the plugin build this one watches for backend failures: the
 * {@code AppService} runs on a daemon thread and never reports back, so
 * without a flag here the process would happily print a node link for a
 * proxy that already died (the "exited with code 5: address already in
 * use" case).
 */
final class ConsoleLogger implements PlatformLogger {

    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicBoolean sawStart = new AtomicBoolean(false);

    @Override
    public void info(String message) {
        note(message);
        System.out.println("[" + LocalTime.now().format(HH_MM_SS) + " INFO]: " + message);
    }

    @Override
    public void warn(String message) {
        System.out.println("[" + LocalTime.now().format(HH_MM_SS) + " WARN]: " + message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        failed.set(true);
        System.out.println("[" + LocalTime.now().format(HH_MM_SS) + " ERROR]: " + message);
        if (throwable != null) {
            System.out.println("  " + throwable.getClass().getName() + ": " + throwable.getMessage());
        }
    }

    /**
     * Raw channel: printed as-is, no prefix. Used for the boot camouflage
     * lines, which must look like the server itself wrote them.
     */
    @Override
    public void raw(String message) {
        note(message);
        System.out.println(message);
    }

    private void note(String message) {
        if (message == null) {
            return;
        }
        if (message.contains("started via JNA")) {
            sawStart.set(true);
        }
        if (message.contains("exited with code") || message.contains("native call failed")) {
            failed.set(true);
        }
    }

    boolean failed() {
        return failed.get();
    }

    boolean sawStart() {
        return sawStart.get();
    }
}
