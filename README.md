# HyperConduit

> **声明**
>
> - **个人自娱自乐项目**：本项目纯属个人的奇思妙想，做出来自己玩玩和验证思路。非严肃工业级产品，不喜勿喷。
> - HyperConduit 是一个仍在验证中的 **Vibe Coding** 项目，不是经过完整安全审计或大规模生产验证的网络产品。请先在可控环境测试，并保留原版 TCP 回退。
> - **尚未经过充分真实公网验证**：本项目尚未在跨运营商、跨境、晚高峰或运营商 QoS 等复杂公网环境中进行充分实测。不同网络对 UDP 的策略差异很大，**不保证抗 QoS、抗丢包、吞吐、延迟或游戏体验会有任何改善**；在某些网络上甚至可能更差。
> - 本项目参考 Hysteria 2 的 Brutal 思路：当确认率下降时，`Brutal` 会在严格上限内提高发送速率，尝试补偿丢包。这是一种偏激进的发包策略；错误设置带宽、共享网络使用，或遇到运营商对 UDP/异常流量的策略时，可能影响同网络中的其他业务，也可能触发限速、丢包或阻断。使用者应自行承担相应网络风险。
> - 这里的 **QUIC-like** 与 **Brutal-like** 表示“参考相关机制自行实现”，**不是 RFC 9000 QUIC 实现**，也不与浏览器 QUIC、HTTP/3 或其他通用 QUIC 端点互通。

HyperConduit 是面向 **Minecraft 1.21.1 NeoForge** 的双端 UDP 隧道 Mod。它把 Minecraft 已有的 Netty 字节流管线承载到自定义的可靠 UDP 传输中；Minecraft 游戏协议、登录与认证流程、白名单、封禁及其他上层服务端逻辑仍由原版处理。

当前版本：**Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21**。

## 它做什么

- 客户端和服务端均启用后，Minecraft 连接通过 HyperConduit 的加密可靠 UDP 隧道传输。
- 默认关闭。关闭时客户端保留原版 TCP 连接方式。
- 不需要填写 PSK、单独的隧道地址或监听地址：客户端直接使用服务器列表中的 `host:port`。
- 服务端可让原版 TCP 与 HyperConduit UDP 使用同一个端口号共存；也可选择仅接受 HyperConduit 连接。
- 提供游戏内配置入口、F3 链路状态、`/hyperconduit status` 和服务端 `watch` 监控。
- 当前只支持 NeoForge；Fabric 尚未实现。

## 安装与使用

1. 客户端与服务端安装相同版本的 `hyperconduit-neoforge-0.1.0.jar`。
2. 启动游戏后，在**多人游戏**或**开放局域网联机**界面点击 `HyperConduit…`。
3. 打开“启用”，设置本端发送带宽与控制模式；默认是 `10 Mbps + Brutal`。
4. 玩家仍像原版一样填写服务器 IP 和端口加入。无需额外部署代理或填写密钥。

设置保存后，只会在**下一次连接**或**下一次开放局域网**时应用；不会热切换已经建立的会话。

### 连接要求与兼容性

- 隧道需要**客户端和服务端都安装并启用** HyperConduit。
- 服务器默认 `disableVanillaTcp=false`：未安装 Mod 的玩家仍可通过原版 TCP 加入；启用 Mod 的玩家使用 UDP 隧道。TCP 与 UDP 是不同传输协议，因此可使用同一端口号。
- 服务端若设置 `disableVanillaTcp=true`，将不再创建原版 TCP listener，只接受 HyperConduit UDP 会话。
- 单人游戏与本地回路连接不经过 HyperConduit；客户端也不会用隧道连接 `localhost`、loopback 或 wildcard 地址。

## 控制模式：Brutal 与 Paced

带宽配置是**逐方向、按本端发送侧**生效的：

- 服务端的 `mbps` 控制服务端 → 玩家方向；
- 客户端的 `mbps` 控制玩家 → 服务端方向；
- 它是人工配置的目标发送速率，不是链路容量自动探测值。请按自己的上行、下行和共享网络情况谨慎设置。

| 模式 | 目标带宽 | 起搏器 | 丢包补偿 | 如何选择 |
| --- | --- | --- | --- | --- |
| **Brutal**（默认） | 使用配置的 `mbps` | 使用 | 开启，有严格上限 | 已确认链路存在非拥塞性丢包，且接受为保持交付率而额外发包时使用。 |
| **Paced** | 使用配置的 `mbps` | 使用 | 关闭 | 希望固定按配置速率发送，不希望因为丢包提高实际发送速率时使用。 |

### Brutal

`Brutal` 是 HyperConduit 对 Hysteria 2 Brutal 思路的 Java 实现与裁剪。它不会像 Reno/CUBIC 一样在发现丢包时做传统的乘法减窗；而是以配置带宽为基准，根据最近确认包与丢失包的比例做**有限补偿**，以提高接收端的有效交付率。

这不意味着它保证带宽或保证低延迟。若配置速率超过真实可用容量，或路径本身确实拥塞，Brutal 仍可能制造更多排队、重传和丢包。请从保守值开始测试。

### Paced

`Paced` 仍然使用相同的可靠重传层与令牌桶起搏器，只是关闭 Brutal 的丢包补偿：它始终以配置的目标速率起搏发送。

`Paced` **不是** Reno、CUBIC、BBR，也不是标准 QUIC 的默认拥塞控制算法；它是本项目内部“固定目标速率 + 起搏、无丢包补偿”的模式。

## 配置与身份信任

常规设置建议在游戏内 `HyperConduit…` 界面修改。配置文件位于 `config/hyperconduit.json`：

```json
{
  "enabled": false,
  "mbps": 10,
  "brutal": true,
  "disableVanillaTcp": false,
  "receiveWindowBytes": 4194304,
  "sendBufferBytes": 4194304
}
```

| 字段 | 含义 |
| --- | --- |
| `enabled` | 是否启用本端 HyperConduit；默认 `false`，关闭时为原版 TCP。 |
| `mbps` | 本端单向目标发送带宽，单位 Mbps。 |
| `brutal` | `true` 为 Brutal；`false` 为 Paced。 |
| `disableVanillaTcp` | 仅服务端监听策略：`true` 时不接受原版 TCP。 |
| `receiveWindowBytes` | 传输层接收流控窗口，默认 4 MiB。 |
| `sendBufferBytes` | 应用数据待发送缓冲上限，默认 4 MiB。 |

### 无 PSK 与首次信任

HyperConduit 不要求玩家手工维护 PSK：

- 服务端首次运行时自动生成长期 X25519 身份并保存到 `config/hyperconduit-server-identity.json`。
- 客户端首次连接一个 `host:port` 时，采用 TOFU（首次使用即信任）保存服务端公钥指纹到 `config/hyperconduit-known-servers.json`。
- 同一地址后续出现不同身份时，客户端不会静默替换记录，会拒绝连接。可在 `HyperConduit… → 已信任服务器…` 中查看并删除旧记录；删除后下一次连接会重新执行首次信任。

## 状态与命令

客户端按 `F3` 可看到类似以下的 HyperConduit 状态行：

```text
[HyperConduit] [运行中] [Brutal 10 Mbps]
[链路] RTT 12ms  p95(10s) 24ms  丢包(10s) 0.0%  重传 0.0/s
[速率] ↓ 937 B/s  ↑ 32 B/s  抖动(10s) 1ms
```

- `↓`、`↑` 与 `重传` 显示最近 1 秒滑动窗口内的累计值；
- `p95(10s)` 与 `抖动(10s)` 使用最近十秒窗口；
- 当前版本的 `丢包(10s)` 统计尚未完整接入传输层丢失事件，**请勿将其作为可靠的诊断依据**；
- 丢包显示达到 2.5% 时为红色，重传大于 0 时为黄色。

命令使用完整名称：

```text
/hyperconduit status
```

- 客户端执行：显示与客户端 F3 同源的本机状态。
- 服务端或控制台执行：显示所有玩家的隧道、原版 TCP、本地连接统计及隧道汇总。

服务端可开启持续观察：

```text
/hyperconduit watch
/hyperconduit watch stop
```

`watch` 每 20 个服务器 tick（通常约一秒）输出一次状态。

---

# 技术说明

下面内容面向希望了解实现边界的读者。HyperConduit 的协议、密码学和拥塞控制代码位于 `:core`；它没有依赖 Minecraft API，也不引入第三方通用 QUIC 协议栈。实现参考 QUIC / quic-go 的可靠 UDP 机制及 Hysteria 2 的 Brutal、Pacer 设计，再以 Java 21 实现并按 Minecraft 单字节流场景裁剪。

## QUIC-like 的边界

HyperConduit 是自定义的单字节流可靠 UDP 协议，借鉴了 QUIC 的 Connection ID、包号确认区间、RTT/PTO、基于包号与时间阈值的丢包检测及流控机制；它**不是** RFC 9000 wire format：

- 一个 `SessionEngine` 只承载一个有序字节流，不实现多流复用、HTTP/3 或标准 QUIC 互操作；
- 每名玩家对应独立 Session，拥有独立的拥塞控制器与 Pacer；
- 建立后数据包使用自定义的 4 字节明文 CID、flags、32 位 packet number 和 AEAD 密文负载；
- 建立前的 v2 握手使用独立明文 envelope（魔数、版本、类型、CID、Cookie/载荷长度），不与建立后的传输包格式混用。

### 可靠传输与流控

建立后的传输帧在 ChaCha20-Poly1305 AEAD 保护下编码：

- `StreamData(offset, data)`：用绝对字节偏移承载数据。接收端缓存乱序片段、忽略重复片段，只有填补缺口后才按顺序交付；
- `Ack(ackDelayMicros, ranges)`：以区间/gap 形式确认包号集合；
- `MaxData(maxOffset)`：接收方授予的最高可发送字节偏移，实现接收窗口流控；
- `Ping()`：ack-eliciting 探测帧；
- `Close(reasonCode, message)`：传输层关闭帧。

发送端以两种阈值判定丢包：包号阈值为 3；时间阈值为 `9 / 8 × max(latest RTT, smoothed RTT)`。已判丢失的 `StreamData`、`MaxData` 和 `Close` 会排入重传队列。PTO 超时会提供探测发送机会；若没有其他可用的 ack-eliciting 帧，则发送 `Ping` 请求 ACK。

### 传输包保护

传输包的 CID 保持明文，供服务端在解密前定位 Session。flags 与 packet number 使用基于 `HMAC-SHA256(maskKey, body)` 的前 5 字节进行异或掩码；原始未掩码头部作为 AEAD AAD 参与认证。此处的掩码是协议实现细节，不承诺规避任意网络识别或策略系统。

## Brutal-like 的实现细节

`BrutalCc` 的目标不是估测或探测链路容量，而是以用户配置的 `bps`（每秒字节数）为锚点。其公式为：

```text
               bps × sRTT × 2
cwnd      = ───────────────────
                   ackRate

                 bps
paceRate = ───────────
              ackRate
```

- 在五个一秒时间槽中累计确认包数与丢失包数；
- 总样本数少于 50 时，固定 `ackRate = 1.0`，避免冷启动时由少量样本触发补偿；
- 正常情况下 `ackRate` 最低为 `0.8`，因此补偿上限为配置速率的 `1 / 0.8 = 1.25` 倍；
- `brutal=false` 时仍使用 `BrutalCc` 与同一 Pacer，但固定 `ackRate = 1.0`，即 Paced 模式。

### Pacer

`Pacer` 参考 Hysteria 的 common pacer 设计，使用令牌桶预算决定“现在是否可发送”及“下一次可发送的等待时间”。它限制连续突发额度，并按当前 pacing rate 补充预算。

内部时间以纳秒值计算，但实现的最小起搏延迟是 **1 ms**；请不要把它理解为微秒级或硬实时调度器。

## 安全与握手

密码学实现仅使用 JDK 21 原语：X25519、ChaCha20-Poly1305、HKDF-SHA256 与 HMAC-SHA256。握手模式为：

```text
Noise_XX_25519_ChaChaPoly_SHA256

-> e
<- e, ee, s, es
-> s, se
```

实际连接先经过无状态 Retry：客户端首次 Initial 不携带有效 Cookie，服务端返回 Cookie；客户端回显合法 Cookie 后，服务端才分配 Session 并执行 Noise 工作。Cookie 绑定源 IP、源 UDP 端口、CID 与过期时间，HMAC 密钥保留当前和上一代并定期轮换。这样可减少伪造 UDP Initial 触发会话分配和昂贵密钥协商的机会。

Noise XX 完成后为两个方向派生独立传输密钥；客户端会在发送最后一个握手消息前验证服务端静态身份，从而实现上文所述的 TOFU 信任流程。

## 工程结构与构建

- `:core`：纯 Java 传输、帧编解码、Noise XX、流控、重传、Pacer 与拥塞控制；包含确定性模拟链路、UDP loopback 和 Netty 集成测试。
- `:mod-common`：配置、身份信任、跨端 Mixin 与通用适配逻辑。
- `:neoforge`：NeoForge 平台入口、GUI、F3 状态与指令。

使用 JDK 21 构建：

```bash
./gradlew :core:test :mod-common:test :neoforge:jar
```

产物路径：

```text
neoforge/build/libs/hyperconduit-neoforge-0.1.0.jar
```

## 参考项目

HyperConduit 没有将下列项目作为运行时库引入，也不与其协议直接互通；这里记录的是本项目在自行实现时参考的公开设计与算法来源。

- [Hysteria 2](https://github.com/HyNetworks/hysteria)
  - 参考其 **Brutal** 拥塞控制思路与公式：按配置目标速率，以近期 ACK / Loss 比例计算有界丢包补偿；
  - 参考其 `internal/congestion/common/pacer.go` 的令牌桶 Pacer 设计：发包预算、最小起搏延迟与突发额度限制；
  - HyperConduit 将这些设计以 Java 21 重写为 `BrutalCc` 与 `Pacer`，并针对单 Minecraft 字节流会话接入自己的可靠 UDP 状态机。

- [quic-go](https://github.com/HyNetworks/quic-go)
  - 参考其 QUIC 传输层的参数与可靠性设计：初始数据报尺寸、ACK 区间表示、平滑 RTT / ACK delay 处理、基于包号及时间阈值的丢包检测、PTO 探测与 Pacer 行为；
  - HyperConduit 使用自定义 packet、frame 与握手格式实现这些机制，只保留 Minecraft 所需的单有序字节流；不实现 RFC 9000 wire format、多流复用、HTTP/3 或标准 QUIC 互操作。
