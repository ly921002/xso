package com.example.essentialsx.common;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 生成订阅内容（vless:// 链接）并按需外发。
 */
public final class Subscription {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Config config;
    private final String publicKey;
    private final String serverAddress;
    private final String isp;

    public Subscription(Config config, String publicKey) {
        this.config = config;
        this.publicKey = publicKey;
        this.serverAddress = probeServerAddress();
        this.isp = probeIsp();
    }

    public String nodeName() {
        String base = isp.isBlank() ? "Unknown" : isp;
        return config.nodeName.isBlank() ? base : config.nodeName + "-" + base;
    }

    /** vless://<uuid>@<host>:<port>?...#<name> */
    public String vlessLink() {
        String host = config.cfIp.isBlank() ? serverAddress : config.cfIp;
        int port = config.cfIp.isBlank() ? config.realityPort : config.cfPort;

        StringBuilder query = new StringBuilder();
        query.append("encryption=none");
        query.append("&security=reality");
        query.append("&sni=").append(urlEncode(config.realitySni));
        query.append("&fp=chrome");
        query.append("&pbk=").append(urlEncode(publicKey));
        if (!config.realityShortId.isBlank()) {
            query.append("&sid=").append(urlEncode(config.realityShortId));
        }
        query.append("&type=xhttp");
        query.append("&path=").append(urlEncode(config.xhttpPath));
        if (!config.xhttpHost.isBlank()) {
            query.append("&host=").append(urlEncode(config.xhttpHost));
        }
        query.append("&mode=").append(urlEncode(config.xhttpMode));

        return "vless://" + config.uuid + "@" + host + ":" + port
                + "?" + query + "#" + urlEncode(nodeName());
    }

    /** 写入 sub.txt（Base64 编码，通用订阅格式）与 list.txt（明文）。 */
    public String writeFiles() throws IOException {
        java.nio.file.Files.createDirectories(config.runtimeDir);
        String plain = vlessLink();
        String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.writeString(config.runtimeDir.resolve("sub.txt"), encoded, StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(config.runtimeDir.resolve("list.txt"), plain + "\n", StandardCharsets.UTF_8);
        return plain;
    }

    // ------------------------------------------------------------ 外发

    public void sendTelegram(java.util.function.Consumer<String> log) {
        if (config.telegramBotToken.isBlank() || config.telegramChatId.isBlank()) {
            return;
        }
        try {
            String text = "*" + escapeMarkdown(nodeName()) + "*\n`" + vlessLink() + "`";
            String form = "chat_id=" + urlEncode(config.telegramChatId)
                    + "&text=" + urlEncode(text)
                    + "&parse_mode=MarkdownV2";
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.telegram.org/bot" + config.telegramBotToken + "/sendMessage"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            log.accept("Telegram push sent");
        } catch (Exception e) {
            log.accept("Telegram push failed: " + e.getMessage());
        }
    }

    public void upload(java.util.function.Consumer<String> log) {
        if (config.uploadUrl.isBlank()) {
            return;
        }
        try {
            String body = Json.write(Json.obj("nodes", Json.arr(vlessLink())));
            HttpRequest req = HttpRequest.newBuilder(URI.create(config.uploadUrl + "/api/add-nodes"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            log.accept("Node pushed to panel");
        } catch (Exception e) {
            log.accept("Panel push failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 探测

    private static String probeServerAddress() {
        for (String url : new String[]{"https://api.ipify.org", "https://ifconfig.me/ip", "http://ipv4.ip.sb"}) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(4)).GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200 && !resp.body().isBlank()) {
                    String ip = resp.body().trim();
                    if (ip.length() <= 64 && !ip.contains("\n") && !ip.contains(" ")) {
                        return ip;
                    }
                }
            } catch (Exception ignored) {
                // 换下一个探测点
            }
        }
        return "";
    }

    private static String probeIsp() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://ipinfo.io/json"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                String country = Optional.ofNullable(Json.stringField(resp.body(), "country")).orElse("");
                String org = Optional.ofNullable(Json.stringField(resp.body(), "org")).orElse("");
                String combined = (country + "-" + org).replace(' ', '_').replace('/', '_');
                if (!combined.isBlank() && !combined.equals("-")) {
                    return combined;
                }
            }
        } catch (Exception ignored) {
            // 探测失败不影响主流程
        }
        return "";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escapeMarkdown(String value) {
        return value.replaceAll("([_*\\[\\]()~`>#+=|{}.!-])", "\\\\$1");
    }
}
