package com.xiefeihong.volumecontrol;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置常量与读写工具。
 *
 * <p>重要：本类同时被 App 进程与 Hook 端（system_server 进程与蓝牙进程）使用，
 * 因此不能引用任何 Xposed API 类。</p>
 */
public final class Prefs {

    /** SharedPreferences 文件名，必须与 Hook 端 XSharedPreferences 读取的文件名一致。 */
    public static final String PREFS_NAME = "settings";

    /** Settings.Global 备用配置键：Hook 端优先从这里读取（system_server 一定能读到）。 */
    public static final String GLOBAL_KEY = "volume_steps_hook_config";

    /** 配置镜像文件：App 以 root 同步写入，Hook 端在 Settings 读取失败时直读该文件兜底。 */
    public static final String MIRROR_CONFIG_FILE = "/data/system/volumecontrol_config";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_MEDIA_STEPS = "media_steps";
    public static final String KEY_BT_MODE = "bt_volume_mode";
    /** 首次运行时捕获的系统原生媒体档位数（模块未覆盖时的 getStreamMaxVolume）。 */
    public static final String KEY_SYSTEM_DEFAULT_STEPS = "system_default_steps";
    /** 模式A 映射曲线类型 SharedPreferences 键。 */
    public static final String KEY_CURVE_TYPE_A = "curve_type_a";
    /** 模式B 映射曲线类型 SharedPreferences 键。 */
    public static final String KEY_CURVE_TYPE_B = "curve_type_b";

    /** 模式A 音量范围 SharedPreferences 键。 */
    public static final String KEY_MIN_ABS_A = "min_abs_volume_a";
    public static final String KEY_MAX_ABS_A = "max_abs_volume_a";

    /** 模式B 音量范围 SharedPreferences 键。 */
    public static final String KEY_MIN_ABS_B = "min_abs_volume_b";
    public static final String KEY_MAX_ABS_B = "max_abs_volume_b";

    /** 耳机模式（有线+外放）音量范围 SharedPreferences 键。 */
    public static final String KEY_MIN_ABS_W = "min_abs_volume_w";
    public static final String KEY_MAX_ABS_W = "max_abs_volume_w";
    /** 耳机模式映射曲线类型 SharedPreferences 键。 */
    public static final String KEY_CURVE_TYPE_W = "curve_type_w";

    /**
     * 蓝牙音量控制模式 A：保持绝对音量（默认）。
     * 手机档位经低音量增强曲线映射为 0~127 的绝对音量发送给耳机。
     */
    public static final int BT_MODE_ABSOLUTE = 0;

    /**
     * 蓝牙音量控制模式 B：停用绝对音量。
     * Hook 蓝牙栈使系统以手机端软件衰减控制音量，耳机固定于自身硬件音量。
     */
    public static final int BT_MODE_SOFTWARE = 1;

    /**
     * 映射曲线类型（Mode A/B 各自独立设置）：step→音量范围的中间分布。
     * 三者均保证 step=1 精确命中最小、maxSteps 精确命中最大。
     */
    public static final int CURVE_LOG = 0;
    public static final int CURVE_LINEAR = 1;
    public static final int CURVE_SQRT = 2;
    /** 曲线类型默认值（默认对数曲线）。 */
    public static final int CURVE_TYPE_DEFAULT = CURVE_LOG;

    /**
     * 蓝牙 AVRCP 绝对音量上限。
     * 若媒体档位数超过该值，相邻档位会映射到相同的 AVRCP 音量（听感相同），
     * 详见 AOSP 蓝牙模块 AvrcpVolumeManager#systemToAvrcpVolume 的换算公式：
     * absVolume = round(档位 * 127 / 最大档位)。
     */
    public static final int AVRCP_MAX_VOLUME = 127;

    /** 模式A 下绝对音量范围（0~127）的默认上下限。 */
    public static final int ABS_VOLUME_MIN_DEFAULT = 0;
    public static final int ABS_VOLUME_MAX_DEFAULT = AVRCP_MAX_VOLUME;

    /** 媒体档位数的可选范围（[TEST] 临时 10~127，回退后 10~29）。 */
    public static final int MEDIA_STEPS_MIN = 10;
    // [TEST] 临时放宽上限至 127，用于实测 ROM/HAL 在高档位数下是否真能渲染出可分辨
    // 的逐级音量差异（测完回退为 29）。对应 activity_main.xml seekMediaSteps android:max=117。
    public static final int MEDIA_STEPS_MAX = 127;
    /** 兜底默认媒体档位数（仅在无法探测系统原生档位时使用）。 */
    public static final int MEDIA_STEPS_DEFAULT = 20;

    /** 媒体流索引（android.media.AudioSystem.STREAM_MUSIC 的稳定取值）。 */
    public static final int STREAM_MUSIC_INDEX = 3;

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 把媒体档位数限制在可选范围内。 */
    public static int clampMediaSteps(int steps) {
        return Math.max(MEDIA_STEPS_MIN, Math.min(MEDIA_STEPS_MAX, steps));
    }

    /** 绝对音量值限制在 0~127。 */
    public static int clampAbs(int v) { return Math.max(0, Math.min(AVRCP_MAX_VOLUME, v)); }

    /** 曲线类型限制在 0~2。 */
    public static int clampCurve(int v) { return Math.max(0, Math.min(CURVE_SQRT, v)); }

    // 配置字符串的序列化 / 解析（原 encodeConfig / decodeConfig）已迁至
    // VolumeConfig.toRaw() / VolumeConfig.fromRaw()，以具名值对象取代 int[] config。
}
