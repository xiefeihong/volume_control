---
kind: dependency_management
name: Android Gradle 依赖管理（Xposed + AndroidX）
category: dependency_management
scope:
    - '**'
source_files:
    - settings.gradle
    - app/build.gradle
    - build.gradle
    - gradle.properties
---

## 1. 使用的系统与工具

- **构建系统**：Android Gradle Plugin (AGP) `8.7.1`，通过根 `build.gradle` 的 `plugins {}` DSL 以版本目录方式声明。
- **仓库源**：`settings.gradle` 中通过 `pluginManagement.repositories` 与 `dependencyResolutionManagement.repositories` 集中声明 `google()`、`mavenCentral()`、`gradlePluginPortal()`，并额外添加 XposedBridge 旧版 API 仓库 `https://api.xposed.info/`。
- **Gradle Wrapper**：项目自带 `gradlew` / `gradlew.bat` 与 `gradle/wrapper/`，由 `gradle-wrapper.properties` 锁定 Gradle 发行版。
- **无 lockfile**：未使用 `gradle.lockfile` 或任何第三方依赖锁定机制；依赖版本硬编码在 `app/build.gradle` 与 `settings.gradle` 中。

## 2. 关键文件

- `settings.gradle`：集中式仓库配置，启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，禁止子模块自行声明仓库。
- `app/build.gradle`：唯一业务模块的依赖声明处。
- `build.gradle`（根）：仅声明 AGP 插件版本。
- `gradle.properties`：JVM 参数、并行、缓存、AndroidX 开关等全局构建属性。

## 3. 架构与约定

### 仓库策略
`settings.gradle` 中 `dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` 强制所有依赖解析走中央仓库表，子模块不得再写 `repositories { ... }`。XposedBridge 旧版 API 单独挂载到 `https://api.xposed.info/`，因为该包不在 Google/Maven Central。

### 依赖分类
`app/build.gradle` 中三类依赖清晰区分：
- `implementation 'androidx.appcompat:appcompat:1.6.1'` — UI 运行时库。
- `implementation 'com.google.android.material:material:1.10.0'` — Material Design 组件。
- `compileOnly 'de.robv.android.xposed:api:82'` — 编译期仅需要的 XposedBridge API，运行期由 LSPosed 框架提供，因此不打入 APK。

### 版本来源
- AGP 版本：根 `build.gradle` 中的 `id 'com.android.application' version '8.7.1'`。
- Android SDK：`app/build.gradle` 中 `compileSdk 35`、`targetSdk 35`、`minSdk 26`。
- Java 兼容：`sourceCompatibility` / `targetCompatibility` 设为 `JavaVersion.VERSION_17`。
- 三方库版本：全部硬编码在 `app/build.gradle` 的 `dependencies {}` 块内，无 BOM、无版本目录（version catalog）。

### 多模块
`settings.gradle` 仅 `include ':app'`，当前为单模块应用，无 library 子模块。

## 4. 约定与约束

- **仓库集中化**：`FAIL_ON_PROJECT_REPOS` 模式确保只有 `settings.gradle` 能声明仓库，子模块无法绕过中央仓库引入私有包。（强制执行于 `settings.gradle`）
- **Xposed 依赖仅编译期**：`compileOnly` 用于 `de.robv.android.xposed:api:82`，避免将 LSPosed 提供的符号打包进 APK；运行期依赖由宿主框架注入。（约定见 `app/build.gradle` 注释）
- **AndroidX 强制开启**：`gradle.properties` 中 `android.useAndroidX=true` 且 `android.nonTransitiveRClass=true`，禁用 support library 迁移路径。（强制执行于 `gradle.properties`）
- **无私有注册表凭据**：未发现 `.gradle/gradle.properties`、`~/.gradle/init.gradle` 或 `local.properties` 中的私有仓库认证配置；XposedBridge 仓库为公开地址。
- **无依赖更新自动化**：未发现 Dependabot、Renovate、`gradle-update` 等自动化工具配置，依赖升级需手动修改版本号。
- **无 vendor 目录**：所有依赖均从远程 Maven 仓库拉取，不存在本地 vendored jar/aar。

## 5. 风险点

- 依赖版本全部硬编码，缺少统一版本管理（如 version catalog），新增依赖时容易版本漂移。
- `compileOnly` 依赖的 XposedBridge API 版本 `82` 较旧，若 LSPosed 升级可能产生 ABI 不兼容，需同步验证。
- 未启用 `gradle.lockfile`，CI 环境可能因网络镜像差异得到不同传递依赖版本。