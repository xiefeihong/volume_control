package com.xiefeihong.volumecontrol;

import java.util.Objects;

/**
 * 单个输出设备的配置：生效模式 + 模式A/B/默认 各自独立的音量范围。不可变值对象。
 *
 * <p>模式A（绝对音量，仅蓝牙）、模式B（软件衰减）与默认（可调最小/最大）各保存一套
 * {@link Range}，互不同步；外放/有线不使用 {@link #absolute}（其 {@link #mode} 只会是默认(0)/相对(2)）。</p>
 *
 * <p>与 {@link Prefs}/{@link VolumeMode}/{@link VolumeConfig} 一样同时被 App 进程与 Hook 端
 * （system_server / 蓝牙进程）使用，禁止引用任何 Android / Xposed API。</p>
 */
public final class DeviceConfig {

    /**
     * 该设备生效模式 id：{@link Prefs#BT_MODE_DEFAULT}(0·系统直通) /
     * {@link Prefs#BT_MODE_ABSOLUTE}(1·绝对音量, 仅蓝牙) / {@link Prefs#BT_MODE_SOFTWARE}(2·相对音量)。
     */
    public final int mode;
    /** 模式A（绝对音量）范围（AVRCP 0~127）；仅蓝牙使用。 */
    public final Range absolute;
    /** 模式B（相对音量 / 软件衰减）范围（系统档位单位）。 */
    public final Range software;
    /** 默认模式可调范围（系统档位单位，仅用 min/max，curve 忽略）；与模式B 独立。 */
    public final Range defaultRange;

    public DeviceConfig(int mode, Range absolute, Range software, Range defaultRange) {
        this.mode = mode;
        this.absolute = absolute;
        this.software = software;
        this.defaultRange = defaultRange;
    }

    /** 是否处于默认（系统直通）模式。 */
    public boolean isDefault() {
        return mode == Prefs.BT_MODE_DEFAULT;
    }

    /** 该设备所选模式对应的 A/B 范围；默认模式返回 {@code null}（其范围由 {@link #defaultRange} 单独持有）。 */
    public Range activeRange() {
        if (mode == Prefs.BT_MODE_SOFTWARE) {
            return software;
        }
        if (mode == Prefs.BT_MODE_ABSOLUTE) {
            return absolute;
        }
        return null;
    }

    /** 返回一个仅模式不同的副本（范围保持不变），供界面按设备写回模式选择。 */
    public DeviceConfig withMode(int newMode) {
        return new DeviceConfig(newMode, absolute, software, defaultRange);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeviceConfig)) {
            return false;
        }
        DeviceConfig other = (DeviceConfig) o;
        return mode == other.mode
                && Objects.equals(absolute, other.absolute)
                && Objects.equals(software, other.software)
                && Objects.equals(defaultRange, other.defaultRange);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, absolute, software, defaultRange);
    }
}
