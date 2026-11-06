package com.example.essentialsx.common;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 二进制获取器。
 * <p>
 * 相对上游"从第三方站点裸下载 45MB .so 并 dlopen"的改动：
 * <ol>
 *   <li>来源必须是自己服务器（{@code BINARY_BASE_URL}）或本地路径（{@code local:}）；</li>
 *   <li>配置了 SHA-256 就强制校验，不匹配直接拒绝加载；</li>
 *   <li>按平台分目录（{@code linux-amd64/sbx.so}），避免拿错架构；</li>
 *   <li>下载落地后保留（见 {@code KEEP_BINARIES}），不再"用完就删、重启重下"。</li>
 * </ol>
 */
public final class BinaryStore {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Config config;
    private final Consumer<String> log;

    public BinaryStore(Config config, Consumer<String> log) {
        this.config = config;
        this.log = log;
    }

    public Path xray() throws IOException {
        Path target = config.runtimeDir.resolve(config.xrayLibName);
        Files.createDirectories(config.runtimeDir);

        if (Files.exists(target) && Files.size(target) > 0) {
            if (config.xraySha256.isBlank() || sha256(target).equalsIgnoreCase(config.xraySha256)) {
                log.accept("Using cached " + config.xrayLibName + " (" + Files.size(target) + " bytes)");
                return target;
            }
            log.accept("Cached " + config.xrayLibName + " does not match pinned SHA-256, re-downloading");
            Files.deleteIfExists(target);
        }

        List<String> sources = candidates(config.xrayLibName);
        if (sources.isEmpty()) {
            throw new IOException("BINARY_BASE_URL is not set and no cached " + config.xrayLibName
                    + " exists in " + config.runtimeDir + ". Point BINARY_BASE_URL at your own server.");
        }

        IOException last = null;
        for (String url : sources) {
            try {
                fetch(url, target);
                return target;
            } catch (IOException e) {
                last = e;
                log.accept("Source failed (" + e.getMessage() + "): " + url);
            }
        }
        throw new IOException("Unable to fetch " + config.xrayLibName + " from any configured source", last);
    }

    private List<String> candidates(String fileName) {
        List<String> urls = new ArrayList<>();
        if (!config.binaryBaseUrl.isBlank()) {
            addSource(urls, config.binaryBaseUrl, fileName);
        }
        for (String mirror : config.binaryMirrors) {
            addSource(urls, mirror, fileName);
        }
        return urls;
    }

    /**
     * 支持两种写法：
     * <ul>
     *   <li>{@code https://host/dir}  → host/dir/{platform}/{file}，再回退 host/dir/{file}</li>
     *   <li>{@code local:/opt/esx}   → 直接复制本地文件</li>
     * </ul>
     */
    private void addSource(List<String> urls, String base, String fileName) {
        if (base.startsWith("local:")) {
            urls.add("local:" + Path.of(base.substring("local:".length())).resolve(fileName).toAbsolutePath());
            return;
        }
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        urls.add(root + "/" + Config.platform() + "/" + fileName);
        urls.add(root + "/" + fileName);
    }

    private void fetch(String url, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            if (url.startsWith("local:")) {
                Path source = Path.of(url.substring("local:".length()));
                if (!Files.isRegularFile(source)) {
                    throw new IOException("local file missing: " + source);
                }
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.accept("Fetching " + url);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build();
                HttpResponse<Path> response;
                try {
                    response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("download interrupted", e);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("HTTP " + response.statusCode());
                }
            }

            if (!config.xraySha256.isBlank()) {
                String actual = sha256(tmp);
                if (!actual.equalsIgnoreCase(config.xraySha256)) {
                    throw new IOException("SHA-256 mismatch (expected " + config.xraySha256 + ", got " + actual + ")");
                }
                log.accept("SHA-256 verified: " + actual);
            } else {
                log.accept("WARNING: XRAY_SHA256 unset, integrity NOT verified. Pin it for production.");
            }

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true, false);
            log.accept("Saved " + target + " (" + Files.size(target) + " bytes)");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest()).toLowerCase(Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
