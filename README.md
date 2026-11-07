# xso 部署指南

> 目标：在一台新服务器上从零跑起 VLESS + XHTTP + REALITY + VLESS Encryption。
> 适用于 Paper / Spigot / Purpur / BungeeCord / Velocity / Fabric。

---

## 1. 哪些必须每台独立生成

| 配置项 | 是否可复用 | 说明 |
|---|---|---|
| `UUID` | ❌ 独立 | 每台一个，泄露影响面隔离 |
| Reality 密钥对 | ❌ 独立 | 首启自动生成于 `world/keypair.properties` |
| `REALITY_PORT` | ❌ 独立 | 各机器端口不同（受主机限制） |
| `REALITY_SHORT_ID` | ❌ 独立 | **留空即自动随机生成**并持久化到 `.shortid` |
| `VLESS_DECRYPTION` | ⚠️ 可复用 | 见下方说明 |
| `VLESS_ENCRYPTION` | ⚠️ 可复用 | 必须与上一项成对 |
| `REALITY_TARGET` / `REALITY_SNI` | ✅ 可相同 | 伪装目标，各机器一样也正常 |
| `XHTTP_PATH` | ✅ 可相同 | 建议换成看着像静态资源的路径 |
| 插件 jar | ✅ 可相同 | 不含任何主机专属信息 |
| `x-*.so` 库 | ❌ 按平台 | 见第 2 节 |

### 关于加密串能否复用

**技术上可以。** 实测：用全新 UUID + 全新 Reality 密钥 + 复用同一对加密串，端到端 `HTTP 200` 成功。加密串只参与 VLESS 层的密钥协商，与 UUID、Reality 密钥、端口、域名完全解耦。

**安全上不建议。** 认证串本质是**预共享密钥**：

- N 台机器复用同一对 ⇒ 任意一台的 `.env` 泄露，**N 台同时失效**
- 攻击者拿到该串后，即便没有 UUID 和 Reality 密钥，也能与你建立加密通道

**推荐**：每台机器在本地跑一次 `xray vlessenc`，各自生成。

---

## 2. 准备二进制库（按平台）

插件按 `os.name` 自动推断文件名：

| 目标平台 | 文件名 | 来源 |
|---|---|---|
| Linux amd64 | `x-linux-amd64.so` | Release 直接下载 |
| Linux arm64 | `x-linux-arm64.so` | Release 直接下载 |
| Windows amd64 | `x-windows-amd64.dll` | Release 直接下载 |
| **FreeBSD amd64** | `x-freebsd-amd64.so` | **需自行编译**（Release 无此产物） |
| macOS / Alpine(musl) | — | 需自行编译 |

### FreeBSD 编译

```bash
# 依赖：Go ≥ 1.27（xray 要求）、gcc
git clone --depth 1 https://github.com/XTLS/Xray-core.git xray-core
cd xray-core
mkdir -p cshared
cp /path/to/xso/cshared/main.go cshared/
cp /path/to/xso/cshared/main_nocgo.go cshared/

CGO_ENABLED=1 GOOS=freebsd GOARCH=amd64 \
  go build -buildvcs=false -trimpath -ldflags "-s -w" \
  -buildmode=c-shared -o x-freebsd-amd64.so ./cshared
```

产物约 33 MB，用 `file` 确认显示 `for FreeBSD`。

> 注意：直接下 Release 的 `x-linux-amd64.so` 在 FreeBSD 上会报
> `version GLIBC_2.4 required ... not defined`——FreeBSD 没有 glibc，必须本地编译。

---

## 3. 准备服务端

| 项目 | 要求 |
|---|---|
| Java | 21（OpenJDK） |
| 服务端 | Paper 1.21.11 或更新 |
| 内存 | 建议 `-Xmx768M` 起，`ulimit -m` 需 ≥ 2GB |

```bash
mkdir -p ~/mc/plugins && cd ~/mc
curl -L -o paper.jar <Paper 下载地址>
echo "eula=true" > eula.txt
cp EssentialsX-1.21.11.jar plugins/
```

---

## 4. 生成该机器专属参数

```bash
# UUID
UUID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python3 -c "import uuid;print(uuid.uuid4())")
# 或直接用 xray： ./xray uuid

# shortId：不用手动生成。留空 REALITY_SHORT_ID 时插件会自动生成
# 8 字节随机 hex 并写入 .shortid（权限 600），重启复用同一个值。

# 加密串（可选；推荐每台独立）
./xray vlessenc
```

`vlessenc` 会输出两组，**选 ML-KEM-768 那组**（后量子）：

```
"decryption": "mlkem768x25519plus.native.600s.<服务端串>"   ← VLESS_DECRYPTION
"encryption": "mlkem768x25519plus.native.0rtt.<客户端串>"   ← VLESS_ENCRYPTION
```

**这两条不能交换**。服务端用 `600s`，客户端用 `0rtt`；混用会报
`chacha20poly1305: message authentication failed`。

---

## 5. 写 `.env`

放在**服务端根目录**（与 `paper.jar` 同级）：

```ini
# ---- 必填 ----
UUID=<第 4 步生成>
REALITY_PORT=<本机可用端口>

# ---- 伪装目标（可全机器相同）----
REALITY_TARGET=www.iij.ad.jp:443
REALITY_SNI=www.iij.ad.jp
XHTTP_PATH=/assets/bundle.js
XHTTP_MODE=auto

# ---- 本机专属 ----
# 留空 = 自动随机生成并持久化到 .shortid；显式填写则原样使用
REALITY_SHORT_ID=

# ---- VLESS 加密（成对，第三方不可混用）----
VLESS_DECRYPTION=mlkem768x25519plus.native.600s.<服务端串>
VLESS_ENCRYPTION=mlkem768x25519plus.native.0rtt.<客户端串>
VLESS_FLOW=xtls-rprx-vision

# ---- 二进制 ----
NATIVE_MODE=jna
BINARY_BASE_URL=local:/绝对路径/到/放so的目录
XRAY_LIB_NAME=x-linux-amd64.so
XRAY_SHA256=<sha256>
KEEP_BINARIES=true

# ---- 行为 ----
SHOW_LOG=false          # 插件自身的技术日志（Using cached / started via JNA）
QUIET=true              # 终端静默：不打印节点链接，Xray 日志压到 none
CLEANUP_MODE=off        # off | binaries | full
CLEANUP_DELAY=60
```

`BINARY_BASE_URL` 三种写法：

```ini
BINARY_BASE_URL=local:/opt/esx              # 本地目录，最省事
BINARY_BASE_URL=https://github.com/ly921002/xso/releases/download/latest   # 官方
BINARY_BASE_URL=https://your-host/esx       # 自建分发
```

用 `local:` 时，库文件名要与 `XRAY_LIB_NAME` 一致放在该目录下。

---

## 6. 放行端口

端口必须真的可用，否则插件静默失败。

- **Pterodactyl**：面板里额外分配 Allocation，只改 `.env` 不会自动开
- **serv00**：`devil port add tcp random <描述>`
- **普通 VPS**：防火墙 + 安全组

---

## 7. 启动与验收

```bash
java21 -Xms256M -Xmx768M -XX:+UseSerialGC -jar paper.jar --nogui
```

### 检查项

```bash
# 1. 配置语法
./xray -test -c world/config.json
# 期望：Configuration OK

# 2. 端口监听
ss -lntp | grep <REALITY_PORT>

# 3. 节点链接（启动日志里）
grep -o "vless://[^ ]*" logs/latest.log | tail -1
```

**链接必须包含 `flow=` 且 `encryption=` 是 0rtt 串**：

```
vless://<UUID>@<IP>:<PORT>?encryption=mlkem768x25519plus.native.0rtt.<...>
  &flow=xtls-rprx-vision&security=reality&sni=www.iij.ad.jp&fp=chrome
  &pbk=<公钥>&sid=<shortId>&type=xhttp&path=%2Fassets%2Fbundle.js&mode=auto
```

### 端到端验证

```bash
# 用 xray 起本地 socks/http 客户端，再从本机访问
curl -x http://127.0.0.1:3080 https://api.ipify.org
# 返回服务器出口 IP 即成功
```

---

## 8. 常见故障对照

| 现象 | 原因 |
|---|---|
| `ML-KEM-768 handshake failed > chacha20poly1305: message authentication failed` | `VLESS_ENCRYPTION` 与服务端串不是同一次生成 |
| `unsupported "decryption"` | 把客户端的 `0rtt` 串填进了 `VLESS_DECRYPTION` |
| `version GLIBC_2.4 required` | Linux 的 `.so` 放到了 FreeBSD |
| `HTTP 404: .../x-<platform>.so` | Release 无该平台产物，需本地编译 |
| `Failed to bind to port` | 端口未分配/被占用 |
| `Operation not permitted`（绑定端口） | serv00 等环境需先 `devil port add` |
| 插件加载但无任何输出 | `SHOW_LOG=false` 是默认值，属正常；看 `world/config.json` 是否生成 |

---

## 9. 终端输出控制

控制台默认会把“这台机器在跑代理”直接写出来。`QUIET=true` 一并处理以下四类：

| 泄漏源 | 处理方式 |
|---|---|
| `vless://...` 节点链接 | 不再打印到控制台；仍写入 `world/sub.txt` 与 `world/list.txt` |
| `REALITY: Listening on non-443...` | `loglevel`/`access` 强制为 `none` |
| `core: Xray 26.10.31 started` | 同上 |
| `WARNING: java.lang.System::load ... com.sun.jna.Native` | jar manifest 里 `Enable-Native-Access: ALL-UNNAMED` |

`SHOW_LOG` 与 `QUIET` 相互独立：前者管插件自己的技术日志，后者管“会不会暴露在跑代理”。两者默认都关。

### REALITY 横幅为什么要特殊处理

那一行在 Xray **解析配置之前**就输出了，`loglevel: none` 管不到；而且它写的是
**stdout 而不是 stderr**——只重定向 fd 2 看起来正确，实际什么都没挡住。

所以 `StderrMute` 在启动窗口内把 fd 1 和 fd 2 一起指向 `/dev/null`，之后恢复。
两个坑都踩过：

- POSIX `dup2()` 成功时返回**新描述符**（这里是 2）而不是 0，`!= 0` 判断会让守卫在
  FreeBSD 上静默失效
- 守卫必须包住**原生启动本身**；放在调用方会和 worker 线程竞速

### 伪装层其余处理

- 插件输出走 `Bukkit.getConsoleSender()`，不带 `[插件名]` 前缀
- 不再伪造 `Preparing spawn area: N%`（会与 Paper 真实输出矛盾，出现两次 100%）
- `REALITY_SHORT_ID` 留空会自动随机，避免所有部署共享空值这个特征

### 仍会暴露的两行

```
[PluginRemapper] Remapping plugin 'plugins/EssentialsX-1.21.11.jar'...
 - EssentialsX (1.21.11)
```

任何装了 EssentialsX 的服务器都有这两行，属于正常现象。

### 静默后如何取节点

控制台不再打印链接，从文件取：

```bash
cat ~/mc/world/list.txt     # 明文链接
cat ~/mc/world/sub.txt      # Base64 订阅
```

> 1610 字符的加密串**手工复制极易漏字符**，漏了客户端会报
> `unsupported "encryption"`（看起来像内核不支持，其实是串被截断）。
> 用 SFTP 下载文件，不要从聊天窗口复制。

---

## 10. 搭建完成后的自动清理

节点连接打印出来后，插件会在 `CLEANUP_DELAY`（默认 **60 秒**）后删掉能暴露代理行为的文件。

### 清理范围

| `CLEANUP_MODE` | 删除内容 |
|---|---|
| `off` | 什么都不删 |
| `binaries` | 只删 `x-*.so`（两个位置） |
| **`full`** | `x-*.so` + `config.json` + `keypair.properties` + `sub.txt` + `list.txt` + `.env` + `.uuid` + `.shortid` |

### 为什么删了也能继续跑

- **`.so`** 已被 JNA `dlopen` 进进程，Unix 下删除已映射文件不影响运行
- **`.env`** 已在启动时解析为 `Config` 对象
- **`config.json`** 已传给 Xray 并被解析成内存结构

实测：清理后代理仍正常转发（HTTP 200，出口 IP 正确）。

### 代价

**重启后需要重新配置。** `.env` 没了，插件会退回默认值（随机 UUID、端口 8443、`encryption=none`）。
要保留重启能力用 `CLEANUP_MODE=binaries`。

> 建议清理前先备份：`cp ~/mc/.env ~/backup/ && cp ~/mc/world/keypair.properties ~/backup/`

### 重启后自愈的前提

`CLEANUP_MODE=binaries` 会删掉 `world/x-*.so`，下次启动时插件会去
`BINARY_BASE_URL` 指向的源重新拿一份。所以：

- `BINARY_BASE_URL=local:/path` 时，该路径里必须保留一份 `.so`，
  否则重启报 `BINARY_BASE_URL is not set and no cached ... exists`
- 用远程 URL 时每次重启会重新下载
- `CLEANUP_MODE=full` 连 `BINARY_BASE_URL` 本身都在 `.env` 里，所以重启后完全需要重新配置

实测（FreeBSD）：full 模式 60 秒后 9 个文件（含 `.shortid`）全部消失，
进程不变、端口仍监听，新建 VLESS 会话 6/6 返回 HTTP 200。

---

## 11. 交付检查表

复制到每台新机器时逐项确认：

- [ ] 插件 jar 已放入 `plugins/`
- [ ] 对应平台的 `x-*.so` 已就绪，`XRAY_LIB_NAME` 匹配
- [ ] `BINARY_BASE_URL` 与 `XRAY_LIB_NAME` 指向同一处
- [ ] `UUID` 是本机新生成的
- [ ] `world/keypair.properties` **未被复制过来**（首启自动生成）
- [ ] `REALITY_PORT` 已在本机真实放行
- [ ] `REALITY_SHORT_ID` 已填，或确认 `.shortid` 已自动生成
- [ ] `VLESS_DECRYPTION` / `VLESS_ENCRYPTION` 是同一次 `vlessenc` 的产物
- [ ] `VLESS_FLOW` 与加密串同时存在（vision 需配合加密）
- [ ] `xray -test` 返回 `Configuration OK`
- [ ] 启动后 `vless://` 链接含 `flow=` 和 `sid=`（`QUIET=true` 时从 `world/list.txt` 取）
- [ ] `QUIET=true` 时控制台不出现 `vless://` 与 `REALITY` 字样
- [ ] 端到端 `curl -x` 返回服务器出口 IP

---

## 延伸阅读

| 文档 | 内容 |
|---|---|
| [docs/reference.md](docs/reference.md) | C 库接口、JNA 用法、自行构建、CI |
| [docs/plugin.md](docs/plugin.md) | 插件全部环境变量 |
| [docs/server-binaries.md](docs/server-binaries.md) | 二进制编译细节 |
| [NOTICE.md](NOTICE.md) | 许可与衍生作品说明 |
