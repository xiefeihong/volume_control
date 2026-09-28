---
kind: configuration_system
name: 基于 SharedPreferences + Settings.Global 的双端配置系统
category: configuration_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/xiefeihong/volumecontrol/Prefs.java
    - app/src/main/java/com/xiefeihong/volumecontrol/XposedEntry.java
    - app/src/main/java/com/xiefeihong/volumecontrol/MainActivity.java
    - app/src/main/java/com/xiefeihong/volumecontrol/Shell.java
    - app/src/main/assets/xposed_init
---

## 1. 使用的系统与框架

- **Android SharedPreferences**：App 进程通过 `Context.getSharedPreferences("settings", MODE_PRIVATE)` 读写本地偏好，用于保存启用开关、缩放百分比、媒体流覆盖档位、基准档位数以及“待重启”标记。
- **Settings.Global**：作为 Hook 端（system_server 进程）的权威配置源。App 通过 root shell 调用 `settings put global volume_steps_hook_config ...` 写入，Hook 端通过 `ContentResolver` 读取。
- **XSharedPreferences**：当 Settings.Global 不可用时，Hook 端回退到 LSPosed 模块自身的共享偏好文件（包名 + `settings`），实现跨进程配置同步。
- **LSPosed / Xposed Bridge**：模块入口由 `assets/xposed_init` 声明为 `com.xiefeihong.volumecontrol.XposedEntry`，在 `android` 包加载时 hook `AudioService.createStreamStates()`，将 `MAX_STREAM_VOLUME[]` 按比例缩放。

## 2. 关键文件与职责

| 文件 | 角色 |
|---|---|
| `app/src/main/java/com/xiefeihong/volumecontrol/Prefs.java` | 所有配置键常量、序列化/反序列化（`encodeConfig`/`decodeConfig`）、CSV 工具（`joinInts`/`parseInts`）、取值边界（`PERCENT_MIN/MAX/STEP`、`AVRCP_MAX_VOLUME=127`、`OTHER_STREAM_MAX=150`、`STREAM_COUNT=12`）集中定义；被 App 与 Hook 两端共用，不引用任何 Xposed API。 |
| `app/src/main/java/com/xiefeihong/volumecontrol/XposedEntry.java` | LSPosed 模块入口。hook `AudioService.createStreamStates()`，按配置对 `MAX_STREAM_VOLUME[]` 原地修改；优先读 `Settings.Global`，失败回退 `XSharedPreferences`。 |
| `app/src/main/java/com/xiefeihong/volumecontrol/MainActivity.java` | 配置 UI：采集并缓存原始档位（`KEY_BASELINE`），实时预览缩放效果，写入 SharedPreferences，通过 `Shell.putGlobalConfig` 推送到 system_server，设置 `KEY_PENDING_RESTART` 后延迟 2 秒软重启 system_server。 |
| `app/src/main/java/com/xiefeihong/volumecontrol/Shell.java` | root/system 操作封装：`putGlobalConfig`、`getGlobalConfig`、`restartSystemServer`、`isRootAvailable`、`isLsposedPresent`。 |
| `app/src/main/assets/xposed_init` | LSPosed 模块注册文件，仅一行类名。 |
| `app/src/main/res/values/arrays.xml` | 音频流名称数组，配合 UI 展示各流原始→目标档位映射。 |

## 3. 架构与约定

### 双端配置读取顺序（Hook 端）
1. 从 `AudioService.mContext` 获取 `ContentResolver`，读取 `Settings.Global.getString("volume_steps_hook_config")`。
2. 解析为 `int[]{enabled, percent, mediaOverride}`，非法返回 null。
3. 若为空，则使用 `XSharedPreferences(BuildConfig.APPLICATION_ID, "settings")` 读取 `enabled/percent/media_override`，构造相同结构返回。
4. 仍失败则返回 null，表示保持系统默认。

### 配置持久化路径
- **App 侧**：`SharedPreferences("settings")` 中保存 `enabled`、`scale_percent`、`media_steps_override`、`baseline_max_volumes`、`pending_restart`。
- **系统侧**：`Settings.Global` 键 `volume_steps_hook_config` 值为 `"{enabled};{percent};{mediaOverride}"` 形式的字符串，由 App 经 root shell 写入。
- **基准值**：首次启动时通过 `AudioManager.getStreamMaxVolume(i)` 采集 12 个流的当前上限，以 CSV 形式存入 `baseline_max_volumes`，后续计算统一基于该备份，避免 ROM 升级导致基准漂移。

### 生效机制
- Hook 仅在 `AudioService.createStreamStates()` 执行时应用一次，因此修改后必须重启 system_server 才能生效。
- App 在写入 Global 并校验成功后，设置 `pending_restart=true`，延迟 2000ms 调用 `kill -9 <pid>` 或等效方式重启 system_server。
- 重启完成后，UI 检测 `pending_restart` 标志并清除。

### 数值约束与边界
- 缩放比例范围 `[25, 400]`，步长 5，默认 100（即不修改）。
- 媒体流（STREAM_MUSIC_INDEX=3）额外受 AVRCP 上限 127 限制；其他流上限 150。
- 媒体流可被 `media_steps_override` 完全覆盖为固定值（0 表示跟随缩放）。
- 所有越界取值通过 `Math.max/min` 钳制，数组取值使用 `Prefs.getOr(array, index, default)` 安全访问。

## 4. 约定与约束

- **跨进程共享契约**：`Prefs.java` 是 App 与 Hook 两端的唯一配置契约，新增配置项必须同时更新 `encodeConfig`/`decodeConfig` 及两端读取逻辑。
- **Hook 端禁止依赖 Xposed API 以外的 Android 组件**：`Prefs` 注释明确要求该类不能被 Xposed API 引用，保证可在任意进程编译。
- **配置优先级**：`Settings.Global > XSharedPreferences`，确保 system_server 能读到最新配置。
- **基准值只写不读外部来源**：`baseline_max_volumes` 仅由 App 首次采集写入，Hook 端从不反向读取，避免循环依赖。
- **重启幂等**：`applyAndRestart` 先写 SharedPreferences，再写 Global，再校验，最后才触发重启；校验失败时提示用户而不重启。
- **蓝牙 AVRCP 映射一致性**：App 预览与 Hook 端缩放算法一致，均遵循 AOSP `AvrcpVolumeManager#systemToAvrcpVolume` 的 `round(档位 * 127 / 最大档位)` 换算关系，并在 UI 中单独展示映射详情。