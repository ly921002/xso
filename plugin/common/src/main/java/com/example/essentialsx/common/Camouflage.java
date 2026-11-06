package com.example.essentialsx.common;

import java.util.List;

/**
 * 启动时的"伪装层"——原样保留上游的设计意图：
 * 伪造 Paper 的启动输出、清屏、以及伪造的 "Done preparing level"。
 * <p>
 * 与上游的唯一区别：这些动作**不再阻塞服务端主线程**，由后台线程延后执行，
 * 因此不会让服务器启动卡住 30 秒。
 */
final class Camouflage {

    private static final List<String> FAKE_PAPER_LINES = List.of(
            "Preparing spawn area: 1%",
            "Preparing spawn area: 4%",
            "Preparing spawn area: 9%",
            "Preparing spawn area: 17%",
            "Preparing spawn area: 26%",
            "Preparing spawn area: 38%",
            "Preparing spawn area: 55%",
            "Preparing spawn area: 73%",
            "Preparing spawn area: 91%",
            "Preparing spawn area: 100%",
            "Time elapsed: 1901 ms",
            "[spark] Starting background profiler...",
            "Done preparing level \"world\" (71.792s)"
    );

    private Camouflage() {
    }

    static void run(PlatformLogger logger, boolean enabled) {
        if (!enabled) {
            return;
        }
        for (String line : FAKE_PAPER_LINES) {
            logger.info(line);
        }
    }

    /** 等价于上游 clearConsole()，但失败时静默。 */
    static void clearConsole() {
        try {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        } catch (Exception ignored) {
            // 控制台不支持 ANSI 时忽略
        }
    }
}
