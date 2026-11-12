# HyperConduit

> **声明**
>
> - **个人自娱自乐项目**：本项目纯属个人的奇思妙想，做出来自己玩玩和验证思路。非严肃工业级产品，不喜勿喷。
> - HyperConduit 是一个仍在验证中的 **Vibe Coding** 项目，不是经过完整安全审计或大规模生产验证的网络产品。请先在可控环境测试，并建议保留原版 TCP 共存。
> - **尚未经过真实公网验证**：本项目尚未在跨运营商、跨境、晚高峰或运营商 QoS 等复杂公网环境中进行实测。不同网络对 UDP 的策略差异很大，**不保证抗 QoS、抗丢包、吞吐、延迟或游戏体验会有任何改善**；在某些网络上甚至可能更差。
> - `Brutal` 是一种偏激进的发包策略：确认率下降时会提高发送速率来补偿丢包（当前实现最多为目标速率的 1.25 倍）。错误设置带宽、共享网络使用，或遇到运营商对 UDP/异常流量的限制时，可能影响同网络中的其他业务，也可能触发限速、丢包或阻断。使用者应自行承担相应网络风险。
> - **1.0.0 底层重构说明**：旧版 `v0.1.1` 是纯 Java 手写的 “QUIC-like / Brutal-like” 传输。`1.0.0` 改为随 Mod 打包的定制 Hysteria 2 `core/` + `apernet/quic-go` 程序，使用真正的 RFC 9000 QUIC / HTTP/3 / TLS 1.3，以及 Hysteria 2 的 Brutal / BBR / Reno 拥塞控制。它不兼容 `v0.1.1` 的配置和协议；因为针对 Minecraft 固定目标做了裁剪，也不能与原版 Hysteria 2 的通用代理互通。

**HyperConduit** 是面向 **Minecraft 1.21.1 NeoForge** 的双端传输 Mod：客户端和服务端都启用后，Minecraft 的联机数据改走一条 **QUIC 隧道**，并可选用 **BBR**、**Brutal** 或 **Reno** 拥塞控制。设计目的是在高丢包、跨网等较差的线路上，尝试获得比单条 TCP 更好的体验。

登录、正版验证、白名单、封禁等游戏逻辑仍由原版 Minecraft 处理。HyperConduit 只改变数据在两端之间的传输方式。

## 当前状态

| 项目 | 状态 |
| --- | --- |
| 局域网联机、NeoForge / Youer 专用服连接 | 已在 Windows 上实测 |
| 公网联机、恶劣网络下与原版 TCP 的效果对比 | **未测试** |
| Linux / macOS 运行 | **未实机运行**，只做过交叉编译和文件校验 |
| 长期稳定性、多人同时连接的限额隔离、SRV / IPv6 实际连接 | 未系统测试 |
| 更多 Mod / 插件组合 | 未系统测试 |

具体测过的项目见文末“测试记录”。

## 它是什么，不是什么

- 默认关闭。关闭时连接方式与原版完全相同。
- 隧道需要**客户端和服务端都安装并启用**。服务端可以同时保留原版 TCP，没装 Mod 的玩家照常加入。
- 玩家仍然在服务器列表里填写原来的 `地址:端口`，不需要域名、证书或手动运行后台程序（隧道程序已打包在 Mod 里，由 Mod 自动启动）。
- 它不是通用代理，也不会绕过原版的登录与验证。
- 它**不保证**改善网络。UDP 被限速或封锁的线路上，它可能更差，甚至连不上；隧道失败时**不会**自动改走 TCP。

当前只支持 NeoForge，Fabric 尚未实现。

## 安装与使用

### 安装

客户端和服务端都需要 **Minecraft 1.21.1 + NeoForge** 和 **Java 21**。把同一版本的 `hyperconduit-neoforge-1.0.0.jar` 放进各自的 `mods/` 目录。

### 启用

- **客户端**：多人游戏界面 → 左上角 `HyperConduit…` → 打开 `启用 HyperConduit` → 保存。
- **局域网主机**：单人世界中打开“对局域网开放”界面 → 左上角 `HyperConduit 主机设置…` → 打开 `启用 HyperConduit` → 保存，然后开放局域网。
- **专用服务端**：编辑服务端目录下的 `config/hyperconduit-hy2.json`，把 `enabled` 设为 `true`，重启服务端。文件不存在时，最小内容是：

  ```json
  {
    "enabled": true
  }
  ```

  文件已存在时，只改需要的字段。

第一次使用建议保持默认：带宽 `0 / 0`、拥塞控制 `BBR`、口令留空。能正常联机后，再按线路情况调整。

设置不会影响已经建立的连接：客户端在下一次连接时生效；局域网主机需要退出当前世界，重新进入并开放局域网；专用服需要重启。

### 放行 UDP

隧道走 **UDP**，端口号与游戏端口相同。游戏端口是 `25565` 时，需要在服务器防火墙或安全组中放行 `UDP 25565`。从外网加入时，路由器端口映射或内网穿透也必须转发 UDP；只转发 TCP 无法使用隧道。

放行后，玩家照常填写服务器的 `地址:端口` 加入即可。

### 服务端的两种模式

- **共存（默认）**：同一个端口号上同时提供原版 TCP 和 HyperConduit UDP。
- **仅隧道**（开启 `禁止原版 TCP`）：不再对外提供原版 TCP，只接受隧道连接。此模式下服务端必须使用固定端口号（不能设为 `0` 让系统随机分配），局域网开放时端口号为 `0` 则无法启动隧道监听。

> 如果希望所有玩家都必须通过口令验证，需要使用仅隧道模式。共存模式下口令只保护隧道入口，不保护原版 TCP。

## 拥塞控制

| 模式 | 如何得到 | 说明 |
| --- | --- | --- |
| **BBR**（默认） | 带宽填 `0`，拥塞控制选 `BBR` | 根据带宽和 RTT 的估计调整发送速率。 |
| **Reno** | 带宽填 `0`，拥塞控制选 `Reno` | 传统的、基于丢包反馈的算法，可用于对照。 |
| **Brutal** | 客户端某个方向填写大于 `0` 的带宽，且主机没有禁用 Brutal | 按填写的目标速率发送，确认率下降时提高速率补偿丢包。 |

我没有对这三种模式做过对比测试，所以这里不推荐哪一种更好。需要强调的只有一点：Brutal 不会自己探测线路容量，目标值是你填的，填得超过真实容量会加重拥塞。

### 带宽怎么填

设置界面的 **发送 Mbps** / **接收 Mbps** 对应配置文件的 `upMbps` / `downMbps`，范围 `0–1000000`。

**客户端**：字面意思——"发送"是客户端发出数据包的目标速率，"接收"是客户端接收数据包的目标速率。哪个方向大于 `0`，哪个方向就启用 Brutal。

**服务端**：同样是字面意思——"发送"限制服务端向**单个玩家**发包的 Brutal 目标，"接收"限制**单个玩家**向服务端发包的 Brutal 目标；`0` 表示不限制。这不是全服总带宽，也不影响 BBR 和 Reno。

**速率协商**：以"服务端 → 客户端"（玩家下载游戏数据）为例，这个方向由服务端负责发包，实际 Brutal 速率取 **客户端 `接收`** 与 **服务端 `发送`**（非 0 时）中的较小值。反之亦然。

| 方向 | 发包方 | 启用 Brutal 的条件 | Brutal 目标速率 |
| --- | --- | --- | --- |
| 服务端 → 客户端 | 服务端 | 客户端 `接收` > 0 | 客户端 `接收` 与服务端 `发送`（非 0 时）中较小者 |
| 客户端 → 服务端 | 客户端 | 客户端 `发送` > 0 | 客户端 `发送` 与服务端 `接收`（非 0 时）中较小者 |

例如客户端 `发送 10 / 接收 20`，主机 `发送 5 / 接收 3`：服务端向玩家发送的目标是 `5 Mbps`，玩家向服务端发送的目标是 `3 Mbps`。

**`禁用 Brutal`**（仅主机生效）：忽略客户端声明的带宽，双方都使用各自设置的 `BBR` 或 `Reno`。连接仍然会建立，它只是拥塞控制的选择，不是拒绝连接的开关。

Brutal 的“目标速率”是协商后的基础值，丢包补偿可能让实际发送速率高于它。

## 连接时会发生什么

### 服务器列表标记

启用后，多人游戏列表里原版信号图标左侧会出现一个 `HC` 标记，鼠标悬停可以看到：服务器是否启用了 HyperConduit、是否允许原版 TCP、Brutal 是否被禁用、是否需要口令。

显示“未声明”或“未知”，只表示这次没有取得有效声明（可能没装 Mod、查询超时、被防火墙拦截，或声明无效），**不能据此认为对方没装 Mod**。

### 连接前的确认

点击加入时，Mod 会先快速检查服务器（最长约 6 秒）。只有在探测到以下两种配置冲突时，才会弹窗询问：

1. **你启用了 HyperConduit，但服务器明确声明未启用，并允许原版 TCP**：询问是否本次改用原版 TCP。
2. **你没有启用，但服务器明确声明禁止原版 TCP**：询问是否本次启用隧道连接。

选择只对**这一次连接**有效，不会修改设置，也不会被记住。无法判断时，直接按你当前的设置连接，不询问。

选择原版 TCP 时，这次连接不使用隧道的身份校验和口令保护。选择隧道后如果失败（超时、口令错误、身份变化等），会显示带 `[HyperConduit]` 的中文错误，不会自动改走 TCP。

## 配置文件

配置在各自游戏目录的 `config/hyperconduit-hy2.json`，完整默认内容：

```json
{
  "enabled": false,
  "upMbps": 0,
  "downMbps": 0,
  "congestion": "bbr",
  "disableBrutal": false,
  "disableVanillaTcp": false,
  "clientToken": "",
  "serverToken": "",
  "connectTimeoutMs": 5000,
  "maxConnections": 64
}
```

| 字段 | 含义 |
| --- | --- |
| `enabled` | 是否启用 HyperConduit。 |
| `upMbps` / `downMbps` | 带宽声明或限制，见上文。 |
| `congestion` | 不使用 Brutal 时的拥塞控制：`bbr` 或 `reno`。 |
| `disableBrutal` | 仅主机生效：双方都不使用 Brutal。 |
| `disableVanillaTcp` | 仅主机生效：仅隧道模式。 |
| `clientToken` / `serverToken` | 可选的访问口令。 |
| `connectTimeoutMs` | 建立隧道、连接后端的超时，`100–120000` 毫秒。 |
| `maxConnections` | 隧道连接数上限，`1–4096`。 |

读取很严格：不接受注释、重复或未知字段、错误的类型或非整数的数字。文件无效时 Mod 启动阶段就会报错，而不是悄悄使用默认值。设置界面里未填的数值会回到默认值。

## 口令与身份信任

### 访问口令

默认不需要口令。主机填写 `serverToken` 后，客户端必须在 `clientToken` 中填写相同的口令才能建立隧道。口令只能使用可打印 ASCII 字符，最长 1024 个，首尾不能有空白。

设置界面不显示已保存的口令：留空保存表示保持原值，点击 `清除客户端口令` / `清除服务器口令` 再保存才会清除。

### 首次信任与身份变化

- 服务端第一次启用隧道时，自动生成长期身份，保存在 `config/hyperconduit/server-identity.json`。**文件含私钥，请勿泄露。** 迁移服务器时请一并保留，否则玩家会看到“身份已变更”。
- 客户端第一次成功连接某个 `地址:端口` 后，记住该服务器的公钥指纹（TOFU，首次使用即信任），保存在 `config/hyperconduit/known-servers.json`。记录以你在服务器列表中**填写的** `地址:端口` 为键。
- 之后同一个地址出现不同的身份时，连接会被拦截，提示“服务器身份已变更”。请先向服主确认是否更换了服务器，再到 `HyperConduit… → 已信任服务器…` 删除旧记录，下次连接会重新信任。
- TOFU 无法防止“第一次就连到了冒充的服务器”。已保存的指纹可以在 `已信任服务器…` 中选中条目查看。服务器列表的探测结果不能作为身份证明，也不会写入信任记录。

## 状态与命令

客户端按 `F3`，或执行 `/hyperconduit status`：

```text
[HyperConduit] [隧道已连接]
[传输] 本地发送 BRUTAL（目标 10.00 Mbps） | QUIC RTT 15.1 ms | 发送 0.02 Mbps | 接收 1.52 Mbps
```

未使用隧道时显示 `[未使用隧道]` 或 `[已禁用]`。

服务端控制台或 OP 权限等级 2 的管理员可以执行：

```text
/hyperconduit status
/hyperconduit watch
/hyperconduit watch stop
```

`watch` 约每秒输出一次：

```text
[HyperConduit] 服务端配置 | 已启用 | 允许协商 Brutal | 原版TCP禁止
[HyperConduit] [PlayerName] 隧道 源 203.0.113.5 | 本地发送 BBR | QUIC RTT 14.1 ms | 发送 0.05 Mbps | 接收 0.02 Mbps
[HyperConduit] 总计 | 隧道 1 | 原版TCP 0 | 本地 0
```

- 控制器名称（`BBR` / `RENO` / `BRUTAL`）是**本端实际使用的发送控制器**。
- RTT 和速率来自本端的 QUIC 统计。速率包含重传和协议开销，不含外层 UDP/IP 报头。
- 第一行是服务端当前保存的配置；保存后、重新开放监听前，它可能与正在运行的策略不同。
- 总计行的"本地"是指单人世界或局域网房主自身的内存连接（MemoryConnection），不经过网络。单人世界的 `F3` 显示的是内置服务端的状态。

## 已知限制

- 没有做过公网与性能测试，不能给出任何"提升多少"的数字。
- 隧道失败不会自动回退到 TCP，需要玩家自己改设置，或使用连接前确认中的"本次使用原版 TCP"。
- 客户端每次联机、服务端每个监听器都会启动一个本机子进程。安全软件阻止它运行时，会提示"无法启动隧道进程"。
- 兼容性只验证过原生 NeoForge 专用服和基于 Paper 的 Youer。如果第三方插件绕过 Minecraft 的 `Connection`，直接读取底层 Netty 通道地址，看到的会是 `127.0.0.1`，不是玩家真实地址。
- 双方需要使用互相兼容的版本。目前没有版本号校验，建议两端使用同一个 jar。
- 修改配置后未重启监听时，对外声明可能与实际生效的策略不符。如果服务端安装了多端口监听类 Mod 且不同监听器的口令或 Brutal 策略不一致，对外声明会变成"未知"。

## 测试记录

已在 Windows 上完成的局域网联机和专用服测试：

- 局域网主机开放、加入、退出重进，以及退出世界后隧道关闭、重新开放；
- 共存模式下原版 TCP 与隧道连接，仅隧道模式下的入口限制；
- BBR / Reno、双向 Brutal 目标协商、主机的每连接上限与禁用 Brutal；
- 口令成功与失败、首次信任、身份变化被拒绝，以及删除信任后重新连接；
- 服务器列表的能力标记与原版延迟显示、两种单次传输选择、在线指标与连接分类；
- NeoForge 专用服的共存连接与在线状态；Youer 专用服的共存、仅隧道连接，以及 `status`、重复 `watch`、`watch stop`。

这些是功能验证，不是效果对比。

---

# 技术说明

以下内容面向想了解实现的读者。

## 1. Java 与 Go 的分工

Java 部分负责 Minecraft 集成、界面和子进程的生命周期。Go 部分（`hyperconduit-tunnel`）负责 QUIC/TLS、HY2 协商、可选口令鉴权和固定目标转发。

```text
Minecraft Client (Netty Channel)
    ↕ 127.0.0.1 临时 TCP 端口
hyperconduit-tunnel（客户端）
    ↕ QUIC / HTTP/3 / TLS 1.3，经 UDP
hyperconduit-tunnel（服务端）
    ↕ 127.0.0.1 临时 TCP 端口 + HCMETA01 前言
Minecraft Server (Netty ServerBootstrap)
```

HTTP/3 只用于鉴权和能力查询。游戏字节流使用 HY2 的 TCP 请求帧，一条 Minecraft TCP 连接对应一条 QUIC stream。

- **客户端（`ConnectionClientMixin`、`ClientTunnelInitializer`）**：启用时，每次联机启动一个 `hyperconduit-tunnel client --managed` 子进程，待它报告本机回环端口后，才把 Netty 的 TCP 连接指向该端口；连接关闭时一并结束子进程。解析之前的原始 `地址:端口` 由 `ServerNameResolverMixin` 记录，用于保持握手包中的主机名，并作为 TOFU 的键。
- **服务端（`ServerConnectionListenerMixin`）**：用 `@Group(min = 1, max = 1)` 同时适配原生 NeoForge 的 `startTcpServerListener` 与 Youer（Paper）的 `bind(SocketAddress)`。共存模式保留原版 TCP 绑定，并另建一个绑定在 `127.0.0.1` 随机端口的私有后端（显式开启 `AUTO_READ`）；仅隧道模式只创建私有后端，再监听同号 UDP。每个监听器对应一个服务端子进程。
- **桥接握手头（Bridge Preamble，`BridgeInitializer`）**：监听器启动时生成 32 字节随机值，编码为 64 位十六进制的 `bridgeToken`。服务端子进程在向私有后端转发每条流之前，先写入魔数 `HCMETA01`、4 字节长度和 JSON 元数据 `{token, peer, connectionId, streamId}`。Java 以恒定时间比较校验通过后，才把 QUIC 层观测到的真实客户端地址放入 `TunnelAttributes.PEER`，并在 `Connection.channelActive` 中使用。
- **生命周期与私有配置**：子进程通过标准输入接收控制指令，父 JVM 退出导致标准输入 EOF 时子进程自行退出。每次启动的私有配置位于 `config/hyperconduit/launch/`，只对当前用户可访问（POSIX `0600` / Windows 专属 ACL）；正常关闭时由 Java 删除，父进程被强杀时可能残留当次文件，但不会被后续启动复用。
- **程序提取**：隧道程序按平台打包在 jar 中，启动前提取到 `config/hyperconduit/bin/`，按清单校验 SHA-256 后才执行。

## 2. Hysteria 2 core 的裁剪

`sidecar/hy2-core` 基于官方 Hysteria 2 `core/` 定制，保留标准 QUIC、HTTP/3 鉴权和原生拥塞控制，并收紧了非 Minecraft 场景：

- **固定目标**：没有 SOCKS/HTTP 入口，也没有通用出站。服务端只接受固定服务标识 `minecraft:0` 的请求，并转发到启动时指定的后端；在 Mod 中，后端是 Java 创建的本机私有监听器。
- **移除的功能**：应用层 UDP 转发与分片、请求嗅探与改写、可配置伪装（Masquerade）、Chrome 指纹模拟、自动重连封装。
- **帧收紧**：TCP 请求、响应帧里保留 padding 长度字段，但写入端固定为 `0`、读取端拒绝非零值；HTTP/3 鉴权头也不再发送随机填充。因此两端必须使用兼容的版本，也不与原版 Hysteria 2 互通。
- **不自动重连、不回退 TCP**：隧道失败直接报错。

## 3. 带宽协商与 Brutal

协商沿用 Hysteria 2 的 HTTP/3 鉴权头 `Hysteria-CC-RX`，请求与响应中的含义不同：

- 请求中是**客户端的接收带宽**（`downMbps`），供服务端决定向客户端发送的目标；
- 响应中是**服务端的接收带宽上限**（`downMbps`），供客户端决定向服务端发送的目标；
- 服务端开启 `disableBrutal` 时，会忽略请求值，并在响应中要求客户端也忽略自己的声明，双方按各自的 `congestion` 使用 BBR 或 Reno。

两端的 `0` 含义不同：客户端的 `0` 表示该方向不请求 Brutal，服务端的 `0` 表示不限制、不会关闭 Brutal。内部单位为字节/秒，即 `Mbps × 1,000,000 ÷ 8`。

Brutal 的窗口与发送速率为：

```text
              bps × sRTT × 2
cwnd     = ───────────────────
                  ackRate

                 bps
paceRate = ───────────
              ackRate
```

`bps` 是协商出的目标（字节/秒），`sRTT` 是平滑 RTT（秒），`cwnd` 以字节计，尚无 RTT 样本时使用初始值，并且不小于一个数据报。`BrutalSender` 用 5 个一秒的时间槽统计近期的确认包与丢失包：样本不足 50 时 `ackRate = 1`，达到 50 后按确认比例计算，下限 `0.8`。所以当前实现的 pacing 速率最多是目标的 `1.25` 倍，由令牌桶 `Pacer` 控制突发和发送间隔。这是 pacing 速率的上限，不是任意短时间窗口内包含全部网络开销的线速上限。

## 4. 能力发现与连接前检查

服务器的能力声明（协议版本 `2`）固定为五个字段：

```json
{
  "protocol": 2,
  "enabled": true,
  "tcpAllowed": true,
  "disableBrutal": false,
  "tokenRequired": false
}
```

- **来源一**：`StatusPacketMixin` 把当前存活监听器的声明加入原版状态 JSON 顶层的 `hyperconduit` 字段。声明由 `CapabilityListenerRegistry` 按实际存活的对外 TCP 与子进程汇总，不读取尚未应用的新配置。
- **来源二**：列表探测没有拿到有效声明时，客户端用复用的 `hyperconduit-tunnel probe` 进程，通过 HTTP/3 向 `https://hyperconduit/capability` 发起只读 GET。查询会建立 QUIC/TLS 连接，每条连接最多查询一次，不做口令鉴权，也不向后端服务发起连接。
- **连接前检查（`TransportDiscovery`）**：总截止 6 秒。先通过 TCP 查询原版状态（连接、读取超时各 1.2 秒）；拿到有效声明就判断是否需要询问，拿到状态但没有声明则按当前设置连接；只有 TCP 状态查询失败时，才再尝试 UDP 的 `probe`。
- 这些查询**不发送口令、不写入信任记录，也不对照已保存的公钥**，结果只用于显示和询问。真正的隧道连接另行执行身份与口令验证。

## 5. 身份与 TOFU

- **服务端身份**：首次使用时生成 ECDSA P-256 密钥与自签名证书。证书有效期 365 天，到期前 30 天内用同一私钥续签，因此公钥指纹不变。
- **客户端信任**：以公钥 **SPKI SHA-256** 指纹为信任锚点，跨进程写入由文件锁保护。只有 TLS 握手证明对方持有该私钥、**并且**口令鉴权通过之后，才写入首次信任；已有记录与当前指纹不一致时，以 `identity_changed` 中止。
- **口令**：通过 HY2 鉴权头在加密的 QUIC 连接内传递，不出现在命令行中。

## 6. 实时指标

Java 在子进程就绪后开启 `observe`，Go 侧每秒从标准输出推送一行 `stats`（最多 128 条活动连接，单行不超过 64 KiB）。指标来自实际的 QUIC 统计和本端发送控制器，不是根据游戏流量推算的。

- Java 用单调序号与时间戳计算两次采样的字节差，得到速率。首帧、超过 3.5 秒间隔后的恢复帧、计数回退时显示 `速率待采样`；超过 3.5 秒没有新帧则显示 `指标暂不可用`。
- 服务端通过 `TunnelAttributes.CONNECTION_ID` 把玩家通道对应到所属监听器的 QUIC 连接；客户端进程只有一条 QUIC 连接。
- 异常的统计数据只会让指标失效，不会中断游戏连接；子进程退出或受管控制协议出错则可能中断连接。

## 7. 平台与构建

jar 默认包含以下五个平台的无 CGO 程序（`io/hyperconduit/sidecar/bin/<平台>/`），不含 `windows-arm64` 和 32 位系统：

| 平台 | 验证状态 |
| --- | --- |
| `windows-amd64` | 自动回归通过；局域网联机及 NeoForge / Youer 专用服已实测 |
| `linux-amd64` / `linux-arm64` | 已交叉编译，并检查 ELF 头、提取与 SHA-256；**未在对应系统上实际运行** |
| `darwin-amd64` / `darwin-arm64` | 已交叉编译，并检查 Mach-O 头、提取与 SHA-256；**未在 macOS 上实际运行** |

仓库结构：

- `sidecar/`：独立的 Go module，包含 `hyperconduit-tunnel`、配置、身份、信任、探测等模块；`sidecar/hy2-core/` 是通过本地 `replace` 引用的定制传输核心。
- `:sidecar-runtime`：二进制提取与校验、私有临时配置、子进程生命周期、桥接前言校验、指标解析。
- `:mod-common`：配置、能力发现、连接前选择界面，以及客户端/服务端 Mixin。
- `:neoforge`：NeoForge 入口、设置与信任管理界面、`F3` 叠加层、服务端命令。
- `:core`：旧版 `v0.1.1` 的 Java 传输实现，仅作参考保留，不再承载联机。

构建需要 **JDK 21** 和 **Go 1.26**（默认使用 `PATH` 中的 `go`，可用 `-PsidecarGo=<路径>` 指定）：

```bash
./gradlew :neoforge:jar
./gradlew :neoforge:verifyDistribution
```

Windows 请使用 `gradlew.bat`。产物：

```text
neoforge/build/libs/hyperconduit-neoforge-1.0.0.jar
```

## 参考项目与许可

HyperConduit 基于下列开源项目构建或衍生（致谢只说明代码来源和依赖关系，不代表上游作者对本项目的背书）：

- **[Hysteria 2 (`HyNetwork/hysteria`)](https://github.com/HyNetwork/hysteria)**（MIT License, Copyright 2023 Toby）
  - `sidecar/hy2-core/` 派生自官方仓库固定提交 [`fb25dc852ebbc70658e67889f411887a82b7c281`](https://github.com/HyNetwork/hysteria/commit/fb25dc852ebbc70658e67889f411887a82b7c281) 的 `core/` 目录，使用了其 QUIC/HTTP3 鉴权、带宽协商、Brutal/BBR/Reno 拥塞控制与 Pacer 实现。
- **[`apernet/quic-go`](https://github.com/apernet/quic-go)** 及上游 **[`quic-go/quic-go`](https://github.com/quic-go/quic-go)**（MIT License）
  - 提供 QUIC 传输、多路复用流、HTTP/3 与拥塞控制接口。
- **[Go 标准库与 `golang.org/x/*`](https://go.dev/)**（BSD-3-Clause License），以及传递依赖 **[`quic-go/qpack`](https://github.com/quic-go/qpack)**、**[`refraction-networking/utls`](https://github.com/refraction-networking/utls)**（由 `apernet/quic-go` 的 TLS 握手引入）、**[`klauspost/compress`](https://github.com/klauspost/compress)**、**[`andybalholm/brotli`](https://github.com/andybalholm/brotli)**。
- **[NeoForge](https://neoforged.net/)**、**[ModDevGradle](https://github.com/neoforged/ModDevGradle)**、**[SpongePowered Mixin](https://github.com/SpongePowered/Mixin)**、**[ParchmentMC](https://parchmentmc.org/)**、**[Netty](https://netty.io/)**、**[Google Gson](https://github.com/google/gson)**，以及测试工具 **[JUnit 5](https://junit.org/junit5/)**、**[OW2 ASM](https://asm.ow2.io/)**、**[`stretchr/testify`](https://github.com/stretchr/testify)**、**[`uber-go/goleak`](https://github.com/uber-go/goleak)**。

本项目以 **MIT** 许可证发布，全文见仓库根目录的 `LICENSE`。内置的 Hysteria 2 core、`apernet/quic-go` 等上游源码与第三方依赖各自遵循其原有许可证，原文随包保存在 jar 内的 `META-INF/hyperconduit-sidecar/`。
