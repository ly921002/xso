# 在自己的服务器上托管 xray 二进制

插件不再从任何第三方站点下载。你需要在自己控制的服务器上放一份 xray，
并让插件通过 `BINARY_BASE_URL` 拉取（或在部署时预置到运行目录）。

---

## 方案 A：官方可执行文件 + 进程模式（最省事）

官方 xray 是普通可执行文件，直接采用 `NATIVE_MODE=process` 即可，不需要自己编译。

### 1. 下载并放到服务器

```bash
# 以 linux-amd64 为例
curl -LO https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip
unzip Xray-linux-64.zip -d xray-dist
mkdir -p /var/www/esx/linux-amd64
cp xray-dist/xray /var/www/esx/linux-amd64/xray
chmod +x /var/www/esx/linux-amd64/xray
```

目录约定（插件会先按平台目录找，找不到再回退扁平目录）：

```
/var/www/esx/
├── linux-amd64/
│   └── xray
├── linux-arm64/
│   └── xray
└── xray            # 可选：所有平台共用一个（仅当你只有一种架构时）
```

### 2. 生成校验值（强烈建议）

```bash
sha256sum /var/www/esx/linux-amd64/xray
# 把输出填进 XRAY_SHA256
```

### 3. 插件侧配置

```ini
NATIVE_MODE=process
XRAY_BINARY=/var/www/esx/linux-amd64/xray
# 或者让插件自己下载到运行目录：
# BINARY_BASE_URL=https://dl.example.com/esx
# XRAY_LIB_NAME=xray
# XRAY_SHA256=<上一步的 sha256>
```

> 注意：进程模式下 `XRAY_LIB_NAME` 指向的是**可执行文件**而不是 `.so`。

---

## 方案 B：编译成 c-shared 库 + JNA 模式（更隐蔽，推荐）

JNA 模式把 xray 作为动态库加载进 JVM，**磁盘上没有可执行文件、进程列表里也没有独立进程**，
配合伪装设计更稳妥。代价是需要自己包一层导出函数。

### 1. 导出层源码

在 xray 源码根的 `main/` 同级新建 `cshared/main.go`：

```go
package main

/*
#include <stdlib.h>
#include <string.h>
*/
import "C"

import (
	"encoding/json"
	"sync"
	"unsafe"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu     sync.Mutex
	server *core.Instance
)

func start(configJSON string) int {
	cfg := &core.Config{}
	if err := json.Unmarshal([]byte(configJSON), cfg); err != nil {
		return 2
	}
	inst, err := core.New(cfg)
	if err != nil {
		return 3
	}
	if err := inst.Start(); err != nil {
		return 4
	}
	server = inst
	return 0
}

//export StartXray
func StartXray(cfg *C.char) C.int {
	mu.Lock()
	defer mu.Unlock()
	if server != nil {
		return 1
	}
	return C.int(start(C.GoString(cfg)))
}

//export StopXray
func StopXray() C.int {
	mu.Lock()
	defer mu.Unlock()
	if server != nil {
		_ = server.Close()
		server = nil
	}
	return 0
}

func main() {}
```

### 2. 编译

```bash
# 在 xray 源码根目录
go build -buildmode=c-shared -o sbx.so ./cshared
```

产物是 `sbx.so` + 头文件，插件只需要 `sbx.so`。

### 3. 校验导出的符号

```bash
nm -D --defined-only sbx.so | grep -E 'StartXray|StopXray'
# 应该看到 StartXray 和 StopXray
```

> 插件在加载前会自行做这一步预检（`JnaRunner.verifySymbols`），
> 缺少符号会直接抛出明确错误，而不是运行到一半才 `UnsatisfiedLinkError`。
> —— 这正是原项目 v0 探针分支踩的坑。

### 4. 放到服务器

```bash
/var/www/esx/linux-amd64/sbx.so
sha256sum /var/www/esx/linux-amd64/sbx.so
```

插件侧：

```ini
NATIVE_MODE=jna
BINARY_BASE_URL=https://dl.example.com/esx
XRAY_LIB_NAME=sbx.so
XRAY_SHA256=<上一步的 sha256>
```

---

## 注意：跨平台不通用

`c-shared` 库跟架构、libc 强绑定，必须为每个目标平台各编一份：

| 目标 | 编译环境 |
|---|---|
| `linux-amd64` | `GOOS=linux GOARCH=amd64 CGO_ENABLED=1` |
| `linux-arm64` | `GOOS=linux GOARCH=arm64 CGO_ENABLED=1`（常用 `aarch64-linux-gnu-gcc` 交叉编译） |
| `linux-amd64` (musl) | 需用 `alpine` 环境或 `musl-gcc`，glibc 编译的库在 Alpine 上加载会失败 |

平台标识由插件按 `os.name` + `os.arch` 计算，格式为 `linux-amd64`、`linux-arm64`、`windows-amd64` 等。

---

## 安全建议

1. **务必设置 `XRAY_SHA256`**。未设置时插件会在日志里打印显式警告——它仍然可用，但等于放弃了完整性校验。
2. 二进制目录建议**只读**、且不要和面板/网站根目录同源。
3. 若用 CDN 分发，开启 HTTPS 并对目录设置访问控制；不要留下可列目录的开放路径。
4. 升级 xray 后记得同步更新 `XRAY_SHA256`，否则插件会判定校验失败并重新下载（这是预期行为）。
