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
 * Why this exists: as a Paper plugin the proxy has to share the host with
 * the Minecraft listener, and most Pterodactyl setups hand out a single
 * allocation. That leaves two listeners fighting for one port, and
 * {@code server.properties} gets rewritten on every boot so the conflict
 * cannot be moved out of the way. Run as {@code server.jar} instead and
 * port {@code REALITY_PORT} is simply ours.
 * <p>
 * Boot sequence:
 * <ol>
 *   <li>replay a plausible Paper boot log (cosmetic; keeps the panel
 *       console looking ordinary),</li>
 *   <li>start the shared {@link AppService}, which loads the .so via JNA
 *       and brings Xray up in-process,</li>
 *   <li>wait, then report whether the backend actually came up.</li>
 * </ol>
 * A backend failure exits non-zero so the panel shows a crash instead of a
 * healthy server holding a node link that cannot work.
 */
public final class StandaloneMain {

    /** Milliseconds to wait for a verdict from the backend thread. */
    private static final long STARTUP_GRACE_MS = 12_000L;

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

        waitForVerdict(log);

        if (log.failed() || !log.sawStart()) {
            // The old behaviour printed "web is running" and a node link even
            // when Xray had already exited with "address already in use".
            log.error("[EssentialsX] FAILED: proxy backend did not start.", null);
            if (!log.sawStart()) {
                log.error("[EssentialsX] hint: no 'started via JNA' line was seen - "
                        + "the library failed to load (platform mismatch, SHA-256 pin, or missing file).", null);
            } else {
                log.error("[EssentialsX] hint: JNA loaded but Xray failed. The most common cause is "
                        + "REALITY_PORT already being in use.", null);
            }
            System.exit(1);
        }

        stopped.await();
    }

    private static void waitForVerdict(ConsoleLogger log) {
        long deadline = System.currentTimeMillis() + STARTUP_GRACE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (log.sawStart()) {
                // Give an immediate bind error time to surface.
                sleepQuietly(1500);
                return;
            }
            if (log.failed()) {
                return;
            }
            sleepQuietly(150);
        }
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
