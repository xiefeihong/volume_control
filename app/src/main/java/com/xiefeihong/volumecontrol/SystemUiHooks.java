package com.xiefeihong.volumecontrol;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * SystemUI 进程（com.android.systemui）侧：音量键入口定位（发现阶段）。
 *
 * <p>实测（在 AudioService#setStreamVolume 同步抓栈）表明：拖滑条之后，MIUI/HyperOS 把音量键
 * 也经 SystemUI → {@code AudioManager#setStreamVolume(绝对档位)} 通过 Binder 下发，与拖动共用
 * 同一入口（见日志 {@code key origin (setStreamVolume)}：{@code setStreamVolumeWithAttribution →
 * IAudioService$Stub.onTransact}）。在 SystemUI 内抓栈又因 ART 跨 hook/deopt 边界而塌缩到
 * {@code HandlerThread.run}，无法用栈回溯出按键方法名。</p>
 *
 * <p>因此本类改用「主动发现」：加载 SystemUI 音量对话框相关类（{@link #VOLUME_ROOTS} 及其实例内部类），
 * 一次性<b>打印其声明方法清单</b>，并对名字命中「按键/音量调节」特征（{@link #looksKeyMethod}）的方法
 * 挂上<b>记录型 Hook</b>——用户按一次音量键后，日志里出现 {@code su key-candidate fired: 类#方法}
 * 的那个方法即为音量键专属入口，下一轮把网格吸附精确补到它上（完全不触碰拖动，保留全精度）。
 * 本阶段仅读日志、放行原实现，不改任何音量行为。</p>
 */
final class SystemUiHooks {

    /** SystemUI 作用域包名。 */
    static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private static final String AUDIO_MANAGER_CLASS = "android.media.AudioManager";
    private static final String METHOD_SET_STREAM_VOLUME = "setStreamVolume";

    /**
     * SystemUI 音量对话框相关类探测根（AOSP 基类 + 常见 MIUI/HyperOS 派生命名）。
     * 加载成功者会扫描其声明方法与内部类；找不到的静默跳过。
     */
    private static final String[] VOLUME_ROOTS = {
            "com.android.systemui.volume.VolumeDialogImpl",
            "com.android.systemui.volume.VolumeDialogImplEx",
            "com.android.systemui.volume.VolumeDialogComponentImpl",
            "com.android.systemui.volume.VolumeUIHandler",
            "com.android.systemui.volume.VolumePanelImpl",
            "com.android.systemui.volume.VolumePanelViewController",
            "com.android.systemui.volume.VolumeSliderControllerImpl",
    };

    /** 方法名特征：疑似「音量键 / 增减音量 / 按键分发」入口（命中则挂记录 Hook）。 */
    private static final String[] KEY_METHOD_TOKENS = {
            "onkey", "dispatchkey", "handlekey", "handlevolume", "volumekey",
            "processvolume", "increasevolume", "decreasevolume", "adjustvolume",
            "changevolume", "onvolumebutton", "volumebutton", "keygesture",
            "onvolumetouch", "volumeup", "volumedown",
    };

    /** 抓栈节流时间戳（纳秒）：避免拖动高频刷屏。 */
    private static long sLastOriginLogNanos;
    /** 记录型 Hook 触发日志节流（纳秒）。 */
    private static long sLastCandidateFiredNanos;
    /** 方法清单只在首次 install 打印一次。 */
    private static boolean sLandscapeDumped;

    private SystemUiHooks() {
    }

    /** 在 SystemUI 进程：挂 setStreamVolume 来源诊断 + 主动发现音量键入口方法。 */
    static void install(ClassLoader classLoader, XposedModule module) {
        // 1) setStreamVolume 来源诊断（确认媒体档位变更确由 SystemUI 发起）
        hookSetStreamVolumeOrigin(classLoader, module);
        // 2) 音量键入口发现（打印方法清单 + 对疑似按键方法挂记录 Hook）
        discoverVolumeKeyEntries(classLoader, module);
    }

    private static void hookSetStreamVolumeOrigin(ClassLoader classLoader, XposedModule module) {
        final Class<?> audioManager;
        try {
            audioManager = classLoader.loadClass(AUDIO_MANAGER_CLASS);
        } catch (Throwable t) {
            XposedKit.logError("systemui AudioManager not found: " + t);
            return;
        }
        int hooked = XposedKit.hookAllMethodsNamed(module, audioManager,
                METHOD_SET_STREAM_VOLUME, new SetStreamVolumeOriginHooker());
        XposedKit.log("systemui " + AUDIO_MANAGER_CLASS + "#" + METHOD_SET_STREAM_VOLUME
                + " hooked: " + hooked + " (volume-key origin diag)");
    }

    /** 遍历候选音量类，打印方法清单并对疑似按键方法挂记录 Hook；仅在首次 install 执行。 */
    private static void discoverVolumeKeyEntries(ClassLoader classLoader, XposedModule module) {
        if (sLandscapeDumped) {
            return;
        }
        sLandscapeDumped = true;
        for (String root : VOLUME_ROOTS) {
            final Class<?> clazz;
            try {
                clazz = classLoader.loadClass(root);
            } catch (Throwable t) {
                continue;   // 该 ROM 无此类，跳过
            }
            XposedKit.log("su volume class present: " + clazz.getName());
            scanAndHook(module, clazz, 0);
        }
    }

    /** 扫描一个类及其（有限层级的）内部类：打印方法名，挂疑似按键方法的记录 Hook。 */
    private static void scanAndHook(XposedModule module, Class<?> clazz, int depth) {
        // 打印本类声明方法（含内部类），便于人工识别按键入口命名
        StringBuilder names = new StringBuilder("su methods[" + clazz.getName() + "]: ");
        final Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            XposedKit.logError("su getDeclaredMethods failed for " + clazz.getName() + ": " + t);
            return;
        }
        boolean first = true;
        for (Method m : methods) {
            if (!first) {
                names.append(", ");
            }
            first = false;
            names.append(m.getName());
            if (looksKeyMethod(m.getName())) {
                hookKeyCandidate(module, clazz, m);
            }
        }
        XposedKit.log(names.toString());

        // 递归内部类（限 2 层，覆盖 $VolumeDialogHandler / $TouchHelper 等按键/拖动回调）
        if (depth < 2) {
            try {
                for (Class<?> inner : clazz.getDeclaredClasses()) {
                    scanAndHook(module, inner, depth + 1);
                }
            } catch (Throwable ignored) {
                // 某些 ROM 反射内部类受限：忽略
            }
        }
    }

    /** 对疑似按键方法挂「记录型」Hooker：命中时打印类#方法+参数，再放行原实现。 */
    private static void hookKeyCandidate(XposedModule module, Class<?> clazz, Method m) {
        try {
            module.hook(m).intercept(new KeyCandidateLogHooker(clazz.getName(), m.getName()));
            XposedKit.log("su key-candidate hooked: " + clazz.getName() + "#" + m.getName());
        } catch (Throwable t) {
            XposedKit.logErrorOnce("su-hook-candidate",
                    "su hook " + clazz.getName() + "#" + m.getName() + " failed: " + t);
        }
    }

    /** 方法名是否命中「音量键 / 增减音量 / 按键分发」特征（忽略大小写）。 */
    private static boolean looksKeyMethod(String methodName) {
        String lower = methodName.toLowerCase(Locale.US);
        for (String token : KEY_METHOD_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 疑似按键方法的记录 Hooker：放行原实现，仅低频打印被调用的类#方法与参数。 */
    private static final class KeyCandidateLogHooker implements XposedInterface.Hooker {
        private final String className;
        private final String methodName;

        KeyCandidateLogHooker(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            long now = System.nanoTime();
            if (now - sLastCandidateFiredNanos >= 200_000_000L) {
                sLastCandidateFiredNanos = now;
                XposedKit.log("su key-candidate fired: " + className + "#" + methodName
                        + " args=" + chain.getArgs());
            }
            return chain.proceed();
        }
    }

    /** setStreamVolume 来源诊断 Hooker：放行原实现，仅在媒体档位落非网格时抓同步栈。 */
    private static final class SetStreamVolumeOriginHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            List<Object> args = chain.getArgs();
            try {
                if (args.size() >= 2 && args.get(0) instanceof Integer
                        && args.get(1) instanceof Integer
                        && (Integer) args.get(0) == Prefs.STREAM_MUSIC_INDEX) {
                    logOriginStackIfOffGrid((Integer) args.get(1), chain.getThisObject());
                }
            } catch (Throwable t) {
                XposedKit.logErrorOnce("su-origin", "systemui setStreamVolume diag error: " + t);
            }
            return chain.proceed();
        }
    }

    /**
     * 媒体档位落到「非按键网格」时，低频（≥4s）抓一次 SystemUI 内的同步调用栈，用于区分
     * 音量键处理与拖动进度回调，定位应当吸附的按键方法名。
     */
    private static void logOriginStackIfOffGrid(int index, Object audioManager) {
        if (index <= 0) {
            return;
        }
        VolumeConfig config = XposedKit.readConfig(resolveContext(audioManager));
        if (config == null || !config.enabled) {
            return;
        }
        int levels = Prefs.clampMediaSteps(config.mediaSteps);
        int segs = Prefs.clampKeySteps(config.keySteps);
        if (levels <= 0 || segs >= levels) {
            return;   // 网格间距<1，逐级即正确
        }
        int nearest = (int) Math.round(index * (double) segs / levels);
        if (Prefs.keyStepLevel(nearest, levels, segs) == index) {
            return;   // 恰在网格上
        }
        long now = System.nanoTime();
        if (now - sLastOriginLogNanos < 4_000_000_000L) {
            return;   // 节流：4s 一次
        }
        sLastOriginLogNanos = now;
        StringBuilder sb = new StringBuilder("su setStreamVolume origin: media idx ")
                .append(index).append('/').append(levels)
                .append(" off-grid (~").append(Math.round(index * 100.0 / levels))
                .append("%). stack:");
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(st.length, 22); i++) {
            sb.append("\n  at ").append(st[i]);
        }
        XposedKit.log(sb.toString());
    }

    /** AudioManager 持有系统 Context（{@code mContext}），用它读 Settings.Global；失败返回 null 走镜像兜底。 */
    private static Context resolveContext(Object audioManager) {
        try {
            Object c = XposedKit.getField(audioManager, "mContext");
            if (c instanceof Context) {
                return (Context) c;
            }
        } catch (Throwable ignored) {
            // 字段名随 ROM 变化时忽略，readConfig 仍会尝试镜像文件兜底
        }
        return null;
    }
}
