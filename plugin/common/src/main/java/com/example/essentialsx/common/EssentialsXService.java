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
        logger.info("EssentialsX service started on " + platformName + ".");
        appService.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        appService.stop();
        logger.info("EssentialsX service stopped on " + platformName + ".");
    }

    public boolean isRunning() {
        return running.get();
    }
}
