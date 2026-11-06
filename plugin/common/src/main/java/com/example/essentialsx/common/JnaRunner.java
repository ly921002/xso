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

    /** \u540e\u7aef\u771f\u5b9e\u72b6\u6001\uff0c\u4f9b\u8c03\u7528\u65b9\u5224\u65ad\u542f\u52a8\u6210\u529f\u4e0e\u5426\u3002 */
    public enum State { NOT_STARTED, STARTING, RUNNING, FAILED }

    private final java.util.concurrent.atomic.AtomicReference<State> state =
            new java.util.concurrent.atomic.AtomicReference<>(State.NOT_STARTED);
    private final java.util.concurrent.atomic.AtomicInteger lastExitCode =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicReference<String> lastError =
            new java.util.concurrent.atomic.AtomicReference<>("");
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
        state.set(State.STARTING);
        library = com.sun.jna.NativeLibrary.getInstance(libraryPath);
        var lib = (com.sun.jna.NativeLibrary) library;
        var startFn = lib.getFunction(startSymbol);
        stopFunction = lib.getFunction(stopSymbol);

        // XrayLastError() 是可选符号：库没导出时不影响启动，只是错误信息会缺一段。
        com.sun.jna.Function errorFn = null;
        try {
            errorFn = lib.getFunction("XrayLastError");
        } catch (Throwable ignored) {
            // 老版本库没有这个符号
        }
        final com.sun.jna.Function errLookup = errorFn;

        Thread t = new Thread(() -> {
            try {
                int code = startFn.invokeInt(new Object[]{payloadJson});
                if (code == 0) {
                    state.set(State.RUNNING);
                } else {
                    lastExitCode.set(code);
                    state.set(State.FAILED);
                    String detail = "";
                    if (errLookup != null) {
                        try {
                            Object raw = errLookup.invoke(String.class, new Object[]{});
                            detail = raw == null ? "" : String.valueOf(raw);
                        } catch (Throwable ignored) {
                            // 取不到就只报退出码
                        }
                    }
                    lastError.set(detail == null ? "" : detail);
                    log.accept(threadName + " exited with code " + code
                            + (detail == null || detail.isBlank() ? "" : ": " + detail));
                }
            } catch (Throwable e) {
                state.set(State.FAILED);
                lastError.set(String.valueOf(e));
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
    /** \u5f53\u524d\u540e\u7aef\u72b6\u6001\u3002 */
    public State state() {
        return state.get();
    }

    /** \u6700\u540e\u4e00\u6b21\u975e\u96f6\u9000\u51fa\u7801\uff0c0 \u8868\u793a\u6ca1\u6709\u5931\u8d25\u3002 */
    public int lastExitCode() {
        return lastExitCode.get();
    }

    /** \u6700\u540e\u4e00\u6b21\u9519\u8bef\u63cf\u8ff0\uff0c\u53ef\u80fd\u4e3a\u7a7a\u3002 */
    public String lastError() {
        return lastError.get();
    }

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
