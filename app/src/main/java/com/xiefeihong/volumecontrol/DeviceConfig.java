package com.xiefeihong.volumecontrol;

import java.util.Objects;

/**
 * 单个输出设备的配置：生效模式 + 模式A/B 各自的音量范围。不可变值对象。
 *
 * <p>模式A（绝对音量，仅蓝牙）与模式B（软件衰减）各保存一套 {@link Range}；
 * 外放/有线不使用 {@link #absolute}（其 {@link #mode} 只会是默认(0)/相对(2)）。</p>
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
    /** 模式A（绝对音量）范围；仅蓝牙使用。 */
    public final Range absolute;
    /** 模式B（相对音量 / 软件衰减）范围。 */
    public final Range software;

    public DeviceConfig(int mode, Range absolute, Range software) {
        this.mode = mode;
        this.absolute = absolute;
        this.software = software;
    }

    /** 是否处于默认（系统直通）模式。 */
    public boolean isDefault() {
        return mode == Prefs.BT_MODE_DEFAULT;
    }

    /** 该设备所选模式对应的范围；默认直通返回 {@code null}。 */
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
        return new DeviceConfig(newMode, absolute, software);
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
                && Objects.equals(software, other.software);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, absolute, software);
    }
}
