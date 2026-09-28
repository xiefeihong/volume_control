---
kind: build_system
name: Gradle Android 构建系统（LSPosed 模块 + 配置 App）
category: build_system
scope:
    - '**'
source_files:
    - settings.gradle
    - build.gradle
    - app/build.gradle
    - gradle.properties
    - gradle/wrapper/gradle-wrapper.properties
    - app/src/main/assets/xposed_init
---

## 1. 构建系统与工具链

项目使用 **Android Gradle Plugin (AGP) 8.7.1** 作为唯一构建系统，通过 Gradle Wrapper (`gradlew` / `gradlew.bat`) 管理。根目录仅声明插件版本，所有工程配置集中在 `app/` 子模块的 `build.gradle` 中，属于典型的单模块 Android 应用结构。

- AGP 版本：`com.android.application` 8.7.1（在根 `build.gradle` 中以 `apply false` 声明，由子模块引入）
- Java 兼容：`sourceCompatibility` / `targetCompatibility` 均设为 `JavaVersion.VERSION_17`
- SDK 目标：`compileSdk 35`、`targetSdk 35`、`minSdk 26`（Android 8.0+，覆盖绝大多数 LSPosed 支持设备）
- 包名：`com.xiefeihong.volumecontrol`

## 2. 关键构建文件与职责

| 文件 | 作用 |
|---|---|
| `settings.gradle` | 定义仓库源（Google、Maven Central、Gradle Plugin Portal），并额外添加 XposedBridge API 专用仓库 `https://api.xposed.info/`；启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自行声明仓库 |
| `build.gradle`（根） | 集中声明 AGP 插件版本 |
| `app/build.gradle` | 模块级全部构建配置：SDK、编译选项、依赖、构建类型、Lint |
| `gradle.properties` | Gradle JVM 参数（`-Xmx2048m`）、并行构建、缓存、AndroidX 开关、非传递 RClass |
| `gradle/wrapper/*` | 固定 Gradle Wrapper 版本，保证构建可重现 |
| `local.properties` | 本地 SDK 路径（未纳入版本控制） |

## 3. 架构与约定

### 3.1 单一 Android Application 模块
仓库只包含一个 `:app` 模块，没有独立的 library 或测试模块。Xposed 模块代码（`XposedEntry.java`、`Avrcp.java`、`Shell.java`、`Prefs.java`）与应用 UI 代码（`MainActivity.java`）打包在同一 APK 中，通过 `assets/xposed_init` 注册入口类供 LSPosed 加载。

### 3.2 依赖管理策略
- 运行时依赖：App 本身仅依赖 `androidx.appcompat` 和 `material`，均为普通 Android 库。
- 编译期依赖：`de.robv.android.xposed:api:82` 使用 `compileOnly` 声明——因为 LSPosed 框架会在运行期提供该 API，APK 内不应再打包它。
- 仓库来源：除标准 Google/Maven Central 外，显式添加 `https://api.xposed.info/` 以获取 XposedBridge API。

### 3.3 构建类型与产物
- 仅定义 `release` 构建类型，且关闭了混淆（`minifyEnabled false`），便于调试 LSPosed Hook 行为。
- 未定义 `debug` 变体自定义，使用 AGP 默认 debug 配置。
- 启用了 ViewBinding 与 BuildConfig 生成。
- Lint 设置为 `abortOnError false`，不阻断构建。

### 3.4 版本与发布约定
- `versionCode 1`、`versionName "1.0"` 位于 `defaultConfig` 中，当前为硬编码初始值，未见自动化递增逻辑。
- 无 CI 脚本、无签名配置、无多渠道/多 flavor 配置。
- 产物为单个 APK（未配置 AAB 输出），直接用于安装到已 root 的设备并通过 LSPosed 激活。

## 4. 约束与规则

- **仓库白名单**：`settings.gradle` 中 `dependencyResolutionManagement.repositoriesMode.set(FAIL_ON_PROJECT_REPOS)` 强制禁止在子模块 `build.gradle` 中再次声明仓库，所有依赖解析必须通过根级统一配置。
- **Java 版本锁定**：编译与目标字节码均锁定为 Java 17，与 AGP 8.7.1 要求一致。
- **AndroidX 强制**：`android.useAndroidX=true` 与 `android.nonTransitiveRClass=true` 确保使用 AndroidX 并启用非传递 RClass 优化。
- **Xposed API 仅编译期引用**：通过 `compileOnly` 而非 `implementation` 引用 XposedBridge API，避免将框架 API 打入最终 APK。
- **最低系统版本**：`minSdk 26` 是硬性约束，低于 Android 8.0 的设备无法安装此模块。
- **构建可重现性**：通过 Gradle Wrapper 锁定 Gradle 版本，并使用 `org.gradle.caching=true` 加速重复构建。

## 5. 缺失项说明

仓库中未发现以下构建系统常见工件：Dockerfile、Makefile、CI/CD 配置文件（如 `.github/workflows/`、`.gitlab-ci.yml`）、签名脚本、自动化发布脚本、Changelog 或版本递增脚本。因此本项目的构建流程完全依赖本地 `./gradlew assembleRelease` 手动执行。