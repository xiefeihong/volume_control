package com.xiefeihong.volumecontrol;

/**
 * 音量配置的不可变值对象（纯 Java，禁止引用任何 Android / Xposed API）。
 *
 * <p>取代此前贯穿全链路的 {@code int[] config}（12 字段魔法下标）。字段具名、按模式
 * 分组为两个 {@link Range}（模式A 绝对音量 / 模式B 软件衰减），配合
 * {@link VolumeMode} 的「枚举即策略」使读取端一眼可懂：{@code mode.range(cfg).min}。</p>
 *
 * <p>与 {@link Prefs}/{@link Avrcp}/{@link VolumeMode} 一样同时被 App 进程与 Hook 端
 * （system_server / 蓝牙进程）使用。</p>
 *
 * <p>序列化字符串为唯一的 10 字段格式，字段按「全局项 → 各模式 {@code min;max;curve}」
 * 连续排列（不兼容任何历史格式）：
 * {@code enabled;mediaSteps;keySteps;mode;A.min;A.max;A.curve;B.min;B.max;B.curve}。
 * 其中 {@code mode} 取 0=模式A / 1=模式B / 2=默认（系统直通）。</p>
 */
public final class VolumeConfig {

    /** 模块是否启用。 */
    public final boolean enabled;
    /** 媒体音量级数（已限制在 10~127）。 */
    public final int mediaSteps;
    /** 音量键步进（跨完整音量条需要的按键段数，已限制在 10~29）。 */
    public final int keySteps;
    /**
     * 生效模式 id：{@link Prefs#BT_MODE_DEFAULT}(0·系统直通) / {@link Prefs#BT_MODE_ABSOLUTE}(1)
     * / {@link Prefs#BT_MODE_SOFTWARE}(2)。模式与启用开关共同决定 {@link #remapActive()}，
     * 且对所有输出（蓝牙/有线/外放）生效。</p>
     */
    public final int btMode;
    /** 模式A：绝对音量范围（蓝牙经 AVRCP 映射；有线/外放经 system_server 衰减）。 */
    public final Range absolute;
    /** 模式B：手机端软件衰减范围。 */
    public final Range software;

    // 单模式范围值对象 Range 已提取为独立顶层类（见 Range.java）。

    public VolumeConfig(boolean enabled, int mediaSteps, int keySteps, int btMode,
            Range absolute, Range software) {
        this.enabled = enabled;
        this.mediaSteps = mediaSteps;
        this.keySteps = keySteps;
        this.btMode = btMode;
        this.absolute = absolute;
        this.software = software;
    }

    /** 是否为默认（系统直通）模式（btMode=={@link Prefs#BT_MODE_DEFAULT}）。 */
    public boolean isDefault() {
        return btMode == Prefs.BT_MODE_DEFAULT;
    }

    /**
     * Hook 端唯一的生效判据：模块是否应改写系统音量（档位数 / 重映射）。
     * 未启用或处于默认（系统直通）模式时均为 false → 保持系统默认行为。
     */
    public boolean remapActive() {
        return enabled && !isDefault();
    }

    /**
     * 解析配置字符串，仅接受 10 字段格式（{@code enabled;mediaSteps;keySteps;mode;} 后接
     * 两个模式 {@code min;max;curve} 三元组，基址 4 与 7）；{@code mode} 归一为 0/1/2。
     * 不兼容任何历史格式。
     *
     * <p>每个数值仅做取值域 clamp 规范化（{@code mediaSteps}/{@code keySteps}/{@code abs}/{@code curve}），
     * 不做历史格式回退、不做 {@code min>max} 交换（App 端写入前已保证 {@code min<=max}）。</p>
     *
     * @return 规范化后的配置；{@code raw} 为 null、字段数不等于 10 或含非数字时返回 null。
     */
    public static VolumeConfig fromRaw(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split(";");
        if (parts.length != 10) {
            return null;
        }
        try {
            boolean enabled = Integer.parseInt(parts[0].trim()) != 0;
            int mediaSteps = Prefs.clampMediaSteps(Integer.parseInt(parts[1].trim()));
            int keySteps = Prefs.clampKeySteps(Integer.parseInt(parts[2].trim()));
            int rawMode = Integer.parseInt(parts[3].trim());
            int btMode = rawMode == Prefs.BT_MODE_SOFTWARE ? Prefs.BT_MODE_SOFTWARE
                    : rawMode == Prefs.BT_MODE_DEFAULT ? Prefs.BT_MODE_DEFAULT
                    : Prefs.BT_MODE_ABSOLUTE;
            return new VolumeConfig(enabled, mediaSteps, keySteps, btMode,
                    readRange(parts, 4), readRange(parts, 7));
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

    /** 序列化为 10 字段字符串（全局项 + 两个模式三元组，逐项 clamp，与 {@link #fromRaw} 互逆）。 */
    public String toRaw() {
        return (enabled ? 1 : 0) + ";" + Prefs.clampMediaSteps(mediaSteps)
                + ";" + Prefs.clampKeySteps(keySteps)
                + ";" + btMode
                + ";" + Prefs.clampAbs(absolute.min) + ";" + Prefs.clampAbs(absolute.max)
                + ";" + Prefs.clampCurve(absolute.curve)
                + ";" + Prefs.clampAbs(software.min) + ";" + Prefs.clampAbs(software.max)
                + ";" + Prefs.clampCurve(software.curve);
    }

    /** 供日志输出的人类可读摘要（取代原 {@code Arrays.toString(config)}）。 */
    @Override
    public String toString() {
        return (enabled ? "on" : "off")
                + ",steps=" + mediaSteps
                + ",keySteps=" + keySteps
                + ",mode=" + (isDefault() ? "默认" : btMode == Prefs.BT_MODE_SOFTWARE ? "B" : "A")
                + ",A=[" + absolute.min + "~" + absolute.max + "/" + absolute.curve + "]"
                + ",B=[" + software.min + "~" + software.max + "/" + software.curve + "]";
    }
}
