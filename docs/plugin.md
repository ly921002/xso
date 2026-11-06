## EssentialsX Plugin

用于多种 MC 服务端的 EssentialsX 插件，自动构建，支持 Paper / Spigot / Purpur / BungeeCord / Fabric / Velocity。

### 使用说明

1. 点击 Use this template ➡ Create a new repository 创建一个私密项目
2. 在 Actions 菜单允许 `I understand my workflows, go ahead and enable them`
3. 修改下方文件里标注的位置，填入你自己的参数，保存后 Actions 会自动构建
4. 等待 2 分钟，在 Releases 的 Latest Build 里下载 jar，放到对应服务端目录后重启

### 不同游戏放置的文件夹不同

- Paper / Spigot / Purpur：放到该服务端目录下的 `plugins/` 文件夹
- BungeeCord：放到 BungeeCord 服务端目录下的 `plugins/` 文件夹
- Velocity：放到 Velocity 服务端目录下的 `plugins/` 文件夹
- Fabric：放到 Fabric 服务端目录下的 `mods/` 文件夹

### 环境变量说明

#### 必填

```
BINARY_BASE_URL=            # 你自己的服务器上存放二进制的根地址，如 https://dl.example.com/esx
UUID=                       # 客户端连接凭据，留空则每次启动随机生成
```

#### 协议

```
REALITY_PORT=8443           # 监听端口
REALITY_TARGET=www.iij.ad.jp:443   # REALITY 伪装目标
REALITY_SNI=www.iij.ad.jp   # 客户端 SNI，默认取 target 的主机名
REALITY_SHORT_ID=           # 可选，shortId
XHTTP_PATH=/assets/bundle.js       # XHTTP 路径（建议用看起来正常的静态资源名）
XHTTP_MODE=auto             # auto / packet-up / stream-up
XHTTP_HOST=                 # 可选，XHTTP host
```

#### 节点信息

```
CFIP=                       # 优选域名或 IP，留空则自动探测本机公网 IP
CFPORT=                     # 留空则等于 REALITY_PORT
NAME=                       # 节点名前缀
```

#### 二进制

```
XRAY_LIB_NAME=sbx.so        # 运行目录下的文件名
XRAY_SHA256=                # 强烈建议填写，未填会打印警告
NATIVE_MODE=jna             # jna=进程内加载（隐蔽）；process=外部进程
XRAY_BINARY=                # NATIVE_MODE=process 时的可执行文件路径
BINARY_MIRRORS=             # 可选，备用下载源，逗号分隔
```

#### 推送（默认全部关闭）

```
UPLOAD_URL=                 # 节点上传面板地址，留空不上传
CHAT_ID=                    # Telegram Chat ID
BOT_TOKEN=                  # Telegram Bot Token
```

#### 行为开关

```
SHOW_LOG=false              # 默认关闭；true 才输出内部调试细节
CAMOUFLAGE=true             # 是否输出伪造的 Paper 启动日志
KEEP_BINARIES=true          # 是否保留下载的二进制（避免每次重启重下）
CLEANUP_MODE=full           # off / binaries / full（默认 full）
CLEANUP_DELAY=60            # 多少秒后清理敏感文件
ALLOW_INSECURE=false        # 链接是否带 allowInsecure=1（REALITY 下建议 false）
FILE_PATH=world             # 运行目录（相对于服务端根目录）
```

#### 可选 WARP 出站

```
WARP_ENABLE=false           # 默认关闭
WARP_PRIVATE_KEY=           # 开启时必填（本版不再内置任何共享密钥）
WARP_PUBLIC_KEY=            # 开启时必填
WARP_ENDPOINT=engage.cloudflareclient.com:2408
WARP_ADDRESS=172.16.0.2/32
WARP_RESERVED=              # 可选，形如 78,135,76
```

> 开启 WARP 但未提供密钥时，插件会直接抛配置错误而不是使用共享密钥。

### 说明

- 只包含一个协议：VLESS + XHTTP + REALITY。
- 不包含任何监控探针。
- 二进制只从 `BINARY_BASE_URL`（你自己控制）获取，配置了 `XRAY_SHA256` 会强制校验。
- 二进制的编译/放置方法见 [docs/server-binaries.md](docs/server-binaries.md)。
