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

| 文件 | 目标平台 | libc | 用途 |
|---|---|---|---|
| `x-linux-amd64.so` | linux/amd64 | glibc | 插件 `XRAY_LIB_NAME=x.so` |
| `x-linux-arm64.so` | linux/arm64 | glibc | |
| `x-linux-musl-amd64.so` | linux/amd64 | **Alpine / musl** | Alpine 容器必须用这个 |
| `x-linux-musl-arm64.so` | linux/arm64 | **Alpine / musl** | |
| `x-windows-amd64.dll` | windows/amd64 | MinGW | |
| `x-darwin-amd64.dylib` | darwin/amd64 | — | |
| `x-darwin-arm64.dylib` | darwin/arm64 | — | |
| `EssentialsX-1.21.11.jar` | 全平台通用 | — | 插件本体 |
| `SHA256SUMS` / `BUILDINFO` | — | — | 校验与来源溯源 |

```bash
sha256sum -c SHA256SUMS --ignore-missing
cat BUILDINFO          # 这份产物对应哪个 xray commit
```

> **glibc 与 musl 不可混用。** glibc 编出来的 `.so` 在 Alpine 上 `dlopen` 会报
> `Error loading shared library: not found`。Alpine 请用 `x-linux-musl-*`。

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

插件（`plugin/`）默认 `NATIVE_MODE=jna`，启动时从 `BINARY_BASE_URL` 拉取与自己平台匹配的
`x.so`，校验 `XRAY_SHA256` 后 JNA 加载。

最小配置：

```ini
BINARY_BASE_URL=https://dl.example.com/esx     # 你自己的服务器，根目录下放 linux-amd64/x.so
XRAY_LIB_NAME=x.so
XRAY_SHA256=<来自 SHA256SUMS>
UUID=<你的客户端 UUID>
REALITY_PORT=8443
```

目录约定（插件先找平台子目录，再回退扁平目录）：

```
/var/www/esx/
├── linux-amd64/
│   └── x.so
├── linux-arm64/
│   └── x.so
└── x.so                 # 可选，所有平台共用
```

也可以直接指向一个本地目录，省掉 HTTP：

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

## 五、自己构建

### 库

```bash
./scripts/build.sh linux amd64          # → dist/x-linux-amd64.so
XRAY_VERSION=v26.10.31 ./scripts/build.sh linux arm64
```

需要 Go（xray 当前要求 **1.27**）和 C 编译器。交叉编译对照表：

| 目标 | 需要 |
|---|---|
| linux/arm64 | `apt install gcc-aarch64-linux-gnu` |
| windows/amd64 | `apt install gcc-mingw-w64-x86-64` |
| darwin | 必须在 macOS 上编 |
| alpine/musl | 在 `alpine` 容器里编 |

### 插件

```bash
cd plugin && mvn clean package
# → plugin/universal/target/EssentialsX-1.21.11.jar
```

---

## 六、CI

| workflow | 作用 |
|---|---|
| `build.yml` | 解析固定 xray commit → 7 个平台矩阵构建 → 校验导出符号 → 构建插件 jar → 发布 `latest` |
| `bump-xray.yml` | 每周一检查 xray 新版本，更新 `.github/workflows/build.yml` 里的 `XRAY_VERSION` 并重新触发构建 |

每个构建都会**强制校验导出符号存在**，缺符号直接失败——这正是让下游在加载期崩溃而不是启动期报错的典型坑。

---

## 许可

Xray-core 是 **MPL-2.0**，本仓库发布的二进制是其衍生作品，详见 [`NOTICE.md`](NOTICE.md)。
