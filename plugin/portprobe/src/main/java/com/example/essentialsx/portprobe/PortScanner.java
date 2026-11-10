package com.example.essentialsx.portprobe;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Finds TCP/UDP ports that can actually be bound on this host.
 * <p>
 * "Usable" here means strictly local: the process manages to {@code bind()} the
 * address. That is the only signal a program can produce on its own, and it is
 * the one that matters when the goal is "which port may I configure".
 * <p>
 * It deliberately does <b>not</b> claim the port is reachable from outside. On
 * shared platforms (Pterodactyl containers, serv00-style hosts) a successful
 * local bind proves nothing about whether the host forwards that port to the
 * internet -- a distinction that has caused real confusion: a service binds
 * happily inside the container while every external connection is refused.
 * {@link #describe} spells that out in the output rather than leaving it to be
 * discovered later.
 * <p>
 * No third-party dependencies: this class is usable straight from a jar's main
 * method, so the same code serves both the plugin and a command-line probe.
 */
public final class PortScanner {

    /** Ports below this need CAP_NET_BIND_SERVICE or root; worth flagging separately. */
    public static final int PRIVILEGED_BELOW = 1024;

    /** How a single port is expected to be used. */
    public enum Proto { TCP, UDP }

    /** One probe result. */
    public static final class Result {
        public final int port;
        public final Proto proto;
        public final boolean bindable;
        /** Non-null when the bind failed; the reason, already user-readable. */
        public final String reason;

        Result(int port, Proto proto, boolean bindable, String reason) {
            this.port = port;
            this.proto = proto;
            this.bindable = bindable;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return proto + "/" + port + (bindable ? " free" : " busy (" + reason + ")");
        }
    }

    private PortScanner() {
    }

    /**
     * Tries to bind one port.
     * <p>
     * SO_REUSEADDR is set because almost every real server sets it; a port stuck
     * in TIME_WAIT would otherwise look unavailable even though a normal service
     * could still take it.
     * <p>
     * The address is the wildcard one: binding 127.0.0.1 would succeed even when
     * another process already holds 0.0.0.0, which is exactly the case worth
     * detecting.
     */
    public static Result probe(int port, Proto proto) {
        if (port < 1 || port > 65535) {
            return new Result(port, proto, false, "out of range");
        }
        if (proto == Proto.TCP) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port), 1);
                return new Result(port, proto, true, null);
            } catch (IOException e) {
                return new Result(port, proto, false, describeIoFailure(e));
            }
        }
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            return new Result(port, proto, true, null);
        } catch (IOException e) {
            return new Result(port, proto, false, describeIoFailure(e));
        }
    }

    /**
     * Turns a bind failure into something actionable.
     * <p>
     * Java surfaces EACCES as a generic "Permission denied" and EADDRINUSE as
     * "Address already in use"; both are kept, but the privileged-port case is
     * called out explicitly because "why can't I use 443" is the common question
     * and the raw message does not mention the 1024 threshold.
     */
    private static String describeIoFailure(IOException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied")) {
            return "permission denied (ports < " + PRIVILEGED_BELOW + " need root)";
        }
        if (lower.contains("address already in use")) {
            return "already in use";
        }
        return msg;
    }

    /**
     * A curated list of ports worth trying first.
     * <p>
     * Ordered by how likely they are to be both free and acceptable to a host:
     * common application ports first, then the ranges shared hosts hand out.
     * Scanning every port is slow and noisy, so it is the fallback rather than
     * the default -- see {@link #candidatesFull}.
     */
    public static List<Integer> candidatesCurated() {
        List<Integer> ports = new ArrayList<>();
        // Proxy/VPN-friendly ports, and the ones REALITY/VLESS links commonly use.
        for (int p : new int[]{443, 8443, 2053, 2083, 2087, 2096, 8080, 8880, 8444, 9443}) {
            ports.add(p);
        }
        // Generic application ports.
        for (int p : new int[]{80, 8081, 8082, 3000, 5000, 7000, 9000, 9090, 10000}) {
            ports.add(p);
        }
        // Minecraft defaults, in case one host is preferred over another.
        for (int p : new int[]{25565, 25566, 25567, 25575}) {
            ports.add(p);
        }
        // Ranges shared hosts typically allocate from.
        addRange(ports, 8000, 8100);
        addRange(ports, 20000, 20100);
        addRange(ports, 24000, 24100);
        addRange(ports, 25000, 25100);
        addRange(ports, 30000, 30100);
        addRange(ports, 40000, 40100);
        return ports;
    }

    /** Every port, in ascending order. Slow; used only when the curated pass finds nothing. */
    public static List<Integer> candidatesFull() {
        List<Integer> ports = new ArrayList<>();
        addRange(ports, 1, 1023);
        addRange(ports, 1024, 65535);
        return ports;
    }

    private static void addRange(List<Integer> into, int from, int to) {
        for (int p = from; p <= to; p++) {
            into.add(p);
        }
    }

    /**
     * Runs a whole scan.
     *
     * @param ports   what to test
     * @param proto   TCP or UDP
     * @param onHit   called for every bindable port, in order
     * @param cancel  polled between ports; returning true stops the scan early
     * @return number of ports tested (not the number of hits)
     */
    public static int scan(List<Integer> ports, Proto proto,
                           Consumer<Result> onHit, BooleanSupplier cancel) {
        int tested = 0;
        for (int port : ports) {
            if (cancel != null && cancel.getAsBoolean()) {
                break;
            }
            Result r = probe(port, proto);
            tested++;
            if (r.bindable) {
                onHit.accept(r);
            }
        }
        return tested;
    }

    /**
     * Hint printed next to results.
     * <p>
     * A local bind is necessary, not sufficient. On a container host the port
     * also has to be allocated and forwarded; saying so here is cheaper than
     * letting someone debug a "node that connects but never works" later.
     */
    public static String caveat() {
        return "A bindable port is only half the story: on containers and shared hosts the"
                + " port must also be allocated/forwarded by the panel. If clients cannot"
                + " reach it from outside, the local bind is not the problem.";
    }

    /** Command-line entry point: {@code java -cp portprobe.jar ...PortScanner [tcp|udp] [full]}. */
    public static void main(String[] args) {
        Proto proto = Proto.TCP;
        boolean full = false;
        for (String a : args) {
            String v = a.toLowerCase(Locale.ROOT);
            if (v.equals("udp")) {
                proto = Proto.UDP;
            } else if (v.equals("tcp")) {
                proto = Proto.TCP;
            } else if (v.equals("full")) {
                full = true;
            } else if (v.equals("-h") || v.equals("--help")) {
                System.out.println("usage: PortScanner [tcp|udp] [full]");
                System.out.println("  default: curated candidate list");
                System.out.println("  full   : every port 1-65535");
                return;
            }
        }

        List<Integer> ports = full ? candidatesFull() : candidatesCurated();
        System.out.println("probing " + ports.size() + " " + proto + " ports"
                + (full ? " (full range)" : " (curated)"));

        long start = System.nanoTime();
        List<Integer> hits = new ArrayList<>();
        int tested = scan(ports, proto, r -> {
            hits.add(r.port);
            System.out.println("  free: " + r.port);
        }, null);
        long ms = (System.nanoTime() - start) / 1_000_000;

        System.out.println();
        System.out.println("tested " + tested + " ports in " + ms + " ms, free: " + hits.size());
        if (hits.isEmpty()) {
            System.out.println("none were bindable; every candidate is taken or blocked");
        } else {
            int privileged = 0;
            for (int p : hits) {
                if (p < PRIVILEGED_BELOW) {
                    privileged++;
                }
            }
            if (privileged > 0) {
                System.out.println("note: " + privileged
                        + " of these are below 1024 and may fail for other users");
            }
            System.out.println(caveat());
        }
    }
}
