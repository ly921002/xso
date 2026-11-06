package com.example.essentialsx.common;

public interface PlatformLogger {
    void info(String message);

    void warn(String message);

    void error(String message, Throwable throwable);

    /**
     * 不加平台插件前缀地输出一行。
     * <p>
     * 各平台的插件 logger 都会自动加 {@code [时间] [插件名]} 前缀，
     * 伪装层如果走 {@link #info} 就会立刻穿帮。支持控制台通道的平台
     * （如 Paper 的 {@code Bukkit.getConsoleSender()}）应覆写此方法。
     * <p>
     * 默认实现退回 {@link #info}，对不支持的平台保持原有行为。
     */
    default void raw(String message) {
        info(message);
    }
}
