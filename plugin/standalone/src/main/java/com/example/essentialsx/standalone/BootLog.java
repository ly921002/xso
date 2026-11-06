package com.example.essentialsx.standalone;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Startup camouflage.
 * <p>
 * The standalone build has no Paper behind it, so the console would
 * otherwise show nothing but proxy chatter. Replaying a plausible boot
 * sequence keeps the process looking like an ordinary Minecraft server to
 * anyone reading the panel console.
 * <p>
 * Two rules, both learned the hard way:
 * <ul>
 *   <li>Lines go straight to {@code System.out}; a prefixed logger would
 *       put the plugin name in front of every line.</li>
 *   <li>Timings are randomised. Fixed constants ({@code 1901 ms},
 *       {@code 71.792s}) are trivially searchable.</li>
 * </ul>
 * This is cosmetic only: nothing here affects the proxy.
 */
final class BootLog {

    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private BootLog() {
    }

    static void replay(int fakeMcPort) {
        String jvm = System.getProperty("java.version", "21");
        String os = System.getProperty("os.name", "Linux");
        String arch = System.getProperty("os.arch", "amd64");

        emit("[bootstrap] Running Java " + jvm + " on " + os + " " + arch);
        pause(90, 240);
        emit("[bootstrap] Loading Paper 1.21.11-132-ver/1.21.11@c5eb079 (2026-05-11T11:43:09Z) for Minecraft 1.21.11");
        pause(900, 1600);
        emit("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Initialising converters for DataConverter...");
        pause(900, 1500);
        emit("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Finished initialising converters for DataConverter in "
                + (1200 + ThreadLocalRandom.current().nextInt(600)) + ".0ms");
        pause(300, 700);
        emit("Loaded " + (1584 + ThreadLocalRandom.current().nextInt(40)) + " advancements");
        pause(200, 400);
        emit("Starting minecraft server version 1.21.11");
        pause(120, 260);
        emit("Loading properties");
        pause(60, 140);
        emit("This server is running Paper version 1.21.11-132-ver/1.21.11@c5eb079 (2026-05-11T11:43:09Z) "
                + "(Implementing API version 1.21.11-R0.1-SNAPSHOT)");
        pause(60, 140);
        emit("[spark] This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling");
        pause(60, 130);
        emit("Server Ping Player Sample Count: 12");
        pause(40, 100);
        emit("Using 4 threads for Netty based IO");
        pause(80, 180);
        emit("Default game type: SURVIVAL");
        pause(60, 150);
        emit("Generating keypair");
        pause(120, 300);
        emit("Starting Minecraft server on 0.0.0.0:" + fakeMcPort);
        pause(400, 900);
        emit("Preparing level \"world\"");
        pause(300, 700);
        emit("Loading 0 persistent chunks for level 'minecraft:overworld'...");
        pause(150, 400);
        emit("Preparing spawn area: 1%");
        for (int pct : new int[]{4, 9, 17, 26, 38, 55, 73, 91, 100}) {
            pause(30, 130);
            emit("Preparing spawn area: " + pct + "%");
        }
        emit("Prepared spawn area in " + ThreadLocalRandom.current().nextInt(4) + " ms");
        pause(200, 500);
        emit("Loaded 0 persistent chunks for level 'minecraft:the_nether'...");
        pause(60, 160);
        emit("Prepared spawn area in 0 ms");
        pause(60, 160);
        emit("Loaded 0 persistent chunks for level 'minecraft:the_end'...");
        pause(40, 120);
        emit("Prepared spawn area in 0 ms");
        pause(100, 260);
        emit("[EssentialsX] Loading server plugin EssentialsX v1.21.11");
        pause(150, 400);
        emit("[EssentialsX] Enabling EssentialsX v1.21.11");
        pause(200, 500);
        emit("Running delayed init tasks");
        pause(100, 300);
        emit("Done (" + (9 + ThreadLocalRandom.current().nextInt(9)) + "."
                + ThreadLocalRandom.current().nextInt(1000) + "s)! For help, type \"help\"");
    }

    private static void emit(String line) {
        System.out.println("[" + LocalTime.now().format(HH_MM_SS) + " INFO]: " + line);
    }

    private static void pause(int minMs, int maxMs) {
        try {
            Thread.sleep(minMs + ThreadLocalRandom.current().nextInt(Math.max(1, maxMs - minMs)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
