# HyperConduit 技术报告与设计规范

HyperConduit 是一个专为 Minecraft 1.21.1 (NeoForge / Java 21) 设计的 **QUIC-like + Brutal-like** 自研传输层隧道系统。

本项目**不依赖任何外部 C 动态库、外部代理二进制或第三方通用 QUIC 框架**（非 gost / Clash / v2ray 包装，亦非引入重量级通用 QUIC 依赖），而是直接参考 **RFC 9000 (QUIC)**、**apernet/quic-go** 与 **Hysteria 2 (Brutal)** 的底层协议设计，在 **Java 21 + Netty** 架构下从零自主实现的可靠 UDP 传输引擎与速率自适应拥塞控制系统。

其设计原则为：**只替换底层字节流承载，完全不侵入 Minecraft 原版逻辑**。Minecraft 的协议序列化、登录认证、Mojang/第三方正版校验、白名单、封禁系统以及服务端反作弊机制均运行于其原始状态，隧道仅作为透明的高性能、抗丢包可靠字节管道工作。

---

## 1. 协议栈整体架构

```text
+-------------------------------------------------------------------+
|               Minecraft Protocol (VarInt 字节流)                  |
|                 (登录 / 认证 / 世界同步 / 游戏逻辑)                  |
+-------------------------------------------------------------------+
                                  │
                                  ▼
+-------------------------------------------------------------------+
|                HyperConduit Netty 适配层                          |
|         (HyperConduitChannel / HyperConduitServerChannel)         |
+-------------------------------------------------------------------+
                                  │
       ┌──────────────────────────┴──────────────────────────┐
       ▼                                                     ▼
+─────────────────────────────────+   +─────────────────────────────+
|     QUIC-like 可靠传输引擎       |   |   Noise XX 加密与身份认证   |
|  - Packet Numbering (隐式单调)  |   |  - Noise_XX_25519_ChaCha... |
|  - 离散 / 连续 ACK Ranges        |   |  - Zero-PSK (免预共享密钥)   |
|  - RFC 9002 丢包检测与 PTO       |   |  - X25519 自动身份生成      |
|  - 基于 Offset 的 Stream 流控    |   |  - 客户端 TOFU 指纹信任     |
|  - 令牌桶平滑起搏器 (Pacer)      |   |  - 无状态 Retry Cookie 防放大|
+─────────────────────────────────+   +─────────────────────────────+
       │                                                     │
       └──────────────────────────┬──────────────────────────┘
                                  ▼
+-------------------------------------------------------------------+
|                     拥塞控制层 (Brutal / Paced)                    |
|    - Brutal: 目标带宽 + 令牌桶起搏 + 滑动窗口丢包率动态补偿 (有界)    |
|    - Paced: 目标带宽 + 令牌桶起搏 (关闭丢包补偿)                   |
+-------------------------------------------------------------------+
                                  │
                                  ▼
+-------------------------------------------------------------------+
|                      UDP 传输层 (单端口复用)                        |
+-------------------------------------------------------------------+
```

---

## 2. 自研 QUIC-like 可靠传输引擎 (`core`)

RFC QUIC 为多路复用与复杂 Web 语义设计，其流调度与头阻塞消除带来庞大开销。Minecraft 连接本质上是单一严格时序依赖的 VarInt 字节流。因此，HyperConduit 裁撤了对游戏无意义的多流复用（Multiplexing），集中实现针对单虚拟流的极低延迟、轻量级可靠 UDP 状态机（`SessionEngine`）。

### 2.1 报文与帧格式 (Packet & Frame Architecture)

数据报文设计兼顾解析效率、抗指纹识别（DPI 混淆）与服务端多会话调度：

```text
0                   1                   2                   3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Connection ID (4 字节, 明文)              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Flags (1 字节) |             Packet Number (4 字节)           |  <- 掩码混淆
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                ChaCha20-Poly1305 加密载荷 (Frames)             |
|                               ...                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Poly1305 AEAD Tag (16 字节)               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

1. **Connection ID (CID, 4 字节明文)**：保留明文 CID 是为了让服务端单套接字在解密前通过 O(1) 路由将会话分发给对应的 `Session`，避免多用户竞争与解密回滚。
2. **头部混淆 (Header Masking)**：`Flags` 与 `Packet Number` 经 `HMAC-SHA256(maskKey, body)[0..5]` 异或掩码混淆。单调递增的包计数器对外呈现伪随机分布，有效规避基于特征计数的深度报文检测。
3. **认证绑定 (AAD)**：未保护的明文头部作为 AAD 参与 AEAD 校验，篡改 CID、Flags 或包号将直接导致认证失败。

### 2.2 帧类型定义 (`Frame`)

所有帧均封装于 AEAD 密文内部，杜绝明文泄露：

- `StreamData(long offset, byte[] data)`：承载绝对字节偏移的应用数据，接收端对未到达的空洞（Gaps）执行环形暂存，按序重组后提交给 Netty。
- `Ack(long ackDelayMicros, List<AckRange> ranges)`：聚合区间确认帧。通过区间编码（`smallest` 到 `largest`）汇报接收集合，极大降低高丢包与高吞吐下的 ACK 帧体积膨胀。
- `MaxData(long maxOffset)`：滑动流控窗口信用通告，限制对端在未经确认前可发送的最高字节偏移，防止发送端冲垮接收端缓冲区。
- `Ping()`：显式触发对端回送 ACK 的探测包（Ack-Eliciting），用于链路保活与 PTO 探测。
- `Close(int reasonCode, String message)`：传输层挥手帧，携带断开原因（正常停服、协议错误、主动挂断）。

### 2.3 丢包检测与探针超时 (RFC 9002 Loss Detection & PTO)

1. **双阈值丢包判定**：
   - **包号阈值 (Packet Threshold)**：当收到包号比已发送包高出 `PACKET_THRESHOLD = 3` 的 ACK 时，判定落后包丢失。
   - **时间阈值 (Time Threshold)**：判定时差达到 `TIME_THRESHOLD = 9/8 * max(smoothedRtt, latestRtt)` 即触发丢失。
2. **RFC 6298 平滑 RTT 采样**：
   - 首个采样直接初始化 RTT 状态；后续采样按 $\text{sRTT} \leftarrow \frac{7}{8}\text{sRTT} + \frac{1}{8}\text{latestRTT}$ 持续迭代；
   - 自动扣除接收端上报的 `ackDelayMicros`，计算高精度 `jitter`（抖动）。
3. **Probe Timeout (PTO) 机制**：
   - 当发送端发送完数据进入静默，且所有传输中包均未收到确认时，丢包检测定时器无法通过新包的 ACK 推进；
   - PTO 驱动发送端发出 `Ping` 探测帧，强迫对端回传 ACK，彻底避免因尾丢包（Tail Loss）引发的长达数秒的假死卡顿。

### 2.4 令牌桶发包起搏器 (Token-Bucket Pacer)

起搏器移植自 Hysteria `internal/congestion/common/pacer.go`，由 `Pacer.java` 驱动：
- 针对拥塞控制器给出的目标速率，在纳秒级粒度计算发包配额（`budget`）与下次发包等待时间（`nanosUntilSend`）。
- 设定突发硬顶（`MAX_BURST_PACKETS = 10`），将原本集中在一个时钟中断涌出的突发数据（Burst）均匀平滑在整个 RTT 周期内，消除网络中间件缓冲区爆满引发的主动丢包。

---

## 3. 自研 Brutal-like 拥塞控制机制

### 3.1 Brutal 核心设计哲学

传统 TCP 拥塞控制（如 Reno、Cubic）基于 **AIMD (加法增大、乘法减小)** 模型，其底层假设是：“丢包必然代表网络链路缓冲区过载”。但在跨运营商互联或公网复杂路由中，人为政策丢包或链路随机扰动非常普遍。在此类路径上，传统算法遇丢包即自断带宽折半退让，导致吞吐断崖式下跌。

Brutal 的哲学是**面向固定预留带宽的自适应补偿**：以用户配置的目标物理带宽为锚点，丢包时不仅不退缩，反而动态计算丢包率并适度提升发包速率，以确保接收端能够恒定收到足额的有效数据。

### 3.2 数学模型与算法实现 (`BrutalCc.java`)

```text
               bps * sRTT * 2
拥塞窗口 cwnd = ────────────────
                   ackRate

                bps
起搏速率 paceRate = ─────────
                   ackRate
```

- **滑动采样窗口**：
  采用 5 个 1 秒时间槽（`PKT_INFO_SLOT_COUNT = 5`）循环记录最近 5 秒内确认包与丢失包的绝对数量。
- **样本数防抖**：
  当最近窗口内累计样本数未达到 `MIN_SAMPLE_COUNT = 50` 时，强制 `ackRate = 1.0`，避免冷启动阶段因少数丢包样本引发起搏速率畸高。
- **有界熔断保护**：
  为防止网络完全断开时发送速率无限发散引发恶性雪崩，`ackRate` 强制设定硬下限 `MIN_ACK_RATE = 0.8`。这意味着 Brutal 的最大丢包补偿倍数严格限制在 $1.25\times$，在抗丢包与公网道德之间取得平衡。

### 3.3 Brutal 与 Paced 模式对比

| 控制模式 | 发送带宽控制 | 令牌桶起搏 (Pacer) | 丢包重传保障 | 丢包补偿计算 (`ackRate`) | 适用场景 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Brutal** (默认) | 固定配置值 (Mbps) | 有 (微秒打散) | 完整具备 | **开启** (动态上调发包率) | 存在非拥塞性随机丢包、跨运营商晚高峰恶劣链路 |
| **Paced** | 固定配置值 (Mbps) | 有 (微秒打散) | 完整具备 | **关闭** (`ackRate` 恒为 1.0) | 带宽严格受限或不允许超额发包的普通公网链路 |

> **技术澄清**：`Paced` **不是** TCP Reno / Cubic，**不是** BBR，也**不是** RFC QUIC 的默认拥塞控制算法。它是 HyperConduit 内部在关闭了 Brutal 丢包率动态膨胀补偿之后、依然保留固定目标带宽与高精度令牌桶起搏的受控发送模式。

### 3.4 BBRv1 演进评估

项目内部已对引入 BBR 进行了全面架构评审：
1. **可行性**：现有的 `CongestionController` 接口、ACK/Loss 回调流以及高精度 `Pacer` 完全能够支撑 BBR 接入。
2. **缺失组件**：BBR 依赖 delivery-rate 采样模型（需对每个在途中数据包打上精确的发送时间戳与交付率快照）、app-limited（应用层饥饿）状态跟踪，以及完整的 `Startup`、`Drain`、`ProbeBW`、`ProbeRTT` 四阶段状态机。
3. **工程评估**：预估工作量为 8~12 人日，属于高复杂度状态机改造。目前作为未来第三拥塞控制选项规划，不替代现有的 Brutal。

---

## 4. Zero-PSK 安全架构与防反射握手

### 4.1 密码学技术选型

HyperConduit 采用纯 JDK 21 标准库密码学实现，无需额外引入 BouncyCastle 或 Native 库：
- **密钥协商**：X25519 (RFC 7748)
- **对称加密**：ChaCha20-Poly1305 AEAD (RFC 7539 / 8439)
- **密钥派生与哈希**：HKDF-SHA256 (RFC 5869) / HMAC-SHA256

### 4.2 Noise_XX 握手流程

握手协议基于 Noise 协议框架中的 `Noise_XX_25519_ChaChaPoly_SHA256` 模式。双方均无需事先配置任何预共享密钥（Zero-PSK）：

```text
客户端 (Initiator)                                服务端 (Responder)
       │                                                 │
       │  1. Initial(Cookie="", Noise e)                 │
       ├────────────────────────────────────────────────>│ (校验 Cookie: 无效)
       │                                                 │
       │  2. Retry(Cookie)                               │
       │<────────────────────────────────────────────────┤ (无状态 HMAC 签发)
       │                                                 │
       │  3. Initial(Cookie, Noise e)                    │
       ├────────────────────────────────────────────────>│ (校验 Cookie: 成功)
       │                                                 │ (分配会话, 执行 DH)
       │  4. Handshake(Noise e, ee, s, es)               │
       │<────────────────────────────────────────────────┤
       │ (客户端校验服务端公钥 s 指纹: TOFU)               │
       │                                                 │
       │  5. Handshake(Noise s, se)                      │
       ├────────────────────────────────────────────────>│
       │                                                 │
       │  6. 握手确认 (Transport Ping/Ack)                │
       │<────────────────────────────────────────────────┤ (建立连接完成)
       │                                                 │
       ▼                                                 ▼
               [派生双向独立 Transport Keys 进入密文传输]
```

### 4.3 无状态 Retry Cookie (防御反射放大与 DoS)

UDP 易受到源地址伪造与反射放大攻击。为防止伪造的 Initial 包导致服务端无节制分配会话或计算昂贵的 X25519 DH，引入了类似 RFC 9000 的无状态 Cookie 机制（`RetryCookie.java`）：
- 服务端内存中维持当前与上一代随机密钥（每 60 秒轮换一次）；
- Cookie 内容由 `Expiry (8 字节) + HMAC-SHA256(Secret, Client_IP || Client_Port || CID || Expiry)[0..16]` 构成；
- 只有握手客户端能够成功回显合法 Cookie 时，服务端才会正式创建 `Session` 并进入昂贵的密码学握手阶段。

### 4.4 身份持久化与 TOFU 机制

- **服务端**：首次以服务端身份运行自动生成长期 X25519 密钥对并持久化于 `config/hyperconduit-server-identity.json`；
- **客户端**：首次连接某 `host:port` 时自动记录其公钥 SHA-256 指纹到 `config/hyperconduit-known-servers.json`（TOFU 机制）；后续若遇到公钥变动将抛出 `IdentityChangedException` 阻断连接，防御公网中间人欺骗。

---

## 5. Netty 管道集成与生命周期

### 5.1 Netty 架构映射

HyperConduit 自定义了 Netty Channel 实现，直接嵌入 Minecraft 的网络管线：
- **客户端**：`HyperConduitChannel` 继承 `AbstractChannel`，接管 `Bootstrap.connect()`；
- **服务端**：`HyperConduitServerChannel` 负责监听 UDP 端口，解复用 CID 并为每个客户端生成派生通道 `HyperConduitServerChildChannel` 接入 Minecraft 原始 Pipeline。

### 5.2 优雅停服 (Graceful Shutdown)

服务端停机（`ServerStoppingEvent`）触发时：
- 服务端向所有存活客户端定向广播 `CLOSE(reason=SERVER_SHUTDOWN, "[HyperConduit] Server is shutting down")`；
- 开放 1.5 秒的 Drain 冲刷窗口以确保最后的确认帧发出，而后主动销毁套接字；
- 客户端在毫秒级收到停服原因并平稳断开，避免原版在 UDP 丢失时卡死 30 秒至超时断开的体验。

---

## 6. 功能特性概述

除底层传输与拥塞控制核心外，系统内建以下配套特性：

1. **同端口单套接字共存**：服务端 UDP 默认自动绑定 Minecraft 正在监听的相同端口。配置 `disableVanillaTcp=false` 时，原版 TCP 与 UDP 隧道同端口共存（未装 Mod 走 TCP，已装走 UDP）；配置为 `true` 时可独占端口。
2. **零手动网络配置**：已移除旧版的 `psk`、`tunnelServer`、`listenAddress` 等繁琐参数，客户端直接读取玩家填写的服务器地址，主机自动适配局域网广播。
3. **图形化界面 (GUI)**：在游戏内“多人游戏”与“开放局域网”界面提供配置入口，支持直观调整带宽、切换 Brutal/Paced 以及管理已信任的服务器指纹。
4. **双端遥测与监控**：
   - 客户端 F3 / 指令实时输出链路质量；
   - 遥测严格区分：**当前 1 秒瞬时速率**（↓/↑/实时重传率）与 **近 10 秒窗口指标**（丢包率/抖动/p95 RTT）；
   - 服务端提供全员汇总与独立玩家状态查询。

---

## 7. 使用方法与操作指南

### 7.1 安装与启动

1. 客户端与服务端均需安装对应版本的 Mod Jar 包（如 `hyperconduit-neoforge-0.1.0.jar`）；
2. 启动游戏，在多人游戏列表或局域网联机界面点击 **`HyperConduit…`** 按钮；
3. 将 **启用** 开关设为 `开启`，配置发送带宽（默认 10 Mbps）与模式（默认 Brutal）；
4. 玩家直接输入原版服务器 IP 和端口加入，无需进行额外的网络隧道搭建。

> **注意**：带宽设置属于**本端发送侧控制**。服务端配置决定服务端到玩家的下行速率，客户端配置决定玩家到服务端的数据上传速率。

### 7.2 调试与状态指令

所有命令均使用完整命名空间：

- **查看当前链路状态**：
  ```text
  /hyperconduit status
  ```
  - **客户端执行**：在聊天框返回与 F3 相同的本机隧道遥测（RTT、p95 RTT、丢包率、瞬时上传/下载速率等）。
  - **服务端 / 控制台执行**：打印当前所有在线玩家的连接类型（隧道 / 原版 TCP / 本地回路）、各自的实时速率与丢包统计，以及全服总计。

- **服务端常驻实时监控**：
  ```text
  /hyperconduit watch
  ```
  开启后每秒输出一次全服隧道实时状态，适合服主在独立终端持续观察链路波动。
  ```text
  /hyperconduit watch stop
  ```
  停止状态持续输出。

### 7.3 配置文件示例 (`config/hyperconduit.json`)

```json
{
  "enabled": true,
  "mbps": 10,
  "brutal": true,
  "disableVanillaTcp": false,
  "receiveWindowBytes": 4194304,
  "sendBufferBytes": 4194304
}
```

- `enabled`：是否启用隧道（默认 `false`，未开启时严格回退为原版 TCP）。
- `mbps`：本端单向发送目标带宽（Mbps）。
- `brutal`：是否启用 Brutal 丢包补偿（`true` 为 Brutal，`false` 为 Paced）。
- `disableVanillaTcp`：服务端是否屏蔽原生 TCP 连接（默认 `false` 允许共存）。
- `receiveWindowBytes` / `sendBufferBytes`：底层滑动流控缓冲区大小（默认 4 MiB）。

---

## 8. 构建与工程结构

### 8.1 模块划分

- `:core`：独立协议库。包含 Noise XX 握手、帧编解码、QUIC-like 可靠传输引擎（`SessionEngine`）、Brutal/Paced 拥塞控制及起搏器。**零 Minecraft 依赖**，含纯 Java 确定性模拟丢包与延迟测试套件。
- `:mod-common`：跨平台通用逻辑。包含 JSON 存储、TOFU 身份系统及 Netty 通用 Mixin。
- `:neoforge`：NeoForge 1.21.1 平台适配。包含事件总线监听、GUI 渲染、F3 调试面板接入与 Minecraft 服务端指令系统。

### 8.2 编译与产物输出

需要 JDK 21 环境：

```bash
./gradlew :core:test :mod-common:test :neoforge:jar
```

构建生成的 Mod Jar 位于：

```text
neoforge/build/libs/hyperconduit-neoforge-0.1.0.jar
```
