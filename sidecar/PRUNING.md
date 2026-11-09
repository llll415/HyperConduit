# 第 9 步裁剪记录

基于官方固定快照及第 2–8 步补丁裁剪，不从其他本地 HY2 工作区导入代码。原始内容可通过 `832a525:sidecar/hy2-core` 查看；后续升级按 `UPSTREAM.md` 的固定提交差异流程进行。

## 已移除

| 范围 | 裁剪内容 | 保留的回归目的 |
| --- | --- | --- |
| `client/udp.go`、`server/udp.go` | 应用 UDP session、地址检查及转发 API | TCP 字节流、固定目标拒绝、鉴权前不拨后端 |
| `internal/frag/`、协议 UDPMessage | 应用 UDP 分片、重组、编解码 | TCP 长度/状态/短写/截断/非法请求 |
| `RequestHook` | 请求嗅探、目标重写、fast-open putback | 认证后按原服务标识拨号，取消及超时 |
| `MasqHandler` | 可配置伪装与其他 HTTP 路由 | 鉴权失败/未知路由固定 404；独立能力发现仍可用 |
| Chrome parroting | 指纹配置、零长度 CID 伪装 | 默认标准 QUIC 握手与 TCP echo |
| `internal/protocol/padding.go` | 随机应用 padding 与鉴权 padding header | TCP framing 尾部长度固定为零，非零拒绝 |
| `client/reconnect.go` | 未使用的自动重连封装 | 失败直接退出，不自动重连或 TCP fallback |
| UDP 专用 mock/test/generator 项 | 对已移除产品能力的测试夹具 | TCP echo、日志、压力、取消、关闭和带宽协商测试保留 |

## 保留边界

- 外层仍为 QUIC/UDP；没有删除 QUIC 标准 padding、PMTUD、流控或 QUIC/TLS 实现。
- HTTP/3 鉴权、HY2 原生带宽协商、BBR/Brutal/Reno 保留；`Hysteria-UDP` 仍明确返回 false。
- TCP framing 的类型、地址、状态、消息及尾部长度字段保留；writer 不再产生应用 padding，reader 拒绝非零值。此开发分支不承诺与原 HY2 或裁剪前开发构建互通，双方须同版本。
- `TrafficLogger`、TCP 事件、连接 observer 和实际 QUIC 统计保留。UDP 专用事件和 Hook 状态已删除。
- core `Outbound` 只保留 TCP 抽象；产品的固定目标限制仍由 `sidecar/internal/tunnel/fixedOutbound` 执行，不以测试用通用出站作为产品入口。
- 后端拨号失败只向远端返回固定 `target unavailable`，详细本地错误仅通过保留的诊断回调提供，不回显目标/系统错误。
- `golang.org/x/time` 不再由本地 core 使用，已从其 module 清单移除。`utls` 等仍由固定 quic-go 依赖，不为删 Chrome 产品选项而 fork 底层依赖；相应许可继续保留。

## 尚未删除

旧 Java `core/`、旧身份存储源码及参考测试暂留。用户已决定最后统一做实机验收，当前不以自动回归代替游戏验证，也不提前删除最后的参考路径。不长期维护双后端，实际 MC 入口始终是新 sidecar。

## 验证

仅编译、单测与受控本机自动化测试，不启动 MC 客户端/专用服、不操作桌面。实际结果见 `HY2CORE_PLAN.md` 第 9 步记录；被裁剪的 UDP/Hook/伪装测试不再计入，测试数量下降不代表跳过仍在产品范围内的回归。
