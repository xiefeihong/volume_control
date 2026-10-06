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
 * 不再持有逐档 index→dB 曲线，增益映射下沉到厂商 audio policy。这里通过 root 读取三处配置，
 * 按「更专属优先」还原媒体流在各输出设备类别下的真实默认曲线：</p>
 * <ol>
 *   <li>主源 {@code /vendor/etc/audio_policy_engine_stream_volumes.xml}——旧式音量引擎表，
 *       按 {@code <volumeGroup><name>music</name>} 给出各 {@code deviceCategory}（含厂商专属的
 *       {@code DEVICE_CATEGORY_A2DP}）显式 dB 点表，最贴近本机调音；</li>
 *   <li>次源 {@code /vendor/etc/audio_policy_volumes.xml}——新式 audio policy（媒体流多走
 *       {@code ref=} 引用），配合 {@code /vendor/etc/default_volume_tables.xml} 解析被引用点表；</li>
 *   <li>回退：硬编码 AOSP 参考曲线（读不到任何 ROM 文件时）。</li>
 * </ol>
 *
 * <p>点表 x＝跨档位区间的百分比（0~100），y＝增益（millibel，dB×100，满量程 0）。
 * 归一化到 0~100% 作展示：以本曲线最负增益为底、0 dB 为顶线性归一（近似硬件增益阶梯，
 * 非最终主观响度）。{@link #load()} 只应在后台线程调用（涉及 root 读文件）。</p>
 */
public final class NativeVolumeCurve {

    private NativeVolumeCurve() {
    }

    /** 输出设备类别：分别给出主源(引擎表)/次源(策略表)的 deviceCategory token。 */
    public enum Device {
        SPEAKER("DEVICE_CATEGORY_SPEAKER", "DEVICE_CATEGORY_SPEAKER",
                "DEFAULT_DEVICE_CATEGORY_SPEAKER_VOLUME_CURVE"),
        WIRED("DEVICE_CATEGORY_HEADSET", "DEVICE_CATEGORY_HEADSET",
                "DEFAULT_MEDIA_VOLUME_CURVE"),
        // 蓝牙：引擎表有专属 A2DP；新策略表只有 EXT_MEDIA
        BT("DEVICE_CATEGORY_A2DP", "DEVICE_CATEGORY_EXT_MEDIA",
                "DEFAULT_MEDIA_VOLUME_CURVE");

        final String engineToken;
        final String policyToken;
        final String aospRefName;

        Device(String engineToken, String policyToken, String aospRefName) {
            this.engineToken = engineToken;
            this.policyToken = policyToken;
            this.aospRefName = aospRefName;
        }
    }

    /** 曲线来源。 */
    public enum Source { ENGINE, POLICY, AOSP }

    /**
     * 纵轴换算口径（同一 dB 阶梯，三种映射，决定曲线弯法）：
     * <ul>
     *   <li>{@link #DB_LINEAR}：dB 在 [floor,ceil] 线性 → 忠实硬件增益阶梯；</li>
     *   <li>{@link #AMPLITUDE}：振幅比 {@code 10^(dB/20)} 归一 → 反映信号幅度；</li>
     *   <li>{@link #PERCEPTUAL}：振幅^0.6（Stevens 幂律，近似等响）归一 → 贴近主观响度。</li>
     * </ul>
     */
    public enum Mode { DB_LINEAR, AMPLITUDE, PERCEPTUAL }

    /** 某设备在给定档位数下的归一化增益曲线。 */
    public static final class Curve {
        public final Device device;
        /** true＝解析自本机 ROM 配置（引擎/策略表）；false＝AOSP 参考回退。 */
        public final boolean fromRom;
        public final Source source;
        public final Mode mode;
        /** 长度 maxSteps+1，gainPercent[i]＝第 i 档的归一化增益 0~100。 */
        public final float[] gainPercent;
        /** 长度 maxSteps+1，gainDb[i]＝第 i 档的硬件增益 dB（millibel/100，负值）。 */
        public final float[] gainDb;
        public final int maxSteps;

        Curve(Device device, boolean fromRom, Source source, Mode mode,
                float[] gainPercent, float[] gainDb, int maxSteps) {
            this.device = device;
            this.fromRom = fromRom;
            this.source = source;
            this.mode = mode;
            this.gainPercent = gainPercent;
            this.gainDb = gainDb;
            this.maxSteps = maxSteps;
        }
    }

    /** AOSP 参考点表（三源全失败时回退），来自本机 dump 的标准 AOSP 媒体曲线。 */
    private static final int[][][] AOSP_POINTS = {
            // SPEAKER
            {{0, -9600}, {1, -5800}, {20, -4000}, {60, -1700}, {100, 0}},
            // WIRED（DEFAULT_MEDIA_VOLUME_CURVE）
            {{1, -5800}, {20, -4000}, {60, -1700}, {100, 0}},
            // BT（EXT_MEDIA → DEFAULT_MEDIA_VOLUME_CURVE）
            {{1, -5800}, {20, -4000}, {60, -1700}, {100, 0}},
    };

    private static final String FILE_ENGINE =
            "/vendor/etc/audio_policy_engine_stream_volumes.xml";
    private static final String FILE_POLICY =
            "/vendor/etc/audio_policy_volumes.xml";
    private static final String FILE_TABLES =
            "/vendor/etc/default_volume_tables.xml";

    private static volatile boolean loaded = false;
    // device.ordinal() → 解析出的点表（null＝该设备未取到，回退 AOSP）
    private static int[][][] parsed = null;
    private static Source[] parsedSource = null;
    // 引擎表 music 组的 <indexMax>（ROM 声明的媒体原生档位数）；0＝未解析到。
    private static int musicIndexMax = 0;

    /** 引擎表 music 组声明的媒体档位数（{@code <indexMax>}）；未加载/未解析到返回 0。 */
    public static int nativeMusicIndexMax() {
        return loaded ? musicIndexMax : 0;
    }

    /**
     * 读文件、解析（含 root 调用）。只在后台线程调用一次；解析失败静默回退 AOSP。
     */
    public static synchronized void load() {
        if (loaded) return;

        Map<String, int[][]> refMap = new HashMap<>();     // reference name → points（default_volume_tables）
        Map<String, int[][]> engineMusic = new HashMap<>(); // 引擎表 music 组 deviceCategory → points
        Map<String, int[][]> policyMusic = new HashMap<>();  // 策略表 MUSIC profile deviceCategory → points

        String tables = read(FILE_TABLES);
        if (tables != null) parseReferences(tables, refMap);

        String engine = read(FILE_ENGINE);
        if (engine != null) parseEngineMusic(engine, refMap, engineMusic);

        String policy = read(FILE_POLICY);
        if (policy != null) parseMusicVolumes(policy, refMap, policyMusic);

        int n = Device.values().length;
        int[][][] points = new int[n][][];
        Source[] sources = new Source[n];
        for (Device d : Device.values()) {
            int[][] p = engineMusic.get(d.engineToken);
            if (p != null) {
                points[d.ordinal()] = p;
                sources[d.ordinal()] = Source.ENGINE;
                continue;
            }
            p = policyMusic.get(d.policyToken);
            if (p != null) {
                points[d.ordinal()] = p;
                sources[d.ordinal()] = Source.POLICY;
            } else {
                sources[d.ordinal()] = Source.AOSP;
            }
        }
        parsed = points;
        parsedSource = sources;
        loaded = true;
    }

    /** 归一化增益曲线（0~100），按档位数 {@code steps} 输出。 */
    public static Curve curveFor(Device device, int steps) {
        return curveFor(device, steps, Mode.DB_LINEAR);
    }

    public static Curve curveFor(Device device, int steps, Mode mode) {
        int maxSteps = Math.max(1, steps);
        int[][] points = resolvePoints(device);
        float floor = floorDb(device, points);   // 最负 dB（底 → 0%）
        float ceil = ceilDb(device, points);     // 最接近 0 的 dB（顶 → 100%）
        float[] gain = new float[maxSteps + 1];
        float[] gainDb = new float[maxSteps + 1];
        for (int i = 0; i <= maxSteps; i++) {
            float percent = percentAcross(i, maxSteps);
            float db = interpolateDb(points, percent);
            gainDb[i] = db / 100f;                 // millibel → dB
            gain[i] = transform(db, floor, ceil, mode);
        }
        Source src = sourceOf(device);
        return new Curve(device, src != Source.AOSP, src, mode, gain, gainDb, maxSteps);
    }

    // --- 归一化 / 采样 ---

    /** 索引 → 跨区间百分比（index=0→0，index=maxSteps→100）。 */
    private static float percentAcross(int index, int maxSteps) {
        return 100f * index / maxSteps;
    }

    /** 按所选口径把 dB（毫贝）映射到 0~100%，端点分别锚定 floor/ceil（保证 0%~100%）。 */
    private static float transform(float dbMB, float floorMB, float ceilMB, Mode mode) {
        if (ceilMB <= floorMB) return 100f;
        switch (mode) {
            case AMPLITUDE: {
                double a = amp(dbMB), af = amp(floorMB), ac = amp(ceilMB);
                return clamp((float) ((a - af) / (ac - af) * 100.0), 0f, 100f);
            }
            case PERCEPTUAL: {
                double p = Math.pow(amp(dbMB), 0.6), pf = Math.pow(amp(floorMB), 0.6),
                        pc = Math.pow(amp(ceilMB), 0.6);
                return clamp((float) ((p - pf) / (pc - pf) * 100.0), 0f, 100f);
            }
            case DB_LINEAR:
            default:
                return clamp((dbMB - floorMB) / (ceilMB - floorMB) * 100f, 0f, 100f);
        }
    }

    /** dB（毫贝）→ 振幅比：{@code 10^(dB/20)}，dB＝millibel/100，故指数＝millibel/2000。 */
    private static double amp(double dbMillibel) {
        return Math.pow(10, dbMillibel / 2000.0);
    }

    /** 本曲线点表的最负 dB（底）。 */
    private static float floorDb(Device device, int[][] points) {
        float floor = points.length > 0 ? points[0][1] : -9600f;
        for (int[] pt : points) floor = Math.min(floor, pt[1]);
        return floor;
    }

    /** 本曲线点表的最接近 0 的 dB（顶）。 */
    private static float ceilDb(Device device, int[][] points) {
        float ceil = points.length > 0 ? points[0][1] : 0f;
        for (int[] pt : points) ceil = Math.max(ceil, pt[1]);
        return ceil;
    }

    /** 索引 0 恒为底（floor），其余按点表 dB 插值后归一，保证单调递增的相对增益。 */
    private static float interpolateDb(int[][] points, float percent) {
        if (points.length == 0) return -9600f;
        if (percent <= points[0][0]) return points[0][1];
        for (int i = 1; i < points.length; i++) {
            if (percent <= points[i][0]) {
                float p0 = points[i - 1][0], d0 = points[i - 1][1];
                float p1 = points[i][0], d1 = points[i][1];
                if (p1 == p0) return d1;
                float t = (percent - p0) / (p1 - p0);
                return d0 + (d1 - d0) * t;
            }
        }
        return points[points.length - 1][1];
    }

    private static int[][] resolvePoints(Device device) {
        if (loaded && parsed != null && parsed[device.ordinal()] != null) {
            return parsed[device.ordinal()];
        }
        return AOSP_POINTS[device.ordinal()];
    }

    private static Source sourceOf(Device device) {
        if (loaded && parsedSource != null) return parsedSource[device.ordinal()];
        return Source.AOSP;
    }

    // --- 文件读取 / XML 解析 ---

    private static String read(String path) {
        try {
            ShellResult r = Shell.su("cat " + path);
            if (r != null && r.isSuccess()) return r.output;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 解析 default_volume_tables.xml 里的 {@code <reference name>} 点表。 */
    private static void parseReferences(String xml, Map<String, int[][]> out) {
        Matcher m = Pattern.compile(
                "<reference[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</reference>",
                Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            out.put(m.group(1), parsePoints(m.group(2)));
        }
    }

    /** 引擎表：定位 {@code <volumeGroup><name>music</name>}，抽取其各 deviceCategory 点表。 */
    private static void parseEngineMusic(String xml, Map<String, int[][]> refMap,
            Map<String, int[][]> out) {
        Matcher g = Pattern.compile("<volumeGroup>(.*?)</volumeGroup>",
                Pattern.DOTALL).matcher(xml);
        while (g.find()) {
            String block = g.group(1);
            Matcher nm = Pattern.compile("<name>\\s*([^<]+?)\\s*</name>").matcher(block);
            if (!nm.find() || !"music".equals(nm.group(1).trim())) continue;
            Matcher im = Pattern.compile("<indexMax>\\s*(\\d+)\\s*</indexMax>").matcher(block);
            if (im.find()) {
                try {
                    musicIndexMax = Integer.parseInt(im.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            extractVolumes(block, refMap, out);
            return;
        }
    }

    /** 策略表：定位 {@code <profile ... group="MUSIC" ...>}，抽取各 deviceCategory 点表。 */
    private static void parseMusicVolumes(String xml, Map<String, int[][]> refMap,
            Map<String, int[][]> out) {
        Matcher p = Pattern.compile(
                "<profile[^>]*group=\"MUSIC\"[^>]*>(.*?)</profile>",
                Pattern.DOTALL).matcher(xml);
        while (p.find()) {
            extractVolumes(p.group(1), refMap, out);
        }
    }

    /** 从一段 XML 抽取所有 {@code <volume deviceCategory>} 点表（自闭合则跟随 ref）。 */
    private static void extractVolumes(String section, Map<String, int[][]> refMap,
            Map<String, int[][]> out) {
        Matcher m = Pattern.compile(
                "<volume\\s+deviceCategory=\"([^\"]+)\"([^>]*?)(/>|>(.*?)</volume>)",
                Pattern.DOTALL).matcher(section);
        while (m.find()) {
            String cat = m.group(1);
            String attrs = m.group(2) + m.group(3);
            String body = m.group(4);
            Matcher rm = Pattern.compile("ref=\"([^\"]+)\"").matcher(attrs);
            if (rm.find()) {
                int[][] ref = refMap.get(rm.group(1));
                if (ref != null && ref.length > 0) out.put(cat, ref);
            } else if (body != null) {
                int[][] pts = parsePoints(body);
                if (pts.length > 0) out.put(cat, pts);
            }
        }
    }

    /** 从含若干 {@code <point>百分比,毫贝</point>} 的片段解析为 int[行][2]（按 x 升序）。 */
    private static int[][] parsePoints(String xml) {
        List<int[]> pts = new ArrayList<>();
        Matcher m = Pattern.compile("<point>\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*</point>").matcher(xml);
        while (m.find()) {
            try {
                pts.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
            } catch (NumberFormatException ignored) {
            }
        }
        pts.sort((a, b) -> Integer.compare(a[0], b[0]));
        return pts.toArray(new int[0][]);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
