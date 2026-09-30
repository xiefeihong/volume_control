package com.xiefeihong.volumecontrol;

/**
 * 音量配置的不可变值对象（纯 Java，禁止引用任何 Android / Xposed API）。
 *
 * <p>取代此前贯穿全链路的 {@code int[] config}（12 字段魔法下标）。字段具名、按模式
 * 分组为三个 {@link Range}（模式A 绝对音量 / 模式B 软件衰减 / 耳机模式），配合
 * {@link VolumeMode} 的「枚举即策略」使读取端一眼可懂：{@code mode.range(cfg).min}。</p>
 *
 * <p>与 {@link Prefs}/{@link Avrcp}/{@link VolumeMode} 一样同时被 App 进程与 Hook 端
 * （system_server / 蓝牙进程）使用。</p>
 *
 * <p>序列化字符串沿用 12 字段格式（与历史 Settings.Global / 镜像文件完全兼容）：
 * {@code enabled;mediaSteps;btMode;A.min;A.max;B.min;B.max;A.curve;B.curve;W.min;W.max;W.curve}。</p>
 */
public final class VolumeConfig {

    /** 模块是否启用。 */
    public final boolean enabled;
    /** 媒体档位数（已限制在 10~29）。 */
    public final int mediaSteps;
    /** 蓝牙音量控制模式 id（{@link Prefs#BT_MODE_ABSOLUTE} / {@link Prefs#BT_MODE_SOFTWARE}）。 */
    public final int btMode;
    /** 模式A：蓝牙 AVRCP 绝对音量范围。 */
    public final Range absolute;
    /** 模式B：手机端软件衰减范围。 */
    public final Range software;
    /** 耳机模式：有线 + 外放软件衰减范围。 */
    public final Range wired;

    /** 单个模式的音量范围（最小~最大 + 映射曲线类型）。不可变。 */
    public static final class Range {
        public final int min;
        public final int max;
        /** 曲线类型：{@link Prefs#CURVE_LOG} / {@link Prefs#CURVE_LINEAR} / {@link Prefs#CURVE_SQRT}。 */
        public final int curve;

        public Range(int min, int max, int curve) {
            this.min = min;
            this.max = max;
            this.curve = curve;
        }
    }

    public VolumeConfig(boolean enabled, int mediaSteps, int btMode,
            Range absolute, Range software, Range wired) {
        this.enabled = enabled;
        this.mediaSteps = mediaSteps;
        this.btMode = btMode;
        this.absolute = absolute;
        this.software = software;
        this.wired = wired;
    }

    /**
     * 解析配置字符串，兼容历史格式（3/5/6/7/8/9 字段旧格式与 12 字段新格式）。
     *
     * <p>返回的每个字段均已规范化：clamp 到合法区间、{@code min > max} 时交换、缺字段取默认。
     * 8 字段的全局曲线同时赋给模式A/B；无 W 字段（&lt;12）时耳机模式默认 0~127 不衰减。</p>
     *
     * @return 规范化后的配置；{@code raw} 为 null 或非法（字段数不符 / 非数字）时返回 null。
     */
    public static VolumeConfig fromRaw(String raw) {
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
            boolean enabled = Integer.parseInt(parts[0].trim()) != 0;
            int mediaSteps;
            int mode = Prefs.BT_MODE_ABSOLUTE;
            int minA = Prefs.ABS_VOLUME_MIN_DEFAULT, maxA = Prefs.ABS_VOLUME_MAX_DEFAULT;
            int minB = Prefs.ABS_VOLUME_MIN_DEFAULT, maxB = Prefs.ABS_VOLUME_MAX_DEFAULT;
            int curveA = Prefs.CURVE_TYPE_DEFAULT;
            int curveB = Prefs.CURVE_TYPE_DEFAULT;
            int minW = Prefs.ABS_VOLUME_MIN_DEFAULT, maxW = Prefs.ABS_VOLUME_MAX_DEFAULT;
            int curveW = Prefs.CURVE_TYPE_DEFAULT;

            if (parts.length >= 7) {
                // 7/8/9/12 字段：[3][4]=A 范围、[5][6]=B 范围
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minA = Integer.parseInt(parts[3].trim());
                maxA = Integer.parseInt(parts[4].trim());
                minB = Integer.parseInt(parts[5].trim());
                maxB = Integer.parseInt(parts[6].trim());
                if (parts.length == 9 || parts.length == 12) {
                    // [7]=曲线A、[8]=曲线B
                    curveA = Integer.parseInt(parts[7].trim());
                    curveB = Integer.parseInt(parts[8].trim());
                } else if (parts.length == 8) {
                    // 旧全局曲线 [7]：A/B 同值
                    curveA = Integer.parseInt(parts[7].trim());
                    curveB = curveA;
                }
                if (parts.length == 12) {
                    // [9]=minW、[10]=maxW、[11]=curveW（耳机模式）
                    minW = Integer.parseInt(parts[9].trim());
                    maxW = Integer.parseInt(parts[10].trim());
                    curveW = Integer.parseInt(parts[11].trim());
                }
            } else if (parts.length == 6 || parts.length == 5) {
                // 旧 5/6 字段：范围值迁移到模式B
                mediaSteps = Integer.parseInt(parts[1].trim());
                mode = Integer.parseInt(parts[2].trim());
                minB = Integer.parseInt(parts[3].trim());
                maxB = Integer.parseInt(parts[4].trim());
            } else {
                // 旧 3 字段格式：percent +  legacy 档位数
                int percent = Integer.parseInt(parts[1].trim());
                int legacyMedia = Integer.parseInt(parts[2].trim());
                mediaSteps = legacyMedia > 0 ? legacyMedia
                        : (int) Math.round(percent * 15 / 100.0);
            }

            if (mode != Prefs.BT_MODE_SOFTWARE) {
                mode = Prefs.BT_MODE_ABSOLUTE;
            }
            mediaSteps = Prefs.clampMediaSteps(mediaSteps);
            minA = Prefs.clampAbs(minA);
            maxA = Prefs.clampAbs(maxA);
            minB = Prefs.clampAbs(minB);
            maxB = Prefs.clampAbs(maxB);
            minW = Prefs.clampAbs(minW);
            maxW = Prefs.clampAbs(maxW);
            curveA = Prefs.clampCurve(curveA);
            curveB = Prefs.clampCurve(curveB);
            curveW = Prefs.clampCurve(curveW);
            if (minA > maxA) { int t = minA; minA = maxA; maxA = t; }
            if (minB > maxB) { int t = minB; minB = maxB; maxB = t; }
            if (minW > maxW) { int t = minW; minW = maxW; maxW = t; }

            return new VolumeConfig(enabled, mediaSteps, mode,
                    new Range(minA, maxA, curveA),
                    new Range(minB, maxB, curveB),
                    new Range(minW, maxW, curveW));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 序列化为可写入 Settings.Global 的 12 字段字符串（写时逐项 clamp，与原
     * {@code Prefs.encodeConfig} 输出逐字节一致）。
     */
    public String toRaw() {
        return (enabled ? 1 : 0) + ";" + Prefs.clampMediaSteps(mediaSteps)
                + ";" + btMode
                + ";" + Prefs.clampAbs(absolute.min) + ";" + Prefs.clampAbs(absolute.max)
                + ";" + Prefs.clampAbs(software.min) + ";" + Prefs.clampAbs(software.max)
                + ";" + Prefs.clampCurve(absolute.curve) + ";" + Prefs.clampCurve(software.curve)
                + ";" + Prefs.clampAbs(wired.min) + ";" + Prefs.clampAbs(wired.max)
                + ";" + Prefs.clampCurve(wired.curve);
    }

    /** 供日志输出的人类可读摘要（取代原 {@code Arrays.toString(config)}）。 */
    @Override
    public String toString() {
        return (enabled ? "on" : "off")
                + ",steps=" + mediaSteps
                + ",mode=" + (btMode == Prefs.BT_MODE_SOFTWARE ? "B" : "A")
                + ",A=[" + absolute.min + "~" + absolute.max + "/" + absolute.curve + "]"
                + ",B=[" + software.min + "~" + software.max + "/" + software.curve + "]"
                + ",W=[" + wired.min + "~" + wired.max + "/" + wired.curve + "]";
    }
}
