# HyperConduit Go sidecar

独立 Go 隧道可运行，尚未接入 Minecraft/Java。仅转发服务端配置的目标，不提供应用 UDP 转发或直接 TCP 回退。

## 目录

`cmd/hyperconduit-tunnel/` CLI；`internal/config/` 配置校验；`internal/tunnel/` 隧道与限制；`examples/` 示例。来源与许可见 [UPSTREAM.md](UPSTREAM.md)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

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

`--config` 只接受 JSON 文件，token 等凭据不能通过命令行传入。就绪后 stdout 输出一行 JSON，`listen` 是实际绑定地址：

```json
{"version":1,"event":"ready","mode":"server","listen":"127.0.0.1:25565"}
```

错误写 stderr，隧道错误包含 `code` 和 `op`，不输出底层敏感信息；Ctrl+C 关闭进程。本机测试程序连接客户端输出的 `listen`。

## 配置示例

`examples/server.json` 默认只监听本机：`listen` 为 `127.0.0.1:25565`（省略时默认 `127.0.0.1:0`）；`target` 为 `127.0.0.1:25566`，即固定转发目标；`certFile` / `keyFile` 是 `cert.pem` / `key.pem` 占位文件名。

`examples/client.json`：`server` 为 `127.0.0.1:25565`；`listen` 为 `127.0.0.1:0`；`caFile` 为 `cert.pem`。

文件路径相对**进程工作目录**，不是配置文件所在目录。示例端口用于本机 echo 实验，与 Minecraft 默认端口不同，不是强制最终值。

客户端不能选择 target；协议中的 service id 仅内部使用，服务端始终连接自己的 `target`。

## 证书

现阶段使用显式证书，遵循标准 TLS 校验：

- 服务端必须提供 `certFile` 和 `keyFile`；客户端可选 `caFile`，缺省使用系统根证书。
- 证书必须匹配客户端使用的名称/IP SAN。`serverName` 可覆盖校验名称；不要求是域名，带 IP SAN 的自签证书可用于本机实验。
- 不支持 insecure 跳过校验，也没有 TOFU。不要使用 `hy2-core` 测试目录中的公开测试证书。
- 自动生成身份与 TOFU 将在第 4 步加入。

## token

- 示例不设置 token，也不附带任何私钥；服务端默认免共享密钥。即使免 token，也只有配置的 MC 目标可达，Minecraft 自身登录认证不受影响。
- 服务端设置 token 后，客户端必须配置相同值；token 不会出现在日志或 CLI 参数中。
- 配置校验：最长 1024 字节，仅 printable ASCII，不能有首尾空白或 CR/LF（HTTP 字段限制）。

## 拥塞与带宽

- `congestion` 仅支持 `bbr` 或 `reno`。
- 客户端 `upMbps` / `downMbps` 非零时，对应上传/下载方向采用 Brutal；未配置的方向使用发送端本地的 BBR/Reno。
- 服务端 `upMbps` / `downMbps` 分别限制向客户端发送/接收的**单连接 Brutal 协商速率**，不是全局限速，也不限制 BBR/Reno；仅设置服务端带宽不会强制启用 Brutal。
- `disableBrutal` 仅服务端有效，`true` 时双向使用 BBR/Reno；默认 `false`。客户端必须保持 `false`。

## 限额与超时

- `maxConnections` 默认 64：服务端限制 QUIC 连接数并限制全局后端连接；客户端限制本地连接。
- `maxStreams` 默认 16，仅服务端生效。客户端写该字段当前会被接受但忽略，不推荐配置。
- 连接超时与请求超时默认 5 秒，空闲超时默认 30 秒；QUIC 窗口和复制缓冲区均为有界值。

## 当前边界

- 仅按原始字节转发 TCP，不解析 Minecraft 协议。
- 无直接 TCP fallback，断线后不自动重连。
- 玩家来源地址、MC 连接关联和 F3 尚未接入。
- 尚未接入 Java 模块；现有游戏行为未切换到此隧道。

## 状态接口

Go 调用方可通过 `Endpoint.Status()` 读取活动连接、TCP↔stream 关联和分类错误计数；未开放 HTTP 服务或额外管理端口。状态接口和 CLI 错误使用固定分类，不记录凭据或原始错误文本。
每条 QUIC 连接只报告**本地发送控制器**，Brutal 速率为协商目标。RTT 单位为纳秒；收发计数属于 QUIC 层，包含重传等开销；丢包计数可能因迟到确认而下修。
连接 ID 仅在本进程内有效，两端 ID 不必相同。`PeerAddr` 来自 QUIC 对端，不接受客户端声明，也尚未传递到 MC。
`disableBrutal` 是协商策略，不是针对篡改客户端的流量管控；没有全服共享限速器。

## 来源

上游固定提交与改动记录见 [UPSTREAM.md](UPSTREAM.md)；依赖许可清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。core 上游仓库：<https://github.com/HyNetwork/hysteria>。
