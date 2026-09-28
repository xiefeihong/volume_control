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
    public static final String KEY_MIN_ABS = "min_abs_volume";
    public static final String KEY_MAX_ABS = "max_abs_volume";

    /** 模式B 衰减乘数（0~200，100=不衰减）。 */
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

    /** 媒体档位数的可选范围（16~29，HyperOS 默认 15）。 */
    public static final int MEDIA_STEPS_MIN = 16;
    public static final int MEDIA_STEPS_MAX = 29;
    public static final int MEDIA_STEPS_DEFAULT = 16;

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
     * 序列化为可写入 Settings.Global 的字符串：
     * {启用};{媒体档位};{蓝牙模式};{最小绝对音量};{最大绝对音量};{衰减乘数}。
     */
    public static String encodeConfig(boolean enabled, int mediaSteps,
            int btMode, int minAbs, int maxAbs, int attenMultiplier) {
        return (enabled ? 1 : 0) + ";" + clampMediaSteps(mediaSteps)
                + ";" + btMode + ";" + minAbs + ";" + maxAbs
                + ";" + Math.max(0, Math.min(200, attenMultiplier));
    }

    /**
     * 解析配置字符串。
     *
     * <p>兼容历史格式：v1.0/v1.1 的三字段（启用;百分比;媒体覆盖）与
     * v1.2 的五字段（启用;媒体档位;模式;最小;最大）及六字段（加衰减乘数），
     * 旧百分比换算为媒体档位后统一收敛到 15~29。</p>
     *
     * @return int[]{enabled(0/1), mediaSteps, btMode, minAbs, maxAbs, attenMultiplier}；非法内容返回 null。
     */
    public static int[] decodeConfig(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split(";");
        if (parts.length != 5 && parts.length != 3 && parts.length != 6) {
            return null;
        }
        try {
            int enabled = Integer.parseInt(parts[0].trim()) != 0 ? 1 : 0;
            int mediaSteps;
            int mode = BT_MODE_ABSOLUTE;
            int minAbs = ABS_VOLUME_MIN_DEFAULT;
            int maxAbs = ABS_VOLUME_MAX_DEFAULT;
            int attenMultiplier = ATTEN_MULTIPLIER_DEFAULT;
            if (parts.length == 5) {
                // 五字段格式：启用;媒体档位;模式;最小;最大
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minAbs = Integer.parseInt(parts[3].trim());
                maxAbs = Integer.parseInt(parts[4].trim());
            } else if (parts.length == 6) {
                // 六字段格式：启用;媒体档位;模式;最小;最大;衰减乘数
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minAbs = Integer.parseInt(parts[3].trim());
                maxAbs = Integer.parseInt(parts[4].trim());
                attenMultiplier = Integer.parseInt(parts[5].trim());
            } else {
                // 旧格式：启用;百分比;媒体覆盖
                int percent = Integer.parseInt(parts[1].trim());
                int legacyMedia = Integer.parseInt(parts[2].trim());
                if (legacyMedia > 0) {
                    mediaSteps = legacyMedia;
                } else {
                    mediaSteps = (int) Math.round(percent * 15 / 100.0);
                }
            }
            if (mode != BT_MODE_SOFTWARE) {
                mode = BT_MODE_ABSOLUTE;
            }
            mediaSteps = clampMediaSteps(mediaSteps);
            minAbs = Math.max(0, Math.min(AVRCP_MAX_VOLUME, minAbs));
            maxAbs = Math.max(0, Math.min(AVRCP_MAX_VOLUME, maxAbs));
            if (minAbs > maxAbs) {
                int tmp = minAbs;
                minAbs = maxAbs;
                maxAbs = tmp;
            }
            attenMultiplier = Math.max(0, Math.min(200, attenMultiplier));
            return new int[]{enabled, mediaSteps, mode, minAbs, maxAbs, attenMultiplier};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
