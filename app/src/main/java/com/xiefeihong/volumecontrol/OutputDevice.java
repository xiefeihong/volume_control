package com.xiefeihong.volumecontrol;

/**
 * 输出设备类别（App 与 Hook 端共用，纯 {@code DEVICE_OUT} int 位掩码判定，
 * 禁止引用任何 Android / Xposed API）。
 *
 * <p>序数与 UI 设备标签、{@link NativeVolumeCurve.Device} 保持一致
 * （SPEAKER/WIRED/BT = 0/1/2），便于 {@code values()[tabIndex]} 直接映射。</p>
 */
public enum OutputDevice {

    /** 内置扬声器 / 受话器。 */
    SPEAKER,
    /** 有线耳机 / USB / 底座 / 线路输出。 */
    WIRED,
    /** 蓝牙（SCO / A2DP / BLE）。 */
    BT;

    /** 仅蓝牙支持「绝对音量（模式A）」；外放/有线只能相对音量或默认直通。 */
    public boolean supportsAbsolute() {
        return this == BT;
    }

    /** 蓝牙输出位：SCO 0x10/0x20/0x40、A2DP 0x80/0x100/0x200、BLE 0x800000/0x1000000/0x2000000。 */
    private static final int BT_MASK = 0x70 | 0x380 | 0x3800000;

    /** 有线耳机/USB/底座/线路输出位（不含内置扬声器 0x2 / 受话器 0x1）。 */
    private static final int WIRED_MASK =
            0x4 | 0x8 | 0x800 | 0x1000 | 0x2000 | 0x4000 | 0x20000;

    /** 内置扬声器 0x2 / 受话器 0x1。 */
    private static final int SPEAKER_MASK = 0x1 | 0x2;

    /**
     * 由 AudioSystem {@code DEVICE_OUT} 位掩码判定设备类别。蓝牙优先；未识别返回 {@code null}
     * （调用方保守放行，避免误伤未知设备）。
     */
    public static OutputDevice fromOutMask(int device) {
        if ((device & BT_MASK) != 0) {
            return BT;
        }
        if ((device & WIRED_MASK) != 0) {
            return WIRED;
        }
        if ((device & SPEAKER_MASK) != 0) {
            return SPEAKER;
        }
        return null;
    }
}
