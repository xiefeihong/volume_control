package com.xiefeihong.volumecontrol;

import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 蓝牙进程（com.android.bluetooth）侧 Hook：音量控制模式。
 *
 * <p>Hook {@code com.android.bluetooth.avrcp.AvrcpVolumeManager}：</p>
 * <ul>
 *   <li>模式A（保持绝对音量，默认）：{@code systemToAvrcpVolume()} 原本按
 *       {@code round(档位*127/最大档位)} 线性换算，替换为低音量增强曲线，
 *       低音量区每档跨度更大，避免「第 1 档无声」与「5% 和 10% 听感相同」；
 *       {@code avrcpToSystemVolume()}（耳机音量键回调）使用反函数保持一致；</li>
 *   <li>模式B（停用绝对音量）：强制 {@code deviceConnected(device, false)} 让框架以
 *       变量音量行为处理该设备（手机端软件衰减），并屏蔽发往耳机的 AVRCP 音量，
 *       耳机固定于自身硬件音量。</li>
 * </ul>
 */
final class BtHooks {

    /** 蓝牙作用域包名。 */
    static final String BT_PACKAGE = "com.android.bluetooth";
    private static final String BT_VOLUME_MANAGER_CLASS =
            "com.android.bluetooth.avrcp.AvrcpVolumeManager";
    private static final String FIELD_DEVICE_MAX_VOLUME = "sDeviceMaxVolume";

    /** 日志节流：避免音量调节时高频刷屏。 */
    private static int sLastLoggedCurveValue = -1;
    private static boolean sModeBLogged;

    private BtHooks() {
    }

    /** 在蓝牙进程加载 AvrcpVolumeManager 相关 Hook。 */
    static void install(ClassLoader classLoader, XposedModule module) {
        final Class<?> volumeManager;
        try {
            volumeManager = classLoader.loadClass(BT_VOLUME_MANAGER_CLASS);
        } catch (Throwable t) {
            XposedKit.logError("bluetooth volume manager not found: " + t);
            return;
        }

        // 模式A：手机档位 → AVRCP 绝对音量（低音量增强曲线），连接与音量变化两条路径都会经过
        int toAvrcp = XposedKit.hookAllMethodsNamed(module, volumeManager,
                "systemToAvrcpVolume", new SystemToAvrcpHooker(volumeManager));
        XposedKit.log("systemToAvrcpVolume hooked: " + toAvrcp);

        // 模式A：耳机音量键回调（AVRCP → 手机档位）使用反函数，保持映射一致
        int toSystem = XposedKit.hookAllMethodsNamed(module, volumeManager,
                "avrcpToSystemVolume", new AvrcpToSystemHooker(volumeManager));
        XposedKit.log("avrcpToSystemVolume hooked: " + toSystem);

        // 模式B：上报设备不支持绝对音量 → 框架按软件衰减处理
        int connected = hookDeviceConnected(module, volumeManager);
        XposedKit.log("deviceConnected hooked: " + connected);

        // 查询接口：模式B 返回 false，模式A 返回 true（覆盖缓存实现即时切换）
        int supported = hookAbsoluteVolumeSupported(module, volumeManager);
        XposedKit.log("getAbsoluteVolumeSupported hooked: " + supported);

        // 模式B：屏蔽发往耳机的 AVRCP 音量（避免双重衰减，耳机固定自身音量）
        int sendChanged = hookSendVolumeChanged(module, volumeManager);
        XposedKit.log("sendVolumeChanged hooked: " + sendChanged);

        XposedKit.log(BT_VOLUME_MANAGER_CLASS + " hooks installed");
    }

    /** 模式A 生效条件：模块启用且选择保持绝对音量。 */
    private static boolean isModeA(int[] config) {
        return config != null && config[0] != 0 && config[2] == Prefs.BT_MODE_ABSOLUTE;
    }

    /** 读取音量管理器记录的系统最大档位（sDeviceMaxVolume）；失败返回 -1。 */
    private static int readDeviceMaxVolume(Class<?> volumeManager) {
        try {
            return XposedKit.getStaticIntField(volumeManager, FIELD_DEVICE_MAX_VOLUME);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 模式A：换算结果替换为线性范围映射（保持绝对音量语义）。 */
    private static final class SystemToAvrcpHooker implements XposedInterface.Hooker {
        private final Class<?> volumeManager;

        SystemToAvrcpHooker(Class<?> volumeManager) {
            this.volumeManager = volumeManager;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                int[] config = XposedKit.readConfig(XposedKit.bluetoothContext());
                if (!isModeA(config)) {
                    return result;
                }
                int maxSteps = readDeviceMaxVolume(volumeManager);
                if (maxSteps <= 0) {
                    return result;
                }
                Object arg0 = chain.getArg(0);
                if (!(arg0 instanceof Integer) || !(result instanceof Integer)) {
                    return result;
                }
                int step = (Integer) arg0;
                int minA = config[3];
                int maxA = config[4];
                // 线性映射：保持绝对音量语义，低档不会无声
                int curved;
                if (minA == 0 && maxA >= Prefs.AVRCP_MAX_VOLUME) {
                    // 无范围限制时使用系统原始线性换算
                    return result;
                } else {
                    curved = (int) Math.round(
                            minA + (maxA - minA) * (double) step / maxSteps);
                    curved = Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, curved));
                }
                if (curved != (Integer) result) {
                    if (curved != sLastLoggedCurveValue) {
                        sLastLoggedCurveValue = curved;
                        XposedKit.log("modeA: step " + step + "/" + maxSteps
                                + " avrcp " + result + " -> " + curved
                                + " (range=" + minA + "~" + maxA + ")");
                    }
                    return curved;
                }
            } catch (Throwable t) {
                XposedKit.logError("modeA systemToAvrcp hook failed: " + t);
            }
            return result;
        }
    }

    /** 模式A：耳机音量键回调按线性反函数映射。 */
    private static final class AvrcpToSystemHooker implements XposedInterface.Hooker {
        private final Class<?> volumeManager;

        AvrcpToSystemHooker(Class<?> volumeManager) {
            this.volumeManager = volumeManager;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                int[] config = XposedKit.readConfig(XposedKit.bluetoothContext());
                if (!isModeA(config)) {
                    return result;
                }
                int maxSteps = readDeviceMaxVolume(volumeManager);
                if (maxSteps <= 0) {
                    return result;
                }
                Object arg0 = chain.getArg(0);
                if (!(arg0 instanceof Integer)) {
                    return result;
                }
                int minA = config[3];
                int maxA = config[4];
                if (minA == 0 && maxA >= Prefs.AVRCP_MAX_VOLUME) {
                    return result; // 无反向映射需要
                }
                int avrcp = (Integer) arg0;
                // 线性反函数：step = (avrcp - minA) * maxSteps / (maxA - minA)
                int range = maxA - minA;
                if (range <= 0) return result;
                int step = (int) Math.round((avrcp - minA) * (double) maxSteps / range);
                return Math.max(0, Math.min(maxSteps, step));
            } catch (Throwable t) {
                XposedKit.logError("modeA avrcpToSystem hook failed: " + t);
            }
            return result;
        }
    }

    /** 模式B：设备连接上报改为“不支持绝对音量”。 */
    private static int hookDeviceConnected(XposedModule module, Class<?> volumeManager) {
        int count = 0;
        for (java.lang.reflect.Method method : volumeManager.getDeclaredMethods()) {
            if (!"deviceConnected".equals(method.getName())) {
                continue;
            }
            try {
                module.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            List<Object> args = chain.getArgs();
                            if (args.size() >= 2 && Boolean.TRUE.equals(args.get(1))) {
                                int[] config = XposedKit.readConfig(
                                        XposedKit.bluetoothContext());
                                if (AudioHooks.isModeB(config)) {
                                    if (!sModeBLogged) {
                                        sModeBLogged = true;
                                        XposedKit.log("modeB: force absoluteVolume=false");
                                    }
                                    Object[] newArgs = args.toArray();
                                    newArgs[1] = Boolean.FALSE;
                                    return chain.proceed(newArgs);
                                }
                            }
                        } catch (Throwable t) {
                            XposedKit.logError("deviceConnected hook failed: " + t);
                        }
                        return chain.proceed();
                    }
                });
                count++;
            } catch (Throwable t) {
                XposedKit.logError("hook deviceConnected failed: " + t);
            }
        }
        return count;
    }

    /** 查询接口：模式B 返回 false，模式A 返回 true（覆盖缓存状态，实现即时切换）。 */
    private static int hookAbsoluteVolumeSupported(XposedModule module, Class<?> volumeManager) {
        int count = 0;
        for (java.lang.reflect.Method method : volumeManager.getDeclaredMethods()) {
            if (!"getAbsoluteVolumeSupported".equals(method.getName())) {
                continue;
            }
            try {
                module.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            int[] config = XposedKit.readConfig(XposedKit.bluetoothContext());
                            if (config == null || config[0] == 0) {
                                return chain.proceed(); // 模块禁用，使用原始值
                            }
                            if (AudioHooks.isModeB(config)) {
                                return Boolean.FALSE;
                            }
                            // 模式A：强制报告支持绝对音量（覆盖连接时缓存的 false）
                            return Boolean.TRUE;
                        } catch (Throwable t) {
                            XposedKit.logError("getAbsoluteVolumeSupported hook failed: " + t);
                        }
                        return chain.proceed();
                    }
                });
                count++;
            } catch (Throwable t) {
                XposedKit.logError("hook getAbsoluteVolumeSupported failed: " + t);
            }
        }
        return count;
    }

    /** 模式B：屏蔽发往耳机的 AVRCP 音量。 */
    private static int hookSendVolumeChanged(XposedModule module, Class<?> volumeManager) {
        int count = 0;
        for (java.lang.reflect.Method method : volumeManager.getDeclaredMethods()) {
            if (!"sendVolumeChanged".equals(method.getName())) {
                continue;
            }
            try {
                module.hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            int[] config = XposedKit.readConfig(XposedKit.bluetoothContext());
                            if (AudioHooks.isModeB(config)) {
                                return null;
                            }
                        } catch (Throwable t) {
                            XposedKit.logError("sendVolumeChanged hook failed: " + t);
                        }
                        return chain.proceed();
                    }
                });
                count++;
            } catch (Throwable t) {
                XposedKit.logError("hook sendVolumeChanged failed: " + t);
            }
        }
        return count;
    }
}
