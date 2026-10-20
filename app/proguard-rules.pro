# VolumeControl R8/ProGuard 规则
#
# 反射现状：模块的反射全部指向【框架类】(android.os.ServiceManager / android.app.ActivityThread /
# AudioService / VolumeStreamState / AvrcpVolumeManager 等运行时对象)，从不按名反射自身类；
# 自有类均由代码直接引用。故只需保住「被外部按名字加载/回调」的少数入口，其余交给 R8 收缩。

# 1) LSPosed 入口：META-INF/xposed/java_init.list 里按 FQN 实例化并回调其 onXxx 方法，
#    类名与方法都必须原样保留，否则模块无法被框架加载。
-keep class com.xiefeihong.volumecontrol.XposedEntry { *; }

# 2) 所有 Hook 回调实现：由注入进系统进程的框架侧通过 XposedInterface.Hooker 接口在运行期
#    调用 intercept()，保留类及其全部方法（含内部类 AudioHooks$*Hooker / BtHooks$*Hooker）。
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker { *; }

# 3) libxposed api 为 compileOnly（运行期由 LSPosed 注入），R8 解析引用时按缺类处理，抑制告警。
-dontwarn io.github.libxposed.**

# 4) 保留注解/泛型签名/异常信息：AppCompat/Material 及 ViewBinding 运行期依赖之。
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# 5) AndroidX/Material 自带 consumer 规则已覆盖大部分；此处兜底保留自定义 View（曲线图控件
#    在 XML 里以类名实例化，layout inflate 依赖原名）。
-keep class com.xiefeihong.volumecontrol.CurveChartView { <init>(...); }
