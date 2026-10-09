package com.xiefeihong.volumecontrol;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;

/**
 * 音量配置的不可变值对象（纯 Java，禁止引用任何 Android / Xposed API）。
 *
 * <p>按「外放 / 有线 / 蓝牙」三设备各自持有一份 {@link DeviceConfig}（生效模式 +
 * 模式A/B 范围），加全局的启用开关、档位数、音量键步进。整体以<b>自描述 XML</b>
 * 序列化，供 App 单键持久化与跨进程（Settings.Global / 镜像文件 / boot 脚本）传递。</p>
 *
 * <p><b>不兼容任何历史格式</b>：位置式 {@code ;} 串（{@code toRaw/fromRaw/readRange}）
 * 已彻底删除，全链路只认 XML。{@link #fromXml(String)} 解析失败或字段缺失一律回落到
 * 对象默认值。</p>
 *
 * <p>与 {@link Prefs}/{@link Avrcp}/{@link VolumeMode}/{@link DeviceConfig} 一样同时被
 * App 进程与 Hook 端（system_server / 蓝牙进程）使用。</p>
 */
public final class VolumeConfig {

    /** 模块总开关：关闭时全部设备直通（不重写档位数、不重映射）。 */
    public final boolean enabled;
    /** 媒体音量级数（已限制在 10~100），全局共用。 */
    public final int mediaSteps;
    /** 音量键步进（跨完整音量条需要的按键段数，已限制在 10~29），全局共用。 */
    public final int keySteps;
    /** 外放（内置扬声器/受话器）配置。 */
    public final DeviceConfig speaker;
    /** 有线耳机/USB/底座配置。 */
    public final DeviceConfig wired;
    /** 蓝牙配置。 */
    public final DeviceConfig bluetooth;

    public VolumeConfig(boolean enabled, int mediaSteps, int keySteps,
            DeviceConfig speaker, DeviceConfig wired, DeviceConfig bluetooth) {
        this.enabled = enabled;
        this.mediaSteps = Prefs.clampMediaSteps(mediaSteps);
        this.keySteps = Prefs.clampKeySteps(keySteps);
        this.speaker = speaker;
        this.wired = wired;
        this.bluetooth = bluetooth;
    }

    // ==================== 按设备访问 ====================

    /** 取指定设备的配置。 */
    public DeviceConfig deviceFor(OutputDevice d) {
        switch (d) {
            case WIRED:
                return wired;
            case BT:
                return bluetooth;
            case SPEAKER:
            default:
                return speaker;
        }
    }

    /** 指定设备的生效模式 id（0/1/2）。 */
    public int modeFor(OutputDevice d) {
        return deviceFor(d).mode;
    }

    /**
     * 指定设备在 system_server 侧应采用的重映射模式；未启用或该设备为默认直通返回 {@code null}
     * （返回 {@link VolumeMode#DEFAULT} 之外的实际重映射枚举）。
     */
    public VolumeMode volumeModeFor(OutputDevice d) {
        if (!enabled) {
            return null;
        }
        DeviceConfig c = deviceFor(d);
        if (c.mode == Prefs.BT_MODE_SOFTWARE) {
            return VolumeMode.SOFTWARE;
        }
        if (c.mode == Prefs.BT_MODE_ABSOLUTE) {
            return VolumeMode.ABSOLUTE;
        }
        return null;
    }

    /**
     * 指定设备当前生效的范围：总开关开启时，非默认设备返回其模式对应范围；默认设备仅当
     * 默认范围（{@link DeviceConfig#defaultRange}）被收窄（非满量程）时返回该范围，满量程/关闭=直通返回 {@code null}。
     */
    public Range activeRangeFor(OutputDevice d) {
        if (!enabled) {
            return null;
        }
        DeviceConfig c = deviceFor(d);
        if (c.mode == Prefs.BT_MODE_DEFAULT) {
            return isFullSpan(c.defaultRange) ? null : c.defaultRange;
        }
        return c.activeRange();
    }

    /** 满量程（等价直通）：{@code min<=0 且 max>=mediaSteps}；null 视为满量程。 */
    private boolean isFullSpan(Range r) {
        return r == null || (r.min <= 0 && r.max >= mediaSteps);
    }

    // ==================== 生效判据 ====================

    /**
     * Hook 端是否应改写系统全局媒体档位数（{@link #mediaSteps} 是否落地）：
     * 「启用档位修改」即 {@link #enabled} 为真时应用用户档位数，关闭时保持系统默认（系统直通）。
     *
     * <p>本判据只决定<b>全局档位数</b>是否改写；单个设备是否做音量重映射另由
     * {@link #activeRangeFor(OutputDevice)} 决定（默认满量程返回 {@code null} → 该设备直通），二者解耦。</p>
     */
    public boolean remapActive() {
        return enabled;
    }

    /** 蓝牙是否处于「相对音量（模式B）」：需抑制 AVRCP 绝对音量、走系统软件衰减。 */
    public boolean suppressBtAbsoluteVolume() {
        return enabled && bluetooth.mode == Prefs.BT_MODE_SOFTWARE;
    }

    /** 蓝牙是否处于「绝对音量（模式A）」：由蓝牙进程经 AVRCP 映射，返回 {@link VolumeMode#ABSOLUTE}；否则 {@code null}。 */
    public VolumeMode bluetoothAvrcpMode() {
        return (enabled && bluetooth.mode == Prefs.BT_MODE_ABSOLUTE) ? VolumeMode.ABSOLUTE : null;
    }

    /** 三设备中是否存在任一非默认（选了绝对/相对）模式。 */
    public boolean anyDeviceNonDefault() {
        return !speaker.isDefault() || !wired.isDefault() || !bluetooth.isDefault();
    }

    /** 返回把三设备模式全部改为默认直通（保留各自 A/B 范围）后的副本，供门控强制回落。 */
    public VolumeConfig withAllDevicesDefault() {
        return new VolumeConfig(enabled, mediaSteps, keySteps,
                speaker.withMode(Prefs.BT_MODE_DEFAULT),
                wired.withMode(Prefs.BT_MODE_DEFAULT),
                bluetooth.withMode(Prefs.BT_MODE_DEFAULT));
    }

    // ==================== 不可变编辑辅助（供 UI） ====================

    public VolumeConfig withEnabled(boolean value) {
        return new VolumeConfig(value, mediaSteps, keySteps, speaker, wired, bluetooth);
    }

    public VolumeConfig withSteps(int media, int key) {
        return new VolumeConfig(enabled, media, key, speaker, wired, bluetooth);
    }

    /** 返回把指定设备替换为新配置后的副本。 */
    public VolumeConfig withDevice(OutputDevice d, DeviceConfig c) {
        switch (d) {
            case WIRED:
                return new VolumeConfig(enabled, mediaSteps, keySteps, speaker, c, bluetooth);
            case BT:
                return new VolumeConfig(enabled, mediaSteps, keySteps, speaker, wired, c);
            case SPEAKER:
            default:
                return new VolumeConfig(enabled, mediaSteps, keySteps, c, wired, bluetooth);
        }
    }

    // ==================== XML 序列化 ====================

    private static final String TAG_ROOT = "config";
    private static final String TAG_DEVICE = "device";
    /** 缺字段时的兜底范围（App 正常写入总会给出完整三元组，此处仅防御畸形 XML）。 */
    private static final Range FALLBACK_RANGE =
            new Range(Prefs.ABS_VOLUME_MIN_DEFAULT, Prefs.ABS_VOLUME_MAX_DEFAULT,
                    Prefs.CURVE_TYPE_DEFAULT);
    /** 默认范围的兜底：满量程直通（min=0、max=最大档位数），curve 对默认无效。 */
    private static final Range DEFAULT_FALLBACK_RANGE =
            new Range(0, Prefs.MEDIA_STEPS_MAX, Prefs.CURVE_LINEAR);

    /**
     * 序列化为紧凑单行 XML。每设备始终写模式B 范围 {@code <b>} 与默认范围 {@code <d>}；
     * 仅蓝牙额外写模式A 范围 {@code <a>}。用于 SharedPreferences 暂存与差异比对（稳定、无换行）。
     */
    public String toXml() {
        return buildXml(false);
    }

    /**
     * 序列化为缩进多行的可读 XML，内容与 {@link #toXml()} 解析等价，仅排版不同。供写入
     * {@code Settings.Global} 与镜像文件，便于人工 {@code cat}/{@code settings get} 直接查看。
     * 全程只用双引号、不含单引号，可安全嵌入 {@code settings put global '<v>'} 与 {@code echo '<v>'}
     * （POSIX 单引号原样保留其中的换行）。
     */
    public String toPrettyXml() {
        return buildXml(true);
    }

    private String buildXml(boolean pretty) {
        String nl = pretty ? "\n" : "";
        String i1 = pretty ? "  " : "";
        String i2 = pretty ? "    " : "";
        StringBuilder sb = new StringBuilder(pretty ? 512 : 256);
        sb.append('<').append(TAG_ROOT)
                .append(" enabled=\"").append(enabled ? 1 : 0)
                .append("\" mediaSteps=\"").append(Prefs.clampMediaSteps(mediaSteps))
                .append("\" keySteps=\"").append(Prefs.clampKeySteps(keySteps))
                .append(pretty ? "\">\n" : "\">");
        appendDevice(sb, "speaker", speaker, false, i1, i2, nl);
        appendDevice(sb, "wired", wired, false, i1, i2, nl);
        appendDevice(sb, "bt", bluetooth, true, i1, i2, nl);
        sb.append("</").append(TAG_ROOT).append('>');
        return sb.toString();
    }

    private static void appendDevice(StringBuilder sb, String name, DeviceConfig c, boolean withAbsolute,
            String i1, String i2, String nl) {
        sb.append(i1).append('<').append(TAG_DEVICE)
                .append(" name=\"").append(name)
                .append("\" mode=\"").append(c.mode).append("\">").append(nl);
        if (withAbsolute) {
            appendRange(sb, 'a', c.absolute, i2, nl);
        }
        appendRange(sb, 'b', c.software, i2, nl);
        appendRange(sb, 'd', c.defaultRange, i2, nl);
        sb.append(i1).append("</").append(TAG_DEVICE).append('>').append(nl);
    }

    private static void appendRange(StringBuilder sb, char tag, Range r, String i2, String nl) {
        Range safe = (r != null) ? r : FALLBACK_RANGE;
        sb.append(i2).append('<').append(tag)
                .append(" min=\"").append(Prefs.clampAbs(safe.min))
                .append("\" max=\"").append(Prefs.clampAbs(safe.max))
                .append("\" curve=\"").append(Prefs.clampCurve(safe.curve))
                .append("\"/>").append(nl);
    }

    /**
     * 解析配置 XML。字段缺失按默认值、未知标签忽略；{@code xml} 为 null/空白、解析异常或根标签
     * 非 {@code config} 时返回 {@code null}（调用方回落默认配置）。
     */
    public static VolumeConfig fromXml(String xml) {
        if (xml == null) {
            return null;
        }
        String trimmed = xml.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(trimmed)));
            Element root = doc.getDocumentElement();
            if (root == null || !TAG_ROOT.equals(root.getTagName())) {
                return null;
            }
            boolean enabled = intAttr(root, "enabled", 0) != 0;
            int mediaSteps = Prefs.clampMediaSteps(
                    intAttr(root, "mediaSteps", Prefs.MEDIA_STEPS_DEFAULT));
            int keySteps = Prefs.clampKeySteps(
                    intAttr(root, "keySteps", Prefs.KEY_STEP_DEFAULT));
            return new VolumeConfig(enabled, mediaSteps, keySteps,
                    readDevice(root, "speaker", false),
                    readDevice(root, "wired", false),
                    readDevice(root, "bt", true));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取指定 name 的设备元素；缺失时给出默认（直通 + 兜底范围）。 */
    private static DeviceConfig readDevice(Element root, String name, boolean withAbsolute) {
        Element el = findDevice(root, name);
        if (el == null) {
            return new DeviceConfig(Prefs.BT_MODE_DEFAULT, FALLBACK_RANGE, FALLBACK_RANGE,
                    DEFAULT_FALLBACK_RANGE);
        }
        int rawMode = intAttr(el, "mode", Prefs.BT_MODE_DEFAULT);
        int mode = normalizeMode(rawMode, withAbsolute);
        Range absolute = withAbsolute ? readRange(el, "a") : FALLBACK_RANGE;
        Range software = readRange(el, "b");
        Range defaultRange = readRange(el, "d", DEFAULT_FALLBACK_RANGE);
        return new DeviceConfig(mode, absolute, software, defaultRange);
    }

    private static Element findDevice(Element root, String name) {
        NodeList nodes = root.getElementsByTagName(TAG_DEVICE);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element
                    && name.equals(((Element) node).getAttribute("name"))) {
                return (Element) node;
            }
        }
        return null;
    }

    /** 读取指定标签的范围；缺失回落 {@link #FALLBACK_RANGE}。 */
    private static Range readRange(Element parent, String tag) {
        return readRange(parent, tag, FALLBACK_RANGE);
    }

    /** 读取指定标签的范围；缺失回落 {@code fallback}。 */
    private static Range readRange(Element parent, String tag, Range fallback) {
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node instanceof Element) {
                Element el = (Element) node;
                return new Range(
                        Prefs.clampAbs(intAttr(el, "min", Prefs.ABS_VOLUME_MIN_DEFAULT)),
                        Prefs.clampAbs(intAttr(el, "max", Prefs.ABS_VOLUME_MAX_DEFAULT)),
                        Prefs.clampCurve(intAttr(el, "curve", Prefs.CURVE_TYPE_DEFAULT)));
            }
        }
        return fallback;
    }

    private static int intAttr(Element el, String name, int def) {
        String v = el.getAttribute(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 归一模式：允许绝对(仅蓝牙)时 0/1/2 原样；否则把绝对(1) 归一为相对(2)（外放/有线不支持模式A），
     * 非法值一律回落默认(0)。
     */
    private static int normalizeMode(int m, boolean allowAbsolute) {
        if (m == Prefs.BT_MODE_SOFTWARE) {
            return Prefs.BT_MODE_SOFTWARE;
        }
        if (m == Prefs.BT_MODE_ABSOLUTE) {
            return allowAbsolute ? Prefs.BT_MODE_ABSOLUTE : Prefs.BT_MODE_SOFTWARE;
        }
        return Prefs.BT_MODE_DEFAULT;
    }

    /** 供日志输出的人类可读摘要。 */
    @Override
    public String toString() {
        return (enabled ? "on" : "off")
                + ",steps=" + mediaSteps
                + ",keySteps=" + keySteps
                + ",speaker=" + deviceDesc(speaker)
                + ",wired=" + deviceDesc(wired)
                + ",bt=" + deviceDesc(bluetooth);
    }

    private static String deviceDesc(DeviceConfig c) {
        String m = (c.mode == Prefs.BT_MODE_SOFTWARE) ? "B"
                : c.mode == Prefs.BT_MODE_ABSOLUTE ? "A" : "默认";
        Range r = (c.mode == Prefs.BT_MODE_DEFAULT) ? c.defaultRange : c.activeRange();
        return m + (r == null ? "" : "[" + r.min + "~" + r.max + "/" + r.curve + "]");
    }
}
