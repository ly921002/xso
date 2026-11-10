package com.example.essentialsx.portprobe;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone diagnostic plugin: reports which TCP/UDP ports can be bound here.
 * <p>
 * Deliberately independent of EssentialsX: it shares no classes, does not touch
 * the proxy code, and can be installed or removed without affecting it. The
 * whole point is to answer "which port may I put REALITY on" before editing
 * anything else.
 * <p>
 * Two ways in:
 * <ul>
 *   <li>{@code PORT_PROBE=true} in {@code .env} -- runs once at startup and
 *       prints the result, so it also works on hosts where nobody opens the
 *       console;</li>
 *   <li>{@code /portprobe [tcp|udp] [full]} -- runs on demand.</li>
 * </ul>
 * The scan never runs on the main thread: a full 65535-port sweep would stall
 * the server for a long time and the watchog would kill it.
 */
public final class PortProbePlugin extends JavaPlugin {

    /** Set while a scan is running, so repeated commands cannot pile up threads. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        // The proxy plugin reads the same .env; one key is enough here and this
        // avoids depending on its Config class.
        if (!truthy(readEnv("PORT_PROBE"))) {
            return;
        }
        boolean full = truthy(readEnv("PORT_PROBE_FULL"));
        String proto = readEnv("PORT_PROBE_PROTO");
        getLogger().info("PORT_PROBE is on, scanning in the background");
        runScan(consoleSender(), full, proto);
    }

    /** Uses the console when available so startup output needs no player. */
    private CommandSender consoleSender() {
        try {
            return Bukkit.getConsoleSender();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Interprets a .env boolean.
     * <p>
     * True is {@code true}, {@code 1}, {@code yes}, {@code on}; anything else
     * (including an empty value) is false. An unset key therefore stays off,
     * which matters because this plugin is opt-in.
     */
    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean full = false;
        String proto = "tcp";
        for (String a : args) {
            String v = a.toLowerCase(Locale.ROOT);
            if (v.equals("full")) {
                full = true;
            } else if (v.equals("udp") || v.equals("tcp")) {
                proto = v;
            } else {
                sender.sendMessage("usage: /" + label + " [tcp|udp] [full]");
                return true;
            }
        }
        runScan(sender, full, proto);
        return true;
    }

    /**
     * Starts a scan on a daemon thread.
     * <p>
     * Daemon because a full sweep can outlive a shutdown and there is nothing
     * worth keeping the JVM alive for. Progress goes through {@link #emit}
     * rather than chat messages so the console log keeps a record.
     */
    private void runScan(CommandSender sender, boolean full, String proto) {
        if (!running.compareAndSet(false, true)) {
            emit(sender, "[PortProbe] a scan is already running");
            return;
        }
        PortScanner.Proto p = "udp".equalsIgnoreCase(proto)
                ? PortScanner.Proto.UDP : PortScanner.Proto.TCP;

        Thread t = new Thread(() -> {
            try {
                List<Integer> ports = full
                        ? PortScanner.candidatesFull()
                        : PortScanner.candidatesCurated();
                emit(sender, "[PortProbe] probing " + ports.size() + " " + p + " ports"
                        + (full ? " (full range)" : " (curated)"));

                long start = System.nanoTime();
                List<Integer> hits = new ArrayList<>();
                int tested = PortScanner.scan(ports, p, r -> hits.add(r.port), () -> false);
                long ms = (System.nanoTime() - start) / 1_000_000;

                emit(sender, "[PortProbe] tested " + tested + " in " + ms + " ms, free: " + hits.size());
                if (hits.isEmpty()) {
                    emit(sender, "[PortProbe] nothing was bindable"
                            + (full ? "" : "; retry with /portprobe " + p.name().toLowerCase(Locale.ROOT) + " full"));
                } else {
                    // Wrap so a long list stays readable in a console.
                    StringBuilder line = new StringBuilder();
                    int perLine = 12;
                    int n = 0;
                    for (int port : hits) {
                        if (n > 0) {
                            line.append(", ");
                        }
                        line.append(port);
                        if (++n % perLine == 0) {
                            emit(sender, "[PortProbe]   " + line);
                            line.setLength(0);
                        }
                    }
                    if (line.length() > 0) {
                        emit(sender, "[PortProbe]   " + line);
                    }
                    emit(sender, "[PortProbe] " + PortScanner.caveat());
                }
            } catch (Throwable e) {
                getLogger().warning("scan failed: " + e);
            } finally {
                running.set(false);
            }
        }, "portprobe-scan");
        t.setDaemon(true);
        t.start();
    }

    /** Console gets plain lines; a player gets chat, so both stay readable. */
    private void emit(CommandSender sender, String message) {
        if (sender == null || sender == Bukkit.getConsoleSender()) {
            getLogger().info(message.replace("[PortProbe] ", ""));
        } else {
            sender.sendMessage(message);
        }
    }

    /**
     * Reads one key from {@code .env} in the server root.
     * <p>
     * Trims and strips an inline {@code # comment} because hand-edited files
     * commonly carry one, and a value like {@code true  # enable} must still
     * parse as true.
     */
    private String readEnv(String key) {
        try {
            Path env = Path.of(".env").toAbsolutePath();
            if (!Files.isRegularFile(env)) {
                return "";
            }
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                if (!s.substring(0, eq).trim().equals(key)) {
                    continue;
                }
                String value = s.substring(eq + 1);
                int hash = value.indexOf('#');
                if (hash >= 0) {
                    value = value.substring(0, hash);
                }
                return value.trim();
            }
        } catch (IOException ignored) {
            // missing or unreadable .env simply means "not configured"
        }
        return "";
    }
}
