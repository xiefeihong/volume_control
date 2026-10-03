package com.xiefeihong.volumecontrol;

import android.content.Context;

import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * SystemUI 进程（com.android.systemui）侧：音量键绕过定位诊断（第一步）。
 *
 * <p>实测（在 AudioService#setStreamVolume 同步抓栈）表明：拖滑条之后，MIUI/HyperOS 把音量键
 * 也经 SystemUI → {@code AudioManager#setStreamVolume(绝对档位)} 通过 Binder 下发，与拖动共用
 * 同一入口，无法在 system_server 侧只补按键（见日志 {@code key origin (setStreamVolume)} 栈：
 * {@code setStreamVolumeWithAttribution → IAudioService$Stub.onTransact}）。</p>
 *
 * <p>本类在 SystemUI 进程 hook {@code AudioManager#setStreamVolume}，抓其<b>同步调用栈</b>：
 * 拖动会显示音量对话框进度条回调（SeekBar / onProgressChanged 一类），按键会显示对话框的按键
 * 处理方法（onKeyDown / handleVolumeKey 一类），据栈即可区分并定位「只属于按键」的方法，供下一步
 * 把网格吸附精确补到该方法上（完全不触碰拖动，保留全精度）。当前仅读日志、放行原实现，不改行为。</p>
 */
final class SystemUiHooks {

    /** SystemUI 作用域包名。 */
    static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private static final String AUDIO_MANAGER_CLASS = "android.media.AudioManager";
    private static final String METHOD_SET_STREAM_VOLUME = "setStreamVolume";

    /** 抓栈节流时间戳（纳秒）：避免拖动高频刷屏。 */
    private static long sLastOriginLogNanos;

    private SystemUiHooks() {
    }

    /** 在 SystemUI 进程加载 setStreamVolume 调用来源诊断 Hook。 */
    static void install(ClassLoader classLoader, XposedModule module) {
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
