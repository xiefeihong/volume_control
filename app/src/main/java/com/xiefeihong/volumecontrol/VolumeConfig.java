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
 * <p>序列化字符串为唯一的 13 字段格式，字段按模式分组、各模式 {@code min;max;curve}
 * 连续排列（不再兼容任何历史格式）：
 * {@code enabled;mediaSteps;keySteps;btMode;A.min;A.max;A.curve;B.min;B.max;B.curve;W.min;W.max;W.curve}。
 * 其中 {@code btMode} 取 0=模式A / 1=模式B / 2=默认（系统直通）。</p>
 */
public final class VolumeConfig {

    /** 模块是否启用。 */
    public final boolean enabled;
    /** 媒体音量级数（已限制在 10~127）。 */
    public final int mediaSteps;
    /** 音量键步进（跨完整音量条需要的按键段数，已限制在 10~29）。 */
    public final int keySteps;
    /**
     * 音量范围模式 id：{@link Prefs#BT_MODE_ABSOLUTE}(0) / {@link Prefs#BT_MODE_SOFTWARE}(1)
     * / {@link Prefs#BT_MODE_DEFAULT}(2·系统直通)。默认模式与启用开关共同决定 {@link #remapActive()}。
     */
    public final int btMode;
    /** 模式A：蓝牙 AVRCP 绝对音量范围。 */
    public final Range absolute;
    /** 模式B：手机端软件衰减范围。 */
    public final Range software;
    /** 耳机模式：有线 + 外放软件衰减范围。 */
    public final Range wired;

    // 单模式范围值对象 Range 已提取为独立顶层类（见 Range.java）。

    public VolumeConfig(boolean enabled, int mediaSteps, int keySteps, int btMode,
            Range absolute, Range software, Range wired) {
        this.enabled = enabled;
        this.mediaSteps = mediaSteps;
        this.keySteps = keySteps;
        this.btMode = btMode;
        this.absolute = absolute;
        this.software = software;
        this.wired = wired;
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
     * 解析配置字符串，接受 13 字段格式（{@code enabled;mediaSteps;keySteps;btMode;} 后接三个
     * 模式的 {@code min;max;curve} 三元组）；{@code btMode} 归一为 0/1/2。为兼容上一版写入的
     * 14 字段（末尾 {@code defaultMode}），若第 14 项为 1 则视为 {@code btMode=2}。
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
            int rawMode = Integer.parseInt(parts[3].trim());
            int btMode = rawMode == Prefs.BT_MODE_SOFTWARE ? Prefs.BT_MODE_SOFTWARE
                    : rawMode == Prefs.BT_MODE_DEFAULT ? Prefs.BT_MODE_DEFAULT
                    : Prefs.BT_MODE_ABSOLUTE;
            // 旧 14 字段迁移：defaultMode=1 视作默认直通模式。
            if (parts.length >= 14 && Integer.parseInt(parts[13].trim()) != 0) {
                btMode = Prefs.BT_MODE_DEFAULT;
            }
            return new VolumeConfig(enabled, mediaSteps, keySteps, btMode,
                    readRange(parts, 4), readRange(parts, 7), readRange(parts, 10));
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

    /** 序列化为 13 字段字符串（按模式分组顺序，逐项 clamp，与 {@link #fromRaw} 互逆）。 */
    public String toRaw() {
        return (enabled ? 1 : 0) + ";" + Prefs.clampMediaSteps(mediaSteps)
                + ";" + Prefs.clampKeySteps(keySteps)
                + ";" + btMode
                + ";" + Prefs.clampAbs(absolute.min) + ";" + Prefs.clampAbs(absolute.max)
                + ";" + Prefs.clampCurve(absolute.curve)
                + ";" + Prefs.clampAbs(software.min) + ";" + Prefs.clampAbs(software.max)
                + ";" + Prefs.clampCurve(software.curve)
                + ";" + Prefs.clampAbs(wired.min) + ";" + Prefs.clampAbs(wired.max)
                + ";" + Prefs.clampCurve(wired.curve);
    }

    /** 供日志输出的人类可读摘要（取代原 {@code Arrays.toString(config)}）。 */
    @Override
    public String toString() {
        return (enabled ? "on" : "off")
                + ",steps=" + mediaSteps
                + ",keySteps=" + keySteps
                + ",mode=" + (isDefault() ? "默认" : btMode == Prefs.BT_MODE_SOFTWARE ? "B" : "A")
                + ",A=[" + absolute.min + "~" + absolute.max + "/" + absolute.curve + "]"
                + ",B=[" + software.min + "~" + software.max + "/" + software.curve + "]"
                + ",W=[" + wired.min + "~" + wired.max + "/" + wired.curve + "]";
    }
}
