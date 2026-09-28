---
kind: error_handling
name: Android LSPosed 模块中的容错式错误处理：吞异常 + 日志回退 + Result 模式
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/xiefeihong/volumecontrol/XposedEntry.java
    - app/src/main/java/com/xiefeihong/volumecontrol/Shell.java
    - app/src/main/java/com/xiefeihong/volumecontrol/MainActivity.java
    - app/src/main/java/com/xiefeihong/volumecontrol/Prefs.java
---

## 1. 整体策略

该仓库是一个基于 LSPosed 的 Android 系统级模块，包含两个运行域：
- **App 进程**（`MainActivity`、`Shell`、`Prefs`）：提供 root 配置界面。
- **system_server 进程**（`XposedEntry`）：Hook `AudioService.createStreamStates()` 并缩放音量档位数组。

整个代码库**没有定义自定义异常类型或统一的错误码体系**。错误处理采用“防御式编程 + 局部捕获 + 降级回退”的模式：在可能抛异常的边界处用 `try/catch` 包裹，将异常记录到日志后返回默认值或空结果，保证上层流程不被中断。

## 2. 关键文件与位置

| 文件 | 职责 | 错误处理方式 |
|---|---|---|
| `XposedEntry.java` | LSPosed Hook 入口，修改 `AudioService.MAX_STREAM_VOLUME` | 所有 Xposed 反射调用均被 `catch (Throwable t)` 包裹，通过 `XposedBridge.log(TAG + ...)` 输出；失败时直接 `return` 保持系统默认行为 |
| `Shell.java` | root shell 执行封装 | 统一使用 `Result` 对象（含 `code`、`output`），异常路径返回 `code == -1` 的结果；超时强制 `destroyForcibly()` 并追加 `[timeout]` 标记 |
| `MainActivity.java` | UI 与状态检测 | 对 `AudioManager` 等系统 API 调用用 `catch (Throwable t)` 返回 0 或 null；线程池关闭时的 `RuntimeException` 被显式 `ignored` |
| `Prefs.java` | 配置序列化/反序列化 | `decodeConfig` / `parseInts` 捕获 `NumberFormatException`，非法输入分别返回 `null` / 空数组 |
| `Avrcp.java` | AVRCP 映射预览 | 无显式异常处理，依赖 `Prefs` 的健壮解析 |

## 3. 架构与约定

### 3.1 Hook 端（system_server）：以“不破坏系统”为第一原则
- `handleLoadPackage` 中 hook 失败（类不存在、方法签名变化）仅记录日志，不影响 system_server 启动。
- `beforeHookedMethod` 内 `applyScaling` 的每次反射调用（读 `mContext`、读 `MAX_STREAM_VOLUME`、写数组元素）都被独立 try-catch 保护。
- 读取配置优先走 `Settings.Global`（system_server 可访问），失败回退到 `XSharedPreferences`，再失败返回 `null`，最终保持 ROM 原始档位。
- 所有日志前缀统一为 `VolumeSteps: `，便于 logcat 过滤。

### 3.2 App 端：Result 模式替代异常传播
`Shell.Result` 是唯一的“错误载体”，字段语义明确：
- `code == 0` → 成功
- `code == -1` → 异常/超时
- `isSuccess()` 作为唯一判断入口

`Shell.exec` 内部将 stdout 和 stderr 合并（`redirectErrorStream(true)`），异常时把 `e.toString()` 放入 `output`，调用方通过检查 `isSuccess()` 决定 UI 提示内容。`restartSystemServer`、`isRootAvailable`、`isLsposedPresent`、`isMagiskPresent` 全部遵循此模式。

### 3.3 UI 层：静默降级
- `readStreamMaxSafe`：捕获 `Throwable`，返回 0，避免崩溃影响预览。
- `buildBluetoothSummary`：捕获 `Throwable`，返回 `null`，UI 显示“未连接”。
- `restartSystemServer`：捕获 `RuntimeException ignored`，因为页面销毁后线程池已关闭属于预期场景。
- 所有耗时操作（root 检测、写入 Settings.Global、重启 system_server）都放在单线程 `ExecutorService` 中，并通过 `Handler(Looper.getMainLooper())` 切回主线程更新 UI。

### 3.4 配置层：解析失败即丢弃
`Prefs.decodeConfig` 对 `;` 分隔的配置字符串做严格校验：长度不足 3、任一字段无法 `Integer.parseInt`，均返回 `null`。`parseInts` 同理，返回空数组。这保证了即使 SharedPreferences 被外部篡改，也不会导致运行时异常。

## 4. 约定与约束

1. **Hook 代码禁止抛出任何异常给 XposedBridge**：所有反射调用必须被 `catch (Throwable t)` 包裹，否则可能导致 system_server 崩溃。
2. **shell 命令结果一律通过 `Shell.Result` 传递**：禁止在 `Shell` 内部抛异常给调用方；调用方通过 `isSuccess()` 分支处理。
3. **用户可见的错误信息来自 `Toast` + 日志**：`MainActivity.applyAndRestart` 在写入失败时将 `Shell.Result.output` 拼接到 Toast 中展示；Hook 端错误仅通过 `XposedBridge.log` 输出，不弹窗。
4. **数值范围在入口处钳制**：百分比使用 `Math.max(PERCENT_MIN, Math.min(PERCENT_MAX, percent))` 限制在 25%–400%，媒体覆盖上限为 `AVRCP_MAX_VOLUME = 127`，其他流上限为 `OTHER_STREAM_MAX = 150`。
5. **配置键集中管理**：`Prefs` 类集中定义所有 SharedPreferences key、Settings.Global key 及常量，避免魔法字符串散落各处。
6. **资源清理**：`Shell.exec` 使用 `try-with-resources` 关闭 `BufferedReader`，并在 `finally` 中确保 `process.destroy()`；`MainActivity.onDestroy` 关闭线程池。

## 5. 缺失与局限

- 没有全局异常处理器（如 `Thread.UncaughtExceptionHandler`）。
- 没有结构化日志框架，仅依赖 `XposedBridge.log` 和 `Toast`。
- 没有重试机制：shell 命令失败一次即返回错误结果。
- 没有区分不同错误类型的枚举或类，仅靠 `Result.code` 和日志文本区分。
