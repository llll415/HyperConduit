# HyperConduit

HyperConduit 是面向 **Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21** 的双端 UDP 隧道 Mod。它把 Minecraft 原有的 Netty 字节流交给随 jar 分发的 Go Hysteria 2 sidecar，由 sidecar 建立加密 QUIC/UDP 隧道；Minecraft 的登录、认证、白名单和其他上层逻辑仍由原版处理。

> 当前是开发分支，不是可分发版本。
>
> 只支持 NeoForge 1.21.1 + Java 21。Go sidecar 已随 jar 打包，但当前只包含构建机平台的二进制；完整平台矩阵属于第 10 步。

## 兼容性与迁移

- 本分支重构了传输核心，使用新的配置和隧道协议。
- **不兼容旧版 `hyperconduit.json`、旧版 Mod 或旧协议**，不提供配置迁移或双协议兼容层。
- 旧 Java lite `core/` 已不再是实际入口，但源码暂时保留，计划第 9 步清理。
- 客户端与服务端必须安装同版本 Mod 并启用；条件不满足时**不会自动回退**，连接直接失败并显示错误。
- 未安装 Mod 的玩家仍可通过原版 TCP 加入（服务端保持 `disableVanillaTcp=false`）。
- 玩家仍填写服务器地址 `host:port`；不需要域名、CA 证书或手动启动 sidecar。
- MC 握手地址、端口和服务器列表显示信息保持原样；原有认证流程也保持不变。

## 工作方式

- Java 端只负责配置、生命周期、拨号重定向和可信来源恢复。
- 客户端连接时先启动本机 sidecar，再把 Minecraft 的 TCP 连接指向 sidecar 输出的回环监听地址。
- 服务端启动时创建私有 TCP backend，并让 sidecar 通过 UDP 接收隧道流量后转发到该 backend。
- 服务端同时保留原版 TCP listener（共存形态）时，两条路径使用同一个数字端口，但协议分别是 TCP 与 UDP。
- 所有隧道失败都作为显式错误返回，不会静默切回原版 TCP。

## 使用

1. 两端安装同一版本的 `hyperconduit-neoforge-*.jar`。
2. 在多人游戏或“开放局域网”界面点击 `HyperConduit…`。
3. 打开“启用”，按需设置带宽、拥塞控制和 token。
4. 正常添加并加入服务器。
5. 设置保存后只在下一次连接或下一次开放局域网时生效，不会热切换已建立的会话。

## 配置

配置文件为 `config/hyperconduit-hy2.json`，使用严格 JSON；旧的 `hyperconduit.json` 会被忽略。

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 本端是否启用隧道；关闭时走原版 TCP。 |
| `upMbps` / `downMbps` | `0` | 客户端：上传/下载声明；服务端：单连接 Brutal 发送/接收上限。`0` 不声明限额，不限制 BBR/Reno。 |
| `congestion` | `bbr` | `bbr` 或 `reno`。 |
| `disableBrutal` | `false` | 仅主机生效：双向改用本地 BBR/Reno，不拒绝连接。 |
| `disableVanillaTcp` | `false` | 仅主机生效：只接受隧道连接。 |
| `clientToken` / `serverToken` | 空 | 可选共享 token；默认免 token。 |
| `connectTimeoutMs` | `5000` | 连接超时，100–120000 毫秒。 |
| `maxConnections` | `64` | 最大并发连接数，1–4096。 |

游戏内 token 输入框不会回显已保存值，显示为掩码；留空表示保持原值。旁边提供“清除客户端口令 / 清除服务器口令”按钮，可显式清空。

## 服务端形态

### 共存（默认）

服务端同时提供同号的原版 TCP 和 HyperConduit UDP 端口。隧道转发到额外的本机随机 TCP 入口；原版 TCP 继续服务未安装 Mod 的玩家。

### only-tunnel

设置 `disableVanillaTcp=true` 后，对外只接受 HyperConduit UDP，MC 的私有 TCP 入口仍保留。该模式要求固定对外端口，**端口 0 不受支持**。LAN 共用同一监听生命周期，但尚未实际联机验证。

每个监听器都会在内部生成随机的 bridge token，并随受保护的元数据一起传给私有 backend。该 token 只用于本机 Java 与 sidecar 之间的桥接，玩家无需配置，也不应配置 `bridgeToken`。

## 来源地址与兼容风险

sidecar 在转发前写入受保护的 `HCMETA01` 元数据（bridge token、peer、connectionId、streamId）；Java 端校验后通过 `Connection.getRemoteAddress` 恢复玩家来源地址。这样不会把 Minecraft 载荷里的任意字段当作真实来源。

但部分 Mod 直接读取 Netty `channel.remoteAddress()` 时仍会看到 loopback，而不是真实玩家地址。这类 Mod 可能表现异常，属于当前已知兼容风险。

## 身份与信任

- 服务端首次运行生成长期身份，保存在 `config/hyperconduit/server-identity.json`，其中包含私钥。
- 客户端首次连接某个 `host:port` 时，按 SPKI TOFU 信任服务端公钥，记录在 `config/hyperconduit/known-servers.json`。
- 信任库由同一个 Go sidecar 持久化和读写，Java 不另存一份指纹。
- 同一地址后续出现不同身份时客户端拒绝连接，不会静默覆盖。
- 可在 `HyperConduit… → 已信任服务器…` 查看列表并删除（forget）旧记录；删除后下一次连接会重新执行首次信任。
- 首次信任仍存在中间人风险；需要更强保证时应通过带外方式核对指纹。

## 状态与诊断

- `F3` 当前只显示连接状态和配置摘要，**不是实际 RTT、丢包或速率指标**；实际指标接入属于第 8 步。
- `/hyperconduit status` 显示状态。
- 服务端支持 `/hyperconduit watch` 和 `/hyperconduit watch stop`。
- 多人列表 HC 标记位于信号图标左侧，区分启用、禁用、未声明、未知/超时及协议不兼容；图形布局与实际操作流程仍待用户验收。

### 多人列表发现

- 优先读取普通 MC status 的能力声明；没有可用声明时，通过复用的 sidecar 发起只读 QUIC 查询，包含仅隧道服务器。
- 查询不发送玩家口令、不访问 MC 后端、不读写 TOFU。显示只供发现，不能证明服务器身份；正式连接仍独立验证 TLS/pin 和口令。
- 并发、队列及超时受限；刷新或离开列表会取消旧任务。无响应不表示未安装。

## 已验证范围

- 专用服务端 smoke：共存形态已通过。
- only-tunnel 形态的 Minecraft status 已通过。
- 尚未验证玩家实际进服跑图、局域网完整流程、公网复杂网络和跨平台运行。

## 实现边界

- sidecar 基于定制 HY2 core 和 apernet/quic-go，每条 MC TCP 连接对应一条有序 QUIC stream。
- 使用成熟 QUIC/TLS 实现，不自行实现 QUIC；保留 HTTP/3 鉴权，不承诺与原版 HY2 的产品互通。
- 不提供应用层 UDP 转发；Minecraft 的 UDP 查询等能力不在隧道范围内。
- 服务端只连接管理员配置的固定 target，客户端不能指定任意目标。
- bridge 元数据只接受 sidecar 的可信观测，不接受 Minecraft 载荷自报。
- 本项目不提供流量伪装或规避网络识别的能力。

## 常见问题

- **连接直接失败**：先确认两端都已启用，且服务端 UDP 端口可达；失败不会回退到 TCP。
- **未装 Mod 的玩家能否加入**：服务端保持共存形态时可以走原版 TCP。
- **能否只开隧道**：可以，设置 `disableVanillaTcp=true`，但必须提供固定端口，不能用端口 0。
- **HC 显示未知**：可能是超时、UDP 不通或能力声明异常，不能据此断言对方未安装；不会因此自动改用 TCP 登录。
- **为什么 F3 没有 RTT**：当前只显示状态与配置，实际指标属于第 8 步。
- **为什么来源 IP 是 127.0.0.1**：部分直接读 Netty `remoteAddress` 的 Mod 会看到 loopback，这是已知兼容风险。
- **换服务端后连不上**：新身份会触发信任拒绝；在“已信任服务器”中删除旧记录后重试。
- **首次连接安全吗**：TOFU 只能防后续身份变化，首次连接仍可能被中间人攻击。

## 构建

需要 JDK 21 与 **Go 1.26**：

```bash
./gradlew :neoforge:jar -PsidecarGo=<go 可执行文件路径>
```

jar 会打包当前构建机平台的 sidecar 二进制、版本及 SHA-256 manifest。Windows/Linux/macOS 的完整构建矩阵、执行权限与 smoke test 属于第 10 步。

## 风险提示

- 跨境使用可能遇到阻断、限速或合规风险；本项目不提供伪装、抗审查或规避策略保证。
- 不保证吞吐、延迟、抗丢包或游戏体验会改善，部分网络中可能更差。
- Brutal 偏激进；带宽设置过高或在共享网络、多连接场景使用，可能影响其他业务并触发限速。请从保守值开始。
- UDP 可能被运营商或网络策略阻断；隧道失败时不会自动切回原版 TCP。

## 后续步骤

- 第 7 步：列表探测代码与自动回归，图形效果及操作流程待用户验收。
- 第 8 步：F3 与命令接入实际连接指标。
- 第 9 步：裁剪旧 Java 核并清理。
- 第 10 步：跨平台打包、完整验证与文档收尾。

## 许可证与第三方

sidecar 的第三方来源与许可证见 [sidecar/THIRD_PARTY_NOTICES.md](sidecar/THIRD_PARTY_NOTICES.md)、[sidecar/licenses/](sidecar/licenses/)、[sidecar/UPSTREAM.md](sidecar/UPSTREAM.md) 和 [sidecar/hy2-core/LICENSE.md](sidecar/hy2-core/LICENSE.md)。构建时这些文件也会放入 jar 的 `META-INF/hyperconduit-sidecar`。
