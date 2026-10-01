package com.xiefeihong.volumecontrol;

/**
 * 三种音量模式的统一抽象（「枚举即策略」），App 与 Xposed Hook 共用。
 *
 * <p>每个枚举常量封装：该模式在 {@link VolumeConfig} 中对应的 {@link VolumeConfig.Range}、
 * SharedPreferences 键、映射空间（AVRCP 绝对音量 vs system_server 软件衰减）、以及适用的
 * 输出设备。配置读取统一为 {@code mode.range(cfg)}（如 {@code .min}/{@code .max}/{@code .curve}），
 * 不再使用魔法下标。</p>
 *
 * <p>重要：本类与 {@link Prefs}/{@link Avrcp}/{@link VolumeConfig} 一样同时被 App 进程与
 * Hook 端（system_server / 蓝牙进程）使用，禁止引用任何 Xposed API 类。</p>
 */
public enum VolumeMode {

    /** 模式A：蓝牙 AVRCP 绝对音量，档位映射为 0~127 直接发送给耳机（蓝牙进程）。 */
    ABSOLUTE {
        @Override public boolean attenuatesInSystemServer() { return false; }
        @Override public boolean drivesAvrcp() { return true; }
        @Override public VolumeConfig.Range range(VolumeConfig cfg) { return cfg.absolute; }
        @Override public String minKey() { return Prefs.KEY_MIN_ABS_A; }
        @Override public String maxKey() { return Prefs.KEY_MAX_ABS_A; }
        @Override public String curveKey() { return Prefs.KEY_CURVE_TYPE_A; }
    },

    /** 模式B：停用绝对音量，由手机端在 system_server 软件衰减音频（仅蓝牙）。 */
    SOFTWARE {
        @Override public boolean attenuatesInSystemServer() { return true; }
        @Override public boolean drivesAvrcp() { return false; }
        @Override public VolumeConfig.Range range(VolumeConfig cfg) { return cfg.software; }
        @Override public String minKey() { return Prefs.KEY_MIN_ABS_B; }
        @Override public String maxKey() { return Prefs.KEY_MAX_ABS_B; }
        @Override public String curveKey() { return Prefs.KEY_CURVE_TYPE_B; }
    },

    /** 耳机模式：有线耳机 + 外放扬声器，在 system_server 软件衰减（与蓝牙模式无关）。 */
    WIRED {
        @Override public boolean attenuatesInSystemServer() { return true; }
        @Override public boolean drivesAvrcp() { return false; }
        @Override public VolumeConfig.Range range(VolumeConfig cfg) { return cfg.wired; }
        @Override public String minKey() { return Prefs.KEY_MIN_ABS_W; }
        @Override public String maxKey() { return Prefs.KEY_MAX_ABS_W; }
        @Override public String curveKey() { return Prefs.KEY_CURVE_TYPE_W; }
    };

    /** 是否在 system_server 通过改写系统音量档位做软件衰减（模式B/耳机模式）。 */
    public abstract boolean attenuatesInSystemServer();

    /** 是否在蓝牙进程做 AVRCP 绝对音量映射（模式A）。 */
    public abstract boolean drivesAvrcp();

    /** 该模式在配置中对应的音量范围（最小~最大 + 曲线）。 */
    public abstract VolumeConfig.Range range(VolumeConfig config);

    /** 该模式最小音量对应的 SharedPreferences 键。 */
    public abstract String minKey();

    /** 该模式最大音量对应的 SharedPreferences 键。 */
    public abstract String maxKey();

    /** 该模式映射曲线对应的 SharedPreferences 键。 */
    public abstract String curveKey();

    // ==================== 设备判定（纯 int 位掩码，自 AudioHooks 迁移） ====================

    /**
     * 判断 setStreamVolumeIndex 的 device 是否为蓝牙输出。
     *
     * <p>采用 AudioSystem {@code DEVICE_OUT} 位掩码白名单（实测日志 device 为该位掩码：
     * 如 0x80=128→A2DP、0x10=16→SCO、0x4=4→有线耳麦），仅当命中蓝牙输出位才返回 true。</p>
     *
     * <p>蓝牙输出位：SCO 0x10/0x20/0x40、A2DP 0x80/0x100/0x200、
     * BLE Headset/Speaker/Broadcast 0x800000/0x1000000/0x2000000。</p>
     */
    public static boolean isBluetoothOutput(int device) {
        final int btMask = 0x70 /* SCO */ | 0x380 /* A2DP */ | 0x3800000 /* BLE */;
        return (device & btMask) != 0;
    }

    /**
     * 是否为可识别的有线耳机/外放扬声器 device-out（耳机模式白名单）。
     *
     * <p>覆盖：受话器 0x1、内置扬声器 0x2、有线耳麦 0x4、有线耳机 0x8、
     * 模拟/数字底座 0x800/0x1000、USB 附件/设备 0x2000/0x4000、线路输出 0x20000。
     * 蓝牙优先排除（见 {@link #isBluetoothOutput}）。</p>
     */
    public static boolean isWiredOrSpeakerOutput(int device) {
        if (isBluetoothOutput(device)) {
            return false;
        }
        final int wiredMask = 0x1 | 0x2 | 0x4 | 0x8 /* earpiece/speaker/wired */
                | 0x800 | 0x1000 /* analog/digital dock */
                | 0x2000 | 0x4000 /* usb accessory/device */
                | 0x20000 /* line */;
        return (device & wiredMask) != 0;
    }

    // ==================== 解析入口 ====================

    /** 蓝牙侧用户所选模式（btMode）；配置为 null 或未启用返回 null。 */
    public static VolumeMode btModeOf(VolumeConfig config) {
        if (config == null || !config.enabled) {
            return null;
        }
        return config.btMode == Prefs.BT_MODE_SOFTWARE ? SOFTWARE : ABSOLUTE;
    }

    /** 供 App：由蓝牙模式 id 得到枚举（不判断启用状态）。 */
    public static VolumeMode ofBtMode(int btModeId) {
        return btModeId == Prefs.BT_MODE_SOFTWARE ? SOFTWARE : ABSOLUTE;
    }

    /** 供 App：枚举写回 btMode 的模式 id。 */
    public int modeId() {
        return this == SOFTWARE ? Prefs.BT_MODE_SOFTWARE : Prefs.BT_MODE_ABSOLUTE;
    }

    /**
     * system_server 应执行软件衰减的模式（SOFTWARE / WIRED），否则返回 null。
     * 蓝牙 + 模式A（AVRCP 由蓝牙进程处理）或未识别设备一律 null → 调用方放行。
     */
    public static VolumeMode forSystemServer(VolumeConfig config, int device) {
        if (config == null || !config.enabled) {
            return null;
        }
        VolumeMode m;
        if (isWiredOrSpeakerOutput(device)) {
            m = WIRED;
        } else if (isBluetoothOutput(device)) {
            m = btModeOf(config);
        } else {
            return null; // 未识别设备保守放行，避免误伤
        }
        return (m != null && m.attenuatesInSystemServer()) ? m : null;
    }

    /** 蓝牙进程应执行 AVRCP 映射的模式（ABSOLUTE），否则返回 null。 */
    public static VolumeMode forBluetoothAvrcp(VolumeConfig config) {
        VolumeMode m = btModeOf(config);
        return (m != null && m.drivesAvrcp()) ? m : null;
    }

    /** 是否处于「停用绝对音量」的蓝牙软件模式（替代 isModeB 的抑制用途）。 */
    public static boolean suppressAbsoluteVolume(VolumeConfig config) {
        return btModeOf(config) == SOFTWARE;
    }
}
