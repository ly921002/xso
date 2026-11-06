package com.example.essentialsx.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 xray 服务端配置：单个 VLESS + XHTTP + REALITY 入站。
 *
 * <pre>
 * inbounds[0]:
 *   protocol = vless
 *   streamSettings:
 *     network          = xhttp
 *     security         = reality
 *     xhttpSettings    = { path, host(可选), mode }
 *     realitySettings  = { target, serverNames[1], privateKey, shortIds[1], xver }
 * </pre>
 *
 * 注意：VLESS + XHTTP + REALITY 组合下 **不能** 设置 flow=xtls-rprx-vision。
 * Vision 依赖 RAW(TCP) 传输，配上 XHTTP 会被客户端直接判定为无效配置。
 */
public final class XrayConfig {

    private final Config config;
    private final String privateKey;

    public XrayConfig(Config config, String privateKey) {
        this.config = config;
        this.privateKey = privateKey;
    }

    public String build() {
        List<Object> inbounds = new ArrayList<>();

        Map<String, Object> user = Json.obj(
                "id", config.uuid,
                "level", 0
        );
        // flow 与 encryption 是联动的一组：
        //   - 没启用 VLESS Encryption 时，xtls-rprx-vision 只允许 RAW(TCP)+TLS/REALITY；
        //   - 启用 VLESS Encryption 后不受底层传输限制，XHTTP 也能配 vision。
        // 空值不输出，避免服务端把它当成"显式要求空 flow"。
        if (!config.vlessFlow.isBlank()) {
            user.put("flow", config.vlessFlow);
        }

        Map<String, Object> settings = Json.obj(
                "clients", Json.arr(user)
        );
        if (!config.vlessDecryption.isBlank()) {
            settings.put("decryption", config.vlessDecryption);
        }

        inbounds.add(Json.obj(
                "tag", "vless-xhttp-reality",
                "protocol", "vless",
                "listen", "0.0.0.0",
                "port", config.realityPort,
                "settings", settings,
                "streamSettings", streamSettings(),
                "sniffing", Json.obj(
                        "enabled", true,
                        "destOverride", Json.arr("http", "tls", "quic")
                )
        ));

        return Json.write(Json.obj(
                "log", Json.obj(
                        "loglevel", "warning",
                        "access", ""
                ),
                "inbounds", inbounds,
                "outbounds", outbounds(),
                "routing", routing()
        ));
    }

    private Map<String, Object> streamSettings() {
        Map<String, Object> xhttp = Json.obj(
                "path", config.xhttpPath,
                "mode", config.xhttpMode
        );
        if (!config.xhttpHost.isBlank()) {
            xhttp.put("host", config.xhttpHost);
        }

        Map<String, Object> reality = Json.obj(
                "show", false,
                "target", config.realityTarget,
                "xver", 0,
                "serverNames", Json.arr(config.realitySni),
                "privateKey", privateKey,
                "shortIds", Json.arr(config.realityShortId)
        );

        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("network", "xhttp");
        stream.put("security", "reality");
        stream.put("xhttpSettings", xhttp);
        stream.put("realitySettings", reality);
        return stream;
    }

    private List<Object> outbounds() {
        List<Object> outbounds = new ArrayList<>();
        if (config.warpEnabled) {
            Map<String, Object> peer = Json.obj(
                    "publicKey", config.warpPublicKey,
                    "endpoint", config.warpEndpoint
            );
            if (!config.warpReserved.isBlank()) {
                List<Object> reserved = new ArrayList<>();
                for (String part : config.warpReserved.split("[,\\s]+")) {
                    if (!part.isBlank()) {
                        reserved.add(Integer.parseInt(part.trim()));
                    }
                }
                peer.put("reserved", reserved);
            }
            outbounds.add(Json.obj(
                    "tag", "warp-out",
                    "protocol", "wireguard",
                    "settings", Json.obj(
                            "secretKey", config.warpPrivateKey,
                            "address", Json.arr(config.warpAddress),
                            "peers", Json.arr(peer),
                            "mtu", 1280
                    )
            ));
        }
        outbounds.add(Json.obj("tag", "freedom", "protocol", "freedom"));
        outbounds.add(Json.obj("tag", "block", "protocol", "blackhole"));
        return outbounds;
    }

    private Map<String, Object> routing() {
        Map<String, Object> routing = Json.obj(
                "domainStrategy", "IPIfNonMatch"
        );
        List<Object> rules = new ArrayList<>();
        // 刻意不用 "geoip:private"：内嵌的 c-shared 内核没有 geoip.dat 时会解析失败，
        // 进而导致 xray 以零入站启动（曾经的静默失败）。这里用等价的字面量 CIDR。
        rules.add(Json.obj(
                "type", "field",
                "ip", Json.arr(
                        "10.0.0.0/8",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "127.0.0.0/8",
                        "169.254.0.0/16",
                        "::1/128",
                        "fc00::/7",
                        "fe80::/10"
                ),
                "outboundTag", "block"
        ));
        if (config.warpEnabled) {
            rules.add(Json.obj(
                    "type", "field",
                    "domain", Json.arr("domain:netflix.com", "domain:nflxvideo.net"),
                    "outboundTag", "warp-out"
            ));
        }
        routing.put("rules", rules);
        return routing;
    }
}
