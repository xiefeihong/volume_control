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
 * <p>序列化字符串为唯一的 14 字段格式，字段按模式分组、各模式 {@code min;max;curve}
 * 连续排列（不再兼容任何历史格式）：
 * {@code enabled;mediaSteps;keySteps;btMode;A.min;A.max;A.curve;B.min;B.max;B.curve;W.min;W.max;W.curve;defaultMode}。</p>
 */
public final class VolumeConfig {

    /** 模块是否启用。 */
    public final boolean enabled;
    /**
     * 默认模式（系统直通）：为 true 时无论 {@link #enabled} 如何，Hook 端都不改写系统
     * 音量逻辑（不改档位数、不重映射），等同未启用。与启用开关解耦的独立选择。
     */
    public final boolean defaultMode;
    /** 媒体音量级数（已限制在 10~127）。 */
    public final int mediaSteps;
    /** 音量键步进（跨完整音量条需要的按键段数，已限制在 10~29）。 */
    public final int keySteps;
    /** 蓝牙音量控制模式 id（{@link Prefs#BT_MODE_ABSOLUTE} / {@link Prefs#BT_MODE_SOFTWARE}）。 */
    public final int btMode;
    /** 模式A：蓝牙 AVRCP 绝对音量范围。 */
    public final Range absolute;
    /** 模式B：手机端软件衰减范围。 */
    public final Range software;
    /** 耳机模式：有线 + 外放软件衰减范围。 */
    public final Range wired;

    // 单模式范围值对象 Range 已提取为独立顶层类（见 Range.java）。

    public VolumeConfig(boolean enabled, int mediaSteps, int keySteps, int btMode,
            Range absolute, Range software, Range wired, boolean defaultMode) {
        this.enabled = enabled;
        this.mediaSteps = mediaSteps;
        this.keySteps = keySteps;
        this.btMode = btMode;
        this.absolute = absolute;
        this.software = software;
        this.wired = wired;
        this.defaultMode = defaultMode;
    }

    /**
     * Hook 端唯一的生效判据：模块是否应改写系统音量（档位数 / 重映射）。
     * 未启用或处于默认（系统直通）模式时均为 false → 保持系统默认行为。
     */
    public boolean remapActive() {
        return enabled && !defaultMode;
    }

    /**
     * 解析配置字符串，接受 14 字段格式（{@code enabled;mediaSteps;keySteps;btMode;} 后接
     * 三个模式的 {@code min;max;curve} 三元组，末尾 {@code defaultMode}）；为兼容旧写入
     * 也接受无 {@code defaultMode} 的 13 字段（{@code defaultMode=false}）。
     *
     * <p>每个数值仅做取值域 clamp 规范化（{@code mediaSteps}/{@code keySteps}/{@code abs}/{@code curve}），
     * 不做历史格式回退、不做 {@code min>max} 交换（App 端写入前已保证 {@code min<=max}）。</p>
     *
     * @return 规范化后的配置；{@code raw} 为 null、字段数不等于 13/14 或含非数字时返回 null。
     */
    public static VolumeConfig fromRaw(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split(";");
        if (parts.length != 13 && parts.length != 14) {
            return null;
        }
        try {
            boolean enabled = Integer.parseInt(parts[0].trim()) != 0;
            int mediaSteps = Prefs.clampMediaSteps(Integer.parseInt(parts[1].trim()));
            int keySteps = Prefs.clampKeySteps(Integer.parseInt(parts[2].trim()));
            int btMode = Integer.parseInt(parts[3].trim()) == Prefs.BT_MODE_SOFTWARE
                    ? Prefs.BT_MODE_SOFTWARE : Prefs.BT_MODE_ABSOLUTE;
            boolean defaultMode = parts.length >= 14
                    && Integer.parseInt(parts[13].trim()) != 0;
            return new VolumeConfig(enabled, mediaSteps, keySteps, btMode,
                    readRange(parts, 4), readRange(parts, 7), readRange(parts, 10), defaultMode);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 读取按 {@code [base]=min,[base+1]=max,[base+2]=curve} 排列的一个模式三元组（逐项 clamp）。 */
    private static Range readRange(String[] parts, int base) {
        int min = Prefs.clampAbs(Integer.parseInt(parts[base].trim()));
        int max = Prefs.clampAbs(Integer.parseInt(parts[base + 1].trim()));
        int curve = Prefs.clampCurve(Integer.parseInt(parts[base + 2].trim()));
        return new Range(min, max, curve);
    }

    /** 序列化为 14 字段字符串（按模式分组顺序，逐项 clamp，与 {@link #fromRaw} 互逆）。 */
    public String toRaw() {
        return (enabled ? 1 : 0) + ";" + Prefs.clampMediaSteps(mediaSteps)
                + ";" + Prefs.clampKeySteps(keySteps)
                + ";" + btMode
                + ";" + Prefs.clampAbs(absolute.min) + ";" + Prefs.clampAbs(absolute.max)
                + ";" + Prefs.clampCurve(absolute.curve)
                + ";" + Prefs.clampAbs(software.min) + ";" + Prefs.clampAbs(software.max)
                + ";" + Prefs.clampCurve(software.curve)
                + ";" + Prefs.clampAbs(wired.min) + ";" + Prefs.clampAbs(wired.max)
                + ";" + Prefs.clampCurve(wired.curve)
                + ";" + (defaultMode ? 1 : 0);
    }

    /** 供日志输出的人类可读摘要（取代原 {@code Arrays.toString(config)}）。 */
    @Override
    public String toString() {
        return (enabled ? "on" : "off")
                + ",steps=" + mediaSteps
                + ",keySteps=" + keySteps
                + ",mode=" + (btMode == Prefs.BT_MODE_SOFTWARE ? "B" : "A")
                + ",A=[" + absolute.min + "~" + absolute.max + "/" + absolute.curve + "]"
                + ",B=[" + software.min + "~" + software.max + "/" + software.curve + "]"
                + ",W=[" + wired.min + "~" + wired.max + "/" + wired.curve + "]"
                + ",default=" + (defaultMode ? "on" : "off");
    }
}
