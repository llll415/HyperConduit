# Java sidecar runtime

独立于 Minecraft 的 Java 21 进程管理模块。当前只打包构建机对应平台的 Go 程序；完整跨平台分发尚未完成。

- `Platform`：选择 OS/架构。
- `BinaryExtractor`：校验 manifest 和 SHA-256，按内容哈希提取程序，设置执行权限。
- `PrivateConfig`：生成独立私有临时配置，关闭后删除；凭据不放命令行。
- `SidecarProcess.startBundled(...)`：校验并启动受管程序。
- `ready()`：等待真实监听地址；取消 future 会停止启动。
- `failure()` / `exit()`：分别提供安全错误分类和进程退出码。
- `close()`：先发送关闭指令，再按超时终止进程及其子进程；可重复调用。

stdout/stderr 持续消费且单行有界；原始 stderr 不保存，避免泄露配置。没有自动重启、静默 TCP 回退或全局单实例锁。
Go 的 `--managed` 模式在 stdin EOF 时退出，用于父 JVM 异常终止后的清理。正常使用必须保留父进程到 sidecar 的 stdin 管道。
临时配置在正常退出后删除；父 JVM 被强杀时可能留下受权限保护的文件，不会在下次启动时复用。配置目录应由运行账户独占管理。

```powershell
.\gradlew.bat :sidecar-runtime:test :neoforge:jar
```

Gradle 需要 Go 1.26；可用 `-PsidecarGo=<路径>` 指定工具链。构建产物和许可证随模块资源打包，不把生成的程序提交到 Git。
MC 拨号、LAN 生命周期、来源地址恢复、探测复用和游戏内界面在后续阶段接入。本模块测试不等同于实际游戏验证。
