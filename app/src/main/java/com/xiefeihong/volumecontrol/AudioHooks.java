package com.xiefeihong.volumecontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;

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
 * <p><b>模式B 双重衰减（均在 VolumeStreamState.setStreamVolumeIndex 内计算）：</b></p>
 * <ol>
 *   <li>第一重：Hook {@code VolumeStreamState#setStreamVolumeIndex} 把系统音量档位
 *       按 maxAbs 曲线映射降低（低音量增强曲线）；</li>
 *   <li>第二重：在 curve 映射结果上再乘「衰减乘数」（0~200%），
 *       使最终音量 = curve映射档位 × 乘数百分比。</li>
 * </ol>
 *
 * <p><b>模式B 保险：</b>{@code avrcpSupportsAbsoluteVolume} 强制上报"不支持"，
 * {@code postSetAvrcpAbsoluteVolumeIndex} 直接拦截发往蓝牙栈的音量值。</p>
 */
final class AudioHooks {

    private static final String METHOD_CREATE_STREAM_STATES = "createStreamStates";
    private static final String METHOD_GET_STREAM_MAX_VOLUME = "getStreamMaxVolume";
    private static final String METHOD_SET_STREAM_VOLUME_INDEX = "setStreamVolumeIndex";
    private static final String METHOD_SET_STREAM_VOLUME = "setStreamVolume";
    private static final String METHOD_AVRCP_SUPPORTS_ABS_VOLUME = "avrcpSupportsAbsoluteVolume";
    private static final String METHOD_POST_AVRCP_VOLUME = "postSetAvrcpAbsoluteVolumeIndex";
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
    private static boolean sModeBLogged;

    /** AudioSystem.DEVICE_OUT_ALL_A2DP_SET 的缓存。 */
    private static volatile Collection<Integer> sA2dpDeviceSet;

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
            // AudioSystem hook 可能导致音量不稳定，暂时禁用
            // hookAudioSystemVolumeIndex(classLoader, module);
            // 衰减乘数已融入 SetStreamVolumeIndexHooker 的 curve 映射计算中
            hookAbsoluteVolumeSuppression(classLoader, module);

            // 立即读取配置填充属性拦截缓存（此时 SettingsProvider 未就绪，
            // 镜像文件通道可读）；失败由属性回调与开机校正重试。
            int steps = refreshMediaStepsOverride();
            XposedKit.log("media steps override: "
                    + (steps < 0 ? "not ready, will retry on demand" : String.valueOf(steps)));

            scheduleBootCorrection();
            // 诊断：安装完成后立即读取并记录当前配置
            int[] diagConfig = XposedKit.readConfig(XposedKit.systemServerContext(null));
            XposedKit.log("system_server hooks installed, config="
                    + (diagConfig != null ? java.util.Arrays.toString(diagConfig) : "null"));
        } catch (Throwable t) {
            XposedKit.logError("hook system server failed: " + t);
        }
    }

    /** 批量 deoptimize（内联防护）；单个失败不影响其余。 */
    private static void deoptimizeQuietly(XposedModule module,
            java.lang.reflect.Executable[] executables) {
        for (java.lang.reflect.Executable executable : executables) {
            try {
                module.deoptimize(executable);
            } catch (Throwable t) {
                XposedKit.logError("deoptimize failed: " + t);
            }
        }
    }

    /** 按名称选取方法（用于精确 deoptimize 目标方法）。 */
    private static java.lang.reflect.Executable[] findMethodsNamed(Class<?> clazz, String name) {
        return java.util.Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> name.equals(m.getName()))
                .toArray(java.lang.reflect.Executable[]::new);
    }

    // ==================== 第一重保险：属性拦截 ====================

    /**
     * 拦截 {@code SystemProperties.getInt("ro.config.media_vol_steps", ...)}：
     * 命中该属性时返回缓存的用户档位数。HyperOS 的 AudioService 初始化通过该
     * 属性读取媒体档位数（默认 15），拦截后 ROM 自身流程即使用正确档位。
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
        int[] config = XposedKit.readConfig(XposedKit.systemServerContext(null));
        int steps = -1;
        if (config != null && config[0] != 0) {
            steps = Prefs.clampMediaSteps(config[1]);
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
        int[] config = XposedKit.readConfig(XposedKit.systemServerContext(null));
        if (config == null) {
            XposedKit.log("no config found, keep system defaults");
            return;
        }
        if (config[0] == 0) {
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
        int mediaSteps = Prefs.clampMediaSteps(config[1]);
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
        int[] config = XposedKit.readConfig(XposedKit.systemServerContext(audioService));
        if (config == null) {
            XposedKit.log(tag + ": no config, keep system defaults");
            return -1;
        }
        if (config[0] == 0) {
            sMediaStepsOverride = -1;
            XposedKit.log(tag + ": disabled, keep system defaults");
            return -1;
        }
        int target = Prefs.clampMediaSteps(config[1]);
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
            return false;
        }
    }

    /** getStreamMaxVolume 自愈兜底（读取必经点）：不一致立即校正并修正本次返回值。 */
    private static final class StreamMaxSelfHealHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
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
     * 1. {@code avrcpSupportsAbsoluteVolume}：强制上报“不支持绝对音量”；
     * 2. {@code postSetAvrcpAbsoluteVolumeIndex}：拦截音量转发，耳机固定自身硬件音量。
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

    /** 上报入口：模式B 下强制“不支持绝对音量”。 */
    private static final class AvrcpSupportsHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                List<Object> args = chain.getArgs();
                if (args.size() < 2 || !Boolean.TRUE.equals(args.get(1))) {
                    return chain.proceed();
                }
                int[] config = XposedKit.readConfig(
                        XposedKit.systemServerContext(chain.getThisObject()));
                if (!isModeB(config)) {
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
            try {
                Object arg0 = chain.getArg(0);
                if (!(arg0 instanceof Integer)) {
                    return chain.proceed();
                }
                int[] config = XposedKit.readConfig(
                        XposedKit.systemServerContext(chain.getThisObject()));
                if (!isModeB(config)) {
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
        // 诊断：列出 VolumeStreamState 全部方法名，便于发现实际方法名
        StringBuilder methods = new StringBuilder();
        for (java.lang.reflect.Method m : streamState.getDeclaredMethods()) {
            if (methods.length() > 0) methods.append(", ");
            methods.append(m.getName());
        }
        XposedKit.log("VolumeStreamState methods: [" + methods + "]");
        int hooked = XposedKit.hookAllMethodsNamed(module, streamState,
                METHOD_SET_STREAM_VOLUME_INDEX, new SetStreamVolumeIndexHooker());
        XposedKit.log(streamState.getName() + "#" + METHOD_SET_STREAM_VOLUME_INDEX
                + " hooked: " + hooked);
        if (hooked == 0) {
            XposedKit.logError("setStreamVolumeIndex NOT FOUND on VolumeStreamState! "
                    + "Available methods: " + methods);
        }
    }

    /**
     * 模式B 备份保险：Hook {@code AudioService#setStreamVolume}，
     * 在更上层拦截音量设置。当 {@code setStreamVolumeIndex} Hook 未生效时，
     * 此 Hook 作为第二道防线直接限制传入的音量档位。
     */
    private static void hookSoftwareVolumeLimit(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> audioService = classLoader.loadClass(XposedKit.AUDIO_SERVICE_CLASS);
            // 诊断：列出 AudioService 中包含 "Volume" 或 "Stream" 的方法名
            StringBuilder methods = new StringBuilder();
            for (java.lang.reflect.Method m : audioService.getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("volume") || name.contains("stream")) {
                    if (methods.length() > 0) methods.append(", ");
                    methods.append(m.getName());
                }
            }
            XposedKit.log("AudioService volume/stream methods: [" + methods + "]");
            int hooked = XposedKit.hookAllMethodsNamed(module, audioService,
                    METHOD_SET_STREAM_VOLUME, new SetStreamVolumeHooker());
            XposedKit.log(audioService.getName() + "#" + METHOD_SET_STREAM_VOLUME
                    + " hooked: " + hooked);
            if (hooked == 0) {
                XposedKit.logError("setStreamVolume NOT FOUND on AudioService! "
                        + "Volume/stream methods: " + methods);
            }
        } catch (Throwable t) {
            XposedKit.logError("hook " + METHOD_SET_STREAM_VOLUME + " failed: " + t);
        }
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
                    int[] diagConfig = XposedKit.readConfig(XposedKit.systemServerContext(null));
                    XposedKit.log("setStreamVolumeIndex first fire: index="
                            + index + " device=" + device
                            + " config=" + (diagConfig != null
                                    ? java.util.Arrays.toString(diagConfig) : "null"));
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
                    audioService = sAudioService;
                    if (audioService == null) {
                        return chain.proceed();
                    }
                }
                int[] config = XposedKit.readConfig(
                        XposedKit.systemServerContext(audioService));
                if (config == null || config[0] == 0) {
                    return chain.proceed();
                }
                // 仅模式B；模式A 保持绝对音量，不在 system_server 修改
                if (!isModeB(config) || config.length < 9) {
                    return chain.proceed();
                }
                int minAbs = config[6];
                int maxAbs = config[7];
                int attenMultiplier = config[8];
                // 无需衰减时直接放行
                if (maxAbs >= Prefs.AVRCP_MAX_VOLUME && minAbs <= 0
                        && attenMultiplier == 100) {
                    return chain.proceed();
                }
                int maxSteps = XposedKit.getIntField(state, FIELD_INDEX_MAX) / 10;
                if (maxSteps <= 0) {
                    return chain.proceed();
                }
                // 第一层：curve 映射
                int mapped = Avrcp.curveToSystemIndex(index, maxSteps, minAbs, maxAbs);
                // 第二层：衰减乘数
                if (attenMultiplier != 100 && attenMultiplier >= 0) {
                    mapped = (int) Math.round(mapped * attenMultiplier / 100.0);
                    mapped = Math.max(0, Math.min(maxSteps, mapped));
                }
                if (mapped >= index) {
                    return chain.proceed();
                }
                if (index != sLastLoggedSystemIndex) {
                    sLastLoggedSystemIndex = index;
                    XposedKit.log("modeB remap: " + index + "/" + maxSteps
                            + " -> " + mapped + " (range=" + minAbs + "~" + maxAbs
                            + " mul=" + attenMultiplier + "%"
                            + " device=" + device + ")");
                }
                Object[] newArgs = args.toArray();
                newArgs[0] = mapped;
                return chain.proceed(newArgs);
            } catch (Throwable t) {
                XposedKit.logOnce("vol-index-err",
                        "setStreamVolumeIndex hook error: " + t);
                return chain.proceed();
            }
        }
    }

    /**
     * 模式B 备份保险：Hook {@code AudioService#setStreamVolume}，
     * 当 {@code setStreamVolumeIndex} Hook 未生效时作为第二道防线。
     * 仅处理模式B，模式A 直接放行。
     */
    private static final class SetStreamVolumeHooker implements XposedInterface.Hooker {
        private static boolean sFirstFireLogged;
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            List<Object> args = chain.getArgs();
            try {
                if (args.size() < 2 || !(args.get(0) instanceof Integer)
                        || !(args.get(1) instanceof Integer)) {
                    return chain.proceed();
                }
                int streamType = (Integer) args.get(0);
                if (streamType != Prefs.STREAM_MUSIC_INDEX) {
                    return chain.proceed();
                }
                Object audioService = chain.getThisObject();
                sAudioService = audioService;
                if (!sFirstFireLogged) {
                    sFirstFireLogged = true;
                    XposedKit.log("setStreamVolume backup fired: streamType="
                            + streamType + " index=" + args.get(1));
                }
                int[] config = XposedKit.readConfig(
                        XposedKit.systemServerContext(audioService));
                if (config == null || config[0] == 0) {
                    return chain.proceed();
                }
                // 仅模式B；模式A 直接放行
                if (!isModeB(config) || config.length < 9) {
                    return chain.proceed();
                }
                int minAbs = config[6];
                int maxAbs = config[7];
                int attenMultiplier = config[8];
                if (maxAbs >= Prefs.AVRCP_MAX_VOLUME && minAbs <= 0
                        && attenMultiplier == 100) {
                    return chain.proceed();
                }
                int index = (Integer) args.get(1);
                if (index <= 0) {
                    return chain.proceed();
                }
                int maxSteps = readMediaStreamMaxSteps(audioService);
                if (maxSteps <= 0) {
                    return chain.proceed();
                }
                int mapped = Avrcp.curveToSystemIndex(index, maxSteps, minAbs, maxAbs);
                if (attenMultiplier != 100 && attenMultiplier >= 0) {
                    mapped = (int) Math.round(mapped * attenMultiplier / 100.0);
                    mapped = Math.max(0, Math.min(maxSteps, mapped));
                }
                if (mapped >= index) {
                    return chain.proceed();
                }
                if (index != sLastLoggedSystemIndex) {
                    sLastLoggedSystemIndex = index;
                    XposedKit.log("modeB setStreamVolume cap: " + index
                            + " -> " + mapped + "/" + maxSteps
                            + " (range=" + minAbs + "~" + maxAbs
                            + " mul=" + attenMultiplier + "%)");
                }
                Object[] newArgs = args.toArray();
                newArgs[1] = mapped;
                return chain.proceed(newArgs);
            } catch (Throwable t) {
                XposedKit.logOnce("vol-sv-err",
                        "setStreamVolume hook error: " + t);
                return chain.proceed();
            }
        }
    }


    // ==================== 模式B 底层音量映射：AudioSystem.setStreamVolumeIndex ====================

    /**
     * 模式B 底层音量映射：Hook {@code AudioSystem#setStreamVolumeIndex(int, int)}，
     * 在原生层应用音量前重新映射音量索引。
     *
     * <p>滑块位置不受影响（可自由到 100%），但实际应用到音频策略的索引被降低。
     * 例如：滑块=127（100%），maxAbs=62 → 实际应用到原生层的索引=62（49%）。</p>
     */
    private static void hookAudioSystemVolumeIndex(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> audioSystem = classLoader.loadClass("android.media.AudioSystem");
            // 尝试 hook setStreamVolumeIndex(int, int) 和 setStreamVolumeIndex(int, int, int)
            int hooked = XposedKit.hookAllMethodsNamed(module, audioSystem,
                    "setStreamVolumeIndex", new AudioSystemVolumeIndexHooker());
            XposedKit.log("AudioSystem#setStreamVolumeIndex hooked: " + hooked);
            if (hooked == 0) {
                XposedKit.logError("AudioSystem#setStreamVolumeIndex NOT FOUND!");
            }
        } catch (Throwable t) {
            XposedKit.logError("hook AudioSystem.setStreamVolumeIndex failed: " + t);
        }
    }

    /**
     * AudioSystem.setStreamVolumeIndex 拦截：模式B 下将音量索引映射到降低的值。
     * 支持多种签名：(int, int), (int, int, int) 等。
     */
    private static final class AudioSystemVolumeIndexHooker implements XposedInterface.Hooker {
        private static boolean sFirstFireLogged;

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            java.util.List<Object> args = chain.getArgs();
            try {
                // 至少需要 stream + index 两个参数
                if (args.size() < 2 || !(args.get(0) instanceof Integer)
                        || !(args.get(1) instanceof Integer)) {
                    return chain.proceed();
                }
                int stream = (Integer) args.get(0);
                // 只处理媒体流
                if (stream != Prefs.STREAM_MUSIC_INDEX) {
                    return chain.proceed();
                }
                int[] config = XposedKit.readConfig(XposedKit.systemServerContext(null));
                if (!AudioHooks.isModeB(config)) {
                    return chain.proceed();
                }
                int index = (Integer) args.get(1);
                if (index <= 0) {
                    return chain.proceed();
                }
                // maxAbs 控制最大音量（0~127）
                int maxAbs = config.length > 4 ? config[4] : Prefs.AVRCP_MAX_VOLUME;
                if (maxAbs >= Prefs.AVRCP_MAX_VOLUME) {
                    return chain.proceed();
                }
                // 获取当前最大档位数（通常是 15 或用户设定的值）
                int maxSteps = config.length > 1 ? config[1] : 15;
                if (maxSteps <= 0) {
                    maxSteps = 15;
                }
                // 使用曲线映射降低音量索引
                int mapped = Avrcp.curveToSystemIndex(index, maxSteps, 0, maxAbs);
                if (mapped >= index) {
                    return chain.proceed();
                }
                // 首次触发日志
                if (!sFirstFireLogged) {
                    sFirstFireLogged = true;
                    XposedKit.log("modeB AudioSystem.setStreamVolumeIndex: "
                            + index + " -> " + mapped + " (maxAbs=" + maxAbs
                            + " maxSteps=" + maxSteps + " args=" + args.size() + ")");
                }
                // 修改索引参数
                Object[] newArgs = args.toArray();
                newArgs[1] = mapped;
                return chain.proceed(newArgs);
            } catch (Throwable t) {
                XposedKit.logOnce("modeB-as-vol-err",
                        "AudioSystem.setStreamVolumeIndex hook error: " + t);
                return chain.proceed();
            }
        }
    }

    /** 判断设备号是否蓝牙 A2DP 输出（优先系统常量集合，失败回退经典掩码）。 */
    private static boolean isA2dpOutputDevice(int device) {
        Collection<Integer> cached = sA2dpDeviceSet;
        if (cached == null) {
            try {
                Class<?> audioSystem = Class.forName("android.media.AudioSystem");
                Object set = XposedKit.getStaticField(audioSystem, "DEVICE_OUT_ALL_A2DP_SET");
                if (set instanceof Collection) {
                    @SuppressWarnings("unchecked")
                    Collection<Integer> devices = (Collection<Integer>) set;
                    sA2dpDeviceSet = devices;
                    cached = devices;
                }
            } catch (Throwable t) {
                XposedKit.logError("read A2DP device set failed: " + t);
            }
        }
        if (cached != null) {
            return cached.contains(device);
        }
        return (device & 0x380) != 0;  // BLUETOOTH_A2DP | _A2DP_HEADPHONES | _A2DP_SPEAKER
    }

    /** 模式A 生效条件：模块启用且选择保持绝对音量。 */
    static boolean isModeA(int[] config) {
        return config != null && config[0] != 0 && config[2] == Prefs.BT_MODE_ABSOLUTE;
    }

    /** 模式B 生效条件：模块启用且选择停用绝对音量。 */
    static boolean isModeB(int[] config) {
        return config != null && config[0] != 0 && config[2] == Prefs.BT_MODE_SOFTWARE;
    }
}
