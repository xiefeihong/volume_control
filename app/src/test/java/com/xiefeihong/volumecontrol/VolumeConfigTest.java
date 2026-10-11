package com.xiefeihong.volumecontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link VolumeConfig} 的 XML 序列化/解析往返、畸形输入回落、构造期钳制与 XXE 加固的 JVM 单元测试。
 */
public class VolumeConfigTest {

    private static DeviceConfig dev(int mode, Range abs, Range soft, Range def) {
        return new DeviceConfig(mode, abs, soft, def);
    }

    private static VolumeConfig sample() {
        return new VolumeConfig(true, 100, 25,
                dev(Prefs.BT_MODE_DEFAULT, new Range(0, 127, 0), new Range(0, 100, 1),
                        new Range(0, 100, 0)),
                dev(Prefs.BT_MODE_SOFTWARE, new Range(0, 127, 0), new Range(1, 80, 2),
                        new Range(0, 100, 0)),
                dev(Prefs.BT_MODE_ABSOLUTE, new Range(10, 127, 0), new Range(0, 100, 1),
                        new Range(0, 100, 0)));
    }

    @Test
    public void xmlRoundTrip_stable() {
        VolumeConfig c = sample();
        String xml = c.toXml();
        VolumeConfig back = VolumeConfig.fromXml(xml);
        assertNotNull(back);
        assertEquals(xml, back.toXml());
    }

    @Test
    public void prettyXml_parsesEquivalentToCompact() {
        VolumeConfig c = sample();
        String compact = c.toXml();
        VolumeConfig fromPretty = VolumeConfig.fromXml(c.toPrettyXml());
        assertNotNull(fromPretty);
        assertEquals(compact, fromPretty.toXml());
    }

    @Test
    public void fieldsPreservedAcrossRoundTrip() {
        VolumeConfig back = VolumeConfig.fromXml(sample().toXml());
        assertNotNull(back);
        assertTrue(back.enabled);
        assertEquals(100, back.mediaSteps);
        assertEquals(25, back.keySteps);
        assertEquals(Prefs.BT_MODE_SOFTWARE, back.modeFor(OutputDevice.WIRED));
        assertEquals(Prefs.BT_MODE_ABSOLUTE, back.modeFor(OutputDevice.BT));
        assertEquals(Prefs.BT_MODE_DEFAULT, back.modeFor(OutputDevice.SPEAKER));
        assertEquals(new Range(10, 127, 0), back.bluetooth.absolute);
        assertEquals(new Range(1, 80, 2), back.wired.software);
    }

    @Test
    public void malformedOrNullReturnsNull() {
        assertNull(VolumeConfig.fromXml(null));
        assertNull(VolumeConfig.fromXml("   "));
        assertNull(VolumeConfig.fromXml("<notconfig enabled=\"1\"/>"));  // 根标签非 config
        assertNull(VolumeConfig.fromXml("<<<broken>>>"));               // 解析异常
    }

    @Test
    public void missingFieldsFallBackToDefaults() {
        VolumeConfig back = VolumeConfig.fromXml("<config enabled=\"1\"></config>");
        assertNotNull(back);
        assertTrue(back.enabled);
        assertEquals(Prefs.MEDIA_STEPS_DEFAULT, back.mediaSteps);
        assertEquals(Prefs.KEY_STEP_DEFAULT, back.keySteps);
        // 无 <device> 子节点 → 各设备回落默认直通模式
        assertEquals(Prefs.BT_MODE_DEFAULT, back.modeFor(OutputDevice.BT));
    }

    @Test
    public void constructorClampsOutOfRange() {
        VolumeConfig c = new VolumeConfig(false, 9999, 0,
                dev(0, new Range(0, 127, 0), new Range(0, 100, 0), new Range(0, 100, 0)),
                dev(0, new Range(0, 127, 0), new Range(0, 100, 0), new Range(0, 100, 0)),
                dev(0, new Range(0, 127, 0), new Range(0, 100, 0), new Range(0, 100, 0)));
        assertEquals(100, c.mediaSteps);   // clampMediaSteps(9999)
        assertEquals(10, c.keySteps);      // clampKeySteps(0)
    }

    @Test
    public void doctypeRejectedForXxe() {
        String evil = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE config [<!ENTITY x \"y\">]>"
                + "<config enabled=\"1\" mediaSteps=\"100\" keySteps=\"25\"/>";
        assertNull(VolumeConfig.fromXml(evil));   // disallow-doctype-decl → 解析异常 → null
    }
}
