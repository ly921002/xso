# java-plugins-plus 二次开发说明（Xray 单协议版）

本目录 `essentialsx-xray/` 是对 [eooce/java-plugins-plus](https://github.com/eooce/java-plugins-plus) 的改造分支，
按需求做了三件事，并顺带修复了原项目会导致实际故障的缺陷。

---

## 1. 需求对照

| 要求 | 落地情况 |
|---|---|
| ① 保持伪装设计 | 保留：EssentialsX 命名、`com.example.essentialsx` 包名、多平台 jar、伪造 Paper 启动日志、控制台清屏、静默后台运行。**唯一差别：这些动作不再阻塞服务端主线程。** |
| ② 去掉哪吒探针 | `NEZHA_*` 配置、`v1.so` / `agent.so` 下载与加载、`nezhaPayload()` / `nezhaV0Payload()` / `generateNezhaConfig()` 全部删除，代码中已无任何探针引用。 |
| ③ 二进制改 xray + cloudflared，从自己服务器拿 | xray 已接入；**cloudflared 按你的选择完全移除**（见下方说明）。二进制来源改为 `BINARY_BASE_URL`（自有服务器）并支持 SHA-256 强校验。 |
| 追加：只保留 VLESS + XHTTP + REALITY | 已收敛为**唯一入站**，vmess/hysteria2/tuic/socks/anytls 全部删除。 |

### 关于 cloudflared 被移除这件事

REALITY 依赖**直连的原始 TCP**，靠真实的 TLS 握手指纹伪装目标站点；而 cloudflared 的 Argo 隧道在
Cloudflare 边缘终结 TLS，REALITY 流量无法穿过它。原项目里 cloudflared 只服务于 `vmess+ws` 这条链路——
单协议改造后它已无任何用途，因此按你的选择整个移除。项目现在只剩 xray 一个内核，也只剩一个二进制要分发。

---

## 2. 与原项目的关键差异（缺陷修复）

| 原缺陷 | 本版处理 |
|---|---|
| `start()` 在 MC 主线程 `sleep(30000)`，服务端启动卡死 30 秒 | 全部工作搬到 `essentialsx-worker` 守护线程，`onEnable` 立即返回 |
| 45 秒后删掉自己刚下的 `.so`，每次重启重下 45–100 MB | `KEEP_BINARIES=true`（默认）保留二进制；仅清理临时产物 |
| `stop()` 不调用 native stop，`/reload` 后端口冲突 | `stop()` 显式调用 `StopXray()` / 销毁子进程 |
| v0 探针分支调用了不存在的符号 → 整体启动失败 | 探针已移除；且 JNA 加载前会 `verifySymbols()` 预检，缺失即明确报错而非运行到一半崩溃 |
| 所有人共用硬编码 WARP 私钥 / 回退 EC 证书 | 默认 `WARP_ENABLE=false`；开启时必须自行提供密钥，否则启动即报错 |
| `.env` 中 25+ 变量但 README 只写一部分（含静默外联的 `AUTO_ACCESS`、`PROJECT_URL`） | 外联行为全部默认关闭，且在下方文档中逐项列明 |
| 手写 JSON 不转义控制字符 | `Json.quote()` 完整转义 `\u0000-\u001f` |
| 1162 行单类、50+ static 字段 | 拆成 9 个职责单一的类，无 `catch (Exception ignored)` 静默吞异常 |

---

## 3. 目录结构

```
essentialsx-xray/
├── pom.xml
├── common/
│   └── src/main/java/com/example/essentialsx/common/
│       ├── Config.java          配置加载（.env > 环境变量 > 默认值）
│       ├── Json.java            极简 JSON 序列化
│       ├── BinaryStore.java     二进制获取 + SHA-256 校验
│       ├── RealityKeypair.java  X25519 密钥对生成/持久化/校验
│       ├── XrayConfig.java      生成 VLESS+XHTTP+REALITY 服务端配置
│       ├── JnaRunner.java       进程内 dlopen 加载（默认）
│       ├── ProcessRunner.java   外部进程加载（NATIVE_MODE=process）
│       ├── Subscription.java    vless:// 链接生成 + TG/面板推送
│       ├── Camouflage.java      伪造 Paper 启动日志 / 清屏
│       ├── AppService.java      主流程编排
│       ├── EssentialsXService.java
│       └── PlatformLogger.java
├── paper/ bungee/ velocity/ fabric/   四个平台入口
├── universal/                         shade 合并为单 jar
└── docs/
    ├── server-binaries.md       ★ 自有服务器上的二进制放法与编译方法
    └── README.md                ★ 使用说明与完整变量表
```

---

## 4. 生成的 xray 配置（实测通过校验）

```json
{
  "log": { "loglevel": "warning", "access": "" },
  "inbounds": [{
    "tag": "vless-xhttp-reality",
    "protocol": "vless",
    "listen": "0.0.0.0",
    "port": 8443,
    "settings": {
      "clients": [{ "id": "<UUID>", "level": 0 }],
      "decryption": "none"
    },
    "streamSettings": {
      "network": "xhttp",
      "security": "reality",
      "xhttpSettings": { "path": "/assets/bundle.js", "mode": "auto" },
      "realitySettings": {
        "show": false,
        "target": "www.iij.ad.jp:443",
        "xver": 0,
        "serverNames": ["www.iij.ad.jp"],
        "privateKey": "<x25519 私钥>",
        "shortIds": [""]
      }
    },
    "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"] }
  }],
  "outbounds": [
    { "tag": "freedom", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [{ "type": "field", "ip": ["geoip:private"], "outboundTag": "block" }]
  }
}
```

> **重要：`clients` 里没有 `flow` 字段。**
> `flow: xtls-rprx-vision` 只适用于 RAW(TCP) 传输，配上 XHTTP 会被判定为无效配置。
> 客户端的 `vless://` 链接同样不带 `flow`。

生成的客户端链接形如：

```
vless://<UUID>@<IP>:8443?encryption=none&security=reality&sni=www.iij.ad.jp&fp=chrome
  &pbk=<公钥>&sid=&type=xhttp&path=%2Fassets%2Fbundle.js&mode=auto#节点名
```

---

## 5. 验证记录

| 项目 | 结果 |
|---|---|
| `mvn clean package`（Temurin 17 / Maven 3.9.9） | ✅ 全部 6 个模块构建成功 |
| `xray run -test -c config.json` | ✅ `Configuration OK.` |
| 端到端：本机跑服务端 + 客户端经 REALITY 走 SOCKS | ✅ `HTTP 204`，0.26 s |
| 服务端监听 | ✅ `0.0.0.0:8443` / `[::]:8443` |
| 密钥对推导与落盘校验 | ✅ 私钥重推公钥与存档一致 |

测试用 xray 为官方 `Xray 26.10.31`；测试结束后进程与临时文件均已清理。

---

## 6. 你还需要做的一件事

把编译好的 xray 放到**你自己的服务器**上，并按 `docs/server-binaries.md` 说明配置
`BINARY_BASE_URL`。在此之前插件会明确报错"未配置二进制来源"，不会去任何第三方站点下载。
