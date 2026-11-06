package com.example.essentialsx.paper;

import com.example.essentialsx.common.EssentialsXService;
import com.example.essentialsx.common.PlatformLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class EssentialsXPaperPlugin extends JavaPlugin {
    private EssentialsXService service;

    @Override
    public void onEnable() {
        service = new EssentialsXService("Paper", new BukkitLogger());
        service.start();
        Runtime.getRuntime().addShutdownHook(new Thread(service::stop, "essentialsx-paper-shutdown"));
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stop();
        }
    }

    private final class BukkitLogger implements PlatformLogger {
        /**
         * All plugin output goes through the console sender.
         * <p>
         * Reason: Paper prints a "Nag author(s)" warning whenever a plugin
         * writes to System.out/err, which names the plugin and contradicts
         * the quiet startup we are aiming for. Using the console sender also
         * yields prefix-free lines (verified by probing).
         */
        @Override
        public void info(String message) {
            raw(message);
        }

        /**
         * 控制台通道：Bukkit 不会给 {@code ConsoleCommandSender} 的输出加插件名。
         * 伪装层靠这个才能打出看起来像服务端本体的行。
         */
        @Override
        public void raw(String message) {
            try {
                Bukkit.getConsoleSender().sendMessage(message);
            } catch (Throwable t) {
                // 服务端尚未就绪或非 Bukkit 环境时退化为直写
                System.out.println(message);
            }
        }

        @Override
        public void warn(String message) {
            getLogger().warning(message);
        }

        /**
         * Same reason as {@link #info}: keep stray stack traces off stderr.
         */
        @Override
        public void error(String message, Throwable throwable) {
            getLogger().severe(message);
        }


    }
}
