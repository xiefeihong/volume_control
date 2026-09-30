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

    /** @deprecated 使用 KEY_MIN_ABS_A / KEY_MIN_ABS_B 代替。 */
    @Deprecated
    public static final String KEY_MIN_ABS = "min_abs_volume";
    /** @deprecated 使用 KEY_MAX_ABS_A / KEY_MAX_ABS_B 代替。 */
    @Deprecated
    public static final String KEY_MAX_ABS = "max_abs_volume";

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
    /** 曲线类型默认值（向后兼容旧配置，沿用对数曲线行为）。 */
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

    /** 媒体档位数的可选范围（10~29）。 */
    public static final int MEDIA_STEPS_MIN = 10;
    public static final int MEDIA_STEPS_MAX = 29;
    /** HyperOS 默认媒体档位数（首次安装默认与「恢复默认」按钮的目标值）。 */
    public static final int MEDIA_STEPS_DEFAULT = 15;
    /** @deprecated 已删除“默认档位”选项，范围从 10 开始。 */
    @Deprecated
    public static final int MEDIA_STEPS_DEFAULT_VAL = 0;

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

    /**
     * 序列化为可写入 Settings.Global 的字符串（12 字段）：
     * {启用};{媒体档位};{模式};{A最小};{A最大};{B最小};{B最大};{曲线A};{曲线B};{W最小};{W最大};{曲线W}。
     * W = 耳机模式（有线+外放）。
     */
    public static String encodeConfig(boolean enabled, int mediaSteps, int mode,
            int minAbsA, int maxAbsA,
            int minAbsB, int maxAbsB, int curveTypeA, int curveTypeB,
            int minAbsW, int maxAbsW, int curveTypeW) {
        return (enabled ? 1 : 0) + ";" + clampMediaSteps(mediaSteps)
                + ";" + mode
                + ";" + clampAbs(minAbsA) + ";" + clampAbs(maxAbsA)
                + ";" + clampAbs(minAbsB) + ";" + clampAbs(maxAbsB)
                + ";" + clampCurve(curveTypeA) + ";" + clampCurve(curveTypeB)
                + ";" + clampAbs(minAbsW) + ";" + clampAbs(maxAbsW)
                + ";" + clampCurve(curveTypeW);
    }

    /** 绝对音量值限制在 0~127。 */
    public static int clampAbs(int v) { return Math.max(0, Math.min(AVRCP_MAX_VOLUME, v)); }

    /** 曲线类型限制在 0~2。 */
    public static int clampCurve(int v) { return Math.max(0, Math.min(CURVE_SQRT, v)); }

    /**
     * 解析配置字符串。
     *
     * <p>兼容历史格式：3/5/6/7/8/9 字段旧格式与 12 字段新格式（追加耳机模式 W）。
     * 8 字段的全局曲线同时赋给 A/B；无曲线字段时默认 {@link #CURVE_LOG}；
     * 无 W 字段（&lt;12）时耳机模式默认 0~127 不衰减。</p>
     *
     * @return int[]{enabled, mediaSteps, mode, minAbsA, maxAbsA, minAbsB, maxAbsB, curveTypeA, curveTypeB, minAbsW, maxAbsW, curveTypeW}；
     *         非法内容返回 null。
     */
    public static int[] decodeConfig(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split(";");
        if (parts.length != 12 && parts.length != 9 && parts.length != 8
                && parts.length != 7 && parts.length != 6 && parts.length != 5
                && parts.length != 3) {
            return null;
        }
        try {
            int enabled = Integer.parseInt(parts[0].trim()) != 0 ? 1 : 0;
            int mediaSteps;
            int mode = BT_MODE_ABSOLUTE;
            int minA = ABS_VOLUME_MIN_DEFAULT, maxA = ABS_VOLUME_MAX_DEFAULT;
            int minB = ABS_VOLUME_MIN_DEFAULT, maxB = ABS_VOLUME_MAX_DEFAULT;
            int curveType = CURVE_TYPE_DEFAULT;
            int curveTypeB = CURVE_TYPE_DEFAULT;
            int minW = ABS_VOLUME_MIN_DEFAULT, maxW = ABS_VOLUME_MAX_DEFAULT;
            int curveTypeW = CURVE_TYPE_DEFAULT;

            if (parts.length >= 7) {
                // 7/8/9 字段：[3][4]=A 范围、[5][6]=B 范围
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minA = Integer.parseInt(parts[3].trim());
                maxA = Integer.parseInt(parts[4].trim());
                minB = Integer.parseInt(parts[5].trim());
                maxB = Integer.parseInt(parts[6].trim());
                if (parts.length == 9) {
                    // 新格式：[7]=曲线A、[8]=曲线B
                    curveType = Integer.parseInt(parts[7].trim());
                    curveTypeB = Integer.parseInt(parts[8].trim());
                } else if (parts.length == 8) {
                    // 旧全局曲线 [7]：A/B 同值
                    curveType = Integer.parseInt(parts[7].trim());
                    curveTypeB = curveType;
                }
                if (parts.length == 12) {
                    // 新格式：[9]=minW、[10]=maxW、[11]=curveW（耳机模式）
                    minW = Integer.parseInt(parts[9].trim());
                    maxW = Integer.parseInt(parts[10].trim());
                    curveTypeW = Integer.parseInt(parts[11].trim());
                }
            } else if (parts.length == 6) {
                // 旧 6 字段：范围值迁移到模式B
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minB = Integer.parseInt(parts[3].trim());
                maxB = Integer.parseInt(parts[4].trim());
            } else if (parts.length == 5) {
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minB = Integer.parseInt(parts[3].trim());
                maxB = Integer.parseInt(parts[4].trim());
            } else {
                // 旧 3 字段格式
                int percent = Integer.parseInt(parts[1].trim());
                int legacyMedia = Integer.parseInt(parts[2].trim());
                mediaSteps = legacyMedia > 0 ? legacyMedia
                        : (int) Math.round(percent * 15 / 100.0);
            }
            if (mode != BT_MODE_SOFTWARE) mode = BT_MODE_ABSOLUTE;
            mediaSteps = clampMediaSteps(mediaSteps);
            minA = clampAbs(minA); maxA = clampAbs(maxA);
            minB = clampAbs(minB); maxB = clampAbs(maxB);
            minW = clampAbs(minW); maxW = clampAbs(maxW);
            curveType = clampCurve(curveType);
            curveTypeB = clampCurve(curveTypeB);
            curveTypeW = clampCurve(curveTypeW);
            if (minA > maxA) { int t = minA; minA = maxA; maxA = t; }
            if (minB > maxB) { int t = minB; minB = maxB; maxB = t; }
            if (minW > maxW) { int t = minW; minW = maxW; maxW = t; }
            return new int[]{enabled, mediaSteps, mode,
                    minA, maxA, minB, maxB, curveType, curveTypeB, minW, maxW, curveTypeW};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
