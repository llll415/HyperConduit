# HyperConduit

HyperConduit 是面向 **Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21** 的双端 UDP 隧道 Mod。它把 Minecraft 原有的 Netty 字节流交给随 jar 分发的 Go Hysteria 2 sidecar，由 sidecar 建立加密 QUIC/UDP 隧道；Minecraft 的登录、认证、白名单和其他上层逻辑仍由原版处理。

> 当前是开发分支，不是可分发版本。
>
> 只支持 NeoForge 1.21.1 + Java 21。Go sidecar 随 jar 打包；默认构建 Windows amd64、Linux amd64/arm64、macOS Intel/Apple Silicon 五平台矩阵，运行时按 JVM 的 OS 与架构自动选择二进制，Windows arm64 不打包。五平台打包、manifest 与哈希校验已通过；Windows amd64 已做用户 LAN 实测，NeoForge/Youer 专用服仅有早期基本路径实测，其他平台只有交叉编译与打包校验，未实机运行。

## 兼容性与迁移

- 本分支重构了传输核心，使用新的配置和隧道协议。
- **不兼容旧版 `hyperconduit.json`、旧版 Mod 或旧协议**，不提供配置迁移或双协议兼容层。
- 旧 Java `core/` 已不再是实际入口；用户最后统一实机验收前暂留源码与参考测试，不长期维护双后端。
- 隧道连接要求两端安装同版本 Mod 并启用；隧道失败不会自动回退到原版 TCP。连接前的单次选择只影响本次连接：仅在对端明确声明时，询问本次改用原版 TCP 或本次启用隧道，详见“连接前传输选择”。
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
5. 设置保存后不会热更新已建立的会话：客户端设置在下一次连接时生效；主机/服务端设置在下次开放局域网或重启服务端监听时生效。

### 连接前传输选择

连接前会做一次只读、未认证的能力探测，仅在以下两种情况询问，且都只影响本次连接：

- 客户端已开启 HyperConduit，且对端明确声明未启用 HyperConduit、允许原版 TCP：询问“本次使用原版 TCP”或取消。
- 客户端未开启 HyperConduit，且对端明确声明禁止原版 TCP：询问“本次启用 HyperConduit 隧道连接”或取消。
- 其余情况按当前设置直接连接，不额外弹窗；未声明、未知或超时不会推断为未安装。

选择不修改全局配置、不记忆，也不影响后续连接；隧道失败后不会自动回退到原版 TCP。选择原版 TCP 时，本次不使用 HyperConduit 的隧道保护与服务器身份校验，已有 pin 不会被探测声明静默降级；探测结果本身未认证，可能过期或被干扰。

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

设置 `disableVanillaTcp=true` 后，对外只接受 HyperConduit UDP，MC 的私有 TCP 入口仍保留。该模式要求固定对外端口，**端口 0 不受支持**。LAN 共用同一监听生命周期；开放局域网、加入及关闭/重新开放世界等基本路径已由用户实测，更广泛的插件与长期稳定性仍未验收。

每个监听器都会在内部生成随机的 bridge token，并随受保护的元数据一起传给私有 backend。该 token 只用于本机 Java 与 sidecar 之间的桥接，玩家无需配置，也不应配置 `bridgeToken`。

## 来源地址与兼容风险

sidecar 在转发前写入受保护的 `HCMETA01` 元数据（bridge token、peer、connectionId、streamId）；Java 端校验后通过 `Connection.getRemoteAddress` 恢复玩家来源地址。这样不会把 Minecraft 载荷里的任意字段当作真实来源。

但部分 Mod 直接读取 Netty `channel.remoteAddress()` 时仍会看到 loopback，而不是真实玩家地址。这类 Mod 可能表现异常，属于当前已知兼容风险。

## 身份与信任

- 服务端首次运行生成长期身份，保存在 `config/hyperconduit/server-identity.json`，其中包含私钥。
- 客户端首次连接某个 `host:port` 时，按 SPKI TOFU 信任服务端公钥，记录在 `config/hyperconduit/known-servers.json`。
- 信任库由同一个 Go sidecar 持久化和读写，Java 不另存一份指纹。
- 同一地址后续出现不同身份时客户端拒绝连接，不会静默覆盖。
- 可在 `HyperConduit… → 已信任服务器…` 查看列表并删除（forget）旧记录；请先带外确认对方身份确已更换，删除后下一次连接会重新执行首次信任。
- 首次信任仍存在中间人风险；需要更强保证时应通过带外方式核对指纹。

## 状态与诊断

- `F3`／`/hyperconduit status` 显示本端实际发送控制器、QUIC 平滑 RTT 和传输速率；不会把本地控制器当作对端算法。
- 速率来自两次 QUIC 传输字节计数的差值，含重传和协议开销，不是 MC 有效载荷；共享隧道连接时是整条 QUIC 连接的统计，不能按玩家拆分游戏有效载荷。首帧速率待采样，RTT=0 表示尚无样本；超时、退出或无关联记录时显示指标不可用。
- 指标经现有受管进程通道每秒传递一次，不新增 HTTP 服务或管理端口；最多保留 128 条活动连接，缺失条目不猜测。
- 服务端支持 `/hyperconduit watch` 和 `/hyperconduit watch stop`。
- 多人列表 HyperConduit 标记位于信号图标左侧，区分启用、禁用、未声明、未知/超时及协议不兼容；列表探测与原版延时已实测，本轮简化后的连接前界面布局仍待确认。

### 多人列表发现

- 优先读取普通 MC status 的能力声明；没有可用声明时，通过复用的 sidecar 发起只读 QUIC 查询，包含仅隧道服务器。
- 查询不发送玩家口令、不访问 MC 后端、不读写 TOFU。显示只供发现，不能证明服务器身份；正式连接仍独立验证 TLS/pin 和口令。
- 并发、队列及超时受限；刷新或离开列表会取消旧任务。无响应不表示未安装。

## 已验证范围

- Windows amd64 LAN 已由用户实测：列表探测与原版延时、双端开关组合、本次原版 TCP、本次启用隧道、口令错误拒绝及失败不回退；更早的 LAN 记录另覆盖身份变化拒绝、信任删除等。
- NeoForge 专用服已由用户实际加入/重连，并通过原版 TCP 共存与在线 status；仅隧道为早期 smoke。Youer 的用户共存/仅隧道基本路径已通过。上述不构成当前新构建的专用服回归，watch 停止、身份更换重测、长时间稳定性及广泛插件兼容仍未覆盖。
- 非 Windows 平台只完成交叉编译、五平台打包与哈希/许可校验，未在对应平台实际运行。
- 未做吞吐、丢包、延迟或游戏体验对比，不构成性能提升保证；未勾选项目仍以[验收清单](docs/HY2CORE_ACCEPTANCE.md)为准。

## 实现边界

- sidecar 基于定制 HY2 core 和 apernet/quic-go，每条 MC TCP 连接对应一条有序 QUIC stream。
- 使用成熟 QUIC/TLS 实现，不自行实现 QUIC；保留 HTTP/3 鉴权，不承诺与原版 HY2 的产品互通。
- HY2 core 已裁剪应用 UDP/分片、嗅探改写、可配置伪装、Chrome 指纹、自动重连和随机应用 padding；QUIC 自身的 padding/流控不动。双方必须使用同一版本，详见 [`sidecar/PRUNING.md`](sidecar/PRUNING.md)。
- 不提供应用层 UDP 转发；Minecraft 的 UDP 查询等能力不在隧道范围内。
- 服务端只连接管理员配置的固定 target，客户端不能指定任意目标。
- bridge 元数据只接受 sidecar 的可信观测，不接受 Minecraft 载荷自报。
- 本项目不提供流量伪装或规避网络识别的能力。

## 常见问题

- **连接直接失败**：先确认两端都已启用，或已按连接前提示选择本次传输；走隧道时还需服务端 UDP 端口可达。隧道失败不会回退到 TCP。
- **未装 Mod 的玩家能否加入**：服务端保持共存形态时可以走原版 TCP。
- **能否只开隧道**：可以，设置 `disableVanillaTcp=true`，但必须提供固定端口，不能用端口 0。
- **HyperConduit 显示未知**：可能是超时、UDP 不通或能力声明异常，不能据此断言对方未安装；不会因此自动改用 TCP 登录。
- **为什么 F3 指标不可用**：可能还没有样本、数据已过期或 sidecar 已退出；普通 TCP 不显示 QUIC 指标。
- **为什么来源 IP 是 127.0.0.1**：部分直接读 Netty `remoteAddress` 的 Mod 会看到 loopback，这是已知兼容风险。
- **换服务端后连不上**：新身份会触发信任拒绝。请先通过带外方式向服主核对新指纹/身份，确认确属预期更换后，再在“已信任服务器”中删除旧记录并重连；不要为绕过提示而盲删信任。
- **首次连接安全吗**：TOFU 只能防后续身份变化，首次连接仍可能被中间人攻击。

## 构建

需要 JDK 21 与 **Go 1.26**。默认使用 PATH 中的 `go`，可用 `-PsidecarGo=<go 可执行文件路径>` 指定。

```bash
./gradlew :neoforge:jar
```

默认构建五平台 sidecar 矩阵：Windows amd64、Linux amd64/arm64、macOS Intel（darwin-amd64）与 Apple Silicon（darwin-arm64）；Windows arm64 不打包。运行时按 JVM 报告的 OS 与架构自动选择对应二进制。

产物位于 `neoforge/build/libs/hyperconduit-neoforge-*.jar`，无需手动启动 sidecar。`./gradlew :neoforge:verifyDistribution` 校验最终 jar 内的五平台二进制、manifest 与 SHA-256；`./gradlew :sidecar-runtime:verifySidecarBundle` 校验打包前的 sidecar bundle（随矩阵构建提供）。

以上仅为自用测试构建，不是已验收发布。交叉编译不等于运行时验证；非 Windows 平台的执行权限、启动与连接 smoke test 仍待实机验收。

## 风险提示

- 跨境使用可能遇到阻断、限速或合规风险；本项目不提供伪装、抗审查或规避策略保证。
- 不保证吞吐、延迟、抗丢包或游戏体验会改善，部分网络中可能更差。
- Brutal 偏激进；带宽设置过高或在共享网络、多连接场景使用，可能影响其他业务并触发限速。请从保守值开始。
- UDP 可能被运营商或网络策略阻断；隧道失败时不会自动切回原版 TCP。

## 后续步骤

- 统一实机验收剩余项：watch 停止与重复 watch、身份更换重测、取消/未知分支、多连接 Brutal 限额隔离，以及吞吐/丢包/延迟对比。
- NeoForge/Youer 长时间稳定性与广泛插件兼容、SRV/IPv6、跨平台 sidecar 运行、race、macOS 分发和日志脱敏仍未覆盖。
- 上述项目完成前，不把自动回归或部分 LAN/专用服实测视为全部验收。

## 许可证与第三方

sidecar 的第三方来源与许可证见 [sidecar/THIRD_PARTY_NOTICES.md](sidecar/THIRD_PARTY_NOTICES.md)、[sidecar/licenses/](sidecar/licenses/)、[sidecar/UPSTREAM.md](sidecar/UPSTREAM.md) 和 [sidecar/hy2-core/LICENSE.md](sidecar/hy2-core/LICENSE.md)。构建时这些文件也会放入 jar 的 `META-INF/hyperconduit-sidecar`。
