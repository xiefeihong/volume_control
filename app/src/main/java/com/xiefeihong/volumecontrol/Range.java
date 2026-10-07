package com.xiefeihong.volumecontrol;

/**
 * 单个音量模式的范围（最小~最大 + 映射曲线类型）。不可变值对象。
 *
 * <p>从 {@link VolumeConfig} 的内部类提取为独立顶层类（同包），供 {@link DeviceConfig}
 * （每设备两套 absolute/software）、{@link VolumeConfig}（{@code activeRangeFor(d)}）
 * 与 App 侧读写共同使用，禁止引用任何 Android / Xposed API。</p>
 *
 * <p>字段含义：{@code min}/{@code max} 为该模式映射后的音量下限/上限（模式A 为
 * AVRCP 绝对音量 0~127；模式B 为手机端软件衰减的目标档位），{@code curve} 为
 * {@link Prefs#CURVE_LOG} / {@link Prefs#CURVE_LINEAR} / {@link Prefs#CURVE_SQRT}。</p>
 */
public final class Range {
    public final int min;
    public final int max;
    /** 曲线类型：{@link Prefs#CURVE_LOG} / {@link Prefs#CURVE_LINEAR} / {@link Prefs#CURVE_SQRT}。 */
    public final int curve;

    public Range(int min, int max, int curve) {
        this.min = min;
        this.max = max;
        this.curve = curve;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Range)) {
            return false;
        }
        Range other = (Range) o;
        return min == other.min && max == other.max && curve == other.curve;
    }

    @Override
    public int hashCode() {
        int result = min;
        result = 31 * result + max;
        result = 31 * result + curve;
        return result;
    }
}
