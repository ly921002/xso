# xso

两件事放在同一个仓库里：

1. **`cshared/`** —— 把 [XTLS/Xray-core](https://github.com/XTLS/Xray-core) 编译成 **c-shared 库**
   （`x.so` / `x-<platform>`），可被 `dlopen` 进宿主进程，不落地可执行文件、不产生独立进程。
2. **`plugin/`** —— 改造版的 EssentialsX 多平台 MC 插件，用 JNA 在 JVM 内加载上面这个库。

`.github/workflows/build.yml` 会构建全部平台的库并发布到 [Releases](../../releases/tag/latest)，
同时构建插件的 jar。

---

## 一、下载产物

打开 **Releases → Latest build**：

| 文件 | 目标平台 | 插件是否自动选择 |
|---|---|---|
| `x-linux-amd64.so` | linux/amd64（glibc） | 是 |
| `x-linux-arm64.so` | linux/arm64（glibc） | 是 |
| `x-windows-amd64.dll` | windows/amd64 | 是 |
| `EssentialsX-1.21.11.jar` | Paper / Bungee / Velocity / Fabric 通用 | — |
| `SHA256SUMS` / `BUILDINFO` | 校验与来源溯源 | — |

**插件会按自己的平台自动挑选对应资产**，从 `latest` release 直接下载，
再用 `SHA256SUMS` 校验，你不需要手动准备任何文件。

```bash
sha256sum -c SHA256SUMS --ignore-missing
cat BUILDINFO          # 这份产物对应哪个 xray commit
```

> Alpine / musl 暂未提供。glibc 编出来的 `.so` 在 Alpine 上 `dlopen` 会报
> `Error loading shared library: not found`，需要另编 musl 版本。

---

## 二、库的接口

```c
int         StartXray(const char* configJSON);  /* 0 = 成功 */
int         StopXray(void);                     /* 0 = 成功 */
int         XrayRunning(void);                  /* 1 = 运行中 */
const char* XrayLastError(void);                /* 无错误时返回 ""，不要 free */
const char* XrayVersion(void);                  /* 不要 free */
void        FreeString(char* s);                /* 释放你自己取到的字符串 */
```

`configJSON` 就是**标准的 xray JSON 配置**（和 `xray run -c config.json` 用的同一份）。
`infra/conf` 格式和裸 protobuf-JSON 都接受。

`StartXray` 启动完立即返回，不阻塞；关停调 `StopXray`。

| 返回码 | 含义 |
|---|---|
| 0 | 成功 |
| 1 | 已经在运行 |
| 2 | `configJSON` 是 NULL |
| 3 | 配置解析失败 → 读 `XrayLastError()` |
| 4 | `core.New` 失败 |
| 5 | `core.Start` 失败 |

### Java / JNA

```java
public interface XrayLibrary extends Library {
    int StartXray(String configJson);
    int StopXray();
    int XrayRunning();
    String XrayLastError();
    String XrayVersion();
}

XrayLibrary lib = Native.load("/opt/esx/x.so", XrayLibrary.class);
int rc = lib.StartXray(Files.readString(Path.of("config.json")));
if (rc != 0) throw new IllegalStateException("xray: " + lib.XrayLastError());
```

完整示例见 [`examples/JnaXray.java`](examples/JnaXray.java)。

---

## 三、插件怎么用这个库

插件默认 `NATIVE_MODE=jna`，启动时**自动从本仓库的 latest release 下载与自己平台匹配的库**
（`x-linux-amd64.so` / `x-windows-amd64.dll`），JNA 加载后运行。

### 配置文件放在哪

插件启动时读取**进程工作目录**下的 `.env`（纯文本，`KEY=VALUE` 一行一个）。
工作目录就是 MC 服务端根目录，即**和 `plugins/` 同级**，不是放进去：

```
/home/container/                 ← 服务端根目录
├── .env                         ← 配置文件放这里
├── server.jar
└── plugins/
    └── EssentialsX-1.21.11.jar  ← 插件放这里
```

Fabric 服务端的 `plugins/` 换成 `mods/`，`.env` 位置不变。

> 为什么不是 `plugins/EssentialsX/`？因为插件用的是 `Path.of("").toAbsolutePath()`，
> 取的是 JVM 的工作目录。用 Pterodactyl 之类面板时，那就是容器的 `/home/container`。

### 最小配置

`/home/container/.env`：

```ini
UUID=
REALITY_PORT=8443
```

就两行，其余全用默认值。完整可选变量见 [`examples/.env.example`](examples/.env.example)。

也可以完全不写 `.env`，改用进程环境变量（面板的 Startup 变量、systemd `Environment=`、
Docker `-e`），两者等价，`.env` 优先级更高。

### UUID 是怎么来的

`UUID` 留空时，首次启动会生成一个随机 UUID 并写入工作目录下的 `.uuid`
（权限 `0600`），之后每次启动都复用它，所以**客户端配置不会因为重启而失效**。
想轮换凭据就删掉 `.uuid` 再重启；想固定就用 `.env` 里的 `UUID=` 显式指定，此时文件被忽略。

节点的配置存在 `world/`（由 `FILE_PATH` 决定）下：`config.json`、`keypair.properties`、`sub.txt`。

### 端口要放行

`REALITY_PORT` 必须对外可达。Pterodactyl 面板的默认 Allocation 只给了
25565 之类的游戏端口，改端口需要面板里额外分配，光改 `.env` 不会自动开。

### 用自己的服务器分发

不想走 GitHub 也可以：

```ini
BINARY_BASE_URL=https://your-host/esx   # 根目录下放 linux-amd64/x-linux-amd64.so
```

目录约定（先找平台子目录，再回退扁平目录）：

```
/var/www/esx/
├── linux-amd64/
│   └── x-linux-amd64.so
├── linux-arm64/
│   └── x-linux-arm64.so
└── x-linux-amd64.so      # 可选，扁平兜底
```

也可以直接指向本地目录，省掉 HTTP：

```ini
BINARY_BASE_URL=local:/opt/esx
```

想用官方可执行文件而不是 c-shared 库，切 `NATIVE_MODE=process` 并设 `XRAY_BINARY`。

插件完整变量表见 [`docs/plugin.md`](docs/plugin.md)；二进制编译细节见
[`docs/server-binaries.md`](docs/server-binaries.md)。

---

## 四、插件是什么

`plugin/` 是 [eooce/java-plugins-plus](https://github.com/eooce/java-plugins-plus) 的改造分支：

- 保留伪装设计（EssentialsX 命名、伪造 Paper 启动日志、清屏、后台静默），
  但**不再阻塞服务端主线程**；
- **去掉哪吒探针**，代码里零残留；
- **只剩一个协议**：VLESS + XHTTP + REALITY；
- 二进制只从**自己的服务器**取，支持 SHA-256 强校验；
- 修掉原项目的致命缺陷（`/reload` 端口冲突、v0 探针符号不存在、启动卡 30 秒、每次重启重下几十 MB）。

细节见 [`plugin/README.md`](plugin/README.md)。

---

## 五、验证记录

本仓库的产物经过以下实测（不是推断）：

| 项目 | 结果 |
|---|---|
| CI 构建 x-linux-amd64.so / x-windows-amd64.dll | ✅ 导出符号逐个校验通过 |
| Release 产物 SHA-256 | ✅ 与 `SHA256SUMS` 全部匹配 |
| UUID 生成 + 重启复用 | ✅ 首次生成写入 `.uuid`，第二次启动读到同一值 |
| 显式 `UUID` 覆盖文件 | ✅ |
| 平台推断资产名 | ✅ linux-amd64 → `x-linux-amd64.so`；windows-amd64 → `x-windows-amd64.dll` |
| 从 Release 自动下载 | ✅ 路径与资产名匹配 |
| JNA 加载 + 符号校验 | ✅ 5 个必需符号齐全 |
| `StartXray` 启动内核 | ✅ `Xray 26.10.31 started`，端口 8443 可连接 |
| `StopXray` 关停 | ✅ 端口关闭 |
| 配置不含 `flow` / `geoip` | ✅ |

用**发布出来的 jar 本身**跑完整链路（Config → BinaryStore → JnaRunner → StartXray → StopXray）已验证通过。

### 已知限制

- **只有 glibc 的 linux/amd64 和 windows/amd64。** arm64 的交叉编译需要完整 aarch64
  sysroot，在托管 runner 上反复卡住，已从矩阵移除；Alpine/musl 同理未提供。
- 下载源是 GitHub，网络受限的环境需要设 `BINARY_MIRRORS` 或 `BINARY_BASE_URL`。

---

## 六、自己构建

### 库

```bash
./scripts/build.sh linux amd64          # → dist/x-linux-amd64.so
```

需要 Go（xray 当前要求 **1.27**）和 C 编译器。交叉编译对照表：

| 目标 | 需要 |
|---|---|
| linux/arm64 | `apt install gcc-aarch64-linux-gnu libc6-dev-arm64-cross` |
| windows/amd64 | `apt install gcc-mingw-w64-x86-64` |
| darwin | 必须在 macOS 上编 |
| alpine/musl | 在 `alpine` 容器里编 |

> arm64 交叉编译要拉完整 aarch64 sysroot，CI 上不稳定，因此默认不进矩阵。

### 插件

```bash
cd plugin && mvn clean package
# → plugin/universal/target/EssentialsX-1.21.11.jar
```

---

## 七、CI

| workflow | 作用 |
|---|---|
| `build.yml` | 解析固定 xray commit → 构建 linux/amd64 + windows/amd64 → 逐个校验导出符号 → 构建插件 jar → 校验 jar 布局 → 发布 `latest` |
| `bump-xray.yml` | 每周一检查 xray 新版本，更新 `.github/workflows/build.yml` 里的 `XRAY_VERSION` 并重新触发构建 |

每个构建都会**强制校验导出符号存在**，缺符号直接失败——这正是让下游在加载期崩溃而不是启动期报错的典型坑。发布前还会检查 jar 里 4 份平台描述符是否齐全。

---

## 许可

Xray-core 是 **MPL-2.0**，本仓库发布的二进制是其衍生作品，详见 [`NOTICE.md`](NOTICE.md)。
