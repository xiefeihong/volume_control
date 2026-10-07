package com.xiefeihong.volumecontrol;

/**
 * 音量模式的统一抽象（「枚举即策略」），App 与 Xposed Hook 共用。
 *
 * <p>仅描述「绝对音量(A) / 相对音量(B) / 默认直通」三种曲线语义本身：该模式是否在
 * system_server 软件衰减、是否在蓝牙进程走 AVRCP。具体「某设备用哪个模式、范围是多少」
 * 已随按设备配置迁入 {@link VolumeConfig}（{@code volumeModeFor(d)} / {@code activeRangeFor(d)}）
 * 与 {@link DeviceConfig}，本枚举不再携带全局 SharedPreferences 键或全局解析入口。</p>
 *
 * <p>重要：本类与 {@link Prefs}/{@link Avrcp}/{@link VolumeConfig}/{@link OutputDevice} 一样
 * 同时被 App 进程与 Hook 端（system_server / 蓝牙进程）使用，禁止引用任何 Xposed API 类。</p>
 */
public enum VolumeMode {

    /** 模式A：蓝牙 AVRCP 绝对音量，档位映射为 0~127 直接发送给耳机（蓝牙进程）。 */
    ABSOLUTE {
        @Override public boolean attenuatesInSystemServer() { return false; }
        @Override public boolean drivesAvrcp() { return true; }
    },

    /** 模式B：停用绝对音量，由手机端在 system_server 软件衰减音频。 */
    SOFTWARE {
        @Override public boolean attenuatesInSystemServer() { return true; }
        @Override public boolean drivesAvrcp() { return false; }
    },

    /**
     * 默认（系统直通）模式：不重映射、不改档位数。任何生效路径都先经
     * {@link VolumeConfig#remapActive()} 与该设备 {@link DeviceConfig#isDefault()} 判定，
     * 默认直通时不会取到本枚举去重映射。
     */
    DEFAULT {
        @Override public boolean attenuatesInSystemServer() { return false; }
        @Override public boolean drivesAvrcp() { return false; }
    };

    /** 是否天然在 system_server 通过改写系统音量档位做软件衰减（模式B）。 */
    public abstract boolean attenuatesInSystemServer();

    /** 是否在蓝牙进程做 AVRCP 绝对音量映射（模式A）。 */
    public abstract boolean drivesAvrcp();

    /** 供 App：由模式 id 得到枚举（不判断启用状态；值=0 返回 {@link #DEFAULT}）。 */
    public static VolumeMode ofBtMode(int btModeId) {
        if (btModeId == Prefs.BT_MODE_SOFTWARE) {
            return SOFTWARE;
        }
        if (btModeId == Prefs.BT_MODE_DEFAULT) {
            return DEFAULT;
        }
        return ABSOLUTE;
    }

    /** 供 App：枚举写回模式 id。 */
    public int modeId() {
        if (this == SOFTWARE) {
            return Prefs.BT_MODE_SOFTWARE;
        }
        if (this == DEFAULT) {
            return Prefs.BT_MODE_DEFAULT;
        }
        return Prefs.BT_MODE_ABSOLUTE;
    }
}
