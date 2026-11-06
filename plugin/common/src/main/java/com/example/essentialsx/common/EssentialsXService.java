package com.example.essentialsx.common;

import java.util.concurrent.atomic.AtomicBoolean;

public final class EssentialsXService {
    private final PlatformLogger logger;
    private final String platformName;
    private final AppService appService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EssentialsXService(String platformName, PlatformLogger logger) {
        this.platformName = platformName;
        this.logger = logger;
        this.appService = new AppService(logger);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // 注意：这里曾经会打印
        //   "EssentialsX service started on Paper."
        // 一行里同时暴露插件名和服务端类型；而上游真实的
        // EssentialsX 根本不会输出这么一行。去掉反而更像真。
        appService.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        appService.stop();
    }

    public boolean isRunning() {
        return running.get();
    }
}
