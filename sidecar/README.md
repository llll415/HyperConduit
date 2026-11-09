# HyperConduit Go sidecar

独立 Go 隧道，尚未接入 Minecraft/Java。仅转发服务端配置的固定目标，不提供应用 UDP 转发或直接 TCP 回退。

## 目录

`cmd/hyperconduit-tunnel/` CLI；`internal/config/` 配置校验；`internal/identity/` 服务端身份；`internal/trust/` SPKI 信任库；`internal/tunnel/` 隧道与限制；`examples/` 示例。来源见 [UPSTREAM.md](UPSTREAM.md)，第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 [licenses/](licenses/)。

## 构建

需要 Go 1.26。PowerShell：

```powershell
cd sidecar
$env:GOWORK = 'off'
go build -trimpath -o build/hyperconduit-tunnel.exe ./cmd/hyperconduit-tunnel
```

`sidecar/build/` 已被 `.gitignore` 忽略，不要提交构建产物。

## 测试

`sidecar` 与 `sidecar/hy2-core` 是两个嵌套 module，需分别测试：

```powershell
cd sidecar
$env:GOWORK = 'off'
go test -count=1 -timeout=10m ./...
cd hy2-core
go test -count=1 -timeout=10m ./...
```

## 运行

```powershell
.\build\hyperconduit-tunnel.exe server --config examples\server.json
.\build\hyperconduit-tunnel.exe client --config examples\client.json
```

`--config` 只接受 JSON 文件，token 等凭据不能通过命令行传入。就绪后 stdout 输出一行 JSON，`listen` 是实际绑定地址；服务端还带 `fingerprint`：

```json
{"version":1,"event":"ready","mode":"server","listen":"127.0.0.1:25565","fingerprint":"SHA256:..."}
```

错误写 stderr，隧道错误包含 `code` 和 `op`，不输出底层敏感信息；Ctrl+C 关闭进程。本机测试程序连接客户端输出的 `listen`。
Java 管理时使用 `--managed`：stdin 接收 `{"version":1,"command":"shutdown"}`；管道关闭或无效指令也会停止进程。独立 CLI 默认不读取 stdin。

## 配置示例

`examples/server.json`：`listen` 为 `127.0.0.1:25565`，`target` 为 `127.0.0.1:25566`，`stateDir` 为 `config/hyperconduit`。未配置证书时自动生成身份，默认位于 `config/hyperconduit/server-identity.json`。

`examples/client.json`：`server` 为 `127.0.0.1:25565`，`listen` 为 `127.0.0.1:0`，`stateDir` 同为 `config/hyperconduit`，未配置 `caFile`，使用 TOFU。两个示例共用 `stateDir` 仅便于本机演示；服务端身份与客户端 `known-servers.json` 是不同文件，不会互相覆盖。

文件路径相对**进程工作目录**，不是配置文件所在目录。示例端口用于本机 echo 实验，与 Minecraft 默认端口不同。客户端不能选择 target；服务端始终连接自己的 `target`。

## 配置字段

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `listen` | `127.0.0.1:0` | 服务端可监听 IP:端口；客户端只允许回环地址且端口为 0 |
| `server` | 无 | 仅客户端，服务端 `host:port` |
| `target` | 无 | 仅服务端，固定转发目标 `host:port` |
| `token` | 空 | 两端一致；为空表示免共享密钥 |
| `certFile` / `keyFile` | 空 | 仅服务端，显式证书对，必须同时提供 |
| `caFile` | 空 | 仅客户端，启用标准 CA 校验 |
| `serverName` | 空 | 仅客户端 CA 模式，覆盖校验名称 |
| `stateDir` | `config/hyperconduit` | 状态文件目录 |
| `identityFile` | `stateDir/server-identity.json` | 仅服务端，托管身份文件 |
| `knownServersFile` | `stateDir/known-servers.json` | 仅客户端 TOFU 信任库 |
| `congestion` | `bbr` | `bbr` 或 `reno` |
| `upMbps` / `downMbps` | 0 | 客户端声明带宽；服务端限制单连接 Brutal 速率，详见下文 |
| `disableBrutal` | `false` | 仅服务端 |
| `maxConnections` | 64 | 1–4096 |
| `maxStreams` | 16 | 仅服务端，8–1024 |
| `connectTimeoutMs` | 5000 | 100–120000 |
| `requestTimeoutMs` | 5000 | 100–120000 |
| `idleTimeoutMs` | 30000 | 4000–120000 |

## 服务端身份

- 未提供 `certFile` / `keyFile` 时，服务端首次启动生成自签名 ECDSA P-256 证书，保存在单个 JSON 文件中，同时含证书与 PKCS#8 私钥；文件权限受限（Unix 0600；Windows 使用受保护 DACL，仅 SYSTEM 与当前用户）。不要分享或提交该文件。
- 证书有效期 1 年；到期前 30 天用同一密钥重签，SPKI 指纹保持不变。`GetCertificate` 会检查缓存，超过 24 小时或临近到期时重新加载或续签。
- `certFile` 与 `keyFile` 必须成对提供，且不能与 `identityFile` 同时使用；`identityFile` 仅服务端有效。省略时使用上述默认路径。
- 已有文件损坏、无法读取或证书与私钥不匹配时直接报错，不会自动重建或覆盖；需人工检查或移走后再启动。

## 客户端信任（TOFU）

- 未配置 `caFile` 时使用 TOFU：信任键是用户输入的规范化 `host:port`，不按 DNS 解析出的 IP。首次 TLS 握手成功并通过 token 认证后才写入 pin。
- 首次信任仍可能被中间人利用（TOFU 固有局限）。可对照服务端 ready 事件中的 `fingerprint`（`SHA256:` 前缀）确认。
- 已记录条目后续指纹变化会被拒绝。并发首次连接会在锁内复查，后到者不能覆盖已写入的 pin。
- 信任库默认位于 `stateDir/known-servers.json`。文件损坏或条目非法时报错，不会自动重建。
- 配置、身份和信任文件会收紧访问权限；状态目录应由运行账户管理，不应放在公共可写目录。管理员/root 仍可访问本机文件。
- 配置 `caFile` 后回到标准 CA 校验，此时不能同时设置 `knownServersFile`；`serverName` 仅在 CA 模式下有效。没有跳过校验的 insecure 开关；TOFU 模式内部以 SPKI 校验替代 CA/主机名校验，并非不认证。

## 信任管理

```powershell
.\build\hyperconduit-tunnel.exe trust list --config examples\client.json
.\build\hyperconduit-tunnel.exe trust forget --config examples\client.json --server 127.0.0.1:25565
```

仅 TOFU 模式可用，配置了 `caFile` 会报错。`forget` 只删除指定 endpoint 条目，不影响已建立的活动会话；下次连接会重新走首次信任。

## token

- 示例不设置 token，也不附带任何私钥；服务端默认免共享密钥。即使免 token，也只有配置的 MC 目标可达，Minecraft 自身登录认证不受影响。
- 服务端设置 token 后，客户端必须配置相同值；token 不会出现在日志或 CLI 参数中。
- 配置校验：最长 1024 字节，仅 printable ASCII，不能有首尾空白或 CR/LF（HTTP 字段限制）。

## 拥塞与带宽

- `congestion` 仅支持 `bbr` 或 `reno`。
- 客户端 `upMbps` / `downMbps` 非零时，对应上传/下载方向采用 Brutal；未配置的方向使用发送端本地的 BBR/Reno。
- 服务端 `upMbps` / `downMbps` 分别限制向客户端发送/接收的**单连接 Brutal 协商速率**，不是全局限速，也不限制 BBR/Reno；仅设置服务端带宽不会强制启用 Brutal。
- `disableBrutal` 仅服务端有效，`true` 时双向使用 BBR/Reno；默认 `false`。客户端必须保持 `false`。

Java 接入使用内部 `bridgeToken` 启用私有 MC 元数据前言，并以 `serverEndpoint` 保留用户选择的信任地址。普通独立转发无需设置这两个字段；不要在公网普通 MC TCP 上启用来源头信任。

`probe` 是 Java 列表发现专用的复用工作进程，stdin/stdout 使用有界 NDJSON 请求/结果，支持取消和 EOF 退出。它仅查询 `GET https://hyperconduit/capability`，不加载配置/口令、不读写信任文件，也不建立游戏转发流。探测使用未认证的服务器身份，只能用于提示；正式连接的 TLS/TOFU 和 token 校验不受影响。端点返回固定的 `{protocol,enabled}`，每条 QUIC 连接仅接受一次能力请求。

受管 client/server 可通过 stdin `{ "version":1, "command":"observe" }` 订阅每秒指标。stdout `stats` 只包含有界活动连接 ID、本端实际发送 CC/Brutal 目标、平滑 RTT 和传输字节计数，不含地址、口令、pin 或游戏数据。样本以进程内单调时间标记；速率由 Java 对连续样本求差，不新增统计 HTTP 服务。未订阅时维持原 ready/error 协议，独立非受管模式不输出指标。

## 限额与超时

- `maxConnections` 默认 64：服务端限制 QUIC 连接数并限制全局后端连接；客户端限制本地连接。
- `maxStreams` 默认 16，仅服务端生效。客户端写该字段当前会被接受但忽略，不推荐配置。
- 连接超时与请求超时默认 5 秒，空闲超时默认 30 秒；QUIC 窗口和复制缓冲区均为有界值。

## 当前边界

- 仅按原始字节转发 TCP，不解析 Minecraft 协议。
- 无直接 TCP fallback，断线后不自动重连。
- MC 桥接已接入可信来源和连接标记；实际 RTT/速率展示仍待第 8 步。
- Java 进程管理位于 [`sidecar-runtime`](../sidecar-runtime/README.md)，尚未接入 MC；游戏内 token/信任管理待实现。身份与信任文件只由 Go 写入。

## 状态接口

Go 调用方可通过 `Endpoint.Status()` 读取活动连接、TCP↔stream 关联和分类错误计数；未开放 HTTP 服务或额外管理端口。状态接口和 CLI 错误使用固定分类，不记录凭据或原始错误文本。
每条 QUIC 连接只报告**本地发送控制器**，Brutal 速率为协商目标。RTT 单位为纳秒；收发计数属于 QUIC 层，包含重传等开销；丢包计数可能因迟到确认而下修。
连接 ID 仅在本进程内有效，两端 ID 不必相同。`PeerAddr` 来自 QUIC 对端，不接受客户端声明，也尚未传递到 MC。
`disableBrutal` 是协商策略，不是针对篡改客户端的流量管控；没有全服共享限速器。

## 来源

上游固定提交与改动记录见 [UPSTREAM.md](UPSTREAM.md)；依赖许可清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，许可原文在 [licenses/](licenses/)。core 上游仓库：<https://github.com/HyNetwork/hysteria>。
