package com.xiefeihong.volumecontrol;

/**
 * 蓝牙 AVRCP 绝对音量映射计算与预览文本。
 *
 * <p>AOSP 蓝牙模块（packages/modules/Bluetooth，com.android.bluetooth.avrcp.AvrcpVolumeManager）
 * 的默认换算为线性公式：{@code absVolume = round(档位 * 127 / 系统最大档位)}。该公式第 1 档
 * AVRCP 值过低（部分耳机无声），且低音量区相邻档位差值小，易被耳机内部档位量化到同一格。</p>
 *
 * <p>因此模式A 改用端点为 [minAbs, maxAbs] 的可选曲线（由 curveType 选择，
 * 预览与实际发送共用同一函数）：设 {@code e=(档位-1)/(最大档位-1)}，{@code lo = minAbs}（minAbs=0 时取 0.15*maxAbs）：</p>
 *
 * <pre>对数（默认）：absVolume = lo * (maxAbs / lo)^e
 * 线性：absVolume = lo + (maxAbs - lo) * e
 * 平方根：absVolume = lo + (maxAbs - lo) * sqrt(e)</pre>
 *
 * <p>三者均保证：档位 1 精确命中 minAbs（minAbs=0 时按 {@link #MIN_VOLUME_FLOOR_RATIO}
 * 取自适应可闻下限，避免第 1 档接近无声）；最大档位精确命中 maxAbs；档位 0 恒为静音。
 * 预览与实际发送（{@code BtHooks.SystemToAvrcpHooker}）调用同一函数，天然一致。
 * 模式B 复用同一曲线（{@code curveToAbsoluteVolume}）再换算回系统档位
 * （{@code avrcp / 127 * maxSteps}）。</p>
 */
public final class Avrcp {

    /**
     * 最小音量下限比例：当用户将「最小音量」设为 0 时，第 1 档自动取 {@code maxAbs} 的此比例
     * 作为可闻下限（自适应耳机量程），而非退化为接近无声的 1。可据实机听感微调此常量。
     */
    public static final double MIN_VOLUME_FLOOR_RATIO = 0.06;

    private Avrcp() {
    }

    /** 规范化绝对音量范围：值域 0~127，且 最小值 &lt;= 最大值。 */
    private static int[] normalizedRange(int minAbs, int maxAbs) {
        int lo = Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, Math.min(minAbs, maxAbs)));
        int hi = Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, Math.max(minAbs, maxAbs)));
        return new int[]{lo, hi};
    }

    /**
     * 对数曲线使用的区间：{@code {lo, hi}}，{@code hi = 归一上限}。
     * 下限 {@code lo}：minAbs≥1 时精确=minAbs；minAbs=0 时按 {@link #MIN_VOLUME_FLOOR_RATIO}
     * 取 hi 的比例作为自适应可闻下限（避免第 1 档接近无声），并保证 lo≤hi、lo≥1。
     */
    private static int[] logRange(int minAbs, int maxAbs) {
        int[] range = normalizedRange(minAbs, maxAbs);
        int hi = range[1];
        int lo = range[0] > 0
                ? range[0]
                : Math.max(1, (int) Math.round(MIN_VOLUME_FLOOR_RATIO * hi));
        return new int[]{Math.min(lo, hi), hi};
    }

    /** 曲线类型名称（预览与日志用）。 */
    static String curveLabel(int curveType) {
        switch (curveType) {
            case Prefs.CURVE_LINEAR: return "线性";
            case Prefs.CURVE_SQRT:   return "平方根";
            default:                 return "对数";
        }
    }

    /**
     * 正向曲线：step → [lo,hi] 区间内的音量值。三曲线共用 logRange 的 lo/hi（min=0 时含 10% 自适应下限）。
     * {@code e=(step-1)/(maxSteps-1)}：线性 {@code lo+(hi-lo)·e}；平方根 {@code lo+(hi-lo)·√e}；对数 {@code lo·(hi/lo)^e}。
     */
    private static int applyCurve(int step, int maxSteps, int lo, int hi, int curveType) {
        // 退化（maxSteps<=1 或 min>=max）：直接输出上限
        if (maxSteps <= 1 || hi <= lo) {
            return Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, hi));
        }
        double e = Math.max(0.0, Math.min(1.0, (double) (step - 1) / (maxSteps - 1)));
        double value;
        if (curveType == Prefs.CURVE_LINEAR) {
            value = lo + (hi - lo) * e;
        } else if (curveType == Prefs.CURVE_SQRT) {
            value = lo + (hi - lo) * Math.sqrt(e);
        } else { // CURVE_LOG
            value = lo * Math.pow((double) hi / lo, e);
        }
        return (int) Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, Math.round(value)));
    }

    /** 反向曲线：音量值 → step（{@link #applyCurve} 的精确反函数）。 */
    private static int invertCurve(int absVolume, int maxSteps, int lo, int hi, int curveType) {
        if (maxSteps <= 1 || hi <= lo) {
            return maxSteps;
        }
        double e;
        if (curveType == Prefs.CURVE_LINEAR) {
            e = (absVolume - lo) / (double) (hi - lo);
        } else if (curveType == Prefs.CURVE_SQRT) {
            double t = (absVolume - lo) / (double) (hi - lo);
            e = t * t;
        } else { // CURVE_LOG
            e = Math.log((double) absVolume / lo) / Math.log((double) hi / lo);
        }
        e = Math.max(0.0, Math.min(1.0, e));
        int step = (int) Math.round(1 + (maxSteps - 1) * e);
        return Math.max(0, Math.min(maxSteps, step));
    }

    /**
     * 模式A 映射：手机档位 → AVRCP 绝对音量（按 curveType 选择分布）。
     *
     * <p>档位 0 保持静音；step=1 精确命中 minAbs（minAbs=0 时取自适应下限），
     * step=maxSteps 精确命中 maxAbs。</p>
     */
    public static int curveToAbsoluteVolume(int step, int maxSteps, int minAbs, int maxAbs,
            int curveType) {
        if (maxSteps <= 0 || step <= 0) {
            return 0;
        }
        int[] range = logRange(minAbs, maxAbs);
        return applyCurve(step, maxSteps, range[0], range[1], curveType);
    }

    /** 曲线反函数：AVRCP 绝对音量 → 手机档位（耳机音量键回调时保持映射一致）。 */
    public static int curveToSystemStep(int absVolume, int maxSteps, int minAbs, int maxAbs,
            int curveType) {
        if (maxSteps <= 0 || absVolume <= 0) {
            return 0;
        }
        int[] range = logRange(minAbs, maxAbs);
        return invertCurve(absVolume, maxSteps, range[0], range[1], curveType);
    }

    /**
     * 模式B 映射：与模式A 相同曲线——手机档位经 {@link #curveToAbsoluteVolume}
     * 映射为 AVRCP 值，再换算回系统音量档位（{@code avrcp / 127 * maxSteps}）。
     *
     * <p>双模式曲线统一；maxAbs&lt;127 时最高系统档位被压缩（滑块上限受限），
     * 为软件衰减固有行为。档位 0 恒为静音。</p>
     *
     * <p><b>精度实测结论（诊断日志 181a202 得出）：</b>本端下发给 audioserver 的
     * {@code AudioSystem#setStreamVolumeIndex} 的 index 恒为粗档位（与返回的
     * {@code mapped} 一致），从不出现 ×10——{@code mIndexMap} 里的 ×10 只是
     * system_server 内部记账。故真实可分辨级数 == maxSteps（与模式A 不同：模式A
     * 的 0~127 是交给耳机渲染的 AVRCP 绝对音量）。因此 {@code maxAbs<127} 时不同
     * UI 档塞入 {@code round(maxAbs/127*maxSteps)} 个落点，相邻重复为鸽笼原理
     * 必然，算法只能重新分布、无法消除；唯一提升精度的是提高 {@code maxSteps}。</p>
     */
    public static int curveToSystemIndex(int step, int maxSteps, int minAbs, int maxAbs,
            int curveType) {
        if (maxSteps <= 0 || step <= 0) {
            return 0;
        }
        int avrcp = curveToAbsoluteVolume(step, maxSteps, minAbs, maxAbs, curveType);
        int value = (int) Math.round(avrcp / (double) Prefs.AVRCP_MAX_VOLUME * maxSteps);
        return Math.max(0, Math.min(maxSteps, value));
    }

    /**
     * 以音量键步进为主，统计「相邻两次按键落到相同输出值」的次数：第 {@code i} 次按键
     * 到达档位 {@code keyStepLevel(i, maxSteps, keySteps)}（吸附到 {@code round(i*maxSteps/keySteps)} 网格），
     * 逐比较相邻按键的输出值。{@code useSystemIndex=true} 取系统档位（模式B/耳机），
     * {@code false} 取 AVRCP（模式A）。因按键序列只是全部档位的一个子集，重复对数明显少于
     * “逐档统计”。
     */
    public static int countDuplicateAtKeySteps(int maxSteps, int keySteps, int minAbs, int maxAbs,
            int curveType, boolean useSystemIndex) {
        if (maxSteps <= 0) {
            return 0;
        }
        int segs = Prefs.clampKeySteps(keySteps);
        int duplicates = 0;
        int prevValue = -1;
        for (int press = 1; press <= segs; press++) {
            int level = Prefs.keyStepLevel(press, maxSteps, keySteps);
            int value = useSystemIndex
                    ? curveToSystemIndex(level, maxSteps, minAbs, maxAbs, curveType)
                    : curveToAbsoluteVolume(level, maxSteps, minAbs, maxAbs, curveType);
            if (press > 1 && value == prevValue) {
                duplicates++;
            }
            prevValue = value;
        }
        return duplicates;
    }

    /**
     * 生成蓝牙音量预览文本（供界面直接显示）。
     *
     * @param maxSteps  生效后的媒体档位数
     * @param btMode    {@link Prefs#BT_MODE_ABSOLUTE} 或 {@link Prefs#BT_MODE_SOFTWARE}
     * @param minAbs    最小音量（当前模式）
     * @param maxAbs    最大音量（当前模式）
     * @param curveType 曲线类型（{@link Prefs#CURVE_LOG}/{@link Prefs#CURVE_LINEAR}/{@link Prefs#CURVE_SQRT}）
     * @param keySteps  音量键步进（按键段数）：判重改以按键序列为准
     */
    public static String buildPreview(int maxSteps, int btMode, int minAbs, int maxAbs,
            int curveType, int keySteps) {
        if (maxSteps <= 0) {
            return "暂无数据";
        }
        if (btMode == Prefs.BT_MODE_SOFTWARE) {
            return buildSoftwarePreview(maxSteps, minAbs, maxAbs, curveType, keySteps);
        }
        return buildAbsolutePreview(maxSteps, minAbs, maxAbs, curveType, keySteps);
    }

    /** 模式B：手机端软件衰减。 */
    private static String buildSoftwarePreview(int maxSteps, int minAbs, int maxAbs,
            int curveType, int keySteps) {
        int lowest = curveToAbsoluteVolume(1, maxSteps, minAbs, maxAbs, curveType);
        int maxPercent = (int) Math.round(maxAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME);

        StringBuilder sb = new StringBuilder();
        sb.append("相对音量模式：停用绝对音量 · ").append(curveLabel(curveType)).append("曲线\n");
        sb.append("档位映射为 AVRCP，再换算回系统音量，范围 ")
                .append(lowest).append('~').append(maxAbs).append("\n");

        int dups = countDuplicateAtKeySteps(maxSteps, keySteps, minAbs, maxAbs, curveType, true);
        if (dups == 0) {
            sb.append("✓ 各次按键的系统音量档位互不相同\n");
        } else {
            sb.append("⚠ 有 ").append(dups)
                    .append(" 次相邻按键落到相同系统档位（可增大按键段数使每次跳更小）\n");
        }
        sb.append("第 1 档 → 音量 ").append(lowest).append("/127\n");
        if (maxAbs < Prefs.AVRCP_MAX_VOLUME) {
            sb.append("最大音量：").append(maxAbs).append("（约 ").append(maxPercent)
                    .append("%，滑块上限）\n");
        } else {
            sb.append("最大音量 127：不限制，滑块可达 100%\n");
        }
        sb.append("· 耳机音量请用耳机自身的音量键调整\n");
        sb.append("· 耳机端音量同步显示会失效（正常现象）\n");
        sb.append("提示：调整后点「保存修改」重启蓝牙即可生效。");
        return sb.toString();
    }

    /** 模式A：所选曲线分布 + 音量范围。 */
    private static String buildAbsolutePreview(int maxSteps, int minAbs, int maxAbs,
            int curveType, int keySteps) {
        int[] range = normalizedRange(minAbs, maxAbs);
        StringBuilder sb = new StringBuilder();

        int duplicates = countDuplicateAtKeySteps(maxSteps, keySteps, range[0], range[1],
                curveType, false);
        int lowest = curveToAbsoluteVolume(1, maxSteps, range[0], range[1], curveType);
        int lowestPercent = (int) Math.round(lowest * 100.0 / Prefs.AVRCP_MAX_VOLUME);
        int spacing = maxSteps >= 2
                ? curveToAbsoluteVolume(2, maxSteps, range[0], range[1], curveType) - lowest : 0;

        sb.append("绝对音量模式：保持绝对音量 · ").append(curveLabel(curveType)).append("曲线\n");
        sb.append("媒体 ").append(maxSteps).append(" 档 → 蓝牙 AVRCP（0~127），范围 ")
                .append(range[0]).append('~').append(range[1]).append("\n");
        if (duplicates == 0) {
            sb.append("✓ 各次按键的 AVRCP 值互不相同\n");
        } else {
            sb.append("⚠ 有 ").append(duplicates)
                    .append(" 次相邻按键落到相同 AVRCP 值（可增大按键段数使每次跳更小）\n");
        }
        sb.append("第 1 档 → AVRCP ").append(lowest)
                .append("（约 ").append(lowestPercent).append("%）\n");
        if (lowest <= 5) {
            sb.append("⚠ 低于部分耳机可闻下限，低音量可能无声\n");
        } else {
            sb.append("✓ 已高于常见可闻下限\n");
        }
        if (maxSteps >= 2) {
            sb.append("低音量区间距：第 1→2 档相差 ").append(spacing).append("\n");
            if (spacing < 8) {
                sb.append("⚠ 偏小，耳机粒度粗时可能听感相同，建议减少档位数或改用模式B\n");
            } else {
                sb.append("✓ 低音量区不易重复\n");
            }
        }
        sb.append("最高档（第 ").append(maxSteps).append(" 档）→ AVRCP ")
                .append(range[1]).append("\n");
        sb.append("提示：耳机内部有效档位较少时（常见 8~19），手机档位多于它仍会重复；模式B 可彻底解决");
        return sb.toString();
    }

    /**
     * 生成「按键次数 → 音量」映射表（等宽对齐、自然换行）：左列为音量键按下第几次（1~按键段数），
     * 右列为该次按键到达档位 {@code keyStepLevel(i, ...)}（吸附到 round(i*maxSteps/keySteps)）对应的输出值。
     * 三种模式共用此方法：{@code useSystemIndex=true}（模式B/耳机，system_server 软件衰减）右列为
     * 系统实际档位（0~maxSteps）；{@code false}（模式A，蓝牙 AVRCP）右列为 0~127 绝对音量。
     */
    public static String buildMappingTable(int maxSteps, boolean useSystemIndex, int minAbs,
            int maxAbs, int curveType, int keySteps, int perLine) {
        if (maxSteps <= 0) {
            return "";
        }
        int segs = Prefs.clampKeySteps(keySteps);
        String column = useSystemIndex
                ? "系统音量档位（0~" + maxSteps + "）" : "AVRCP 音量（0~127）";
        return renderMappingTable("按键次数 → " + column + "，共 " + segs + " 次：\n",
                maxSteps, minAbs, maxAbs, curveType, useSystemIndex, keySteps, perLine);
    }

    /**
     * 默认（系统原生）曲线映射表，横轴=档位：左列音量键按下第几次（1~按键段数），
     * 右列该次按键到达档位 {@code keyStepLevel} 的默认增益。{@code showDb} true 取硬件增益 dB，
     * false 取按 dB 归一的相对增益 0~100%。
     *
     * @param steps    当前档位数（横轴 1~steps，与模式A/B 一致取当前档位）
     * @param gainPct  长度 steps+1 的归一化增益（按档位索引）
     * @param gainDb   长度 steps+1 的硬件增益 dB（可为 null）
     * @param showDb   true＝右列取 dB；false＝右列取百分比
     * @param keySteps 音量键步进（按键段数）
     * @param perLine  每行条目数（&lt;=0 交回自然换行）
     */
    public static String buildNativeStepsTable(int steps, float[] gainPct, float[] gainDb,
            boolean showDb, int keySteps, int perLine) {
        if (steps <= 0 || gainPct == null || gainPct.length < steps + 1) {
            return "";
        }
        int segs = Prefs.clampKeySteps(keySteps);
        int pressWidth = Math.max(2, String.valueOf(segs).length());
        StringBuilder sb = new StringBuilder();
        sb.append(showDb
                ? "按键次数 → 增益（dB，硬件阶梯），共 " + segs + " 次：\n"
                : "按键次数 → 相对增益（按dB归一 0~100%），共 " + segs + " 次：\n");
        String cellFmt = "%" + pressWidth + "d→" + (showDb ? "%6.1f  " : "%3d%%  ");
        for (int press = 1; press <= segs; press++) {
            int level = Prefs.keyStepLevel(press, steps, keySteps);
            if (showDb) {
                float db = (gainDb != null && level < gainDb.length) ? gainDb[level] : 0f;
                sb.append(String.format(java.util.Locale.US, cellFmt, press, db));
            } else {
                int out = Math.round(Math.max(0f, Math.min(100f, gainPct[level])));
                sb.append(String.format(cellFmt, press, out));
            }
            if (press < segs && perLine > 0 && press % perLine == 0) {
                sb.append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 默认（系统原生）曲线映射表，横轴=百分比：左列 XML 点表的档位百分比（{@code anchorPct}），
     * 右列该锚点增益（{@code showDb} 取 dB，否则取 0~100% 相对增益）。
     */
    public static String buildNativePercentTable(int[] anchorPct, float[] gainPct, float[] gainDb,
            boolean showDb, int perLine) {
        if (anchorPct == null || anchorPct.length == 0) {
            return "";
        }
        int pctWidth = Math.max(2, String.valueOf(anchorPct[anchorPct.length - 1]).length());
        StringBuilder sb = new StringBuilder();
        sb.append(showDb
                ? "档位百分比 → 增益（dB，硬件阶梯），共 " + anchorPct.length + " 点：\n"
                : "档位百分比 → 相对增益（按dB归一 0~100%），共 " + anchorPct.length + " 点：\n");
        String cellFmt = "%" + pctWidth + "d%%→" + (showDb ? "%6.1f  " : "%3d%%  ");
        for (int i = 0; i < anchorPct.length; i++) {
            if (showDb) {
                float db = (gainDb != null && i < gainDb.length) ? gainDb[i] : 0f;
                sb.append(String.format(java.util.Locale.US, cellFmt, anchorPct[i], db));
            } else {
                int out = (gainPct != null && i < gainPct.length)
                        ? Math.round(Math.max(0f, Math.min(100f, gainPct[i]))) : 0;
                sb.append(String.format(cellFmt, anchorPct[i], out));
            }
            if (i < anchorPct.length - 1 && perLine > 0 && (i + 1) % perLine == 0) {
                sb.append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 按所选曲线渲染「按键次数 → 输出值」行：左列 = 第几次按键（1~clampKeySteps(keySteps)），
     * 右列 = 该次到达档位 {@code keyStepLevel(i, ...)}（吸附到 round(i*maxSteps/keySteps)）的输出值。
     * 每个条目格式化为统一宽度（右对齐补空格）、条目间以两空格分隔；等宽字体下 TextView
     * 可在空格处自然换行并保持各列对齐。useSystemIndex 时取系统档位（0~maxSteps），否则取 0~127。
     */
    private static String renderMappingTable(String title, int maxSteps, int minAbs, int maxAbs,
            int curveType, boolean useSystemIndex, int keySteps, int perLine) {
        StringBuilder sb = new StringBuilder(title);
        int segs = Prefs.clampKeySteps(keySteps);
        int valueMax = useSystemIndex ? maxSteps : Prefs.AVRCP_MAX_VOLUME;
        int pressWidth = Math.max(2, String.valueOf(segs).length());
        int valWidth = Math.max(2, String.valueOf(valueMax).length());
        String cellFmt = "%" + pressWidth + "d→%" + valWidth + "d";
        for (int press = 1; press <= segs; press++) {
            int level = Prefs.keyStepLevel(press, maxSteps, keySteps);
            int value = useSystemIndex
                    ? curveToSystemIndex(level, maxSteps, minAbs, maxAbs, curveType)
                    : curveToAbsoluteVolume(level, maxSteps, minAbs, maxAbs, curveType);
            sb.append(String.format(cellFmt, press, value));
            if (press < segs) {
                // perLine>0 时每满一行换行（等宽单元→列对齐）；perLine<=0 则交给 TextView 自然换行。
                if (perLine > 0 && press % perLine == 0) {
                    sb.append('\n');
                } else {
                    sb.append("  ");
                }
            }
        }
        sb.append('\n');
        return sb.toString();
    }
}
