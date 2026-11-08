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
导入内容在暂存区形成的完整 Git tree 已核对等于上方官方 core tree，覆盖文件内容、路径和模式；Windows 自动换行不计入定制修改。
`sidecar/.gitattributes` 禁止对此快照做自动换行转换，保证入库内容可与上游 tree 对照。

## 本项目对 core 的修改

- **本次：无。** 不重命名 package/module、不升级依赖、不裁剪功能、不改协议。
- 当前原样 core 仍具有通用目标出站、应用 UDP 及其他待裁能力；不要把它当作已完成限制的专用隧道发布。
- 后续修改在本节逐项记录目的、受影响路径和回归测试，不覆盖原始来源信息。
- 第 2 步建立我们自己的 Go module，通过本地 `replace` 使用这份可编辑 core。

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

建立基线后，本地快照目录的 Git tree 应等于上方记录值：

```powershell
git rev-parse HEAD:sidecar/hy2-core
```

后续定制会使该 tree 改变，这是预期行为；需通过基线提交与后续提交的 diff 追踪修改。
升级时获取新的固定官方提交，先比较 `core/`、`go.mod`、`go.sum` 和许可变化，再合并所需修复并运行完整回归，不直接覆盖定制目录。
