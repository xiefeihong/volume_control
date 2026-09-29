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

    /** 模式A 音量范围 SharedPreferences 键。 */
    public static final String KEY_MIN_ABS_A = "min_abs_volume_a";
    public static final String KEY_MAX_ABS_A = "max_abs_volume_a";
    public static final String KEY_ATTEN_MULTIPLIER_A = "atten_multiplier_a";

    /** 模式B 音量范围 SharedPreferences 键。 */
    public static final String KEY_MIN_ABS_B = "min_abs_volume_b";
    public static final String KEY_MAX_ABS_B = "max_abs_volume_b";
    public static final String KEY_ATTEN_MULTIPLIER_B = "atten_multiplier_b";

    /** @deprecated 使用 KEY_MIN_ABS_A / KEY_MIN_ABS_B 代替。 */
    @Deprecated
    public static final String KEY_MIN_ABS = "min_abs_volume";
    /** @deprecated 使用 KEY_MAX_ABS_A / KEY_MAX_ABS_B 代替。 */
    @Deprecated
    public static final String KEY_MAX_ABS = "max_abs_volume";
    /** @deprecated 使用 KEY_ATTEN_MULTIPLIER_A / KEY_ATTEN_MULTIPLIER_B 代替。 */
    @Deprecated
    public static final String KEY_ATTEN_MULTIPLIER = "atten_multiplier";

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
     * 蓝牙 AVRCP 绝对音量上限。
     * 若媒体档位数超过该值，相邻档位会映射到相同的 AVRCP 音量（听感相同），
     * 详见 AOSP 蓝牙模块 AvrcpVolumeManager#systemToAvrcpVolume 的换算公式：
     * absVolume = round(档位 * 127 / 最大档位)。
     */
    public static final int AVRCP_MAX_VOLUME = 127;

    /** 模式A 下绝对音量范围（0~127）的默认上下限。 */
    public static final int ABS_VOLUME_MIN_DEFAULT = 0;
    public static final int ABS_VOLUME_MAX_DEFAULT = AVRCP_MAX_VOLUME;

    /** 模式B 衰减乘数默认值（100% = 不衰减，对应 0~100 滑条的 100）。 */
    public static final int ATTEN_MULTIPLIER_DEFAULT = 100;

    /** 媒体档位数的可选范围（16~29）。0 表示使用系统默认（不修改）。 */
    public static final int MEDIA_STEPS_DEFAULT_VAL = 0; // 0 = 系统默认
    public static final int MEDIA_STEPS_MIN = 16;
    public static final int MEDIA_STEPS_MAX = 29;
    /** @deprecated 使用 MEDIA_STEPS_DEFAULT_VAL 代替。 */
    @Deprecated
    public static final int MEDIA_STEPS_DEFAULT = 16;

    /** 媒体流索引（android.media.AudioSystem.STREAM_MUSIC 的稳定取值）。 */
    public static final int STREAM_MUSIC_INDEX = 3;

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 把媒体档位数限制在可选范围内；0 = 系统默认，不夹持。 */
    public static int clampMediaSteps(int steps) {
        if (steps == 0) return 0;
        return Math.max(MEDIA_STEPS_MIN, Math.min(MEDIA_STEPS_MAX, steps));
    }

    /**
     * 序列化为可写入 Settings.Global 的字符串（9 字段）：
     * {启用};{媒体档位};{模式};{A最小};{A最大};{A乘数};{B最小};{B最大};{B乘数}。
     */
    public static String encodeConfig(boolean enabled, int mediaSteps, int mode,
            int minAbsA, int maxAbsA, int mulA,
            int minAbsB, int maxAbsB, int mulB) {
        return (enabled ? 1 : 0) + ";" + (mediaSteps == 0 ? 0 : clampMediaSteps(mediaSteps))
                + ";" + mode
                + ";" + clampAbs(minAbsA) + ";" + clampAbs(maxAbsA) + ";" + clampMul(mulA)
                + ";" + clampAbs(minAbsB) + ";" + clampAbs(maxAbsB) + ";" + clampMul(mulB);
    }

    /** 绝对音量值限制在 0~127。 */
    public static int clampAbs(int v) { return Math.max(0, Math.min(AVRCP_MAX_VOLUME, v)); }
    /** 衰减乘数限制在 0~200。 */
    public static int clampMul(int v) { return Math.max(0, Math.min(200, v)); }

    /**
     * 解析配置字符串。
     *
     * <p>兼容历史格式：3/5/6 字段旧格式与 9 字段新格式。
     * 旧格式的范围值迁移到模式B（新格式字段 6~8），模式A 使用默认值。</p>
     *
     * @return int[]{enabled, mediaSteps, mode, minAbsA, maxAbsA, mulA, minAbsB, maxAbsB, mulB}；
     *         非法内容返回 null。
     */
    public static int[] decodeConfig(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split(";");
        if (parts.length != 9 && parts.length != 6 && parts.length != 5 && parts.length != 3) {
            return null;
        }
        try {
            int enabled = Integer.parseInt(parts[0].trim()) != 0 ? 1 : 0;
            int mediaSteps;
            int mode = BT_MODE_ABSOLUTE;
            // 默认值：两个模式各自独立
            int minA = ABS_VOLUME_MIN_DEFAULT, maxA = ABS_VOLUME_MAX_DEFAULT, mulA = ATTEN_MULTIPLIER_DEFAULT;
            int minB = ABS_VOLUME_MIN_DEFAULT, maxB = ABS_VOLUME_MAX_DEFAULT, mulB = ATTEN_MULTIPLIER_DEFAULT;

            if (parts.length == 9) {
                // 新格式：启用;档位;模式;A最小;A最大;A乘数;B最小;B最大;B乘数
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minA = Integer.parseInt(parts[3].trim());
                maxA = Integer.parseInt(parts[4].trim());
                mulA = Integer.parseInt(parts[5].trim());
                minB = Integer.parseInt(parts[6].trim());
                maxB = Integer.parseInt(parts[7].trim());
                mulB = Integer.parseInt(parts[8].trim());
            } else if (parts.length == 6) {
                // 旧 6 字段：范围值迁移到模式B
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minB = Integer.parseInt(parts[3].trim());
                maxB = Integer.parseInt(parts[4].trim());
                mulB = Integer.parseInt(parts[5].trim());
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
            // 0 = 系统默认，不夹持
            if (mediaSteps != 0) mediaSteps = clampMediaSteps(mediaSteps);
            minA = clampAbs(minA); maxA = clampAbs(maxA); mulA = clampMul(mulA);
            minB = clampAbs(minB); maxB = clampAbs(maxB); mulB = clampMul(mulB);
            if (minA > maxA) { int t = minA; minA = maxA; maxA = t; }
            if (minB > maxB) { int t = minB; minB = maxB; maxB = t; }
            return new int[]{enabled, mediaSteps, mode,
                    minA, maxA, mulA, minB, maxB, mulB};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
