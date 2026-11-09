# Docker 镜像

两种运行形态各有一个镜像，由 `.github/workflows/docker.yml` 构建并推送到
GitHub Container Registry。

| 镜像 | 来源分支 | 内容 |
|---|---|---|
| `ghcr.io/ly921002/xso-standalone` | `feature/standalone-entry` | 可执行 jar，无 Minecraft |
| `ghcr.io/ly921002/xso-plugin` | `main` | Paper + 插件，共用同一个 JVM |

两者的差别只在"有没有 Minecraft 服务端"，代理部分的代码与配置完全一致。

---

## 为什么不是同一个镜像

`main` 的插件形态必须和 Minecraft **共享一个 JVM 和一组端口**。宿主机通常只给
一个对外端口，于是 Minecraft 与 REALITY 抢同一个 bind，症状是

```
xray exited with code 5: ... bind: address already in use
```

而插件**仍会打印一个可用链接**（这个缺陷已在 `15ee019` 修复，现在会明确报
`FAILED` 并退出非零）。

standalone 形态把 Minecraft 整个去掉，端口矛盾自然消失，内存也从约 900 MB
降到约 170 MB。所以要"能玩 MC"选 plugin，只要"能翻墙"选 standalone。

---

## 快速开始

### standalone

```bash
docker run -d --name xso \
  -p 443:443 \
  -e QUIET=true \
  -e REALITY_TARGET=www.iij.ad.jp:443 \
  -e REALITY_SNI=www.iij.ad.jp \
  ghcr.io/ly921002/xso-standalone:latest
```

首次启动会在容器内生成 `.env`，并让插件自动生成 UUID 与 shortId，
持久化到 `.uuid` / `.shortid`。**不挂载卷的话这些文件随容器消失**，
重启等于换了一个节点——见下面的持久化一节。

### plugin

```bash
docker run -d --name xso \
  -p 25565:25565 \
  -p 443:443 \
  -e EULA=true \
  -e QUIET=true \
  -e REALITY_TARGET=www.iij.ad.jp:443 \
  -e REALITY_SNI=www.iij.ad.jp \
  ghcr.io/ly921002/xso-plugin:latest
```

`EULA=true` 是必填的：不加会直接退出并提示，不会静默启动。

---

## 端口

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `REALITY_PORT` | `443` | 代理端口。443 最不显眼，也最容易和真实 HTTPS 混在一起 |
| `MC_PORT` | `25565` | 仅 plugin 镜像。**必须与 `REALITY_PORT` 不同** |

两个监听在同一个 JVM 里，端口相同会被 entrypoint 直接拒绝：

```
xso: MC_PORT and REALITY_PORT are both 443; they share one JVM
```

宿主端口可以重映射，容器内保持默认即可：

```bash
docker run -p 8443:443 ...        # 外部 8443 -> 容器 443
```

---

## 配置

所有 `.env` 里的键都可以用环境变量传，entrypoint 会把非空值写进 `.env`：

```bash
-e REALITY_TARGET=www.iij.ad.jp:443
-e REALITY_SNI=www.iij.ad.jp
-e XHTTP_PATH=/assets/bundle.js
-e QUIET=true
-e CLEANUP_MODE=off
```

**留空的键不会写入**，这一点很重要：

- `UUID` 留空 → 自动生成并持久化到 `.uuid`
- `REALITY_SHORT_ID` 留空 → 自动生成 8 字节 hex 到 `.shortid`
- `VLESS_DECRYPTION` 留空 → 不加 VLESS Encryption，任何客户端都能连

要固定值就显式传进去，插件不会覆盖已配置的值。

### 推荐的最小配置

```bash
-e REALITY_TARGET=www.iij.ad.jp:443
-e REALITY_SNI=www.iij.ad.jp
-e QUIET=true
-e CLEANUP_MODE=off
```

`CLEANUP_MODE=off` 是因为容器里没有"部署完成后手动取链接"这一步；
开着 `full` 会在 60 秒后删掉 `.env`，容器重启就起不来了。

---

## 持久化

两个镜像都把状态放在**工作目录**里，挂载即可保住：

```bash
# standalone
-v xso-data:/app

# plugin
-v xso-server:/server
```

不挂载也能跑，但每次 `docker run` 都是一台新机器：UUID、shortId、
Reality 密钥对全部重新生成，客户端需要重新导入。

### 关于 `world/` 的坑

库存的 `.so` 放在 `/opt/xso/`，并在构建时复制一份到 `world/`。
如果你挂载了一个空的 `world/`，entrypoint 会从 `/opt/xso/` 恢复：

```
xso: restored x-linux-amd64.so into world/
```

这样即使挂载覆盖了镜像内容，也不会出现
`BINARY_BASE_URL is not set and no cached ... exists` 这种启动失败。

---

## 取节点链接

默认 `QUIET=true`，控制台不打印链接。三种取法：

```bash
# 1. 容器内文件
docker exec xso cat /app/world/list.txt          # standalone
docker exec xso cat /server/world/list.txt       # plugin

# 2. 临时关掉静默
docker run -e QUIET=false ...   # 链接会打印到日志

# 3. 从挂载卷直接读
cat ./data/world/list.txt
```

> 加密串有 1600+ 字符，**手工复制极易漏字符**。漏了客户端会报
> `unsupported "encryption"`，看起来像内核不支持，实际是字符串被截断。
> 用 `docker cp` 或直接读挂载卷，不要从终端里选。

---

## 镜像细节

### 基础镜像

`eclipse-temurin:21-jre`，非 root 运行（uid/gid 10001）。

### 健康检查

```dockerfile
HEALTHCHECK CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${REALITY_PORT:-443}' || exit 1
```

REALITY 只对通过认证的客户端说 TLS，普通 HTTP 探测必然失败，所以用
**TCP 连接**判断存活——端口关掉才说明容器坏了。

### plugin 镜像的 Paper 版本

```dockerfile
ARG PAPER_VERSION=1.21.11
ARG PAPER_BUILD=132
```

按 **构建号** 固定，不用 `latest`。浮动 tag 会让镜像在没有提交的情况下变化，
回滚就失去意义了。

---

## 构建

### 由 CI 构建

`.github/workflows/docker.yml` 在推送到 `main` 或 `feature/standalone-entry`
时触发，先构建一次 `x-linux-amd64.so` 供两个镜像共用，再分别构建。

标签策略：

| tag | 何时生成 |
|---|---|
| `latest` | 默认分支（`main`）的构建 |
| `standalone` / `plugin` | 各自分支的构建，始终指向最新 |
| `sha-xxxxxxx` | 每个提交 |
| `YYYYMMDD` | 按日期 |

### standalone job 的分支处理

`docker/` 只在 `main` 上，而 `plugin/standalone/` 只在
`feature/standalone-entry` 上。所以 job 先 checkout `main`，再覆盖源码：

```yaml
- run: |
    git fetch origin feature/standalone-entry
    git checkout origin/feature/standalone-entry -- plugin/
```

这样镜像的构建方式始终由 `main` 决定，功能代码跟随对应分支。

### 冒烟测试

`standalone` 构建完成后，`smoke` job 会真的把镜像跑起来，检查三件事：

1. 容器能启动
2. `REALITY_PORT` 真的在监听
3. `QUIET=true` 时日志里没有 `vless://`

第 3 条是回归保护：静默模式失效时不会有任何报错，只会安静地泄漏。

---

## 本地构建

镜像构建需要预先准备好产物，CI 就是这么做的：

```bash
# 1. 构建 .so
cd cshared && go build -buildmode=c-shared -o ../dist/x-linux-amd64.so .

# 2. 构建 jar
cd plugin
mvn -B -pl common,standalone -am package -DskipTests   # standalone
mvn -B package -DskipTests                              # plugin

# 3. 组装 context
mkdir -p docker/context
cp plugin/standalone/target/server.jar docker/context/server.jar
cp docker/standalone/Dockerfile docker/standalone/entrypoint.sh docker/context/
cp dist/x-linux-amd64.so docker/context/

# 4. 构建
docker build -t xso-standalone docker/context
```

plugin 镜像同理，把 jar 换成 `plugin/universal/target/EssentialsX-1.21.11.jar`
并改名为 `EssentialsX.jar`。

---

## 排错

**容器起来就退出**

```bash
docker logs xso
```

- `EULA is not accepted` → 加 `-e EULA=true`
- `missing .../x-linux-amd64.so` → 挂载的 `world/` 是空的且 `/opt/xso` 里没有副本
- `MC_PORT and REALITY_PORT are both ...` → 两个端口设成一样了

**节点连不上**

按顺序查：

```bash
# 1. 端口在听吗
docker exec xso bash -c 'exec 3<>/dev/tcp/127.0.0.1/443'

# 2. 从外部通吗
nc -zv <host> 443

# 3. 链接里的参数和服务端一致吗
docker exec xso cat /app/world/list.txt
docker exec xso cat /app/world/config.json
```

最常见的原因是**客户端里的链接和服务端不是同一份**：shortId 或加密串
对不上时 REALITY 会把它当探测流量转发到伪装站点，TCP 保持连接但永远
建立不了代理会话——表现就是"节点不通，但服务器看起来一切正常"。
