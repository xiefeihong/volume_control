# 音量阶数（VolumeControl）

Android 媒体音量档位自定义 LSPosed 模块，支持 16~29 档可调，并提供两种蓝牙耳机音量控制模式，解决蓝牙音量「低档位无声」与「相邻档位听感相同」的常见问题。

## 功能特性

- **媒体档位自定义**：16~29 档可选（系统默认通常 15 或 30），仅影响媒体流（音乐/视频），其他音频流不受影响
- **模式A · 保持绝对音量**：手机档位经低音量增强曲线（√ 映射）映射为 0~127 的 AVRCP 绝对音量发送给耳机，低音量区每档间距更大，避免「5% 与 10% 听感相同」与「首档无声」
- **模式B · 停用绝对音量**：Hook 蓝牙栈停用绝对音量，由手机端直接衰减音频信号，耳机固定自身硬件音量，从根本上避免档位重复与低档无声
- **音量范围可调**：「最小」抬高第 1 档（低档听不见时调到 8~20），「最大」限制最高音量
- **实时预览**：切换模式或调整参数时即时显示映射效果与各档位对应值
- **五重档位保险**：属性拦截 + 静态数组改写 + createStreamStates 改写 + 实例校验校正 + post-fs-data 开机脚本，确保档位数修改可靠生效

## 系统要求

- Android 8.0+（minSdk 26）
- 已 Root 设备（su 授权）
- LSPosed（libxposed API 102+）已安装并激活

## 安装与使用

1. 编译安装本应用（APK）
2. 在 LSPosed 中启用本模块（作用域已通过 `staticScope` 静态声明，无需手动勾选）
3. 打开应用，启用模块开关，调整所需参数
4. 修改媒体档位数后点「重启系统框架」生效（屏幕短暂黑屏后自动恢复）
5. 切换蓝牙模式或调整音量范围后点「重启蓝牙」生效

## 两种模式对比

| | 模式A（默认） | 模式B |
|---|---|---|
| 原理 | 保持绝对音量，曲线映射 AVRCP | 停用绝对音量，手机端软件衰减 |
| 适用场景 | 耳机支持绝对音量、档位不重复 | 耳机内部档位少（8~19），低音量问题严重 |
| 耳机音量控制 | 耳机按键可控制，同步显示音量 | 耳机按键控制自身硬件音量，不同步显示 |
| 低音量表现 | √ 曲线增强，间距加大 | 完全由手机曲线控制，无耳机量化干扰 |

## 项目结构

```
app/src/main/java/com/xiefeihong/volumecontrol/
├── MainActivity.java     # 主界面：参数设置、状态检测、预览展示
├── Prefs.java            # 配置常量与序列化/反序列化（App 与 Hook 端共用）
├── Avrcp.java            # AVRCP 绝对音量映射计算与预览文本生成
├── XposedEntry.java      # LSPosed 模块入口（libxposed API 102）
├── XposedKit.java        # 共享基础层：日志、反射工具、配置读取、Context 解析
├── AudioHooks.java       # 系统框架侧 Hook：档位保险 + 模式B软件衰减 + 绝对音量压制
├── BtHooks.java          # 蓝牙进程侧 Hook：模式A曲线替换 + 模式B设备上报拦截
└── Shell.java            # root shell 执行工具：配置写入、蓝牙/系统重启、日志读取
```

## 技术栈

- **语言**：Java 17
- **构建**：Gradle 8.7.1 / AGP 8.7.1 / compileSdk 35
- **框架**：libxposed API 102（LSPosed 新 API，编译期依赖，运行期由框架注入）
- **界面**：AndroidX AppCompat + Material3（DayNight 主题，ViewBinding）
- **作用域**：静态声明（`staticScope=true`），目标 `android`（系统框架）+ `com.android.bluetooth`（蓝牙）

## 构建

```bash
./gradlew assembleDebug
```

输出 APK 位于 `app/build/outputs/apk/debug/`。

## 配置格式

配置以 `启用;媒体档位;蓝牙模式;最小音量;最大音量` 格式写入 `Settings.Global`（键名 `volume_steps_hook_config`），同时以镜像文件备份到 `/data/system/volumecontrol_config`，供 Hook 端在 SettingsProvider 未就绪时读取。

## 许可证

本项目仅供学习与个人使用。
