package com.xiefeihong.volumecontrol;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 系统框架（system_server）侧的全部 Hook 与档位保险。
 *
 * <p><b>档位数（五重保险，重启手机立即生效）：</b></p>
 * <ol>
 *   <li>拦截 {@code SystemProperties.getInt("ro.config.media_vol_steps")} —— HyperOS 的
 *       AudioService 初始化读取该属性决定媒体档位数（参考 HyperCeiler）。缓存未就绪时
 *       在回调内现场刷新（v1.8 的缺陷：加载时读不到配置就永不拦截）；</li>
 *   <li>AudioService 构造器开始前改写静态档位数组 MAX_STREAM_VOLUME；</li>
 *   <li>createStreamStates 前再改写一次（ROM 可能在构造器中途按属性重新赋值数组）；</li>
 *   <li>构造完成后（constructor）、开机延迟校正（boot correction）、界面读取
 *       （getStreamMaxVolume 自愈）三处校验实例 mIndexMax，不一致立即校正；</li>
 *   <li>App 保存配置时以 root 写入 post-fs-data 开机脚本，system_server 启动前直接
 *       setprop 真实属性（最强保障，不依赖任何 Hook）。</li>
 * </ol>
 *
 * <p>对 AudioService 构造器与 createStreamStates 执行 {@code deoptimize}，防止调用点
 * 被内联后绕过对 SystemProperties.getInt 的拦截。</p>
 *
 * <p><b>模式B 软件衰减（VolumeStreamState.setStreamVolumeIndex 内计算）：</b></p>
 * <p>Hook {@code VolumeStreamState#setStreamVolumeIndex} 把系统音量档位
 * 按 minAbs~maxAbs 曲线映射降低（低音量增强曲线）；并通过反射恢复
 * mIndexMap 原始档位，避免 getStreamVolume 读回 remapped 二次映射导致音量卡死。</p>
 *
 * <p><b>模式B 保险：</b>{@code avrcpSupportsAbsoluteVolume} 强制上报"不支持"，
 * {@code postSetAvrcpAbsoluteVolumeIndex} 拦截发往蓝牙栈的音量值。
 * 切换模式后需重启蓝牙使 {@code deviceConnected} 重新触发。</p>
 */
final class AudioHooks {

    private static final String METHOD_CREATE_STREAM_STATES = "createStreamStates";
    private static final String METHOD_GET_STREAM_MAX_VOLUME = "getStreamMaxVolume";
    private static final String METHOD_SET_STREAM_VOLUME_INDEX = "setStreamVolumeIndex";
    private static final String METHOD_AVRCP_SUPPORTS_ABS_VOLUME = "avrcpSupportsAbsoluteVolume";
    private static final String METHOD_POST_AVRCP_VOLUME = "postSetAvrcpAbsoluteVolumeIndex";
    /** 音量键步进：接管 adjustStreamVolume，按用户设定的 delta 重算目标级（仅媒体、仅按键）。 */
    private static final String METHOD_ADJUST_STREAM_VOLUME = "adjustStreamVolume";
    private static final String METHOD_GET_STREAM_VOLUME = "getStreamVolume";
    private static final String METHOD_SET_STREAM_VOLUME = "setStreamVolume";
    /** 拖后按键绕过 adjustStreamVolume、经 Binder 下发的服务端同步入口（优先挂此名）。 */
    private static final String METHOD_SET_STREAM_VOLUME_WITH_ATTRIBUTION =
            "setStreamVolumeWithAttribution";
    /** setStreamVolume 若仅有带 callingPackage 的重载时补传的调用方包名（system_server 内调用可过权限）。 */
    private static final String SET_VOLUME_CALLING_PACKAGE = "com.android.systemui";
    private static final String PROP_MEDIA_VOL_STEPS = "ro.config.media_vol_steps";
    private static final String FIELD_MAX_STREAM_VOLUME = "MAX_STREAM_VOLUME";
    private static final String FIELD_STREAM_STATES = "mStreamStates";
    private static final String FIELD_STREAM_TYPE = "mStreamType";
    private static final String FIELD_INDEX_MAX = "mIndexMax";
    private static final String FIELD_AUDIO_SERVICE = "this$0";

    /** AudioService 类（install 时解析，供各 Hooker 使用）。 */
    private static volatile Class<?> sAudioServiceClass;
    /** system_server 最近一次见到的 AudioService 实例。 */
    private static volatile Object sAudioService;
    /**
     * 属性拦截缓存：ro.config.media_vol_steps 的返回值（15~29）；-1 表示
     * 未就绪或模块停用（不拦截）。由配置读取路径与属性回调现场刷新。
     */
    private static volatile int sMediaStepsOverride = -1;

    /** getStreamMaxVolume 自愈：已验证一致的档位数（快路径）。 */
    private static volatile int sVerifiedMaxSteps;
    /** getStreamMaxVolume 自愈：上次慢路径校验时间（毫秒）。 */
    private static volatile long sLastSelfHealCheck;
    private static final long SELF_HEAL_THROTTLE_MS = 5000;

    /** 属性回调内现场刷新的节流间隔（毫秒）：开机早期属性读取高频。 */
    private static volatile long sLastOverrideRefresh;
    private static final long OVERRIDE_REFRESH_THROTTLE_MS = 1000;

    /** 日志节流：避免音量拖动时高频刷屏。 */
    private static int sLastLoggedSystemIndex = -1;
    private static int sLastBlockedIndex = -1;
    private static boolean sPropHitLogged;

    /** 诊断：最近一次音量键接管时间戳；绕过路径日志节流时间戳。 */
    private static volatile long sLastKeyAdjustNanos;
    private static long sLastBypassLogNanos;

    /** ROM 原始档位数组：只在首次调用时备份。 */
    private static int[] sOriginalMaxStreamVolumes;

    private AudioHooks() {
    }

    // ==================== 安装 ====================

    /** 在 system_server 启动关键服务前安装全部系统框架侧 Hook。 */
    static void install(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> audioService = classLoader.loadClass(XposedKit.AUDIO_SERVICE_CLASS);
            sAudioServiceClass = audioService;

            // 第一重保险（重启手机立即生效的关键）：拦截 ro.config.media_vol_steps
            hookMediaStepsProperty(classLoader, module);

            int ctors = XposedKit.hookAllConstructors(module, audioService,
                    new AudioServiceCtorHooker());
            XposedKit.log(audioService.getName() + " constructors hooked: " + ctors);

            // 第三重保险：createStreamStates 前再改写一次数组
            int states = XposedKit.hookAllMethodsNamed(module, audioService,
                    METHOD_CREATE_STREAM_STATES, new CreateStreamStatesHooker());
            XposedKit.log(audioService.getName() + "#" + METHOD_CREATE_STREAM_STATES
                    + " hooked: " + states);

            // 自愈兜底：界面/系统每次读取媒体档位上限时校验，发现未生效立即校正
            int selfHeal = XposedKit.hookAllMethodsNamed(module, audioService,
                    METHOD_GET_STREAM_MAX_VOLUME, new StreamMaxSelfHealHooker());
            XposedKit.log(audioService.getName() + "#" + METHOD_GET_STREAM_MAX_VOLUME
                    + " hooked: " + selfHeal);

            // 防内联：构造器与 createStreamStates 若被 AOT 内联，其中的
            // SystemProperties.getInt 调用会绕过 Hook 拦截；强制解释执行。
            deoptimizeQuietly(module, audioService.getDeclaredConstructors());
            deoptimizeQuietly(module, findMethodsNamed(
                    audioService, METHOD_CREATE_STREAM_STATES));

            hookSoftwareVolumeCurve(classLoader, module);
            hookAbsoluteVolumeSuppression(classLoader, module);
            hookVolumeKeyStep(classLoader, module);
            // 「拖后按键」网格吸附（保守重启）：拖动后 HyperOS 将对话框置「直接落值」态，音量键
            // 改走 setStreamVolumeWithAttribution 绕开 adjust 接管，落点变成 ROM 默认~7%。本 Hook 仅对
            // 【孤立且 Δ 够大（真实按键一整步）】的媒体写入吸附到相邻网格档；密集（拖动）与 Δ 过小
            // （SystemUI 同步/取整回写）一律原样放行，避免之前的 32%↔36% 自激振荡。详见 MediaVolumeGridSnapHooker。
            hookMediaVolumeGridSnap(classLoader, module);

            // 立即读取配置填充属性拦截缓存（此时 SettingsProvider 未就绪，
            // 镜像文件通道可读）；失败由属性回调与开机校正重试。
            int steps = refreshMediaStepsOverride();
            XposedKit.log("media steps override: "
                    + (steps < 0 ? "not ready, will retry on demand" : String.valueOf(steps)));

            scheduleBootCorrection();
            // 诊断：安装完成后立即读取并记录当前配置
            VolumeConfig diagConfig = XposedKit.readConfig(XposedKit.systemServerContext(null));
            XposedKit.log("system_server hooks installed, config=" + diagConfig);
        } catch (Throwable t) {
            XposedKit.logError("hook system server failed: " + t);
        }
    }

    /** 批量 deoptimize（内联防护）；单个失败不影响其余。 */
    private static void deoptimizeQuietly(XposedModule module,
            Executable[] executables) {
        for (Executable executable : executables) {
            try {
                module.deoptimize(executable);
            } catch (Throwable t) {
                XposedKit.logError("deoptimize failed: " + t);
            }
        }
    }

    /** 按名称选取方法（用于精确 deoptimize 目标方法）。 */
    private static Executable[] findMethodsNamed(Class<?> clazz, String name) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> name.equals(m.getName()))
                .toArray(Executable[]::new);
    }

    // ==================== 第一重保险：属性拦截 ====================

    /**
     * 拦截 {@code SystemProperties.getInt("ro.config.media_vol_steps", ...)}：
     * 命中该属性时返回缓存的用户档位数。HyperOS 的 AudioService 初始化通过该
     * 属性读取媒体档位数（默认 20），拦截后 ROM 自身流程即使用正确档位。
     *
     * <p>关键修复（相对 v1.8）：缓存未就绪（-1）时在回调内<b>现场刷新</b>——
     * AudioService 构造读取属性的那一刻才是真正需要配置值的时刻，届时
     * SettingsProvider / 镜像文件往往已可读；v1.8 仅在模块加载时读一次，
     * 冷启动早期读不到就永远错过。刷新带 1 秒节流，就绪后回调只剩
     * volatile 读 + 字符串比较，开销可忽略。</p>
     */
    private static void hookMediaStepsProperty(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> systemProperties = classLoader.loadClass("android.os.SystemProperties");
            int hooked = XposedKit.hookAllMethodsNamed(module, systemProperties,
                    "getInt", new PropGetIntHooker());
            XposedKit.log("SystemProperties#getInt hooked: " + hooked
                    + " (target: " + PROP_MEDIA_VOL_STEPS + ")");
        } catch (Throwable t) {
            XposedKit.logError("hook media steps property failed: " + t);
        }
    }

    /** 属性拦截回调：命中 ro.config.media_vol_steps 时返回用户档位数。 */
    private static final class PropGetIntHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            XposedKit.logOnce("hook-prop-getint", "HOOK fired: SystemProperties#getInt");
            try {
                Object key = chain.getArg(0);
                if (key instanceof String && PROP_MEDIA_VOL_STEPS.equals(key)) {
                    int override = sMediaStepsOverride;
                    if (override < Prefs.MEDIA_STEPS_MIN) {
                        override = refreshMediaStepsOverrideThrottled();
                    }
                    if (override >= Prefs.MEDIA_STEPS_MIN) {
                        if (!sPropHitLogged) {
                            sPropHitLogged = true;
                            XposedKit.log("property hit: " + PROP_MEDIA_VOL_STEPS
                                    + " -> " + override);
                        }
                        return override;
                    }
                }
            } catch (Throwable t) {
                XposedKit.logError("property intercept failed: " + t);
            }
            return chain.proceed();
        }
    }

    /** 读取配置并刷新属性拦截缓存（未就绪 / 停用置 -1，不拦截）；返回缓存的档位数。 */
    private static int refreshMediaStepsOverride() {
        VolumeConfig config = XposedKit.readConfig(XposedKit.systemServerContext(null));
        int steps = -1;
        if (config != null && config.remapActive()) {
            steps = config.mediaSteps;
        }
        sMediaStepsOverride = steps;
        return steps;
    }

    /** 属性回调内的节流刷新：开机早期配置可能短暂不可读，这里按秒级重试。 */
    private static int refreshMediaStepsOverrideThrottled() {
        long now = System.currentTimeMillis();
        if (now - sLastOverrideRefresh < OVERRIDE_REFRESH_THROTTLE_MS) {
            return sMediaStepsOverride;
        }
        sLastOverrideRefresh = now;
        return refreshMediaStepsOverride();
    }

    // ==================== 构造器 / createStreamStates：静态数组改写 ====================

    /** AudioService 构造器：开始前缓存 Context 并改写静态档位数组；完成后校验实例档位。 */
    private static final class AudioServiceCtorHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            XposedKit.logOnce("hook-ctor", "HOOK fired: AudioService.<init>");
            try {
                List<Object> args = chain.getArgs();
                if (!args.isEmpty() && args.get(0) instanceof Context) {
                    XposedKit.cacheSystemContext((Context) args.get(0));
                }
                applyMediaSteps();
            } catch (Throwable t) {
                XposedKit.logError("constructor: apply media steps failed: " + t);
            }
            chain.proceed();
            try {
                Object instance = chain.getThisObject();
                sAudioService = instance;
                syncMediaStreamMax(instance, "constructor");
            } catch (Throwable t) {
                XposedKit.logError("constructor: sync media steps failed: " + t);
            }
            return null;
        }
    }

    /** createStreamStates：流状态创建前再改写一次数组（ROM 可能中途按属性重赋值）。 */
    private static final class CreateStreamStatesHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            XposedKit.logOnce("hook-createstream", "HOOK fired: createStreamStates");
            try {
                applyMediaSteps();
            } catch (Throwable t) {
                XposedKit.logError(METHOD_CREATE_STREAM_STATES
                        + ": apply media steps failed: " + t);
            }
            return chain.proceed();
        }
    }

    /** 把媒体流档位上限改为用户设置值（15~29，其余流不动；原地修改兼容 final 字段）。 */
    private static void applyMediaSteps() {
        Class<?> audioServiceClass = sAudioServiceClass;
        if (audioServiceClass == null) {
            return;
        }
        VolumeConfig config = XposedKit.readConfig(XposedKit.systemServerContext(null));
        if (config == null) {
            XposedKit.log("no config found, keep system defaults");
            return;
        }
        if (!config.remapActive()) {
            sMediaStepsOverride = -1;
            XposedKit.log("disabled, keep system defaults");
            return;
        }
        int[] maxStreamVolumes = XposedKit.getStaticIntArrayField(
                audioServiceClass, FIELD_MAX_STREAM_VOLUME);
        if (maxStreamVolumes == null) {
            XposedKit.logError("unexpected MAX_STREAM_VOLUME type, abort");
            return;
        }
        if (sOriginalMaxStreamVolumes == null
                || sOriginalMaxStreamVolumes.length != maxStreamVolumes.length) {
            sOriginalMaxStreamVolumes = maxStreamVolumes.clone();
        }
        int mediaSteps = config.mediaSteps;
        maxStreamVolumes[Prefs.STREAM_MUSIC_INDEX] = mediaSteps;
        // 属性拦截缓存同步（幂等）
        sMediaStepsOverride = mediaSteps;
        XposedKit.log("media steps: " + sOriginalMaxStreamVolumes[Prefs.STREAM_MUSIC_INDEX]
                + " -> " + mediaSteps + " (others untouched)");
    }

    // ==================== 实例档位校验 / 校正 / 自愈 ====================

    /**
     * 校验并校正媒体流实例档位（{@code mIndexMax} = 档位数×10），返回目标档位数。
     * 调用方：构造完成、开机延迟校正、界面读取自愈。
     *
     * @return 目标档位数；无配置 / 已停用 / 实例不可用时返回 -1
     */
    private static int syncMediaStreamMax(Object audioService, String tag) {
        VolumeConfig config = XposedKit.readConfig(XposedKit.systemServerContext(audioService));
        if (config == null) {
            XposedKit.log(tag + ": no config, keep system defaults");
            return -1;
        }
        if (!config.remapActive()) {
            sMediaStepsOverride = -1;
            XposedKit.log(tag + ": disabled, keep system defaults");
            return -1;
        }
        int target = config.mediaSteps;
        sMediaStepsOverride = target;
        int current = readMediaStreamMaxSteps(audioService);
        if (current < 0) {
            XposedKit.logError(tag + ": mStreamStates unavailable (obj="
                    + audioService.getClass().getName() + ")");
            return -1;
        }
        if (current == target) {
            sVerifiedMaxSteps = target;
            XposedKit.log(tag + ": media max verified " + target);
            return target;
        }
        if (correctMediaStreamMax(audioService, target)) {
            sVerifiedMaxSteps = target;
            XposedKit.log(tag + ": media max corrected " + current + " -> " + target);
            return target;
        }
        XposedKit.logError(tag + ": media max correct failed");
        return -1;
    }

    /** 读取媒体流实例当前档位上限（档位数）；实例不可用返回 -1。 */
    private static int readMediaStreamMaxSteps(Object audioService) {
        try {
            Object fieldValue = XposedKit.getField(audioService, FIELD_STREAM_STATES);
            if (!(fieldValue instanceof Object[])
                    || ((Object[]) fieldValue).length <= Prefs.STREAM_MUSIC_INDEX) {
                return -1;
            }
            Object mediaState = ((Object[]) fieldValue)[Prefs.STREAM_MUSIC_INDEX];
            if (mediaState == null) {
                return -1;
            }
            return XposedKit.getIntField(mediaState, FIELD_INDEX_MAX) / 10;
        } catch (Throwable t) {
            XposedKit.logErrorOnce("read-media-max", "read media mIndexMax failed: " + t);
            return -1;
        }
    }

    /** 直接把媒体流实例的 mIndexMax 设为档位数×10；成功返回 true。 */
    private static boolean correctMediaStreamMax(Object audioService, int steps) {
        try {
            Object fieldValue = XposedKit.getField(audioService, FIELD_STREAM_STATES);
            if (!(fieldValue instanceof Object[])
                    || ((Object[]) fieldValue).length <= Prefs.STREAM_MUSIC_INDEX) {
                return false;
            }
            Object mediaState = ((Object[]) fieldValue)[Prefs.STREAM_MUSIC_INDEX];
            if (mediaState == null) {
                return false;
            }
            XposedKit.setIntField(mediaState, FIELD_INDEX_MAX, steps * 10);
            return true;
        } catch (Throwable t) {
            XposedKit.logErrorOnce("correct-media-max", "correct media mIndexMax failed: " + t);
            return false;
        }
    }

    /** getStreamMaxVolume 自愈兜底（读取必经点）：不一致立即校正并修正本次返回值。 */
    private static final class StreamMaxSelfHealHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            XposedKit.logOnce("hook-selfheal", "HOOK fired: getStreamMaxVolume (self-heal)");
            Object result = chain.proceed();
            try {
                Object arg0 = chain.getArg(0);
                if (!(arg0 instanceof Integer)
                        || Prefs.STREAM_MUSIC_INDEX != (Integer) arg0) {
                    return result;
                }
                if (!(result instanceof Integer)) {
                    return result;
                }
                sAudioService = chain.getThisObject();
                int current = (Integer) result;
                if (current <= 0 || current == sVerifiedMaxSteps) {
                    return result;
                }
                long now = System.currentTimeMillis();
                if (now - sLastSelfHealCheck < SELF_HEAL_THROTTLE_MS) {
                    return result;
                }
                sLastSelfHealCheck = now;
                int target = syncMediaStreamMax(chain.getThisObject(), "self-heal");
                if (target >= 1 && target != current) {
                    return target;
                }
            } catch (Throwable t) {
                XposedKit.logError("self-heal check failed: " + t);
            }
            return result;
        }
    }

    // ==================== 开机主动校正 ====================

    /** 开机校正时间点（毫秒）：覆盖系统服务逐步就绪的过程。 */
    private static final long[] BOOT_CORRECTION_DELAYS_MS =
            {2_000L, 5_000L, 15_000L, 30_000L, 60_000L};

    /** 模块加载后延迟主动校正档位（开机兜底，主线程执行避免并发改实例字段）。 */
    private static void scheduleBootCorrection() {
        Handler handler = new Handler(Looper.getMainLooper());
        for (long delay : BOOT_CORRECTION_DELAYS_MS) {
            handler.postDelayed(RUN_BOOT_CORRECTION, delay);
        }
    }

    private static final Runnable RUN_BOOT_CORRECTION = () -> {
        try {
            refreshMediaStepsOverride();
            Object audioService = sAudioService;
            if (audioService == null) {
                audioService = systemAudioService();
            }
            if (audioService == null) {
                XposedKit.logError("boot correction: audio service unavailable");
                return;
            }
            XposedKit.log("boot correction: service="
                    + audioService.getClass().getName());
            syncMediaStreamMax(audioService, "boot correction");
        } catch (Throwable t) {
            XposedKit.logError("boot correction failed: " + t);
        }
    };

    /**
     * 反射获取 system_server 内的 AudioService 实例。
     *
     * <p>{@code ServiceManager.getService("audio")} 在 system_server 进程内返回的
     * 是本地 Binder Stub（IAudioService.Stub 子类），它通过 {@code this$0} 等
     * 实例字段持有外部 AudioService——必须解引用拿到真正的 AudioService，
     * 直接把 Stub 当 AudioService 用会导致读 mStreamStates 失败（v1.8 缺陷）。</p>
     */
    private static Object systemAudioService() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("getService", String.class)
                    .invoke(null, Context.AUDIO_SERVICE);
            if (binder == null) {
                return null;
            }
            Object service = audioServiceFromBinder(binder);
            if (service == null) {
                XposedKit.logErrorOnce("audio-service", "audio binder has no AudioService"
                        + " field: " + binder.getClass().getName());
            }
            return service;
        } catch (Throwable t) {
            XposedKit.logErrorOnce("audio-service", "get audio service failed: " + t);
            return null;
        }
    }

    /** 从 Binder Stub 中找出 AudioService 类型的实例字段（含 this$0 外部类引用）。 */
    private static Object audioServiceFromBinder(Object binder) {
        Class<?> c = binder.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (XposedKit.AUDIO_SERVICE_CLASS.equals(field.getType().getName())) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(binder);
                        if (value != null) {
                            return value;
                        }
                    } catch (Throwable t) {
                        XposedKit.logError("read max stream volume field failed: " + t);
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // ==================== 模式B：软件衰减范围 + 绝对音量压制 ====================

    /**
     * 模式B 的系统框架侧保险（双通道拦截）：
     * 1. {@code avrcpSupportsAbsoluteVolume}：强制上报"不支持绝对音量"，使 AudioService
     *    对 A2DP 设备走软件衰减路径（否则音量仅通过 AVRCP 发送，被 sendVolumeChanged 阻断）；
     * 2. {@code postSetAvrcpAbsoluteVolumeIndex}：拦截音量转发。
     */
    private static void hookAbsoluteVolumeSuppression(ClassLoader classLoader,
            XposedModule module) {
        try {
            Class<?> audioService = classLoader.loadClass(XposedKit.AUDIO_SERVICE_CLASS);
            int hooked = XposedKit.hookAllMethodsNamed(module, audioService,
                    METHOD_AVRCP_SUPPORTS_ABS_VOLUME, new AvrcpSupportsHooker());
            XposedKit.log(audioService.getName() + "#" + METHOD_AVRCP_SUPPORTS_ABS_VOLUME
                    + " hooked: " + hooked);
        } catch (Throwable t) {
            XposedKit.logError("hook " + METHOD_AVRCP_SUPPORTS_ABS_VOLUME + " failed: " + t);
        }
        try {
            Class<?> deviceBroker = classLoader.loadClass(XposedKit.AUDIO_DEVICE_BROKER_CLASS);
            int hooked = XposedKit.hookAllMethodsNamed(module, deviceBroker,
                    METHOD_POST_AVRCP_VOLUME, new PostAvrcpVolumeHooker());
            XposedKit.log(deviceBroker.getName() + "#" + METHOD_POST_AVRCP_VOLUME
                    + " hooked: " + hooked);
        } catch (Throwable t) {
            XposedKit.logError("hook " + METHOD_POST_AVRCP_VOLUME + " failed: " + t);
        }
    }

    /** 上报入口：模式B 下强制"不支持绝对音量"（使 AudioService 走软件衰减路径）。 */
    private static final class AvrcpSupportsHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            XposedKit.logOnce("hook-avrcp-support", "HOOK fired: avrcpSupportsAbsoluteVolume");
            try {
                List<Object> args = chain.getArgs();
                if (args.size() < 2 || !Boolean.TRUE.equals(args.get(1))) {
                    return chain.proceed();
                }
                VolumeConfig config = XposedKit.readConfig(
                        XposedKit.systemServerContext(chain.getThisObject()));
                if (!config.suppressBtAbsoluteVolume()) {
                    return chain.proceed();
                }
                XposedKit.logOnce("modeb-support", "modeB: force avrcp support=false");
                Object[] newArgs = args.toArray();
                newArgs[1] = Boolean.FALSE;
                return chain.proceed(newArgs);
            } catch (Throwable t) {
                XposedKit.logError("avrcpSupports hook failed: " + t);
                return chain.proceed();
            }
        }
    }

    /** 发送入口：模式B 下拦截音量转发（耳机端固定）。 */
    private static final class PostAvrcpVolumeHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            XposedKit.logOnce("hook-post-avrcp", "HOOK fired: postSetAvrcpAbsoluteVolumeIndex");
            try {
                Object arg0 = chain.getArg(0);
                if (!(arg0 instanceof Integer)) {
                    return chain.proceed();
                }
                VolumeConfig config = XposedKit.readConfig(
                        XposedKit.systemServerContext(chain.getThisObject()));
                if (!config.suppressBtAbsoluteVolume()) {
                    return chain.proceed();
                }
                int index = (Integer) arg0;
                if (index != sLastBlockedIndex) {
                    sLastBlockedIndex = index;
                    XposedKit.log("modeB: block avrcp volume forward (index=" + index + ")");
                }
                return null;
            } catch (Throwable t) {
                XposedKit.logError("postAvrcpVolume hook failed: " + t);
                return chain.proceed();
            }
        }
    }

    /**
     * 模式B：音量范围对手机端软件衰减生效。
     * {@code VolumeStreamState#setStreamVolumeIndex(index, device)} 是档位应用必经点，
     * 蓝牙 A2DP 设备上的衰减档位按「最小~最大」范围重映射（低音量增强曲线）。
     */
    private static void hookSoftwareVolumeCurve(ClassLoader classLoader, XposedModule module) {
        final Class<?> streamState;
        try {
            streamState = classLoader.loadClass(XposedKit.VOLUME_STREAM_STATE_CLASS);
        } catch (Throwable t) {
            XposedKit.logError(XposedKit.VOLUME_STREAM_STATE_CLASS + " not found: " + t);
            return;
        }
        int hooked = XposedKit.hookAllMethodsNamed(module, streamState,
                METHOD_SET_STREAM_VOLUME_INDEX, new SetStreamVolumeIndexHooker());
        XposedKit.log(streamState.getName() + "#" + METHOD_SET_STREAM_VOLUME_INDEX
                + " hooked: " + hooked);
        if (hooked == 0) {
            XposedKit.logError("setStreamVolumeIndex NOT FOUND on VolumeStreamState!");
        }
    }

    /**
     * 音量键步进：接管 {@code AudioService#adjustStreamVolume}，对媒体流的
     * ADJUST_RAISE/LOWER 按当前位置吸附到 {@code keySteps} 网格（目标 =
     * {@code Prefs.nextKeyStepUp/Down}，即严格大于/小于当前的 {@code round(i*maxSteps/keySteps)}），
     * 再直接 {@code setStreamVolume}，完全覆盖 ROM 默认的「按 maxIndex/15 分段」步进，
     * 并使滑块百分比落在 100/keySteps 的整数倍。屏幕滑条拖动走 setStreamVolume 不经此路径，
     * 保留全部级数精度。
     *
     * <p>若 {@code hooked:0}（ROM 音量键不走此方法），则保持系统默认步进并如实记录。</p>
     */
    private static void hookVolumeKeyStep(ClassLoader classLoader, XposedModule module) {
        Class<?> audioService = sAudioServiceClass;
        if (audioService == null) {
            try {
                audioService = classLoader.loadClass(XposedKit.AUDIO_SERVICE_CLASS);
            } catch (Throwable t) {
                XposedKit.logError("key step: AudioService class load failed: " + t);
                return;
            }
        }
        int hooked = XposedKit.hookAllMethodsNamed(module, audioService,
                METHOD_ADJUST_STREAM_VOLUME, new AdjustStreamVolumeHooker());
        XposedKit.log("key step: " + audioService.getName() + "#" + METHOD_ADJUST_STREAM_VOLUME
                + " hooked: " + hooked
                + (hooked == 0 ? " (keys routed elsewhere; step stays default)" : ""));
    }

    /**
     * 「拖后按键」网格吸附：挂 {@code AudioService#setStreamVolumeWithAttribution}（拖后音量键
     * 绕开 adjustStreamVolume、经 Binder 落到服务端的同步入口），按时间间隔区分拖动（密集放行、
     * 保留全精度）与按键（孤立吸附到网格）。取不到该方法时回退挂 {@code setStreamVolume}。
     *
     * <p>详见 {@link MediaVolumeGridSnapHooker}。与 {@link #hookVolumeKeyStep} 互补：正常按键走
     * adjustStreamVolume 已被接管吸附，本 Hook 专门兜住「拖动之后」改走 setStreamVolume 的按键。</p>
     */
    private static void hookMediaVolumeGridSnap(ClassLoader classLoader, XposedModule module) {
        Class<?> audioService = sAudioServiceClass;
        if (audioService == null) {
            try {
                audioService = classLoader.loadClass(XposedKit.AUDIO_SERVICE_CLASS);
            } catch (Throwable t) {
                XposedKit.logError("grid snap: AudioService class load failed: " + t);
                return;
            }
        }
        int hooked = XposedKit.hookAllMethodsNamed(module, audioService,
                METHOD_SET_STREAM_VOLUME_WITH_ATTRIBUTION, new MediaVolumeGridSnapHooker());
        String method = METHOD_SET_STREAM_VOLUME_WITH_ATTRIBUTION;
        if (hooked == 0) {
            method = METHOD_SET_STREAM_VOLUME;
            hooked = XposedKit.hookAllMethodsNamed(module, audioService,
                    method, new MediaVolumeGridSnapHooker());
        }
        XposedKit.log("grid snap: " + audioService.getName() + "#" + method
                + " hooked: " + hooked
                + (hooked == 0 ? " (no setStreamVolume entry; post-drag keys stay default)" : ""));
    }

    /**
     * 反射调用 {@code AudioService} 上的单 int 参方法并取 int 返回（getStreamVolume / getStreamMaxVolume）。
     * 遍历类层次 + {@code setAccessible} 以兼容非 public（{@code getMethod} 只查 public →
     * MiAudioService / AudioServiceBinderWrapper 上 NoSuchMethodException）。
     */
    private static int invokeIntMethod(Object target, String name, int arg) throws Throwable {
        Method m = findIntMethod(target.getClass(), name);
        if (m == null) {
            throw new NoSuchMethodException(
                    name + "(int) not found on " + target.getClass().getName());
        }
        m.setAccessible(true);
        Object r;
        try {
            r = m.invoke(target, arg);
        } catch (InvocationTargetException e) {
            throw (e.getCause() != null) ? e.getCause() : e;
        }
        if (r instanceof Integer) {
            return (Integer) r;
        }
        throw new NoSuchMethodException(name + " not returning int");
    }

    /** 在类层次中查找名字为 name、单个 int 参数的方法（任意可见性）。 */
    private static Method findIntMethod(Class<?> c, String name) {
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod(name, int.class);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 供 {@link MediaVolumeGridSnapHooker}：读取当前媒体流逻辑档位（0~levels）。
     * 优先用真实 {@link #sAudioService} 实例（{@code AudioServiceBinderWrapper} 上无
     * getStreamVolume），回退 {@code fallbackThis}；任何异常返回 -1（调用方保守放行）。
     */
    static int readMediaVolumeIndex(Object fallbackThis) {
        Object audioService = sAudioService != null ? sAudioService : fallbackThis;
        if (audioService == null) {
            return -1;
        }
        try {
            return invokeIntMethod(audioService, METHOD_GET_STREAM_VOLUME,
                    Prefs.STREAM_MUSIC_INDEX);
        } catch (Throwable t) {
            XposedKit.logErrorOnce("read-cur-vol", "read current media index failed: " + t);
            return -1;
        }
    }

    /**
     * 供 {@link MediaVolumeGridSnapHooker}：读取媒体流<b>当前物理最大档位</b>（getStreamMaxVolume）。
     * 必须与 {@link AdjustStreamVolumeHooker} 同源（都取实时物理上限），<b>不能</b>用
     * {@code config.mediaSteps}（那是「期望值」，改档位数后未重启框架时物理上限仍为旧值，
     * 两者不一致会令两个 Hook 对网格刻度产生分歧而自激振荡）。失败返回 -1（调用方保守放行）。
     */
    static int readMediaMaxIndex(Object fallbackThis) {
        Object audioService = sAudioService != null ? sAudioService : fallbackThis;
        if (audioService == null) {
            return -1;
        }
        try {
            return invokeIntMethod(audioService, METHOD_GET_STREAM_MAX_VOLUME,
                    Prefs.STREAM_MUSIC_INDEX);
        } catch (Throwable t) {
            XposedKit.logErrorOnce("read-max-vol", "read media max index failed: " + t);
            return -1;
        }
    }

    /**
     * 服务端「拖后按键」网格吸附 Hooker（宿主 {@link AudioHooks} 的私有实现细节，与其他 Hooker 一致保持内部类）。
     *
     * <p><b>当前状态：保守重启（带防误伤门限）。</b> 早期版本曾因①刻度不同源（用 config.mediaSteps）
     * 与②把 SystemUI 非按键同步写入也吸附，导致音量自激振荡（32%↔36% 循环）与反向掉档。现两者已修：
     * levels 改用<b>实时物理上限</b> getStreamMaxVolume（与 {@link AdjustStreamVolumeHooker} 同源）；并对
     * <b>Δ 过小</b>（≈零/±1）的调用一律视为系统回写、原样放行，从根上消除误伤。</p>
     *
     * <p>背景（adb 实测 + SystemUI 抓栈定位）：未拖动时音量键走
     * {@code AudioService#adjustStreamVolume}，由 {@link AdjustStreamVolumeHooker} 吸附到网格；
     * 但一旦拖过一次滑条，HyperOS 会把音量对话框置为「直接落值」状态，随后的音量键改走
     * {@code SystemUI → Binder → AudioService#setStreamVolumeWithAttribution(绝对档位)}，
     * 绕开 {@code adjustStreamVolume} 接管，导致落点偏离网格（步长约 7~14%）。</p>
     *
     * <p>{@code setStreamVolumeWithAttribution} 是拖动与「拖后按键」在服务端唯一共同经过的<b>同步</b>
     * 入口。二者的唯一区别是<b>时间形态</b>：拖动是密集连发（相邻两次 &lt;~100ms），按键是孤立一次
     * （与上一次间隔 &gt;~450ms）；再叠加 Δ 门限区分真实按键与系统回写。本类据此：</p>
     * <ul>
     *   <li>媒体档位变更且与上一次媒体变更间隔 &lt; {@link #KEY_SNAP_GAP_NS}（密集）→ 判为拖动，
     *       直接放行，<b>保留拖动全精度</b>（不读配置，热路径仅整数比较）；</li>
     *   <li>间隔足够大（孤立）且 Δ 够大（真实按键一整步）→ 相对当前档位吸附到<b>相邻的一个网格档</b>
     *       （与 adjustStreamVolume 接管同用 {@code Prefs.nextKeyStepUp/Down}），每次恰好跳 1 段。</li>
     * </ul>
     *
     * <p>时间戳 {@link #sLastSetNanos} 在每次媒体档位变更时刷新，使连续拖动期间判据稳定为「密集」。</p>
     */
    private static final class MediaVolumeGridSnapHooker implements XposedInterface.Hooker {

        /** 判定「拖动 vs 按键」的相邻媒体变更最小间隔：小于此值视为拖动密集连发，放行不动。 */
        private static final long KEY_SNAP_GAP_NS = 450_000_000L;

        /** system_server 最近一次媒体档位 setStreamVolume(WithAttribution) 的时间戳（纳秒）。 */
        private static volatile long sLastSetNanos;

        /** 上次已记录的吸附落点，用于吸附日志去重（避免同值重复刷屏）。 */
        private static int sLastSnapLogged = -1;

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            List<Object> args = chain.getArgs();
            try {
                // 目标方法签名：setStreamVolumeWithAttribution(int streamType, int index, int flags, ...)
                if (args.size() < 3 || !(args.get(0) instanceof Integer)
                        || !(args.get(1) instanceof Integer)) {
                    return chain.proceed();
                }
                if ((Integer) args.get(0) != Prefs.STREAM_MUSIC_INDEX) {
                    return chain.proceed();
                }
                int index = (Integer) args.get(1);
                long now = System.nanoTime();
                long prev = sLastSetNanos;
                sLastSetNanos = now;   // 每次媒体变更都刷新，使拖动期间判据稳定为「密集」
                if (index <= 0) {
                    return chain.proceed();
                }
                // 热路径优先：密集连发（拖动）直接放行，连配置都不读，保留全精度。
                if (now - prev < KEY_SNAP_GAP_NS) {
                    return chain.proceed();
                }
                // 孤立变更 → 读配置判断是否需要吸附到网格。
                VolumeConfig config = XposedKit.readConfig(
                        XposedKit.systemServerContext(chain.getThisObject()));
                if (config == null || !config.remapActive()) {
                    return chain.proceed();
                }
                // levels 必须取【实时物理上限】(getStreamMaxVolume)，与 AdjustStreamVolumeHooker 同源；
                // 绝不能用 config.mediaSteps（改档位数后未重启框架时物理上限仍为旧值，两者不一致会自激振荡）。
                int levels = readMediaMaxIndex(chain.getThisObject());
                int segs = Prefs.clampKeySteps(config.keySteps);
                if (levels <= 0 || segs >= levels) {
                    return chain.proceed();   // 读不到上限或网格不稀疏，逐级即正确
                }
                // 读当前媒体档位（优先真实 AudioService 实例，回退 this）；读不到或无变化保守放行。
                int current = readMediaVolumeIndex(chain.getThisObject());
                if (current < 0 || index == current) {
                    return chain.proceed();
                }
                // 【防误伤/防振荡】真实按键相对当前是一整步（~7%），而 SystemUI 对话框同步/取整写入
                // 的 Δ 只有 0/±1。Δ 过小一律视为系统回写、原样放行——否则会把同步写入吸附
                // 成反向掉档并与 SystemUI 相互回写振荡（实测按音量+反而 40→39 的根因）。
                int delta = index - current;
                int minKeyDelta = Math.max(2, Math.round(levels * 0.03f));
                if (Math.abs(delta) < minKeyDelta) {
                    return chain.proceed();
                }
                // 相对「当前档位」吸附到相邻的一个网格档（与 adjustStreamVolume 接管一致），
                // 保证每次按键恰好跳 1 段（~100/keySteps %），而非把绝对目标就近取整而跨档跳。
                boolean raise = delta > 0;
                int target = raise
                        ? Prefs.nextKeyStepUp(current, levels, segs)
                        : Prefs.nextKeyStepDown(current, levels, segs);
                if (target == current || target == index) {
                    return chain.proceed();   // 顶/底，或本就在正确的网格步上
                }
                if (target != sLastSnapLogged) {
                    sLastSnapLogged = target;
                    XposedKit.log("post-drag key snap: media " + index + " -> " + target
                            + " (cur=" + current + ", " + (raise ? "up" : "down") + ")/" + levels);
                }
                Object[] newArgs = args.toArray();
                newArgs[1] = target;
                return chain.proceed(newArgs);
            } catch (Throwable t) {
                XposedKit.logErrorOnce("grid-snap", "media grid snap error: " + t);
                return chain.proceed();
            }
        }
    }

    /**
     * 反射调用 {@code AudioService#setStreamVolume(int, int, int)}：HyperOS 的 MiAudioService
     * 继承 AudioService，该方法可能非 public（{@code getMethod} 只查 public → NoSuchMethodException）
     * 或仅存在带 {@code callingPackage} 的重载。故遍历类层次查找名字为 setStreamVolume、
     * 前 3 参为 int 的方法，优先参数最少者，{@code setAccessible} 后调用；多余参数按类型补默认值。
     */
    private static void invokeSetStreamVolume(Object audioService, int streamType, int index,
            int flags) throws Throwable {
        Method target = null;
        Class<?> c = audioService.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!METHOD_SET_STREAM_VOLUME.equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length >= 3 && p[0] == int.class && p[1] == int.class && p[2] == int.class
                        && (target == null || p.length < target.getParameterTypes().length)) {
                    m.setAccessible(true);
                    target = m;
                }
            }
            c = c.getSuperclass();
        }
        if (target == null) {
            throw new NoSuchMethodException(METHOD_SET_STREAM_VOLUME
                    + "(int,int,int,...) not found on " + audioService.getClass().getName());
        }
        Class<?>[] p = target.getParameterTypes();
        Object[] args = new Object[p.length];
        args[0] = streamType;
        args[1] = index;
        args[2] = flags;
        for (int i = 3; i < p.length; i++) {
            Class<?> t = p[i];
            if (t == String.class) {
                args[i] = SET_VOLUME_CALLING_PACKAGE;
            } else if (t == int.class || t == Integer.class) {
                args[i] = 0;
            } else if (t == boolean.class || t == Boolean.class) {
                args[i] = false;
            } else {
                args[i] = null;
            }
        }
        try {
            target.invoke(audioService, args);
        } catch (InvocationTargetException e) {
            throw (e.getCause() != null) ? e.getCause() : e;
        }
    }

    /**
     * 音量键步进 Hooker：仅对媒体流 RAISE/LOWER 生效，以当前级 ± delta 为新目标。
     * 任何异常或不适用的调用一律 {@code chain.proceed()} 保留系统默认行为。
     */
    private static final class AdjustStreamVolumeHooker implements XposedInterface.Hooker {
        private static boolean sFiredLogged;
        private static boolean sEntryLogged;
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                List<Object> args = chain.getArgs();
                if (!sEntryLogged) {
                    sEntryLogged = true;
                    XposedKit.log("key step hook fired (first): args=" + args
                            + " this=" + chain.getThisObject().getClass().getName());
                }
                if (args.size() < 3 || !(args.get(0) instanceof Integer)
                        || !(args.get(1) instanceof Integer)) {
                    return chain.proceed();
                }
                int streamType = (Integer) args.get(0);
                int direction = (Integer) args.get(1);
                int flags = args.get(2) instanceof Integer ? (Integer) args.get(2) : 0;
                boolean raise = direction == AudioManager.ADJUST_RAISE;
                boolean lower = direction == AudioManager.ADJUST_LOWER;
                if (streamType != Prefs.STREAM_MUSIC_INDEX || (!raise && !lower)) {
                    return chain.proceed();
                }
                // HyperOS 音量键可能走 AudioService$AudioServiceBinderWrapper（其上无 getStreamMaxVolume 等
                // 方法），故优先用真正的 AudioService 实例读写音量；缺失时回退到 chain.getThisObject()。
                Object audioService = sAudioService != null ? sAudioService : chain.getThisObject();
                VolumeConfig config = XposedKit.readConfig(
                        XposedKit.systemServerContext(audioService));
                if (config == null || !config.remapActive()) {
                    return chain.proceed();
                }
                int keySteps = Prefs.clampKeySteps(config.keySteps);
                int maxSteps = invokeIntMethod(audioService,
                        METHOD_GET_STREAM_MAX_VOLUME, streamType);
                int oldIndex = invokeIntMethod(audioService,
                        METHOD_GET_STREAM_VOLUME, streamType);
                if (maxSteps <= 0 || oldIndex < 0) {
                    return chain.proceed();
                }
                // 按当前位置吸附到 keySteps 网格：新目标 = 严格大于/小于当前的网格级，
                // 避免固定 delta 累加造成的漂移（使滑块百分比落在 100/keySteps 的整数倍）。
                int newIndex = raise
                        ? Prefs.nextKeyStepUp(oldIndex, maxSteps, keySteps)
                        : Prefs.nextKeyStepDown(oldIndex, maxSteps, keySteps);
                newIndex = Math.max(0, Math.min(maxSteps, newIndex));
                if (newIndex == oldIndex) {
                    // 已到顶/底：交回系统处理（不强制变更）。
                    return chain.proceed();
                }
                if (!sFiredLogged) {
                    sFiredLogged = true;
                    XposedKit.log("key step applied: " + (raise ? "RAISE " : "LOWER ")
                            + oldIndex + " -> " + newIndex + " levels=" + maxSteps
                            + " segs=" + keySteps + " (~" + Math.round(newIndex * 100.0 / maxSteps) + "%)");
                } else {
                    XposedKit.log("key step: " + (raise ? "+" : "-") + (newIndex - oldIndex)
                            + " " + oldIndex + " -> " + newIndex);
                }
                sLastKeyAdjustNanos = System.nanoTime();
                invokeSetStreamVolume(audioService, streamType, newIndex, flags);
                // 跳过原实现（它自带 ±1 或按 maxIndex/15 的步进），避免双算。
                return null;
            } catch (Throwable t) {
                XposedKit.logErrorOnce("key-step-adjust",
                        "adjustStreamVolume key step error: " + t);
                return chain.proceed();
            }
        }
    }

    /**
     * 诊断：若媒体流逻辑档位落在「非按键网格」上，且最近 200ms 内没有发生过
     * 我们接管的 adjustStreamVolume，则说明本次音量变化绕过了接管路径（拖滑条
     * 或 MIUI 自有的音量键/解除静音处理）。拖拽属于设计内保留全精度，但若是音量键
     * 则需定位那条绕过方法——故低频（≥6s 一次）抓取调用栈。仅读日志，不改行为。
     */
    private static void maybeLogKeyBypass(int index, VolumeConfig config) {
        if (index <= 0) {
            return;
        }
        long now = System.nanoTime();
        if (now - sLastKeyAdjustNanos < 200_000_000L) {
            return;   // 刚由按键接管改过，属正常网格落点
        }
        int levels = Prefs.clampMediaSteps(config.mediaSteps);
        int segs = Prefs.clampKeySteps(config.keySteps);
        if (levels <= 0 || segs >= levels) {
            return;   // 网格间距<1，逐级即正确，不判
        }
        int nearest = (int) Math.round(index * (double) segs / levels);
        if (Prefs.keyStepLevel(nearest, levels, segs) == index) {
            return;   // 恰在网格上
        }
        if (now - sLastBypassLogNanos < 6_000_000_000L) {
            return;   // 节流：6s 一次
        }
        sLastBypassLogNanos = now;
        StringBuilder sb = new StringBuilder("key-step bypass? media idx ")
                .append(index).append('/').append(levels)
                .append(" off-grid (~").append(Math.round(index * 100.0 / levels))
                .append("%), no recent adjust. stack:");
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(st.length, 12); i++) {
            sb.append("\n  at ").append(st[i]);
        }
        XposedKit.log(sb.toString());
    }

    /**
     * 模式B 软件衰减曲线映射：
     * {@code VolumeStreamState#setStreamVolumeIndex(index, device)} 是档位应用必经点。
     *
     * <p>仅模式B 在 system_server 侧修改音量（curve + 衰减乘数），
     * 模式A 保持绝对音量，不在 system_server 干预。</p>
     */
    private static final class SetStreamVolumeIndexHooker implements XposedInterface.Hooker {
        private static boolean sAnyFireLogged;
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            List<Object> args = chain.getArgs();
            try {
                if (args.size() < 2 || !(args.get(0) instanceof Integer)
                        || !(args.get(1) instanceof Integer)) {
                    return chain.proceed();
                }
                int index = (Integer) args.get(0);
                int device = (Integer) args.get(1);
                if (index <= 0) {
                    return chain.proceed();
                }
                // 首次触发诊断
                if (!sAnyFireLogged) {
                    sAnyFireLogged = true;
                    VolumeConfig diagConfig = XposedKit.readConfig(XposedKit.systemServerContext(null));
                    XposedKit.log("setStreamVolumeIndex first fire: index="
                            + index + " device=" + device
                            + " config=" + diagConfig);
                }
                Object state = chain.getThisObject();
                if (XposedKit.getIntField(state, FIELD_STREAM_TYPE)
                        != Prefs.STREAM_MUSIC_INDEX) {
                    return chain.proceed();
                }
                Object audioService;
                try {
                    audioService = XposedKit.getField(state, FIELD_AUDIO_SERVICE);
                } catch (Throwable t) {
                    XposedKit.logErrorOnce("vss-this", "read VolumeStreamState.this$0 failed: " + t);
                    audioService = sAudioService;
                    if (audioService == null) {
                        return chain.proceed();
                    }
                }
                VolumeConfig config = XposedKit.readConfig(
                        XposedKit.systemServerContext(audioService));
                if (config == null || !config.remapActive()) {
                    return chain.proceed();
                }
                // 诊断：媒体档位落到「非按键网格」且近期无按键接管 → 音量键可能走了
                // 绕过 adjustStreamVolume 的路径，抓一次调用栈以定位该方法。
                maybeLogKeyBypass(index, config);
                // 按输出设备分派：外放/有线/蓝牙各自按其模式在 system_server 改写档位；
                // 蓝牙模式A（AVRCP）由蓝牙进程处理、该设备默认直通、未识别设备一律放行。
                OutputDevice d = OutputDevice.fromOutMask(device);
                if (d == null) {
                    return chain.proceed();
                }
                Range range = config.activeRangeFor(d);
                if (range == null) {
                    return chain.proceed();            // 总开关关闭或该设备默认=直通
                }
                if (d.supportsAbsolute() && config.modeFor(d) == Prefs.BT_MODE_ABSOLUTE) {
                    return chain.proceed();            // 蓝牙模式A 经 AVRCP，不在此衰减
                }
                int minAbs = range.min;
                int maxAbs = range.max;
                int curveType = range.curve;
                // 全量程无需衰减时直接放行
                if (maxAbs >= Prefs.AVRCP_MAX_VOLUME && minAbs <= 0) {
                    return chain.proceed();
                }
                int maxSteps = XposedKit.getIntField(state, FIELD_INDEX_MAX) / 10;
                if (maxSteps <= 0) {
                    return chain.proceed();
                }
                // curve 映射（按所选曲线，与模式A 同算法）
                int mapped = Avrcp.curveToSystemIndex(index, maxSteps, minAbs, maxAbs, curveType);
                // 仅在无需改变时放行；mapped>index 抬升低档位以生效「最小音量」下限，
                // mapped<index 压低高档位以生效「最大音量」上限（软件衰减）。
                if (mapped == index) {
                    return chain.proceed();
                }
                if (index != sLastLoggedSystemIndex) {
                    sLastLoggedSystemIndex = index;
                    XposedKit.log("vol remap: " + index + "/" + maxSteps
                            + " -> " + mapped + " (range=" + minAbs + "~" + maxAbs
                            + " device=" + device + ")");
                }
                Object[] newArgs = args.toArray();
                newArgs[0] = mapped;
                chain.proceed(newArgs); // HAL 应用重映射后的增益（升/降）
                // 恢复 mIndexMap 中的原始档位，避免 getStreamVolume 读回 remapped
                // 后二次映射导致音量卡死（VOL_UP 无效）
                try {
                    Object map = XposedKit.getField(state, "mIndexMap");
                    if (map instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<Integer, Integer> indexMap =
                                (Map<Integer, Integer>) map;
                        indexMap.put(device, index * 10);
                    }
                } catch (Throwable t) {
                    XposedKit.logErrorOnce("restore-index-map", "restore mIndexMap failed: " + t);
                }
                return null;
            } catch (Throwable t) {
                XposedKit.logOnce("vol-index-err",
                        "setStreamVolumeIndex hook error: " + t);
                return chain.proceed();
            }
        }
    }
}
