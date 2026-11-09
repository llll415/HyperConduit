# HyperConduit

> **声明**
>
> - **个人自娱自乐项目**：本项目纯属个人的奇思妙想，做出来自己玩玩和验证思路。非严肃工业级产品，不喜勿喷。
> - HyperConduit 是一个仍在验证中的 **Vibe Coding** 项目，不是经过完整安全审计或大规模生产验证的网络产品。请先在可控环境测试，并建议保留原版 TCP 共存。
> - **尚未经过充分真实公网验证**：本项目尚未在跨运营商、跨境、晚高峰或运营商 QoS 等复杂公网环境中进行充分实测。不同网络对 UDP 的策略差异很大，**不保证抗 QoS、抗丢包、吞吐、延迟或游戏体验会有任何改善**；在某些网络上甚至可能更差。
> - 本项目的 `Brutal` 模式在确认率下降时，会在补偿系数上限内提高发送速率尝试补偿丢包。这是一种偏激进的发包策略；错误设置带宽、共享网络使用，或遇到运营商对 UDP/异常流量的策略时，可能影响同网络中的其他业务，也可能触发限速、丢包或阻断。使用者应自行承担相应网络风险。
> - **1.0.0 底层重构说明**：相比旧版 `v0.1.1` 纯 Java 手搓的“QUIC-like / Brutal-like”，**`1.0.0` 已重构为随包托管的定制 Hysteria 2 `core/` + `apernet/quic-go` 二进制**，使用真正的 **RFC 9000 QUIC / HTTP/3 / TLS 1.3** 以及原生的 **Brutal / BBR / Reno** 拥塞控制（不兼容 `v0.1.1` 旧配置与旧协议；同时因针对 Minecraft 固定目标做了协议裁剪，不与原版 Hysteria 2 通用代理直接互通）。

**HyperConduit** 是一个面向 **Minecraft 1.21.1 NeoForge** 的双端传输 Mod。它的目标很直接：把 Minecraft 游戏连接承载到 **QUIC/UDP 隧道**中，利用 **BBR**，或更激进的 **Brutal** 拥塞控制，改善跨运营商、晚高峰、高丢包等恶劣网络环境下的联机体验。

## 它做什么

- **底层换用 QUIC/UDP**：把 Minecraft 的 Netty 字节流装进基于 Hysteria 2 core 与 `apernet/quic-go` 的加密 QUIC 隧道中传输；登录、正版验证、白名单、封禁等上层逻辑仍由原版处理。
- **可选 BBR / Brutal / Reno**：默认使用 `BBR`；遇到持续非拥塞性丢包时，可按方向填写上下行带宽启用 `Brutal`。
- **开箱即用**：内置五平台隧道程序，无需域名、无需申请 CA 证书，也无需手动启动后台进程；连接时依然填写原本的服务器 `host:port`。
- **同号 TCP／UDP 共存**：服务端默认可让原版 TCP 与 HyperConduit UDP 使用相同的端口号（没装 Mod 的玩家照常直连）；也可设为仅接受隧道连接。
- **游戏内可配置、可观测**：提供客户端与局域网主机设置界面、服务器列表状态徽标、`F3` 实时传输信息以及 `/hyperconduit status` / `watch` 命令。

## 安装与使用

### 1. 环境准备

客户端与服务端均需 **Minecraft 1.21.1 对应的 NeoForge** 与 **Java 21**。将同一版本的 `hyperconduit-neoforge-1.0.0.jar` 放入双端的 `mods/` 目录。

Mod 安装后**默认关闭**（`enabled=false`），首次启动不会自动生成配置文件，也不会改变原版连接。

### 2. 开启隧道

- **客户端**：进入 **多人游戏** 界面 → 点击左上角 `HyperConduit…` → 将 `启用 HyperConduit` 设为 **开启** → 点击 **保存**。
- **局域网（LAN）主机**：进入单人世界后打开 **对局域网开放** → 点击左上角 `HyperConduit 主机设置…` → 将 `启用 HyperConduit` 设为 **开启** 并保存 → 开放局域网。
- **专用服务端（Dedicated Server）**：在服务端根目录下创建或编辑 `config/hyperconduit-hy2.json`，将 `enabled` 设为 `true`。新建文件时，最小配置为：
  ```json
  {
    "enabled": true
  }
  ```
  保存后重启服务端。已有配置只需修改对应字段，其他设置保持不变。

初次使用可保留发送/接收带宽 **`0 / 0 Mbps`**、拥塞控制 **`BBR`**、口令为空；连接正常后，再按线路情况调整带宽。

### 3. 放行端口并加入

确认主机或服务端的防火墙/安全组已放行**与游戏端口同号的 UDP 端口**（例如游戏端口是 `25565`，就放行 `UDP 25565`）。从外网加入时，路由器端口转发或内网穿透也需支持并转发 UDP；仅有 TCP 转发不能承载隧道。玩家在多人游戏列表中照常填写服务器的 `host:port` 加入即可。

设置保存后不会热切换已经连上的会话：客户端设置在下一次连接时生效；局域网主机需退出当前世界、重新进入并开放局域网；专用服需重启。

## 流控模式：BBR、Brutal 与 Reno

HyperConduit 的核心在于拥塞控制选择，客户端与服务端会**按上行、下行两个方向独立协商**：

| 模式 | 触发方式 | 适用场景 |
| --- | --- | --- |
| **BBR**（默认推荐） | 客户端对应方向带宽设为 `0`，发送端拥塞控制选 `BBR` | 默认选项，基于带宽与 RTT 估计调节发送。 |
| **Brutal** | 客户端将 `upMbps` 或 `downMbps` 设为 `> 0`（且主机未禁用 Brutal） | 按声明目标发送并根据确认率补偿丢包，适合希望手动配置目标带宽的用户。 |
| **Reno** | 客户端对应方向带宽设为 `0`，发送端拥塞控制选 `Reno` | 传统丢包反馈算法，可按线路表现选择或用于对照。 |

### 带宽该怎么填？

- **客户端的 `upMbps` / `downMbps`**：分别代表你希望声明的**上传**和**下载**速率（Mbps）。哪个方向填了大于 `0` 的数值，哪个方向就会请求启用 `Brutal`；填 `0` 的方向则使用该方向发送端选定的 `BBR` 或 `Reno`：上传看客户端设置，下载看服务端设置。
- **主机/服务端的 `upMbps` / `downMbps`**：代表服务端允许**单条客户端连接**协商的 `Brutal` 发送/接收目标上限，`0` 表示不限制。例如主机填 `5` / `3`，客户端填 `10` / `10`，则服务端发送的 Brutal 目标为 `5 Mbps`，客户端发送的目标为 `3 Mbps`；丢包补偿可能使发送速率高于该目标。它不是全服总带宽均分，也不会给 `BBR` 或 `Reno` 限速。
- **主机开启 `禁用 Brutal`（`disableBrutal=true`）**：服务端双向忽略客户端的带宽声明，双方强制使用各自本地的 `BBR` 或 `Reno`，连接依然正常建立。

## 共存模式、列表探测与连接前选择

### 共存与仅隧道

- **共存模式（默认，`disableVanillaTcp=false`）**：同一端口同时提供原版 TCP 与 HyperConduit UDP。装了 Mod 并开启的玩家走隧道，没装 Mod 的玩家走原版 TCP。
- **仅隧道模式（`disableVanillaTcp=true`）**：对外关闭原版 TCP，只接受 HyperConduit UDP 连接。注意：仅隧道模式必须使用固定端口，不支持随机端口 `0`。

### 多人列表徽标与单次连接询问

多人游戏列表中，原版信号图标左侧会显示 `HC` 状态徽标，鼠标悬停可查看对端是否启用隧道、是否允许 TCP 直连、是否禁用 Brutal 以及是否需要口令。若显示“未声明”或“未知”，表示当前未取得有效的 HyperConduit 声明：可能已收到响应但没有声明，也可能声明无效或查询超时，不代表对端一定没装 Mod。

当你点击服务器加入时，只有遇到以下**两种明确冲突**才会弹出确认提示：

1. **你开了 HyperConduit，但服务器明确声明未启用隧道、允许原版 TCP** → 询问是否**本次使用原版 TCP**。
2. **你关着 HyperConduit，但服务器明确声明禁止原版 TCP** → 询问是否**本次启用 HyperConduit 隧道连接**。

弹窗中的选择**仅对本次连接生效**，不会修改全局设置，也不会记住选择。未声明或探测超时时按当前设置连接，不额外询问。选择原版 TCP 时，本次不使用 HyperConduit 的服务器身份校验与隧道保护；选择隧道后，超时、口令错误或身份变化会直接报错，不会在后台切回 TCP。

## 配置文件、口令与身份信任

配置文件位于各自运行目录的 `config/hyperconduit-hy2.json`。以下为完整默认配置：

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

带宽为整数，范围 `0–1000000` Mbps；连接超时范围为 `100–120000` 毫秒，并发连接配置上限为 `1–4096`。口令最多 1024 个可打印 ASCII 字符，首尾不能有空白。配置使用严格 JSON，不接受注释、重复或未知字段。

### 可选口令与首次信任（TOFU）

- **访问口令**：默认免口令。主机设置 `serverToken` 后，客户端需在 `clientToken` 填写相同口令才能建立隧道。游戏内口令输入框不回显明文（留空保存即保持原值，点击右侧 `清除客户端口令` / `清除服务器口令` 再保存可清空）。注意：口令只保护 UDP 隧道入口；若要强制所有玩家验证隧道口令，主机需同时开启 `禁止原版 TCP`。
- **自动身份信任**：服务端首次启用隧道时会在 `config/hyperconduit/server-identity.json` 自动生成长期密钥（含私钥，请勿泄露）。客户端首次成功连接某个 `host:port` 后，会将服务端公钥指纹记入 `config/hyperconduit/known-servers.json`。
- **身份变更保护**：若同一地址后续公钥发生变化，客户端会拦截连接并提示身份已变更。请先向服主确认是否更换了服务器身份，确认无误后可在 `HyperConduit… → 已信任服务器…` 中删除旧记录再重连。
- **首次信任**：首次连接的身份记录依赖当次接触到的服务器公钥。需要核验时，通过可信的其他渠道向服主确认公钥指纹；列表能力探测不作为身份证明，也不会写入信任记录。

## 状态查看与命令

客户端在游戏中按 `F3`，或执行 `/hyperconduit status`，可看到类似如下的实时状态：

```text
[HyperConduit] [隧道已连接]
[传输] 本地发送 BRUTAL（目标 10.00 Mbps） | QUIC RTT 15.1 ms | 发送 0.02 Mbps | 接收 1.52 Mbps
```

服务端控制台或具备 OP 权限（等级 2）的管理员可执行：

```text
/hyperconduit status
/hyperconduit watch
/hyperconduit watch stop
```

```text
[HyperConduit] 服务端配置 | 已启用 | 允许协商 Brutal | 原版TCP禁止
[HyperConduit] [PlayerName] 隧道 源 203.0.113.5 | 本地发送 BBR | QUIC RTT 14.1 ms | 发送 0.05 Mbps | 接收 0.02 Mbps
[HyperConduit] 总计 | 隧道 1 | 原版TCP 0 | 本地 0
```

- 状态行中的控制器名称（`BBR` / `RENO` / `BRUTAL`）代表**本端实际运行的发送控制器**；
- 速率为本端 QUIC 传输层统计（含重传与协议开销），多流复用同一连接时为连接汇总值。
- 服务端首行是当前内存配置，玩家行和总计是实际在线连接；保存设置后、监听重建前，配置与正在运行的策略可能不同。单人世界的 `F3` 显示集成服务端状态。

---

# 技术说明

以下内容面向希望了解内部架构、协议裁剪与实现边界的读者。

## 1. Java + Go Sidecar 协同架构

HyperConduit 将 Minecraft 集成与底层 QUIC 传输解耦为两个进程层：

```text
Minecraft Client (Netty Channel)
    ↕ 127.0.0.1 ephemeral TCP
hyperconduit-tunnel (Go client sidecar)
    ↕ QUIC / HTTP/3 / TLS 1.3 over UDP
hyperconduit-tunnel (Go server sidecar)
    ↕ 127.0.0.1 ephemeral TCP + HCMETA01 preamble
Minecraft Server (Netty ServerBootstrap)
```

HTTP/3 用于鉴权与能力发现；游戏字节流通过 HY2 的 TCP framing 承载于 QUIC stream。

- **客户端拨号重定向（`ClientTunnelInitializer`）**：Mixin 拦截客户端 `Bootstrap.handler(...)`。启用隧道时，先按需提取并拉起本机 `hyperconduit-tunnel client --managed` 子进程，待其通过标准输出返回 `ready` 事件与本机回环监听端口后，将 Netty 的底层 TCP `connect` 指向该回环地址。DNS/SRV 解析前的原始用户输入 `host:port` 由 `ServerNameResolverMixin` 捕获，用于保持 Minecraft 握手包中的主机名/端口不变，并作为 TOFU 信任键。
- **服务端双绑定与私有后端（`ServerConnectionListenerMixin`）**：以 `@Group(min = 1, max = 1)` 兼容原生 NeoForge 的 `startTcpServerListener` 与 Youer 中来自 Paper 的 `bind(SocketAddress)` 绑定结构。共存模式下保留原版对外 TCP 绑定，并克隆出一个绑定在 `127.0.0.1:0`（显式开启 `AUTO_READ`）的私有 TCP 后端；仅隧道模式下仅创建私有后端与同号 UDP 监听。
- **受保护的桥接前言（`BridgeInitializer`）**：每个服务端监听器启动时生成 32 字节随机值，并编码为 64 字符十六进制 `bridgeToken`。服务端 sidecar 在向私有后端转发每条 TCP 流前，先写入固定魔数 `HCMETA01`、4 字节大端长度及 JSON 元数据 `{token, peer, connectionId, streamId}`。Java 端通过恒定时间比较验证 `token` 后，将 QUIC 层观测到的真实客户端地址写入 `TunnelAttributes.PEER`，并在 `Connection.channelActive` 中恢复远端地址（注：若有第三方插件绕过 Minecraft `Connection` 直接调用底层 Netty `channel.remoteAddress()`，仍会看到 `127.0.0.1`）。
- **受管生命周期与权限隔离**：子进程通过标准输入接收控制指令（`observe` / `shutdown`）。当父 JVM 退出或关闭管道导致标准输入 EOF 时，Go 进程自动退出并清理套接字。每次启动生成的私有运行配置位于 `config/hyperconduit/launch/`，采用当前用户独占权限（POSIX `0600` / Windows 用户专属 ACL 与句柄共享锁）保护。正常关闭时由 Java 清理临时配置；父 JVM 被强制结束时可能留下当次文件，后续启动不复用。

## 2. Hysteria 2 Core 裁剪与流控细节

`sidecar/hy2-core` 基于官方 Hysteria 2 `core/` 源码定制，保留了标准 QUIC 传输、HTTP/3 鉴权握手与原生拥塞控制，并对非 Minecraft 场景做了收紧：

- **固定目标转发**：产品只提供 Minecraft 固定目标 TCP 转发，不提供 SOCKS/HTTP 入口。内置 core 已裁除应用层 UDP 转发与分片、请求嗅探改写、可配置伪装（Masquerade）、Chrome 指纹模拟及自动重连封装；服务端 `fixedOutbound` 将合法的固定服务请求转发至启动配置指定的后端。在 Mod 中，该后端是 Java 创建的本机私有 Minecraft 监听器。
- **帧格式收紧**：每条 Minecraft TCP 连接对应一条有序 QUIC stream。TCP 请求头与响应头的 padding 长度字段保留在帧结构内，但写入端固定写 `0`、读取端拒绝任何非零 padding，且 HTTP/3 鉴权头不再发送随机 `Hysteria-Padding`。因此本 Mod 要求双端版本一致，不与原版 Hysteria 2 互通。
- **Brutal 速率计算与 Pacer**：
  ```text
                 bps × sRTT × 2
  cwnd      = ───────────────────
                     ackRate

                   bps
  paceRate  = ───────────
                 ackRate
  ```
  其中 `bps` 为协商目标的字节/秒，`sRTT` 以秒计，`cwnd` 单位为字节，`paceRate` 单位为字节/秒。窗口计算在尚无 RTT 样本时使用初始值，并设有一个最大数据报大小的下限。

  `BrutalSender` 在 5 个一秒时间槽中统计近期确认包数与丢包数；总样本数不足 50 时 `ackRate=1`，达到 50 后按确认包占比计算，下限为 `0.8`。因此当前实现的 pacing rate 最多为协商目标的 `1.25` 倍，由令牌桶 `Pacer` 控制突发预算与发包间隔；该倍数不是任意短时间窗口内包含所有网络开销的线速硬上限。
- **双向带宽协商**：HTTP/3 鉴权请求中的 `Hysteria-CC-RX` 是客户端接收带宽声明，供服务端决定发送目标；响应中的同名字段是服务端接收带宽上限，供客户端决定发送目标。响应值为 `auto` 时，客户端改用本地 `BBR` 或 `Reno`。
  - 客户端对应方向声明为 `0` 时，该方向的发送端使用本地 `BBR` 或 `Reno`。
  - 服务端对应方向上限为 `0` 表示不限制 Brutal 目标；两者均非零时，目标取较小值。
  - 主机设置 `disableBrutal=true` 时，服务端通过 `IgnoreClientBandwidth` 忽略客户端下行声明，并通过响应中的 `auto` 要求客户端忽略上行声明，双方使用各自本地的 `BBR` 或 `Reno`。

## 3. 能力发现、连接前检查与 SPKI TOFU

### 能力发现（Protocol 2）

列表探测与连接前检查均使用协议版本 `2` 的严格五字段声明：

```json
{
  "protocol": 2,
  "enabled": true,
  "tcpAllowed": true,
  "disableBrutal": false,
  "tokenRequired": false
}
```

- **原版状态扩展与 QUIC 补缺**：`StatusPacketMixin` 在服务端状态 JSON 顶层附加当前活跃监听器的 `hyperconduit` 快照（由 `CapabilityListenerRegistry` 按实际存活的对外 TCP 与 sidecar 聚合，不读取未应用的新配置）。列表探测未取得明确声明时，客户端通过复用的 `hyperconduit-tunnel probe` 进程向 `https://hyperconduit/capability` 发起只读 HTTP/3 GET 查询。查询会建立 QUIC/TLS 连接，每条连接最多查询一次，不执行口令鉴权或后端拨号。
- **连接前短路检查（`TransportDiscovery`）**：发起连接时设 6 秒总截止，TCP 连接与读取超时各为 1.2 秒。若取得 TCP 状态响应且含有效声明，则按规则判定是否弹窗；若响应中没有有效声明，则直接沿用当前设置连接，避免普通原版服务器额外等待 UDP 超时。TCP 状态查询失败时再尝试 UDP `probe` 查询。

能力查询不发送玩家口令、不写入 TOFU，也不校验服务器公钥是否与已有 pin 一致；正式隧道连接独立执行身份与口令验证。

### 持久身份与 TOFU 信任库

- **服务端身份（`internal/identity`）**：首次启动生成 ECDSA P-256 密钥对与自签名证书（有效期 365 天，到期前 30 天内复用同一私钥自动续签），持久化于 `config/hyperconduit/server-identity.json`。
- **客户端信任（`internal/trust`）**：以公钥 **SPKI SHA-256** 指纹作为信任锚点，持久化于 `config/hyperconduit/known-servers.json`，跨进程写入由文件锁保护。仅当正式连接的 TLS 握手证明服务端持有对应私钥、且通过 HTTP/3 口令鉴权后，才执行首次信任落盘；若已有条目指纹不匹配，直接返回 `identity_changed` 中止连接。

## 4. 实时指标通道（Telemetry）

Java 在收到子进程 `ready` 后发送 `observe` 命令开启遥测，Go 侧每秒通过标准输出推送一行 `stats` JSON 帧（最多 128 条活动连接）：

- `TelemetryTracker` 使用单调递增的序号与纳秒时间戳计算两次采样间的传输字节差值速率；
- 首帧、超过 3.5 秒间隔后的恢复帧或字节计数回退时重置速率基线（显示 `速率待采样`）；超过 3.5 秒未收到新帧或进程退出时清空快照（显示 `指标暂不可用`）；
- 服务端通过 `TunnelAttributes.CONNECTION_ID` 精确关联每个玩家通道所属的监听器与 QUIC 连接，多条流共享同一 QUIC 连接时展示该连接的汇总传输率。

RTT 为 QUIC 平滑 RTT，速率不包含外层 UDP/IP 与链路层报头。统计样本无效只使指标失效或重新采样；子进程退出或受管控制协议失败则可能中断连接。

## 5. 平台支持、构建与验证状态

### 内置平台矩阵

Mod jar 默认打包以下 5 个平台的无 CGO 二进制（位于 `io/hyperconduit/sidecar/bin/<platform>/`，不支持 `windows-arm64` 及 32 位系统）：

| 平台标识 | 当前验证状态 |
| --- | --- |
| `windows-amd64` | 自动回归通过，局域网联机及 NeoForge / Youer 专用服主要连接路径已实测 |
| `linux-amd64` / `linux-arm64` | 已完成交叉编译、ELF 机器类型头检查、提取测试与 SHA-256 校验，尚未在对应系统实机运行 |
| `darwin-amd64` / `darwin-arm64` | 已完成交叉编译、Mach-O 机器类型头检查、提取测试与 SHA-256 校验，尚未在 macOS 实机运行 |

**1.0.0 重构后的传输实现已经过局域网联机测试，目前尚未进行公网联机与性能测试。** 已验证的功能包括：

- LAN 主机开放、加入、退出重连，以及退出世界后的隧道关闭与重新开放。
- 共存模式下的原版 TCP 与隧道连接，以及仅隧道模式下的入口限制。
- BBR/Reno 使用、双向 Brutal 目标协商、主机每连接目标上限及禁用 Brutal。
- 口令成功与失败、首次信任、身份变化拒绝连接，以及删除指定信任后的重新连接。
- 列表能力与原版延时探测、两种单次传输选择、在线指标及连接分类。
- NeoForge 专用服的共存连接与在线状态；Youer 专用服的共存/仅隧道连接，以及在线 `status`、重复 `watch` 和 `watch stop`。

上述结果是功能联机验证，不是恶劣网络下的效果对比。跨运营商、跨境、晚高峰等公网线路表现，以及受控丢包下 BBR/Brutal 相对原版 TCP 的收益，需要后续测试。长期稳定性、多玩家限额隔离、SRV/IPv6 实际连接和更广泛的 Mod/插件组合也尚未系统验证。

### 工程结构与从源码构建

- `sidecar/`（独立 Go module）：`hyperconduit-tunnel` 主程序、配置/身份/信任/探测模块；`sidecar/hy2-core/` 是通过本地 replace 引用的独立 Go module，包含定制传输核心。
- `:sidecar-runtime`（Java 21）：跨平台二进制提取校验、私有临时配置、受管进程生命周期、桥接前言校验与遥测解析。
- `:mod-common`（Java 21）：跨加载器共享的配置模型、能力探测、连接前选择界面与客户端/服务端 Mixin。
- `:neoforge`（Java 21）：NeoForge 模组入口、配置/信任管理界面、F3 调试叠加层与服务端命令（仓库中另保留旧 `:core` 模块仅作历史参考）。

构建需要 **JDK 21** 与 **Go 1.26**（默认调用 `PATH` 中的 `go`，可通过 `-PsidecarGo=<路径>` 指定）：

```bash
./gradlew :neoforge:jar
./gradlew :neoforge:verifyDistribution
```

Windows 下请使用 `gradlew.bat`。单文件自包含产物位于：

```text
neoforge/build/libs/hyperconduit-neoforge-1.0.0.jar
```

## 致谢与开源许可

HyperConduit 基于以下开源项目构建或衍生（致谢仅说明代码来源与依赖关系，不代表上游作者对本项目的背书或协议互通）：

- **[Hysteria 2 (`HyNetwork/hysteria`)](https://github.com/HyNetwork/hysteria)**（MIT License, Copyright 2023 Toby）
  - `sidecar/hy2-core/` 直接派生自官方仓库固定提交 [`fb25dc852ebbc70658e67889f411887a82b7c281`](https://github.com/HyNetwork/hysteria/commit/fb25dc852ebbc70658e67889f411887a82b7c281) 的 `core/` 子目录，使用了其 QUIC/HTTP3 握手鉴权、带宽协商、Brutal/BBR/Reno 拥塞控制与通用 Pacer 实现。
- **[`apernet/quic-go`](https://github.com/apernet/quic-go)** 及上游 **[`quic-go/quic-go`](https://github.com/quic-go/quic-go)**（MIT License）
  - 提供底层 QUIC 传输、多路复用流、HTTP/3 与拥塞控制接口。
- **[Go 标准库与 `golang.org/x/*`](https://go.dev/)**（BSD-3-Clause License）及传输层传递依赖 **[`quic-go/qpack`](https://github.com/quic-go/qpack)**、**[`refraction-networking/utls`](https://github.com/refraction-networking/utls)**、**[`klauspost/compress`](https://github.com/klauspost/compress)**、**[`andybalholm/brotli`](https://github.com/andybalholm/brotli)**。
- **[NeoForge](https://neoforged.net/)**、**[ModDevGradle](https://github.com/neoforged/ModDevGradle)**、**[SpongePowered Mixin](https://github.com/SpongePowered/Mixin)**、**[ParchmentMC](https://parchmentmc.org/)**、**[Netty](https://netty.io/)**、**[Google Gson](https://github.com/google/gson)**，以及测试工具 **[JUnit 5](https://junit.org/junit5/)**、**[OW2 ASM](https://asm.ow2.io/)**、**[`stretchr/testify`](https://github.com/stretchr/testify)**、**[`uber-go/goleak`](https://github.com/uber-go/goleak)**。

本 Mod 元数据声明为 **MIT**；内置上游源码与第三方依赖各自遵循其原有许可证。第三方声明与许可原文随包保存在 jar 内的 `META-INF/hyperconduit-sidecar/`。
