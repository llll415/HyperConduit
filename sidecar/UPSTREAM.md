# HY2 core 来源与改动记录

## 固定来源

| 项目 | 值 |
| --- | --- |
| 官方仓库 | <https://github.com/HyNetwork/hysteria> |
| 读取分支 | `master`（只用于选择快照，不作为浮动依赖） |
| 固定提交 | `fb25dc852ebbc70658e67889f411887a82b7c281` |
| 提交链接 | <https://github.com/HyNetwork/hysteria/commit/fb25dc852ebbc70658e67889f411887a82b7c281> |
| 提交标题 | `ci: publish scripts with wrangler-action` |
| 导入日期 | 2026-11-09 |
| 上游目录 → 本地目录 | `core/` → `sidecar/hy2-core/` |
| 上游 core Git tree | `62c94cb9c8fc9dbe79b03dba2638d582582a234a` |
| 导入文件数 | 63（含隐藏配置、许可证和所有测试夹具） |
| 原始 module 路径 | `github.com/apernet/hysteria/core/v2` |
| 最低 Go 版本 | `1.26.0`（上游 `go.mod`） |
| 固定 QUIC 依赖 | `github.com/apernet/quic-go v0.63.1-0.20261004180939-a10df75c260c` |
| core 许可证 | MIT，原文见 [`hy2-core/LICENSE.md`](hy2-core/LICENSE.md) |

来源为本轮从官方 GitHub 新克隆的工作副本；未使用旁边用户维护的 HY2 仓库或其未提交改动。
用 `git -c core.autocrlf=false archive <SHA> core` 导出，未带入 `.git`、`app/`、`extras/`、`go.work`。
导入基线提交 `832a525` 中的完整 core Git tree 与上方官方 tree 一致，覆盖文件内容、路径和模式。
`sidecar/.gitattributes` 禁止对此快照做自动换行转换，保证入库内容可与上游 tree 对照。

## 本项目对 core 的修改

- 基线提交 `832a525`：原样导入，无定制修改。
- 第 2 步（2026-11-09）：
  - `client/client.go`：新增 `NewClientContext`、`TCPContext` 和连接关闭信号；取消只影响拨号中的 stream，不改变带宽协商。
  - `server/config.go`：要求显式 `Outbound`，移除默认任意目标 TCP/UDP 出站；新增连接数与请求超时配置。
  - `server/server.go`：限制 QUIC 连接数、握手/认证及 TCP 请求等待；原子发布认证状态；关闭时通知活动连接并等待处理退出。
  - `server/copy.go`：双向复制关闭后等待两侧退出，检查短写。
  - 新增取消、配置和复制测试；原有集成测试改为显式使用 test-only 出站，保持测试目的不变。
- 未修改 QUIC 依赖、拥塞算法、HTTP/3 鉴权字段和 TCP framing。
- 应用 UDP、嗅探、伪装及 Chrome 相关源码尚未删除；专用入口禁用这些能力，后续再裁剪。
- 调用方 module 位于 `sidecar/`，通过本地 `replace` 使用本目录；`v2.0.0` 仅是满足 module 主版本的 require 标记，实际源码始终来自本地固定快照及定制补丁。
- 第 3 步（2026-11-09）：
  - 新增 `observe` 包：本地连接 ID、发送控制器、QUIC 指标快照和 stream 上下文，不包含凭据。
  - `client/client.go`：握手结果补实际发送控制器，暴露 `ConnectionStats` 与 stream 身份。
  - `server/config.go`、`server/server.go`：增加连接/错误观察回调与可选 `ContextOutbound`，传递真实 QUIC 对端和 stream ID，后端拨号支持请求取消。
  - `errors/errors.go`：增加类型化 admission limit 错误，避免按文本猜测原因。
  - 未修改带宽协议或拥塞算法；保留 `TrafficLogger` 和原有事件接口。两端 ID 仅在各自进程内有效，不要求相同。
- 第 4 步（2026-11-09）：`client/config.go`、`client/client.go` 透传标准库 `VerifyConnection` 回调，用于在 TLS 密钥证明后检查 SPKI；未改协议、TLS 算法或 QUIC 依赖。身份生成、文件锁、信任存储位于调用方 `internal/identity`、`internal/privatefile`、`internal/trust`。

## 测试基线

- 环境：Windows amd64，`go version go1.26.4 windows/amd64`。
- 在 `sidecar/hy2-core/` 中设置 `GOWORK=off`，避免外部 workspace 干扰。
- `go mod verify`：通过（`all modules verified`）。
- `go test -count=1 -timeout=10m ./...`：通过，7 个有测试的 package 全部通过，其余 6 个 package 无测试文件；集成测试约 73 秒。
- `go vet ./...`：通过。
- `CGO_ENABLED=0 go build -trimpath ./...`：通过（仅构建 core 库，不是可运行 sidecar）。
- 关闭导出时的换行转换后，再次运行 `go test -json -count=1 -timeout=10m ./...`：7 个测试包通过，116 个测试/子测试通过事件，0 跳过、0 失败；集成测试约 66 秒。
- 完整 JSON 输出保存在本机临时目录 `C:\Users\Administrator\AppData\Local\Temp\opencode\hyperconduit-hy2core-step1-tests.jsonl`（不入库，可能随临时文件清理）。
- 本阶段不代表通过 MC 进服、公网性能或其他操作系统验证。

## 复核与后续上游更新

复核原样基线：

```powershell
git rev-parse 832a525:sidecar/hy2-core
```

后续定制会使该 tree 改变，这是预期行为；需通过基线提交与后续提交的 diff 追踪修改。
升级时获取新的固定官方提交，先比较 `core/`、`go.mod`、`go.sum` 和许可变化，再合并所需修复并运行完整回归，不直接覆盖定制目录。

## 第 2 步验证

- Windows amd64 / Go 1.26.4；新增 sidecar 测试连续运行 5 次通过。
- 覆盖 6 MiB 双向字节流、8 路 stream 隔离、TLS 拒绝、可选 token、固定目标拒绝、UDP 禁用、连接限额、认证/请求超时、取消与远端停服清理。
- sidecar：3 个测试包、123 个测试/子测试通过；core：7 包、130 个通过；均 0 跳过、0 失败。
- 两个 module 的 `go vet` 通过；sidecar `go mod verify` 和 `CGO_ENABLED=0` CLI 构建通过。
- JSON 测试报告位于本机临时目录 `opencode/hyperconduit-{sidecar,core}-step2-tests.jsonl`，不随源码分发。
- 未运行 race detector：本机无 C 编译器且 CGO 关闭；未验证 Minecraft 或其他操作系统。

## 第 3 步验证

- Windows amd64 / Go 1.26.4：sidecar 3 个测试包、200 个测试/子测试通过；core 8 包、141 个通过；0 跳过、0 失败。
- 14 组双向协商、单连接带宽隔离、stream 关联、并发状态读取、错误计数与取消回归通过；sidecar 及核心上下文用例重复 5 次通过。
- 两个 module 的 `go vet`、依赖校验及无 CGO CLI 构建通过。报告：本机临时目录 `opencode/hyperconduit-{sidecar,core}-step3-tests.jsonl`。
- 未运行 race detector，未验证 MC 接入、公网性能或其他平台。

## 第 4 步验证

- Windows amd64 / Go 1.26.4：sidecar 6 包、316 个测试/子测试通过；1 个 Unix 权限用例在 Windows 跳过，0 失败。core 8 包、141 个通过，0 跳过/失败。
- 覆盖自动身份复用/续期、SPKI 变化拒绝、失败不落 pin、损坏存储、跨进程单赢家、锁取消与 Windows 私有 DACL；sidecar 重复 3 次通过。
- 两个 module 的 `go vet`、sidecar 依赖校验通过；无 CGO 构建 Windows amd64、Linux amd64、macOS arm64 通过。Linux/macOS 仅编译，未运行。
- 报告位于本机临时目录 `opencode/hyperconduit-{sidecar,core}-step4-tests.jsonl`。未做 race、MC 或公网验证。
