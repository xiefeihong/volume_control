package com.xiefeihong.volumecontrol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「默认（系统原生）」音量曲线。
 *
 * <p>Android 13+/AIDL Audio HAL 下，framework 层（{@code dumpsys audio} / AudioService）
 * 不再持有逐档 index→dB 曲线，增益映射下沉到厂商 audio policy。这里通过 root 读取
 * {@code /vendor/etc/audio_policy_volumes.xml}（媒体流按 deviceCategory 引用某曲线名）与
 * {@code /vendor/etc/default_volume_tables.xml}（被引用的实际「档位百分比 → 增益毫贝」点表），
 * 还原媒体流在各输出设备类别下的真实默认曲线；读不到时回退到硬编码的 AOSP 参考曲线。</p>
 *
 * <p>点表 x＝跨档位区间的百分比（0~100），y＝增益（millibel，dB×100，满量程 0）。
 * 归一化到 0~100% 作展示：以本曲线最负增益为底、0 dB 为顶线性归一（近似硬件增益阶梯，
 * 非最终主观响度）。{@link #load()} 只应在后台线程调用（涉及 root 读文件）。</p>
 */
public final class NativeVolumeCurve {

    private NativeVolumeCurve() {
    }

    /** 输出设备类别（对应 audio_policy_volumes.xml 的 deviceCategory）。 */
    public enum Device {
        SPEAKER("DEVICE_CATEGORY_SPEAKER", "DEFAULT_DEVICE_CATEGORY_SPEAKER_VOLUME_CURVE"),
        WIRED("DEVICE_CATEGORY_HEADSET", "DEFAULT_MEDIA_VOLUME_CURVE"),
        BT("DEVICE_CATEGORY_EXT_MEDIA", "DEFAULT_MEDIA_VOLUME_CURVE");

        final String categoryToken;
        final String aospRefName;

        Device(String categoryToken, String aospRefName) {
            this.categoryToken = categoryToken;
            this.aospRefName = aospRefName;
        }
    }

    /** 某设备在给定档位数下的归一化增益曲线。 */
    public static final class Curve {
        public final Device device;
        /** true＝解析自本机 ROM 配置；false＝AOSP 参考回退。 */
        public final boolean fromRom;
        /** 长度 maxSteps+1，gainPercent[i]＝第 i 档的归一化增益 0~100。 */
        public final float[] gainPercent;
        public final int maxSteps;

        Curve(Device device, boolean fromRom, float[] gainPercent, int maxSteps) {
            this.device = device;
            this.fromRom = fromRom;
            this.gainPercent = gainPercent;
            this.maxSteps = maxSteps;
        }
    }

    /** AOSP 参考点表（读不到 ROM 文件时回退），来自本机 dump 的标准 AOSP 媒体曲线。 */
    private static final int[][][] AOSP_POINTS = {
            // SPEAKER：DEFAULT_DEVICE_CATEGORY_SPEAKER_VOLUME_CURVE
            {{0, -9600}, {1, -5800}, {20, -4000}, {60, -1700}, {100, 0}},
            // WIRED：DEFAULT_MEDIA_VOLUME_CURVE
            {{1, -5800}, {20, -4000}, {60, -1700}, {100, 0}},
            // BT：DEFAULT_MEDIA_VOLUME_CURVE
            {{1, -5800}, {20, -4000}, {60, -1700}, {100, 0}},
    };

    /** 解析到的各设备点表（[deviceOrdinal][k][2]）；null 元素表示该设备需回退。 */
    private static volatile int[][][] parsedPoints;
    private static volatile boolean loaded = false;

    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "<reference\\b[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</reference>", Pattern.DOTALL);
    private static final Pattern MUSIC_VOLUME_PATTERN = Pattern.compile(
            "<volume\\b[^>]*stream=\"AUDIO_STREAM_MUSIC\"[^>]*deviceCategory=\"([^\"]*)\"[^>]*>",
            Pattern.DOTALL);
    private static final Pattern POINT_PATTERN = Pattern.compile(
            "<point>\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*</point>");

    /**
     * 后台线程调用：读 ROM 配置并解析媒体流各设备类别的点表。任何异常都静默回退到
     * {@link #AOSP_POINTS}；解析结果进程内缓存一次。
     */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        int[][][] points = new int[Device.values().length][][];
        try {
            String tables = readXml("/vendor/etc/default_volume_tables.xml");
            String volumes = readXml("/vendor/etc/audio_policy_volumes.xml");
            Map<String, int[][]> refs = parseReferences(tables);
            Map<String, int[][]> musicByCategory = parseMusicVolumes(volumes, refs);
            for (Device d : Device.values()) {
                int[][] p = musicByCategory.get(d.categoryToken);
                if (p != null && p.length >= 2) {
                    points[d.ordinal()] = p;
                }
            }
        } catch (Throwable ignored) {
            // 读取/解析失败：points 保持 null，curveFor 走 AOSP 回退
        }
        parsedPoints = points;
        loaded = true;
    }

    /** 主线程：取某设备在 {@code maxSteps} 档下的归一化增益曲线（0 档恒为 0%）。 */
    public static Curve curveFor(Device device, int maxSteps) {
        int steps = Math.max(1, maxSteps);
        int[][] parsed = parsedPoints == null ? null : parsedPoints[device.ordinal()];
        boolean fromRom = parsed != null && parsed.length >= 2;
        int[][] points = fromRom ? parsed : AOSP_POINTS[device.ordinal()];
        int floorM = points[0][1];
        for (int[] pt : points) {
            floorM = Math.min(floorM, pt[1]);
        }
        float[] gain = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double percentAcross = i * 100.0 / steps;
            int milliBel = interpolate(points, percentAcross);
            double norm = floorM >= 0 ? 1.0 : (milliBel - (double) floorM) / (0.0 - floorM);
            gain[i] = (float) (clamp01(norm) * 100.0);
        }
        return new Curve(device, fromRom, gain, steps);
    }

    private static String readXml(String path) {
        ShellResult r = Shell.su("cat " + path + " 2>/dev/null");
        return r != null && r.isSuccess() ? r.output : null;
    }

    /** default_volume_tables.xml：reference 名 → 点表。 */
    private static Map<String, int[][]> parseReferences(String xml) {
        Map<String, int[][]> map = new HashMap<>();
        if (xml == null) {
            return map;
        }
        Matcher m = REFERENCE_PATTERN.matcher(xml);
        while (m.find()) {
            String name = m.group(1);
            String body = m.group(2);
            String ref = extractAttr(body, "ref");
            if (ref != null && map.containsKey(ref)) {
                map.put(name, map.get(ref));
                continue;
            }
            int[][] pts = parsePoints(body);
            if (pts.length >= 2) {
                map.put(name, pts);
            }
        }
        return map;
    }

    /** audio_policy_volumes.xml：deviceCategory → 媒体点表（内联点或跟随 ref 引用）。 */
    private static Map<String, int[][]> parseMusicVolumes(String xml, Map<String, int[][]> refs) {
        Map<String, int[][]> map = new HashMap<>();
        if (xml == null) {
            return map;
        }
        Matcher m = MUSIC_VOLUME_PATTERN.matcher(xml);
        while (m.find()) {
            String category = m.group(1);
            boolean selfClosing = m.group(0).endsWith("/>");
            int start = m.end();
            if (selfClosing) {
                // 自闭合标签：整段信息在开标签内（多为 ref="NAME"）
                String tag = m.group(0);
                putResolved(map, category, tag, null, refs);
            } else {
                int end = xml.indexOf("</volume>", start);
                if (end < 0) {
                    continue;
                }
                String tag = xml.substring(m.start(), start);
                String body = xml.substring(start, end);
                putResolved(map, category, tag, body, refs);
            }
        }
        return map;
    }

    private static void putResolved(Map<String, int[][]> map, String category,
            String openTag, String body, Map<String, int[][]> refs) {
        if (category == null || category.isEmpty() || map.containsKey(category)) {
            return;
        }
        if (body != null) {
            int[][] inline = parsePoints(body);
            if (inline.length >= 2) {
                map.put(category, inline);
                return;
            }
        }
        String ref = extractAttr(openTag, "ref");
        if (ref != null && refs.containsKey(ref)) {
            map.put(category, refs.get(ref));
        }
    }

    private static int[][] parsePoints(String s) {
        List<int[]> list = new ArrayList<>();
        if (s != null) {
            Matcher pm = POINT_PATTERN.matcher(s);
            while (pm.find()) {
                list.add(new int[] {Integer.parseInt(pm.group(1)), Integer.parseInt(pm.group(2))});
            }
        }
        list.sort((a, b) -> Integer.compare(a[0], b[0]));
        return list.toArray(new int[0][]);
    }

    /** 从标签片段中提取 {@code name="VALUE"} 属性值。 */
    private static String extractAttr(String tag, String attr) {
        Matcher am = Pattern.compile(attr + "=\"([^\"]+)\"").matcher(tag);
        return am.find() ? am.group(1) : null;
    }

    /** 在按百分比升序的点表间对 {@code percentAcross} 线性插值出毫贝增益。 */
    private static int interpolate(int[][] points, double percentAcross) {
        if (percentAcross <= points[0][0]) {
            return points[0][1];
        }
        int[] last = points[points.length - 1];
        if (percentAcross >= last[0]) {
            return last[1];
        }
        for (int i = 1; i < points.length; i++) {
            int[] p0 = points[i - 1];
            int[] p1 = points[i];
            if (percentAcross <= p1[0]) {
                double t = (percentAcross - p0[0]) / (double) (p1[0] - p0[0]);
                return (int) Math.round(p0[1] + t * (p1[1] - p0[1]));
            }
        }
        return last[1];
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }
}
