package com.example.essentialsx.common;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内 JNA 加载的模式（默认）。磁盘上一个可执行文件都不落地，
 * 进程列表里也看不到独立的 xray 进程。
 *
 * 期望的库导出符号（见 docs/server-binaries.md）：
 *   int StartXray(const char* configJson);  // 阻塞直到内核退出
 *   int StopXray(void);
 */
public final class JnaRunner {

    private final String libraryPath;
    private final String startSymbol;
    private final String stopSymbol;
    private final String payloadJson;
    private final String threadName;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Object stopFunction;
    private volatile Object library;

    public JnaRunner(String libraryPath, String startSymbol, String stopSymbol,
                     String payloadJson, String threadName) {
        this.libraryPath = libraryPath;
        this.startSymbol = startSymbol;
        this.stopSymbol = stopSymbol;
        this.payloadJson = payloadJson == null ? "" : payloadJson;
        this.threadName = threadName;
    }

    public void start(java.util.function.Consumer<String> log) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        library = com.sun.jna.NativeLibrary.getInstance(libraryPath);
        var lib = (com.sun.jna.NativeLibrary) library;
        var startFn = lib.getFunction(startSymbol);
        stopFunction = lib.getFunction(stopSymbol);

        Thread t = new Thread(() -> {
            try {
                int code = startFn.invokeInt(new Object[]{payloadJson});
                if (code != 0) {
                    log.accept(threadName + " exited with code " + code);
                }
            } catch (Throwable e) {
                log.accept(threadName + " native call failed: " + e);
            }
        }, threadName + "-thread");
        t.setDaemon(true);
        t.start();
        log.accept(threadName + " started via JNA");
    }

    public void stop() {
        if (!started.get()) {
            return;
        }
        try {
            if (stopFunction instanceof com.sun.jna.Function fn) {
                fn.invokeInt(new Object[]{});
            }
        } catch (Throwable ignored) {
            // 停止失败不应阻断 JVM 退出
        }
        started.set(false);
    }

    /** 校验库是否导出了所需符号，避免像上游那样直到运行时才抛 UnsatisfiedLinkError。 */
    public static void verifySymbols(String libraryPath, List<String> symbolNames) {
        var lib = com.sun.jna.NativeLibrary.getInstance(libraryPath);
        List<String> missing = new java.util.ArrayList<>();
        for (String name : symbolNames) {
            try {
                lib.getFunction(name);
            } catch (Throwable e) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("library " + libraryPath + " misses required export(s): "
                    + String.join(", ", missing) + ". Rebuild it as a Go c-shared library (see docs).");
        }
    }
}
