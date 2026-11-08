# 第三方软件许可声明（sidecar/hy2-core）

本文件记录 `sidecar/hy2-core` 在当前源码基线中，经 Go 构建实际选中的第三方模块及其许可文本。许可原文按 `sidecar/licenses/<模块路径>@<版本>/<上游文件名>` 保存，未作删改。

## 生成方法与范围

在 `sidecar/hy2-core` 目录执行：

```powershell
$env:GOWORK="off"
go list -deps -test -f '{{if .Module}}{{.Module.Path}}|{{.Module.Version}}{{end}}' ./...
```

环境：Windows/amd64，Go 1.26.4。上表模块集合来自 `go list -deps -test ./...` 的实际编译/测试选择，不是 `go list -m all` 中全部 transitive 模块的简单罗列；仅出现在其他模块 `go.mod` 中、但未被本仓库构建选中的模块未列入。例如 `go list -m all` 中的 `davecgh/go-spew`、`pmezard/go-difflib` 未列入，因为当前 `testify` 使用其 `internal/spew`、`internal/difflib` 内嵌副本，相应许可已单独保留。

## 模块清单

| 模块 | 版本 | 许可标识 | 许可文件 | 构建范围 |
|---|---|---|---|---|
| github.com/andybalholm/brotli | v1.1.0 | MIT | [LICENSE](licenses/github.com/andybalholm/brotli@v1.1.0/LICENSE) | 运行 + 测试 |
| github.com/apernet/quic-go | v0.63.1-0.20261004180939-a10df75c260c | MIT | [LICENSE](licenses/github.com/apernet/quic-go@v0.63.1-0.20261004180939-a10df75c260c/LICENSE) | 运行 + 测试 |
| github.com/klauspost/compress | v1.17.9 | BSD-3-Clause | [LICENSE](licenses/github.com/klauspost/compress@v1.17.9/LICENSE)、[internal/snapref/LICENSE](licenses/github.com/klauspost/compress@v1.17.9/internal/snapref/LICENSE)（BSD-3-Clause） | 运行 + 测试 |
| github.com/quic-go/qpack | v0.6.0 | MIT | [LICENSE.md](licenses/github.com/quic-go/qpack@v0.6.0/LICENSE.md) | 运行 + 测试 |
| github.com/refraction-networking/utls | v1.8.2 | BSD-3-Clause | [LICENSE](licenses/github.com/refraction-networking/utls@v1.8.2/LICENSE)、[dicttls/LICENSE](licenses/github.com/refraction-networking/utls@v1.8.2/dicttls/LICENSE)（BSD-3-Clause） | 运行 + 测试 |
| github.com/stretchr/objx | v0.5.3 | MIT | [LICENSE](licenses/github.com/stretchr/objx@v0.5.3/LICENSE) | 运行 + 测试 |
| github.com/stretchr/testify | v1.12.1 | MIT | [LICENSE](licenses/github.com/stretchr/testify@v1.12.1/LICENSE)、[internal/difflib/LICENSE](licenses/github.com/stretchr/testify@v1.12.1/internal/difflib/LICENSE)（BSD-3-Clause）、[internal/spew/LICENSE](licenses/github.com/stretchr/testify@v1.12.1/internal/spew/LICENSE)（ISC） | 运行 + 测试 |
| go.uber.org/goleak | v1.3.0 | MIT | [LICENSE](licenses/go.uber.org/goleak@v1.3.0/LICENSE) | 仅测试 |
| go.yaml.in/yaml/v3 | v3.0.5 | MIT + Apache-2.0 | [LICENSE](licenses/go.yaml.in/yaml/v3@v3.0.5/LICENSE)、[NOTICE](licenses/go.yaml.in/yaml/v3@v3.0.5/NOTICE) | 运行 + 测试 |
| golang.org/x/crypto | v0.54.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/crypto@v0.54.0/LICENSE)、[PATENTS](licenses/golang.org/x/crypto@v0.54.0/PATENTS) | 运行 + 测试 |
| golang.org/x/exp | v0.0.0-20240506185415-9bf2ced13842 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/exp@v0.0.0-20240506185415-9bf2ced13842/LICENSE)、[PATENTS](licenses/golang.org/x/exp@v0.0.0-20240506185415-9bf2ced13842/PATENTS) | 运行 + 测试 |
| golang.org/x/net | v0.57.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/net@v0.57.0/LICENSE)、[PATENTS](licenses/golang.org/x/net@v0.57.0/PATENTS) | 运行 + 测试 |
| golang.org/x/sys | v0.47.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/sys@v0.47.0/LICENSE)、[PATENTS](licenses/golang.org/x/sys@v0.47.0/PATENTS) | 运行 + 测试 |
| golang.org/x/text | v0.40.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/text@v0.40.0/LICENSE)、[PATENTS](licenses/golang.org/x/text@v0.40.0/PATENTS) | 运行 + 测试 |
| golang.org/x/time | v0.15.0 | BSD-3-Clause | [LICENSE](licenses/golang.org/x/time@v0.15.0/LICENSE)、[PATENTS](licenses/golang.org/x/time@v0.15.0/PATENTS) | 仅测试 |

构建范围说明：`testify`、`objx` 因 `client`、`server` 中随源码提供的非 `_test.go` mock 文件而进入运行依赖；`yaml` 由 `testify/assert/yaml` 引入；`x/exp/constraints` 由拥塞控制代码引入。`goleak` 与 `x/time` 仅在测试构建中出现。

## 特别说明

1. **依赖范围**：本清单建立于原样基线 `832a525`；第 2 步加入自有入口和 core 定制，未升级上表第三方模块。UDP、Chrome/uTLS、brotli 等相关依赖尚未裁剪，因此本清单**不代表最终二进制的依赖清单**。发布前须按实际入口和目标平台复核。
2. **HY2 自身许可**：`sidecar/hy2-core/LICENSE.md` 为上游 MIT 许可原文，未复制到 `sidecar/licenses/`。本文件仅记录第三方依赖，不构成对 HyperConduit 用户项目整体的许可结论，也不构成任何泛化许可保证。
3. **Go 标准库**：Go 运行时、标准库及标准库内 `vendor/golang.org/x/...` 副本随 Go 工具链分发，不属于上表模块；其许可为 Go 项目的 BSD-3-Clause。若分发编译后的二进制，应同时保留 Go 的版权与许可声明。
4. **附加许可**：
   - `github.com/refraction-networking/utls` 主体为 BSD-3-Clause（Go Authors），其实际编译的 `dicttls` 子包另有独立 BSD-3-Clause 许可，已保留。
   - `github.com/klauspost/compress` 主体为 BSD-3-Clause，实际编译的 `internal/snapref`（Snappy-Go 衍生代码）另有 BSD-3-Clause 许可，已保留。
   - `github.com/stretchr/testify` 的 `internal/difflib`（BSD-3-Clause）与 `internal/spew`（ISC）为内嵌第三方代码，已分别保留。
   - `github.com/andybalholm/brotli` 为 Google Brotli 的 Go 移植，根目录 MIT 许可覆盖实际编译的 `brotli` 与 `brotli/matchfinder` 包。
   - `go.yaml.in/yaml/v3` 为 MIT + Apache-2.0 混合许可（libyaml 移植文件为 MIT，其余为 Apache-2.0），已保留 `LICENSE` 与 `NOTICE`。
5. **平台差异**：上表在 Windows/amd64 下生成；其他 GOOS/GOARCH 可能选择不同的平台相关包（例如 `golang.org/x/sys/windows` 与 `golang.org/x/sys/unix`），但对应模块的许可文件相同。发布多平台二进制时应按目标平台复核。
6. **审计边界**：本清单以模块根许可文件及随包附加许可为准，未逐一审计每个 `.go` 源文件的版权头；Apache-2.0 组件的 NOTICE 聚合、最终二进制分发的额外合规文案，建议在发布前由项目方复核。
