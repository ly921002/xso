# 技术参考

> 本文件承接原 README 的技术细节（C 库接口、构建、CI）。
> 部署步骤见根目录 [README.md](../README.md)。

---

## 库的接口

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


---

## 自己构建

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


---

## CI

| workflow | 作用 |
|---|---|
| `build.yml` | 解析固定 xray commit → 构建 linux/amd64 + windows/amd64 → 逐个校验导出符号 → 构建插件 jar → 校验 jar 布局 → 发布 `latest` |
| `bump-xray.yml` | 每周一检查 xray 新版本，更新 `.github/workflows/build.yml` 里的 `XRAY_VERSION` 并重新触发构建 |

每个构建都会**强制校验导出符号存在**，缺符号直接失败——这正是让下游在加载期崩溃而不是启动期报错的典型坑。发布前还会检查 jar 里 4 份平台描述符是否齐全。

---


---

## 许可

Xray-core 是 **MPL-2.0**，本仓库发布的二进制是其衍生作品，详见 [`NOTICE.md`](NOTICE.md)。
