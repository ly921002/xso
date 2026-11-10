# PortProbe —— 端口可用性探测

一个独立的 Paper 插件，回答一个具体问题：**这台机器上哪些端口可以绑定**。

它和代理代码完全无关：不依赖 `essentialsx-common`，不改任何现有类，
装上、用、卸掉都不影响代理。

---

## 它回答什么，不回答什么

| 结论 | 是否可靠 |
|---|---|
| 本进程能 `bind()` 这个端口 | ✅ 可靠 |
| 从互联网能连到这个端口 | ❌ 不测，也测不了 |

这个区别很重要。在容器和共享主机上，本地 `bind()` 成功**不代表**外部可达：
Pterodactyl 需要面板分配端口，serv00 需要 `devil port add`。曾经遇到的现象就是
服务在容器里绑得好好的，外部每一个连接都被拒绝——插件输出末尾会带上这句提醒，
而不是让你事后自己发现。

---

## 用法

### 方式一：`.env` 开关（启动时自动跑）

在**服务端根目录**的 `.env` 里加：

```ini
PORT_PROBE=true
```

可选：

```ini
PORT_PROBE_PROTO=udp      # 默认 tcp
PORT_PROBE_FULL=true      # 默认只扫精选区间
```

结果在启动日志里：

```
[PortProbe] probing 629 TCP ports (curated)
[PortProbe] tested 629 in 130 ms, free: 12
[PortProbe]   443, 8443, 8090, 8101, 20000, 20001, 20002, 24010, ...
[PortProbe] A bindable port is only half the story: ...
```

布尔值接受 `true` / `1` / `yes` / `on`，其他一律按关闭处理，所以默认不会启用。

### 方式二：游戏内或控制台命令

```
/portprobe              # 精选区间，TCP
/portprobe full         # 全端口 1-65535
/portprobe udp          # UDP
/portprobe udp full     # UDP 全端口
```

权限 `portprobe.use`，默认仅 OP。

扫描始终在后台线程执行——全端口扫描要跑很久，放在主线程会触发服务器看门狗。

---

## 为什么先精选再全扫

全端口 65535 个逐个试探耗时很长，而且在别人的共享主机上容易被当成端口扫描。
所以默认只试**值得试的**：

| 分组 | 端口 |
|---|---|
| 代理/VPN 常用 | 443, 8443, 2053, 2083, 2087, 2096, 8080, 8880, 8444, 9443 |
| 通用应用 | 80, 8081, 8082, 3000, 5000, 7000, 9000, 9090, 10000 |
| Minecraft | 25565, 25566, 25567, 25575 |
| 共享主机常用段 | 8000-8100, 20000-20100, 24000-24100, 25000-25100, 30000-30100, 40000-40100 |

共 629 个，通常几百毫秒跑完。如果这 629 个全被占，再加 `full` 扫全量。

---

## 实现要点

### 用通配地址 bind，不是 127.0.0.1

```java
socket.bind(new InetSocketAddress(port), 1);
```

绑 `127.0.0.1` 时，即使别的进程已经占着 `0.0.0.0` 也能成功——那恰好是需要发现的
情况。所以这里绑通配地址。

### 设了 `SO_REUSEADDR`

真实服务几乎都会设它。不设的话，处于 `TIME_WAIT` 的端口会被误判为不可用，
而实际服务照样能绑上去。

### 错误信息翻译成人话

Java 对 `EACCES` 只给一句 `Permission denied`，对 `EADDRINUSE` 只给
`Address already in use`。前者在端口小于 1024 时几乎必然出现（需要 root 或
`CAP_NET_BIND_SERVICE`），但原始信息不会提这个门槛，所以单独标出来：

```
permission denied (ports < 1024 need root)
```

---

## 构建

```bash
cd plugin
mvn -B -pl portprobe -am package
# → portprobe/target/PortProbe-1.21.11.jar  (~13 KB)
```

也可直接当命令行工具跑（`PortScanner` 带 `main`）：

```bash
java -cp portprobe/target/PortProbe-1.21.11.jar \
  com.example.essentialsx.portprobe.PortScanner [tcp|udp] [full]
```

CI 在 `.github/workflows/portprobe.yml` 里跑真实 Linux 验证：构建、检查 jar 布局、
确认能扫出空闲端口、确认被占用的端口会被排除、以及 UDP 与全端口模式都能跑完。

---

## 实测记录

| 环境 | 结果 |
|---|---|
| GitHub Actions (Linux) | 精选 629 个，多数空闲，`SO_REUSEADDR` 行为符合预期 |
| serv00 (FreeBSD) | 全部 `EPERM`——该主机只允许 `devil port add` 预留过的端口 |

serv00 的结果与直接用 Python `socket.bind()` 逐端口试探**完全一致**，说明扫描逻辑
没有偏差；绑不上是主机策略，不是探测写错了。

> 在 serv00 这类主机上，PortProbe 的价值不在"扫出一堆可用端口"，而在**快速确认
> 某个具体端口是否真的被放行**——这正好是配 `REALITY_PORT` 前需要知道的。

---

## 卸载

删掉 `plugins/PortProbe-*.jar` 即可，不留任何状态文件。
