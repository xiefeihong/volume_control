---
kind: external_dependency
name: LSPosed / Xposed 框架（系统级 Hook 运行时）
slug: lspose
category: external_dependency
category_hints:
    - framework_behavior
scope:
    - '**'
---

本模块通过 LSPosed（兼容旧版 Xposed API，最低版本 93）以 system_server 作用域 Hook Android 系统 `AudioService#createStreamStates`，在方法执行前按百分比缩放 `MAX_STREAM_VOLUME[]` 静态数组，从而改变各音频流的音量阶数。
- 模块声明位于 `AndroidManifest.xml`：`xposedmodule=true`、`xposedminversion=93`、`xposedsharedprefs=true`，并通过 `@array/xposedscope` 限定作用域为系统框架进程。
- 入口类由 `assets/xposed_init` 指向 `com.xiefeihong.volumecontrol.XposedEntry`。
- 依赖仅编译期引入 `de.robv.android.xposed:api:82`，运行时代码由 LSPosed 提供。
- 配置通道优先写入 `Settings.Global`（键名 `volume_steps_hook_config`），回退到 XSharedPreferences；保存后通过 `kill system_server` 软重启使 AudioService 重新读取档位。