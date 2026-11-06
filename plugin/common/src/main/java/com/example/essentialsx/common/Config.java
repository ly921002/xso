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

    /**
     * VLESS 加密（decryption 字段）。默认 {@code none}。
     * <p>
     * 例：{@code mlkem768x25519plus.native.0rtt.<认证参数>}。
     * 结构为 {@code 握手方式.流量外观.票据有效期.padding.*.认证参数}，
     * 服务端与客户端必须一致；末尾认证参数由 {@code xray vlessenc} 生成。
     */
    public final String vlessDecryption;
    /**
     * VLESS 客户端加密串（vless:// 链接里的 {@code encryption=}）。默认取 decryption 的值。
     * <p>
     * 与服务端的 {@link #vlessDecryption} <b>不是同一个字符串</b>：两者由
     * {@code xray vlessenc} 一次生成，服务端串带 {@code 600s} 之类的票据时长，
     * 客户端串带 {@code 0rtt}。交叉使用会握手失败（MAC 校验不通过）。
     * <p>
     * 留空时回退到 {@code VLESS_DECRYPTION}，以兼容旧配置；启用 VLESS 加密时应显式填写。
     */
    public final String vlessEncryption;
    /** 流控。{@code xtls-rprx-vision} 需配合 VLESS 加密才能在 XHTTP 下使用。 */
    public final String vlessFlow;
    /**
     * 是否在链接里加 {@code allowInsecure=1}。默认 false。
     * <p>
     * REALITY 不需要这个参数：它靠 {@code publicKey} + {@code shortId}
     * 验证服务端身份，不走 CA 证书链，所以
     * “跳过证书校验”在这里没有意义。反而会让
     * v2rayNG 之类的客户端弹出“未加密连接”警告。
     */
    public final boolean allowInsecure;

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
    /** Xray terminal log level; default warning, so only warnings/errors show. */
    public final String xrayLogLevel;
    public final boolean keepBinaries;
    public final int cleanupDelaySeconds;

    // --- 二进制来源 ---
    /** 自定义来源（优先级最高）。留空则使用内置的 GitHub Releases 地址。 */
    public final String binaryBaseUrl;
    public final List<String> binaryMirrors;
    /** 内置默认来源：本仓库的 latest release。 */
    public static final String DEFAULT_BINARY_BASE_URL =
            "https://github.com/ly921002/xso/releases/download/latest";
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

        this.uuid = requireUuid(get("UUID", ""), Path.of("").toAbsolutePath());
        this.runtimeDirName = get("FILE_PATH", "world");
        this.runtimeDir = Path.of("").toAbsolutePath().resolve(runtimeDirName).normalize();

        this.realityPort = integer("REALITY_PORT", 8443);
        this.realityTarget = get("REALITY_TARGET", "www.iij.ad.jp:443");
        this.realitySni = get("REALITY_SNI", hostOf(realityTarget));
        this.realityShortId = get("REALITY_SHORT_ID", "");
        this.xhttpPath = normalizePath(get("XHTTP_PATH", "/assets/bundle.js"));
        this.xhttpHost = get("XHTTP_HOST", "");
        this.xhttpMode = get("XHTTP_MODE", "auto");

        // 默认不启用 VLESS 加密，行为与之前一致；启用后必须保证客户端用同一串。
        this.vlessDecryption = get("VLESS_DECRYPTION", "none");
        // 客户端串与服务端串成对生成但内容不同；未显式配置时回退到 decryption，
        // 保持 VLESS_DECRYPTION=none 的旧行为完全不变。
        this.vlessEncryption = get("VLESS_ENCRYPTION", vlessDecryption);
        // 默认空 flow。只有启用了 VLESS 加密，XHTTP 才能安全地配 vision。
        this.vlessFlow = get("VLESS_FLOW", "");

        this.cfIp = get("CFIP", "");
        this.cfPort = integer("CFPORT", realityPort);
        this.allowInsecure = bool("ALLOW_INSECURE", false);
        this.nodeName = get("NAME", "");

        this.uploadUrl = get("UPLOAD_URL", "");
        this.subPath = get("SUB_PATH", "sub");
        this.telegramChatId = get("CHAT_ID", "");
        this.telegramBotToken = get("BOT_TOKEN", "");

        // 默认关闭。开着会把 "Using cached x.so" / "started via JNA"
        // 之类的内部技术细节打进控制台，等于主动提醒别人这里在跑什么。
        this.showLog = bool("SHOW_LOG", false);
        this.xrayLogLevel = get("XRAY_LOG_LEVEL", "warning");
        this.keepBinaries = bool("KEEP_BINARIES", true);
        this.cleanupDelaySeconds = integer("CLEANUP_DELAY", 45);

        this.binaryBaseUrl = get("BINARY_BASE_URL", DEFAULT_BINARY_BASE_URL);
        this.binaryMirrors = splitList(get("BINARY_MIRRORS", ""));
        // 默认与 CI 产物同名：x-linux-amd64.so / x-windows-amd64.dll / x-darwin-arm64.dylib
        this.xrayLibName = get("XRAY_LIB_NAME", "x-" + platform() + librarySuffix());
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

    /**
     * UUID 解析顺序：环境变量 &gt; 已持久化的 .uuid 文件 &gt; 首次随机生成。
     * <p>
     * 生成后会写回 {@code .uuid}，保证重启后客户端配置不会失效；文件只写入一次，
     * 之后始终复用。删掉该文件即可轮换凭据。
     */
    private static String requireUuid(String raw, Path root) {
        if (!raw.isBlank()) {
            return raw.trim();
        }

        Path store = root.resolve(".uuid").normalize();
        if (Files.isRegularFile(store)) {
            try {
                String saved = Files.readString(store, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (isValidUuid(saved)) {
                    return saved;
                }
                System.err.println("[EssentialsX] " + store + " does not contain a valid UUID, regenerating");
            } catch (IOException e) {
                System.err.println("[EssentialsX] could not read " + store + ": " + e.getMessage());
            }
        }

        String generated = java.util.UUID.randomUUID().toString();
        try {
            Files.writeString(store, generated + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
            // 仅所有者可读：这个值等同于客户端凭据
            try {
                Files.setPosixFilePermissions(store,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows 上没有 POSIX 权限，忽略
            }
            System.err.println("[EssentialsX] UUID is empty, generated and persisted to "
                    + store + ": " + generated);
        } catch (IOException e) {
            System.err.println("[EssentialsX] UUID is empty, generated a random one for this run only"
                    + " (could not persist to " + store + ": " + e.getMessage() + "): " + generated);
        }
        return generated;
    }

    private static boolean isValidUuid(String value) {
        if (value.length() < 8 || value.length() > 128 || value.contains(" ")) {
            return false;
        }
        // 允许标准 UUID，也允许自定义的任意短标识
        return value.matches("[A-Za-z0-9-]+");
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
