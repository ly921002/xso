package com.example.essentialsx.standalone;

import com.example.essentialsx.common.AppService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone entry point: runs the proxy on its own, without a Minecraft
 * server underneath.
 * <p>
 * Why this exists: as a Paper plugin the proxy shares the host with the
 * Minecraft listener, and most Pterodactyl setups hand out a single
 * allocation. Two listeners, one port, and {@code server.properties} is
 * rewritten from the panel's Startup variables on every boot so the
 * conflict cannot be moved aside. As {@code server.jar} the port is ours.
 * <p>
 * Boot sequence:
 * <ol>
 *   <li>replay a plausible Paper boot log (cosmetic; keeps the panel
 *       console looking ordinary),</li>
 *   <li>start the shared {@link AppService}, which loads the .so via JNA
 *       and brings Xray up in-process,</li>
 *   <li>poll {@link AppService#backendState()} for a verdict.</li>
 * </ol>
 * The verdict is read from the service, not scraped from the log: with
 * {@code SHOW_LOG=false} the backend's own progress lines are discarded,
 * so a log-based check would always report failure.
 */
public final class StandaloneMain {

    /** How long to wait for the backend to reach RUNNING or FAILED. */
    private static final long STARTUP_GRACE_MS = 20_000L;

    private StandaloneMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        ConsoleLogger log = new ConsoleLogger();

        BootLog.replay(fakeMcPort());

        AppService service = new AppService(log);
        service.start();

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.stop();
            stopped.countDown();
        }, "standalone-shutdown"));

        String state = waitForVerdict(service);

        if (!"RUNNING".equals(state)) {
            log.error("[EssentialsX] FAILED: proxy backend state is " + state + ".", null);
            if ("NOT_STARTED".equals(state)) {
                log.error("[EssentialsX] hint: the backend never began starting - "
                        + "usually a missing or unreadable .so, or a SHA-256 pin mismatch.", null);
            } else if ("FAILED".equals(state)) {
                int code = service.backendExitCode();
                String detail = service.backendError();
                log.error("[EssentialsX] exit code: " + code
                        + (detail == null || detail.isBlank() ? "" : ", reason: " + detail), null);
                log.error("[EssentialsX] hint: a bind failure here almost always means "
                        + "REALITY_PORT is already in use.", null);
            } else {
                log.error("[EssentialsX] hint: the backend did not finish starting within "
                        + (STARTUP_GRACE_MS / 1000) + "s.", null);
            }
            System.exit(1);
        }

        log.info("[EssentialsX] backend is up");
        stopped.await();
    }

    /** Polls the service until it leaves STARTING, or the grace period ends. */
    private static String waitForVerdict(AppService service) {
        long deadline = System.currentTimeMillis() + STARTUP_GRACE_MS;
        String state = "NOT_STARTED";
        while (System.currentTimeMillis() < deadline) {
            state = service.backendState();
            if ("RUNNING".equals(state) || "FAILED".equals(state)) {
                return state;
            }
            sleepQuietly(200);
        }
        return state;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Port printed by the fake {@code Starting Minecraft server on ...} line.
     * Purely cosmetic; falls back to 25565 when unset or unparseable.
     */
    private static int fakeMcPort() {
        try {
            Path env = Path.of(".env").toAbsolutePath();
            if (!Files.exists(env)) {
                return 25565;
            }
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) {
                    continue;
                }
                if (line.substring(0, eq).trim().equals("FAKE_MC_PORT")) {
                    return Integer.parseInt(line.substring(eq + 1).trim());
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // cosmetic only
        }
        return 25565;
    }
}
