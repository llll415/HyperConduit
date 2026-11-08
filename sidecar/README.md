# HyperConduit Go sidecar（源码基线阶段）

此目录将承载 HyperConduit 专用的 Go 隧道程序。目前仅导入了未经修改的 HY2 core，**没有可执行入口、没有 MC 接入，也没有完成固定目标或功能裁剪**。

## 目录

- `hy2-core/`：官方 core 的完整固定快照，包括许可证、测试、mock 和测试证书。
- `UPSTREAM.md`：固定提交、内容校验、测试基线与后续改动记录。
- `THIRD_PARTY_NOTICES.md`、`licenses/`：当前基线所用第三方依赖的许可记录。

没有导入 HY2 的 `app/`、`extras/`、上游 `go.work` 或 Git 元数据。
导入源码内的 UDP、嗅探、伪装接口、浏览器指纹模仿等仍是上游原样实现；不代表这些功能已成为本项目产品能力。

## 本地检查

需要 Go 1.26.0 或更新的兼容工具链。在 PowerShell 中：

```powershell
cd sidecar/hy2-core
$env:GOWORK = 'off'
go mod verify
go test -count=1 -timeout=10m ./...
go vet ./...
```

其他 shell 可使用 `GOWORK=off go test -count=1 -timeout=10m ./...` 等形式。
测试使用本机 TCP/UDP 端口；依赖未缓存时需要联网下载。
测试目录中的 `test.crt` / `test.key` 是上游公开测试夹具，**严禁用作实际服务端身份**。

## 后续接入方式

保留 core 的原始 module 路径 `github.com/apernet/hysteria/core/v2`，便于追踪上游和维护内部 import。
第 2 步建立独立 sidecar module 后，使用本地替换指向此快照：

```go
replace github.com/apernet/hysteria/core/v2 => ./hy2-core
```

现在尚未创建该调用方 module，也没有添加占位版本或空程序。
Java 模块、Gradle 构建和现有游戏行为均未切换到此 core。
