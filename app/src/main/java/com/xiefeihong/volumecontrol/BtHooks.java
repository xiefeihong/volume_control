package com.xiefeihong.volumecontrol;

import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 蓝牙进程（com.android.bluetooth）侧 Hook：音量控制模式。
 *
 * <p>Hook {@code com.android.bluetooth.avrcp.AvrcpVolumeManager}：</p>
 * <ul>
 *   <li>模式A（保持绝对音量，默认）：{@code systemToAvrcpVolume()} 使用低音量增强曲线
 *       （sqrt 映射），与 UI 预览保持一致；{@code avrcpToSystemVolume()}（耳机音量键回调）
 *       使用曲线反函数保持双向一致；</li>
 *   <li>模式B（停用绝对音量）：{@code deviceConnected} 强制上报不支持 + {@code sendVolumeChanged}
 *       阻断 AVRCP 发送。耳机固定于自身硬件音量，手机端通过 AudioService 软件衰减控制音量。</li>
 * </ul>
 *
 * <p>切换模式（A↔B）后需重启蓝牙使 {@code deviceConnected} 重新触发。</p>
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

        // 模式A：手机档位 → AVRCP 绝对音量（sqrt 低音量增强曲线）
        int toAvrcp = XposedKit.hookAllMethodsNamed(module, volumeManager,
                "systemToAvrcpVolume", new SystemToAvrcpHooker(volumeManager));
        XposedKit.log("systemToAvrcpVolume hooked: " + toAvrcp);

        // 模式A：耳机音量键回调（AVRCP → 手机档位）使用曲线反函数，保持映射一致
        int toSystem = XposedKit.hookAllMethodsNamed(module, volumeManager,
                "avrcpToSystemVolume", new AvrcpToSystemHooker(volumeManager));
        XposedKit.log("avrcpToSystemVolume hooked: " + toSystem);

        // 模式B：设备连接上报改为"不支持绝对音量"（使 AudioService 走软件衰减路径）
        int connected = hookDeviceConnected(module, volumeManager);
        XposedKit.log("deviceConnected hooked: " + connected);

        // 模式B：屏蔽发往耳机的 AVRCP 音量（避免双重衰减，耳机固定自身音量）
        int sendChanged = hookSendVolumeChanged(module, volumeManager);
        XposedKit.log("sendVolumeChanged hooked: " + sendChanged);

        XposedKit.log(BT_VOLUME_MANAGER_CLASS + " hooks installed");
    }

    /** 读取音量管理器记录的系统最大档位（sDeviceMaxVolume）；失败返回 -1。 */
    private static int readDeviceMaxVolume(Class<?> volumeManager) {
        try {
            return XposedKit.getStaticIntField(volumeManager, FIELD_DEVICE_MAX_VOLUME);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 模式A：系统档位 → AVRCP 绝对音量。
     * 使用 {@link Avrcp#curveToAbsoluteVolume} 可选曲线映射，与预览完全一致。
     */
    private static final class SystemToAvrcpHooker implements XposedInterface.Hooker {
        private final Class<?> volumeManager;

        SystemToAvrcpHooker(Class<?> volumeManager) {
            this.volumeManager = volumeManager;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                VolumeConfig config = XposedKit.readConfig(XposedKit.bluetoothContext());
                VolumeMode m = VolumeMode.forBluetoothAvrcp(config);
                if (m == null) {
                    return result; // 非模式A（含未启用/模式B）走系统原始换算
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
                VolumeConfig.Range range = m.range(config);
                int minA = range.min;
                int maxA = range.max;
                int curveType = range.curve;
                // 无范围限制时保持系统原始线性换算
                if (minA == 0 && maxA >= Prefs.AVRCP_MAX_VOLUME) {
                    return result;
                }
                // 按所选曲线映射（与 Avrcp.curveToAbsoluteVolume 一致）
                int curved = Avrcp.curveToAbsoluteVolume(step, maxSteps, minA, maxA, curveType);
                if (curved != (Integer) result) {
                    if (curved != sLastLoggedCurveValue) {
                        sLastLoggedCurveValue = curved;
                        XposedKit.log("modeA: step " + step + "/" + maxSteps
                                + " avrcp " + result + " -> " + curved
                                + " (range=" + minA + "~" + maxA
                                + " curve=" + Avrcp.curveLabel(curveType) + ")");
                    }
                    return curved;
                }
            } catch (Throwable t) {
                XposedKit.logError("modeA systemToAvrcp hook failed: " + t);
            }
            return result;
        }
    }

    /**
     * 模式A：耳机 AVRCP 音量 → 系统档位。
     * 使用 {@link Avrcp#curveToSystemStep} 反函数，与正向所选曲线保持一致。
     */
    private static final class AvrcpToSystemHooker implements XposedInterface.Hooker {
        private final Class<?> volumeManager;

        AvrcpToSystemHooker(Class<?> volumeManager) {
            this.volumeManager = volumeManager;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                VolumeConfig config = XposedKit.readConfig(XposedKit.bluetoothContext());
                VolumeMode m = VolumeMode.forBluetoothAvrcp(config);
                if (m == null) {
                    return result; // 非模式A（含未启用/模式B）无需反算
                }
                int maxSteps = readDeviceMaxVolume(volumeManager);
                if (maxSteps <= 0) {
                    return result;
                }
                Object arg0 = chain.getArg(0);
                if (!(arg0 instanceof Integer)) {
                    return result;
                }
                VolumeConfig.Range range = m.range(config);
                int minA = range.min;
                int maxA = range.max;
                int curveType = range.curve;
                if (minA == 0 && maxA >= Prefs.AVRCP_MAX_VOLUME) {
                    return result; // 无范围限制时无需反算
                }
                int avrcp = (Integer) arg0;
                // 曲线反函数（按所选类型）
                int step = Avrcp.curveToSystemStep(avrcp, maxSteps, minA, maxA, curveType);
                return Math.max(0, Math.min(maxSteps, step));
            } catch (Throwable t) {
                XposedKit.logError("modeA avrcpToSystem hook failed: " + t);
            }
            return result;
        }
    }

    /** 模式B：设备连接时上报"不支持绝对音量"，使 AudioService 走本地软件衰减路径。 */
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
                                VolumeConfig config = XposedKit.readConfig(
                                        XposedKit.bluetoothContext());
                                if (VolumeMode.suppressAbsoluteVolume(config)) {
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

    /** 模式B：屏蔽发往耳机的 AVRCP 音量命令（耳机固定自身硬件音量）。 */
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
                            VolumeConfig config = XposedKit.readConfig(XposedKit.bluetoothContext());
                            if (VolumeMode.suppressAbsoluteVolume(config)) {
                                return null; // 模式B：阻止发送 AVRCP
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
