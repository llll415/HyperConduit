# HyperConduit

HyperConduit 是 Minecraft 1.21.1 NeoForge 的 QUIC-like + Brutal-like 隧道模组。它保持 Minecraft 原有协议、登录、认证、白名单和封禁逻辑不变，只替换底层传输通道。

## 当前状态

- Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21。
- 客户端与服务端均安装模组后，可将 Minecraft 字节流放入加密的 QUIC-like 可靠 UDP 隧道。
- 默认允许原版 TCP 与 HyperConduit 同端口共存；可在主机设置中禁止原版 TCP。
- Fabric 尚未实现。

## 使用

1. 主机与玩家安装同一版本的 `hyperconduit-neoforge-0.1.0.jar`。
2. 在多人游戏或“开放局域网联机”界面点击 `HyperConduit…`。
3. 开启 HyperConduit，设置本端发送带宽和控制模式。
4. 玩家仍按原版方式填写服务器地址和端口；不需要填写 PSK、隧道地址或监听地址。

关闭 HyperConduit 时，连接严格回退为原版 TCP。

## 身份与信任

首次作为服务端启动隧道时，模组自动生成并保存服务器身份：

```text
config/hyperconduit-server-identity.json
```

客户端首次连接某个 `host:port` 时自动信任服务器身份，并保存已知服务器指纹：

```text
config/hyperconduit-known-servers.json
```

同一地址后续出现不同身份会被拒绝。可在 `HyperConduit… → 已信任服务器…` 删除旧记录，再重新连接确认新身份。

## 控制模式

带宽和控制器是**逐方向、本端发送侧**的设置：服务端设置影响服务端→玩家，客户端设置影响玩家→服务端。

### Brutal

`Brutal` 是默认模式。它使用配置的目标发送带宽、pacer 和丢包补偿；面对主动 QoS 丢包时不会像 Reno/Cubic 一样因丢包大幅退让，适合本项目的跨运营商晚高峰场景。

### Paced

`Paced` 保留固定目标带宽、可靠重传和 pacer，但关闭 Brutal 的丢包补偿。

它**不是** Reno、Cubic、BBR，也不是 QUIC 的默认拥塞控制算法。它适合不希望因丢包补偿提高实际发送速率的链路。

### BBR 评估

BBR 尚未实现。现有 `CongestionController`、ACK/loss 事件和 `Pacer` 基础足以实现 BBRv1，但还需要 delivery-rate 采样、app-limited 标记、Startup/Drain/ProbeBW/ProbeRTT 状态机、ProbeRTT 压窗和 ACK aggregation 处理。

建议先实现 BBRv1，作为实验性第三选项；不建议直接做 BBRv2。预计工作量约 **8–12 人日**，复杂度高，必须在模拟丢包、突发 ACK、乱序、空闲/恢复及真实跨网链路上与 Brutal/Paced 对比验证。

## 状态查看

客户端按 `F3` 可查看本机状态：

```text
[HyperConduit] [运行中] [Brutal 10 Mbps]
[链路] RTT 12ms  p95(10s) 24ms  丢包(10s) 0.0%  重传 0.0/s
[速率] ↓ 937 B/s  ↑ 32 B/s  抖动(10s) 1ms
```

- `↓ / ↑ / 重传`：当前 1 秒实时值。
- `丢包 / p95 / 抖动`：最近 10 秒窗口。
- 丢包达到 2.5% 显示红色；实时重传大于 0 显示黄色。

命令统一使用完整名称：

```text
/hyperconduit status
```

- 客户端执行：返回与客户端 F3 相同的本机状态。
- 服务端/控制台执行：返回所有隧道玩家和总计。

纯服务端可持续观察：

```text
/hyperconduit watch
/hyperconduit watch stop
```

`watch` 每秒输出一份全服实时状态。

## 配置

普通用户配置文件为 `config/hyperconduit.json`：

```json
{
  "enabled": false,
  "mbps": 10,
  "brutal": true,
  "disableVanillaTcp": false,
  "receiveWindowBytes": 4194304,
  "sendBufferBytes": 4194304
}
```

建议从游戏内设置修改。保存后的传输参数在下一次新连接或下一次开放 LAN 时生效，不热切换已建立会话。

## 构建

```bash
./gradlew :core:test :mod-common:test :neoforge:jar
```

输出：

```text
neoforge/build/libs/hyperconduit-neoforge-0.1.0.jar
```

## 已知限制

- 当前协议版本需要双端使用同一新版 jar；旧版本不能互通。
- 服务器身份变更后，当前实现需要在已信任服务器列表删除旧记录并重新连接；“确认后恢复同一次握手”尚未实现。
- 正常停服会尝试发送带 `[HyperConduit]` 前缀的关闭帧；进程强杀、断电或网络完全不可达时仍只能依赖超时断开。
