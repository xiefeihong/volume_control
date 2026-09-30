package com.xiefeihong.volumecontrol;

/**
 * 三种音量模式的统一抽象（「枚举即策略」），App 与 Xposed Hook 共用。
 *
 * <p>每个枚举常量封装：配置数组 config[] 的索引（含历史长度回退）、SharedPreferences
 * 键、映射空间（AVRCP 绝对音量 vs system_server 软件衰减）、以及适用的输出设备。</p>
 *
 * <p>重要：本类与 {@link Prefs}/{@link Avrcp} 一样同时被 App 进程与 Hook 端
 * （system_server / 蓝牙进程）使用，禁止引用任何 Xposed API 类。</p>
 */
public enum VolumeMode {

    /** 模式A：蓝牙 AVRCP 绝对音量，档位映射为 0~127 直接发送给耳机（蓝牙进程）。 */
    ABSOLUTE {
        @Override public boolean attenuatesInSystemServer() { return false; }
        @Override public boolean drivesAvrcp() { return true; }
        @Override public boolean available(int[] c) { return c.length >= 5; }
        @Override public int minAbs(int[] c) { return c[3]; }
        @Override public int maxAbs(int[] c) { return c[4]; }
        @Override public int curveType(int[] c) { return c.length >= 8 ? c[7] : Prefs.CURVE_LOG; }
        @Override public String minKey() { return Prefs.KEY_MIN_ABS_A; }
        @Override public String maxKey() { return Prefs.KEY_MAX_ABS_A; }
        @Override public String curveKey() { return Prefs.KEY_CURVE_TYPE_A; }
    },

    /** 模式B：停用绝对音量，由手机端在 system_server 软件衰减音频（仅蓝牙）。 */
    SOFTWARE {
        @Override public boolean attenuatesInSystemServer() { return true; }
        @Override public boolean drivesAvrcp() { return false; }
        @Override public boolean available(int[] c) { return c.length >= 7; }
        @Override public int minAbs(int[] c) { return c[5]; }
        @Override public int maxAbs(int[] c) { return c[6]; }
        @Override public int curveType(int[] c) {
            return c.length >= 9 ? c[8] : (c.length >= 8 ? c[7] : Prefs.CURVE_LOG);
        }
        @Override public String minKey() { return Prefs.KEY_MIN_ABS_B; }
        @Override public String maxKey() { return Prefs.KEY_MAX_ABS_B; }
        @Override public String curveKey() { return Prefs.KEY_CURVE_TYPE_B; }
    },

    /** 耳机模式：有线耳机 + 外放扬声器，在 system_server 软件衰减（与蓝牙模式无关）。 */
    WIRED {
        @Override public boolean attenuatesInSystemServer() { return true; }
        @Override public boolean drivesAvrcp() { return false; }
        @Override public boolean available(int[] c) { return c.length >= 12; }
        @Override public int minAbs(int[] c) { return c[9]; }
        @Override public int maxAbs(int[] c) { return c[10]; }
        @Override public int curveType(int[] c) { return c[11]; }
        @Override public String minKey() { return Prefs.KEY_MIN_ABS_W; }
        @Override public String maxKey() { return Prefs.KEY_MAX_ABS_W; }
        @Override public String curveKey() { return Prefs.KEY_CURVE_TYPE_W; }
    };

    /** 是否在 system_server 通过改写系统音量档位做软件衰减（模式B/耳机模式）。 */
    public abstract boolean attenuatesInSystemServer();

    /** 是否在蓝牙进程做 AVRCP 绝对音量映射（模式A）。 */
    public abstract boolean drivesAvrcp();

    /** 配置数组是否含该模式所需字段（历史长度兼容）。 */
    public abstract boolean available(int[] config);

    /** 从配置数组读取该模式的最小音量（config 索引由常量自身决定）。 */
    public abstract int minAbs(int[] config);

    /** 从配置数组读取该模式的最大音量。 */
    public abstract int maxAbs(int[] config);

    /** 从配置数组读取该模式的映射曲线类型（含回退）。 */
    public abstract int curveType(int[] config);

    /** 该模式最小音量对应的 SharedPreferences 键。 */
    public abstract String minKey();

    /** 该模式最大音量对应的 SharedPreferences 键。 */
    public abstract String maxKey();

    /** 该模式映射曲线对应的 SharedPreferences 键。 */
    public abstract String curveKey();

    // ==================== 设备判定（纯 int 位掩码，自 AudioHooks 迁移） ====================

    /**
     * 判断 setStreamVolumeIndex 的 device 是否为蓝牙输出。
     * 采用「白名单非蓝牙才 remap」策略：仅识别 AudioSystem DEVICE_OUT 位掩码的蓝牙段（SCO+A2DP+BLE）。
     * 校验提示：若运行时日志显示 device 为小整数（如 speaker=2/wired=3~4、A2DP=8），
     * 则 device 实为 AudioDeviceInfo type，需改用蓝牙类型集 {7,8,26,27,30}。
     */
    public static boolean isBluetoothOutput(int device) {
        // DEVICE_OUT: SCO 0x40/0x80/0x100 + A2DP 0x200/0x400/0x800 = 0xFC0；BLE 输出高位段
        final int btMask = 0xFC0 | 0x1C000;
        return (device & btMask) != 0;
    }

    /** 是否为可识别的有线耳机/外放扬声器 device-out（耳机模式白名单）。 */
    public static boolean isWiredOrSpeakerOutput(int device) {
        if (isBluetoothOutput(device)) {
            return false;
        }
        // EARPIECE 0x1 | SPEAKER 0x2 | WIRED_HEADPHONE 0x4 | HEADPHONES 0x8 | WIRED_HEADSET 0x10
        final int wiredMask = 0x1 | 0x2 | 0x4 | 0x8 | 0x10;
        return (device & wiredMask) != 0;
    }

    // ==================== 解析入口 ====================

    /** 蓝牙侧用户所选模式（config[2]）；模块未启用或配置过短返回 null。 */
    public static VolumeMode btModeOf(int[] config) {
        if (config == null || config.length < 3 || config[0] == 0) {
            return null;
        }
        return config[2] == Prefs.BT_MODE_SOFTWARE ? SOFTWARE : ABSOLUTE;
    }

    /** 供 App：由蓝牙模式 id 得到枚举（不判断启用状态）。 */
    public static VolumeMode ofBtMode(int btModeId) {
        return btModeId == Prefs.BT_MODE_SOFTWARE ? SOFTWARE : ABSOLUTE;
    }

    /** 供 App：枚举写回 config[2] 的模式 id。 */
    public int modeId() {
        return this == SOFTWARE ? Prefs.BT_MODE_SOFTWARE : Prefs.BT_MODE_ABSOLUTE;
    }

    /**
     * system_server 应执行软件衰减的模式（SOFTWARE / WIRED），否则返回 null。
     * 蓝牙 + 模式A（AVRCP 由蓝牙进程处理）或未识别设备一律 null → 调用方放行。
     */
    public static VolumeMode forSystemServer(int[] config, int device) {
        if (config == null || config.length < 1 || config[0] == 0) {
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
        return (m != null && m.attenuatesInSystemServer() && m.available(config)) ? m : null;
    }

    /** 蓝牙进程应执行 AVRCP 映射的模式（ABSOLUTE），否则返回 null。 */
    public static VolumeMode forBluetoothAvrcp(int[] config) {
        VolumeMode m = btModeOf(config);
        return (m != null && m.drivesAvrcp() && m.available(config)) ? m : null;
    }

    /** 是否处于「停用绝对音量」的蓝牙软件模式（替代 isModeB 的抑制用途）。 */
    public static boolean suppressAbsoluteVolume(int[] config) {
        return btModeOf(config) == SOFTWARE;
    }
}
