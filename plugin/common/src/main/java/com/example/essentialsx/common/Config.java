package com.example.essentialsx.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 全局配置。优先级：.env 文件 &gt; 进程环境变量 &gt; 内置默认值。
 * <p>
 * 相对上游做的收敛：
 * <ul>
 *   <li>只剩 VLESS + XHTTP + REALITY 一个入站；</li>
 *   <li>移除了全部监控探针与隧道转发相关配置；</li>
 *   <li>不内置任何共享凭据、共享 WARP 私钥、共享回退证书。</li>
 * </ul>
 */
public final class Config {

    private final Map<String, String> dotEnv;

    public final String uuid;
    public final String runtimeDirName;
    public final Path runtimeDir;

    // --- VLESS + XHTTP + REALITY ---
    public final int realityPort;
    public final String realityTarget;
    public final String realitySni;
    public final String realityShortId;
    public final String xhttpPath;
    public final String xhttpHost;
    public final String xhttpMode;

    public final String cfIp;
    public final int cfPort;
    public final String nodeName;

    // --- 订阅输出 ---
    public final String uploadUrl;
    public final String subPath;
    public final String telegramChatId;
    public final String telegramBotToken;

    // --- 行为开关 ---
    public final boolean showLog;
    public final boolean camouflage;
    public final boolean keepBinaries;
    public final int cleanupDelaySeconds;

    // --- 二进制来源（必须是自己服务器）---
    public final String binaryBaseUrl;
    public final List<String> binaryMirrors;
    public final String xrayLibName;
    public final String xraySha256;
    public final String nativeMode;
    public final String xrayBinary;

    // --- 可选 WARP 出站 ---
    public final boolean warpEnabled;
    public final String warpPrivateKey;
    public final String warpPublicKey;
    public final String warpEndpoint;
    public final String warpAddress;
    public final String warpReserved;

    public Config(Map<String, String> dotEnv) {
        this.dotEnv = dotEnv;

        this.uuid = requireUuid(get("UUID", ""));
        this.runtimeDirName = get("FILE_PATH", "world");
        this.runtimeDir = Path.of("").toAbsolutePath().resolve(runtimeDirName).normalize();

        this.realityPort = integer("REALITY_PORT", 8443);
        this.realityTarget = get("REALITY_TARGET", "www.iij.ad.jp:443");
        this.realitySni = get("REALITY_SNI", hostOf(realityTarget));
        this.realityShortId = get("REALITY_SHORT_ID", "");
        this.xhttpPath = normalizePath(get("XHTTP_PATH", "/assets/bundle.js"));
        this.xhttpHost = get("XHTTP_HOST", "");
        this.xhttpMode = get("XHTTP_MODE", "auto");

        this.cfIp = get("CFIP", "");
        this.cfPort = integer("CFPORT", realityPort);
        this.nodeName = get("NAME", "");

        this.uploadUrl = get("UPLOAD_URL", "");
        this.subPath = get("SUB_PATH", "sub");
        this.telegramChatId = get("CHAT_ID", "");
        this.telegramBotToken = get("BOT_TOKEN", "");

        this.showLog = !List.of("false", "disable", "no", "0").contains(
                get("SHOW_LOG", "true").toLowerCase(Locale.ROOT));
        this.camouflage = bool("CAMOUFLAGE", true);
        this.keepBinaries = bool("KEEP_BINARIES", true);
        this.cleanupDelaySeconds = integer("CLEANUP_DELAY", 45);

        this.binaryBaseUrl = get("BINARY_BASE_URL", "");
        this.binaryMirrors = splitList(get("BINARY_MIRRORS", ""));
        this.xrayLibName = get("XRAY_LIB_NAME", "sbx.so");
        this.xraySha256 = get("XRAY_SHA256", "");
        this.nativeMode = get("NATIVE_MODE", "jna").toLowerCase(Locale.ROOT);
        this.xrayBinary = get("XRAY_BINARY", "");

        this.warpEnabled = bool("WARP_ENABLE", false);
        this.warpPrivateKey = get("WARP_PRIVATE_KEY", "");
        this.warpPublicKey = get("WARP_PUBLIC_KEY", "");
        this.warpEndpoint = get("WARP_ENDPOINT", "engage.cloudflareclient.com:2408");
        this.warpAddress = get("WARP_ADDRESS", "172.16.0.2/32");
        this.warpReserved = get("WARP_RESERVED", "");

        if (warpEnabled && (warpPrivateKey.isBlank() || warpPublicKey.isBlank())) {
            throw new IllegalStateException(
                    "WARP_ENABLE=true requires WARP_PRIVATE_KEY and WARP_PUBLIC_KEY (no shared key is built in)");
        }
    }

    public static Config load() {
        return new Config(readDotEnv());
    }

    public String get(String key, String fallback) {
        String v = dotEnv.get(key);
        if (v == null) {
            v = System.getenv(key);
        }
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private int integer(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean bool(String key, boolean fallback) {
        String v = get(key, "");
        if (v.isBlank()) {
            return fallback;
        }
        return List.of("true", "yes", "1", "on").contains(v.toLowerCase(Locale.ROOT));
    }

    private static String normalizePath(String raw) {
        String p = raw.startsWith("/") ? raw : "/" + raw;
        return p.isBlank() ? "/assets/bundle.js" : p;
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String part : raw.split("[,\\s]+")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String hostOf(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return "";
        }
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            return end > 0 ? hostPort.substring(1, end) : hostPort;
        }
        int idx = hostPort.lastIndexOf(':');
        return idx > 0 ? hostPort.substring(0, idx) : hostPort;
    }

    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osName = os.contains("win") ? "windows"
                : os.contains("mac") ? "darwin"
                : os.contains("freebsd") ? "freebsd"
                : "linux";
        String archName = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64"
                : arch.contains("arm") ? "armv7"
                : arch.contains("riscv64") ? "riscv64"
                : "amd64";
        return osName + "-" + archName;
    }

    public static String librarySuffix() {
        String p = platform();
        return p.startsWith("windows") ? ".dll" : p.startsWith("darwin") ? ".dylib" : ".so";
    }

    private static String requireUuid(String raw) {
        if (raw.isBlank()) {
            String generated = java.util.UUID.randomUUID().toString();
            System.err.println("[EssentialsX] UUID is empty, generated a random one for this run: " + generated);
            return generated;
        }
        return raw.trim();
    }

    public static Map<String, String> readDotEnv() {
        Map<String, String> values = new LinkedHashMap<>();
        Path envPath = Path.of(".env").toAbsolutePath().normalize();
        if (!Files.exists(envPath)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                parseLine(line).ifPresent(e -> values.put(e.getKey(), e.getValue()));
            }
        } catch (IOException ignored) {
            // 退回纯环境变量
        }
        return values;
    }

    private static Optional<Map.Entry<String, String>> parseLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }
        if (trimmed.startsWith("export ")) {
            trimmed = trimmed.substring("export ".length()).trim();
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
            return Optional.empty();
        }
        String key = trimmed.substring(0, eq).trim();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Map.entry(key, stripValue(trimmed.substring(eq + 1))));
    }

    private static String stripValue(String value) {
        String v = value.trim();
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) {
            return v.substring(1, v.length() - 1);
        }
        int hash = v.indexOf(" #");
        return (hash >= 0 ? v.substring(0, hash) : v).trim();
    }
}
