# 第三方软件许可声明（sidecar / hyperconduit-tunnel）

本文件记录当前 `sidecar/cmd/hyperconduit-tunnel` 在随包发布的 5 个目标平台上，经 Go 构建实际选中的第三方模块，以及 `sidecar/hy2-core` 测试构建额外选中的模块。许可原文按 `sidecar/licenses/<模块路径>@<版本>/<上游文件名>` 保存，未作删改。

## 当前依赖结论（2026-11-09 验证）

- **运行依赖**：5 个目标平台（`windows/amd64`、`linux/amd64`、`linux/arm64`、`darwin/amd64`、`darwin/arm64`）的第三方模块集合完全一致，共 10 个：`github.com/andybalholm/brotli`、`github.com/apernet/quic-go`、`github.com/klauspost/compress`、`github.com/quic-go/qpack`、`github.com/refraction-networking/utls`、`golang.org/x/crypto`、`golang.org/x/exp`、`golang.org/x/net`、`golang.org/x/sys`、`golang.org/x/text`。
- **测试依赖**：`sidecar/hy2-core` 的测试构建额外选中 4 个模块：`github.com/stretchr/testify`、`github.com/stretchr/objx`、`go.yaml.in/yaml/v3`、`go.uber.org/goleak`。它们仅用于测试，不进入 `hyperconduit-tunnel` 二进制。
- **不再链接**：`golang.org/x/time` 已不在当前 runtime 或 test 集合中。其历史许可文件仍保留在 `sidecar/licenses/`，仅用于历史追溯，不代表当前选中。

## 生成方法与验证环境

在 `sidecar` 目录执行（每个目标平台重复设置 `GOOS`/`GOARCH`）：

```powershell
$env:GOWORK="off"
$env:CGO_ENABLED="0"
$env:GOOS="windows"; $env:GOARCH="amd64"   # 依次替换为 linux/amd64、linux/arm64、darwin/amd64、darwin/arm64
go list -deps -f '{{if .Module}}{{.Module.Path}}|{{.Module.Version}}{{end}}' ./cmd/hyperconduit-tunnel
```

在 `sidecar/hy2-core` 目录执行（取测试集合）：

```powershell
$env:GOWORK="off"
$env:CGO_ENABLED="0"
$env:GOOS="windows"; $env:GOARCH="amd64"
go list -deps -test -f '{{if .Module}}{{.Module.Path}}|{{.Module.Version}}{{end}}' ./...
```

- **目标平台**：`windows/amd64`、`linux/amd64`、`linux/arm64`、`darwin/amd64`、`darwin/arm64`，与 `sidecar-runtime/build.gradle.kts` 中 `sidecarPlatformIds` 的 5 个随包目标一致。
- **工具链**：`go version go1.26.4 windows/amd64`；两个 module 的 `go` 指令均为 `1.26.0`。非 Windows 目标仅做交叉编译依赖枚举，未在对应平台运行。
- 上述命令只枚举 Go 编译依赖；未运行 Gradle，也未对 Java/Gradle 侧依赖作结论。
- 本清单来自实际编译/测试选择，不是 `go list -m all` 的全量 transitive 列表；未被当前构建选中的模块不列入。上表模块均已在 `sidecar/licenses/` 有对应版本的许可文件，本次未发现需要新增许可的新模块。

## 当前模块清单

| 模块 | 版本 | 许可标识 | 许可文件 | 当前构建范围 |
|---|---|---|---|---|
| github.com/andybalholm/brotli | v1.1.0 | MIT | [LICENSE](licenses/github.com/andybalholm/brotli@v1.1.0/LICENSE) | 运行（5 平台）+ core 测试 |
| github.com/apernet/quic-go | v0.63.1-0.20261004180939-a10df75c260c | MIT | [LICENSE](licenses/github.com/apernet/quic-go@v0.63.1-0.20261004180939-a10df75c260c/LICENSE) | 运行（5 平台）+ core 测试 |
| github.com/klauspost/compress | v1.17.9 | BSD-3-Clause | [LICENSE](licenses/github.com/klauspost/compress@v1.17.9/LICENSE)、[internal/snapref/LICENSE](licenses/github.com/klauspost/compress@v1.17.9/internal/snapref/LICENSE)（BSD-3-Clause） | 运行（5 平台）+ core 测试 |
| github.com/quic-go/qpack | v0.6.0 | MIT | [LICENSE.md](licenses/github.com/quic-go/qpack@v0.6.0/LICENSE.md) | 运行（5 平台）+ core 测试 |
| github.com/refraction-networking/utls | v1.8.2 | BSD-3-Clause | [LICENSE](licenses/github.com/refraction-networking/utls@v1.8.2/LICENSE)、[dicttls/LICENSE](licenses/github.com/refraction-networking/utls@v1.8.2/dicttls/LICENSE)（BSD-3-Clause） | 运行（5 平台）+ core 测试 |
| golang.org/x/crypto | v0.54.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/crypto@v0.54.0/LICENSE)、[PATENTS](licenses/golang.org/x/crypto@v0.54.0/PATENTS) | 运行（5 平台）+ core 测试 |
| golang.org/x/exp | v0.0.0-20240506185415-9bf2ced13842 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/exp@v0.0.0-20240506185415-9bf2ced13842/LICENSE)、[PATENTS](licenses/golang.org/x/exp@v0.0.0-20240506185415-9bf2ced13842/PATENTS) | 运行（5 平台）+ core 测试 |
| golang.org/x/net | v0.57.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/net@v0.57.0/LICENSE)、[PATENTS](licenses/golang.org/x/net@v0.57.0/PATENTS) | 运行（5 平台）+ core 测试 |
| golang.org/x/sys | v0.47.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/sys@v0.47.0/LICENSE)、[PATENTS](licenses/golang.org/x/sys@v0.47.0/PATENTS) | 运行（5 平台）+ core 测试 |
| golang.org/x/text | v0.40.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/text@v0.40.0/LICENSE)、[PATENTS](licenses/golang.org/x/text@v0.40.0/PATENTS) | 运行（5 平台）+ core 测试 |
| github.com/stretchr/objx | v0.5.3 | MIT | [LICENSE](licenses/github.com/stretchr/objx@v0.5.3/LICENSE) | 仅 core 测试 |
| github.com/stretchr/testify | v1.12.1 | MIT | [LICENSE](licenses/github.com/stretchr/testify@v1.12.1/LICENSE)、[internal/difflib/LICENSE](licenses/github.com/stretchr/testify@v1.12.1/internal/difflib/LICENSE)（BSD-3-Clause）、[internal/spew/LICENSE](licenses/github.com/stretchr/testify@v1.12.1/internal/spew/LICENSE)（ISC） | 仅 core 测试 |
| go.uber.org/goleak | v1.3.0 | MIT | [LICENSE](licenses/go.uber.org/goleak@v1.3.0/LICENSE) | 仅 core 测试 |
| go.yaml.in/yaml/v3 | v3.0.5 | MIT + Apache-2.0 | [LICENSE](licenses/go.yaml.in/yaml/v3@v3.0.5/LICENSE)、[NOTICE](licenses/go.yaml.in/yaml/v3@v3.0.5/NOTICE) | 仅 core 测试 |

## 运行依赖来源与裁剪状态

- `github.com/apernet/quic-go` 是 QUIC/HTTP3 传输的直接依赖；`golang.org/x/crypto`、`golang.org/x/net`、`golang.org/x/sys`、`golang.org/x/text` 随其 TLS/HTTP3 与平台适配代码进入运行集合。
- `github.com/refraction-networking/utls`、`github.com/andybalholm/brotli`、`github.com/klauspost/compress` 由固定版本 `apernet/quic-go` 的 `internal/handshake` 传递引入；`github.com/quic-go/qpack` 由 `quic-go/http3` 引入。**即使产品已移除 Chrome/uTLS 指纹功能（见 `PRUNING.md`），这些模块仍在当前二进制的编译依赖图中，不能按“功能已关闭”视为未链接。**
- `golang.org/x/exp` 仅因 `x/exp/constraints` 被 `hy2-core` 的拥塞控制代码（BBR/Brutal）使用而保留。
- `testify`、`objx`、`yaml/v3`、`goleak` 仅出现在 core 的测试构建（包括 `internal/integration_tests/mocks` 测试支撑包）中，不在 `./cmd/hyperconduit-tunnel` 的运行依赖内。

## 历史基线（832a525，非当前依赖）

第 9 步裁剪前的基线清单共 15 个第三方模块：`brotli`、`apernet/quic-go`、`klauspost/compress`、`qpack`、`utls`、`objx`、`testify`、`goleak`、`yaml/v3`、`x/crypto`、`x/exp`、`x/net`、`x/sys`、`x/text`、`x/time`。该表仅作历史对照，不代表当前选择：

| 模块 | 基线（832a525）状态 | 当前状态 |
|---|---|---|
| testify / objx / yaml/v3 | 运行 + 测试（`client`、`server` 中随源码提供的非 `_test.go` mock 文件引入） | 仅 core 测试；不进入 tunnel 二进制 |
| go.uber.org/goleak | 仅测试 | 仅 core 测试 |
| golang.org/x/time | 仅测试 | **当前未链接**（core 已移除该依赖）；[历史许可](licenses/golang.org/x/time@v0.15.0/LICENSE)、[PATENTS](licenses/golang.org/x/time@v0.15.0/PATENTS) 保留，未删除 |

## 特别说明

1. **HY2 自身许可**：`sidecar/hy2-core/LICENSE.md` 为上游 MIT 许可原文，未复制到 `sidecar/licenses/`。本文件仅记录第三方依赖，不构成对 HyperConduit 用户项目整体的许可结论，也不构成任何泛化许可保证。
2. **Go 标准库**：Go 运行时、标准库及标准库内 `vendor/golang.org/x/...` 副本随 Go 工具链分发，不属于上表模块；其许可为 Go 项目的 BSD-3-Clause。随包 jar 已将 `GOROOT/LICENSE` 复制为 `META-INF/hyperconduit-sidecar/GO-LICENSE`（见 `sidecar-runtime/build.gradle.kts` 的 `processResources`）。若以其他方式分发编译后的二进制，也应同时保留 Go 的版权与许可声明。
3. **附加许可**：
   - `github.com/refraction-networking/utls` 主体为 BSD-3-Clause（Go Authors），其实际编译的 `dicttls` 子包另有独立 BSD-3-Clause 许可，已保留。
   - `github.com/klauspost/compress` 主体为 BSD-3-Clause，实际编译的 `internal/snapref`（Snappy-Go 衍生代码）另有 BSD-3-Clause 许可，已保留。
   - `github.com/stretchr/testify` 的 `internal/difflib`（BSD-3-Clause）与 `internal/spew`（ISC）为内嵌第三方代码，已分别保留。
   - `github.com/andybalholm/brotli` 为 Google Brotli 的 Go 移植，根目录 MIT 许可覆盖实际编译的 `brotli` 与 `brotli/matchfinder` 包。
   - `go.yaml.in/yaml/v3` 为 MIT + Apache-2.0 混合许可（libyaml 移植文件为 MIT，其余为 Apache-2.0），已保留 `LICENSE` 与 `NOTICE`。
4. **平台与工具链**：本清单由 `go1.26.4 windows/amd64` 对 5 个目标平台交叉枚举依赖生成；Linux/macOS 目标仅验证编译依赖，未运行。不同平台可能选择不同的平台相关包（例如 `golang.org/x/sys/windows` 与 `golang.org/x/sys/unix`），但对应模块的许可文件相同。
5. **审计边界**：本文件仅覆盖上述 Go module 的模块根许可及随包附加许可，未逐一审计每个 `.go` 源文件的版权头，也不覆盖 Java/Gradle 侧依赖。Apache-2.0 组件的 NOTICE 聚合、最终二进制分发的额外合规文案，建议在发布前由项目方复核；本文件不构成法律意见或完整合规保证。
