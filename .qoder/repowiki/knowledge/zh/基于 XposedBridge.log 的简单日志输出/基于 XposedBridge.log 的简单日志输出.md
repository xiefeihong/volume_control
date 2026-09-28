---
kind: logging_system
name: 基于 XposedBridge.log 的简单日志输出
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/xiefeihong/volumecontrol/XposedEntry.java
---

## 1. 使用的系统/方案

本仓库是一个 LSPosed 模块，仅通过 `de.robv.android.xposed.XposedBridge.log()` 进行日志输出，没有引入任何第三方日志框架（如 SLF4J、Logback、Timber 等），也没有自定义 Logger 封装。所有日志均直接调用 `XposedBridge.log(String)`。

## 2. 关键文件

- `app/src/main/java/com/xiefeihong/volumecontrol/XposedEntry.java`：唯一使用日志的文件。定义静态常量 `TAG = "VolumeSteps: "`，在 hook 生命周期与配置读取路径中输出调试信息。
- 其他 Java 源文件（`MainActivity.java`、`Avrcp.java`、`Prefs.java`、`Shell.java`）均未包含任何日志调用。

## 3. 架构与约定

- **单一入口**：所有日志集中在 `XposedEntry` 类中，由 LSPosed 模块入口方法 `handleLoadPackage` 触发，用于记录 hook 是否成功、缩放逻辑是否执行以及配置读取结果。
- **日志格式**：采用“固定 TAG + 空格 + 描述性消息”的拼接方式，例如：
  - `"hook failed: " + t`
  - `"apply scaling failed: " + t`
  - `"scaled: " + Arrays.toString(...) + " (percent=..., mediaOverride=...)"`
- **无日志级别**：代码中没有区分 INFO/WARN/ERROR/DEBUG 等级别，所有 `XposedBridge.log` 调用都是同一种调用形式，无法按级别过滤。
- **无结构化字段**：日志是纯字符串拼接，未使用 JSON、键值对或任何结构化格式；虽然部分消息会附带数组、百分比等上下文，但它们是文本嵌入而非独立字段。
- **异常处理中的日志**：在 `beforeHookedMethod`、`applyScaling`、`readConfig` 的 catch 块中捕获 `Throwable` 后统一通过 `XposedBridge.log` 输出，避免异常被吞掉。

## 4. 约定与约束

- **仅依赖 XposedBridge.log**：仓库内所有日志输出均通过 `XposedBridge.log`，未见其他日志 API。
- **TAG 集中管理**：日志前缀通过类级常量 `TAG = "VolumeSteps: "` 统一管理，便于在 logcat 中过滤。
- **无日志开关/等级控制**：代码中没有实现任何运行时开关来启用/禁用日志，也没有根据构建类型（debug/release）切换日志级别。
- **无 sink/输出目标配置**：日志直接交由 Xposed/LSPosed 框架写入 system_server 的日志通道，应用层不配置输出目的地。
- **作用域限制**：由于该模块运行在 system_server 进程（target package 为 `android`），日志输出受系统框架进程的日志策略影响，不在 Android App 进程内。

总结：该项目没有独立的日志子系统，仅以 `XposedBridge.log` 做最基础的字符串式调试输出，不具备结构化、分级、可配置等现代日志系统的特征。