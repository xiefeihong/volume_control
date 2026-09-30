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
    public static final double MIN_VOLUME_FLOOR_RATIO = 0.15;

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
     * 正向曲线：step → [lo,hi] 区间内的音量值。三曲线共用 logRange 的 lo/hi（min=0 时含 15% 自适应下限）。
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

    /** 统计曲线映射后「相邻档位数值相同」的档位对数量（不含静音档 0）。 */
    public static int countDuplicatePairs(int maxSteps, int minAbs, int maxAbs, int curveType) {
        int duplicates = 0;
        for (int step = 2; step <= maxSteps; step++) {
            if (curveToAbsoluteVolume(step, maxSteps, minAbs, maxAbs, curveType)
                    == curveToAbsoluteVolume(step - 1, maxSteps, minAbs, maxAbs, curveType)) {
                duplicates++;
            }
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
     */
    public static String buildPreview(int maxSteps, int btMode, int minAbs, int maxAbs,
            int curveType) {
        if (maxSteps <= 0) {
            return "暂无数据";
        }
        if (btMode == Prefs.BT_MODE_SOFTWARE) {
            return buildSoftwarePreview(maxSteps, minAbs, maxAbs, curveType);
        }
        return buildAbsolutePreview(maxSteps, minAbs, maxAbs, curveType);
    }

    /** 模式B：手机端软件衰减。 */
    private static String buildSoftwarePreview(int maxSteps, int minAbs, int maxAbs,
            int curveType) {
        int lowest = curveToAbsoluteVolume(1, maxSteps, minAbs, maxAbs, curveType);
        int maxPercent = (int) Math.round(maxAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME);

        StringBuilder sb = new StringBuilder();
        sb.append("模式B：停用绝对音量（最大音量 ").append(maxAbs).append("）\n");
        sb.append("✓ 档位经与模式A 相同的").append(curveLabel(curveType))
                .append("曲线映射为 AVRCP，再换算回系统音量\n");
        sb.append("✓ 从根本上避免「相邻档位听感相同」与「低档位无声」\n");
        if (maxAbs < Prefs.AVRCP_MAX_VOLUME) {
            sb.append("最大音量：").append(maxAbs).append("（约 ").append(maxPercent)
                    .append("%，滑块上限）\n");
            sb.append("第 1 档 → 音量 ").append(lowest).append("/127\n");
        } else {
            sb.append("最大音量 127：不限制，滑块可达 100%\n");
        }
        sb.append("· 耳机音量请用耳机自身的音量键调整\n");
        sb.append("· 耳机端音量同步显示会失效（正常现象）\n");
        sb.append("提示：调整后点「重启蓝牙」即可生效（设置会自动保存）。");
        return sb.toString();
    }

    /** 模式A：所选曲线分布 + 音量范围。 */
    private static String buildAbsolutePreview(int maxSteps, int minAbs, int maxAbs,
            int curveType) {
        int[] range = normalizedRange(minAbs, maxAbs);
        StringBuilder sb = new StringBuilder();

        int duplicates = countDuplicatePairs(maxSteps, range[0], range[1], curveType);
        int lowest = curveToAbsoluteVolume(1, maxSteps, range[0], range[1], curveType);
        int lowestPercent = (int) Math.round(lowest * 100.0 / Prefs.AVRCP_MAX_VOLUME);
        int spacing = maxSteps >= 2
                ? curveToAbsoluteVolume(2, maxSteps, range[0], range[1], curveType) - lowest : 0;

        sb.append("模式A：保持绝对音量 · ").append(curveLabel(curveType)).append("曲线\n");
        sb.append("媒体 ").append(maxSteps).append(" 档 → 蓝牙 AVRCP（0~127），范围 ")
                .append(range[0]).append('~').append(range[1]).append("\n");
        if (duplicates == 0) {
            sb.append("✓ 数值无重复：各档 AVRCP 值互不相同\n");
        } else {
            sb.append("⚠ 有 ").append(duplicates)
                    .append(" 对相邻档位映射到相同的 AVRCP 值（档位偏多）\n");
        }
        sb.append("第 1 档 → AVRCP ").append(lowest)
                .append("（约 ").append(lowestPercent).append("%）");
        if (lowest <= 5) {
            sb.append("  ⚠ 低于部分耳机可闻下限，低音量可能无声\n");
        } else {
            sb.append("  ✓ 已高于常见可闻下限\n");
        }
        if (maxSteps >= 2) {
            sb.append("低音量区间距：第 1→2 档相差 ").append(spacing);
            if (spacing < 8) {
                sb.append("  ⚠ 偏小，耳机粒度粗时可能听感相同，建议减少档位数或改用模式B\n");
            } else {
                sb.append("  ✓ 低音量区不易重复\n");
            }
        }
        sb.append("最高档（第 ").append(maxSteps).append(" 档）→ AVRCP ")
                .append(range[1]).append("\n");
        sb.append("提示：耳机内部有效档位较少时（常见 8~19），手机档位多于它仍会重复；模式B 可彻底解决");
        return sb.toString();
    }

    /**
     * 生成「档位 → 音量」逐档映射表（等宽文本，显示在音量范围卡片内，便于调整时就地查看结果）。
     * 模式A 显示 AVRCP 绝对音量（0~127）；模式B 显示软件衰减音量（0~127，取本模式范围/曲线，值域落在 min~max 之间）。
     */
    public static String buildMappingTable(int maxSteps, int btMode, int minAbs, int maxAbs,
            int curveType) {
        if (maxSteps <= 0) {
            return "";
        }
        boolean software = (btMode == Prefs.BT_MODE_SOFTWARE);
        String title = software
                ? "档位 → 软件衰减音量（0~127）：\n"
                : "档位 → AVRCP 音量（0~127）：\n";
        return renderMappingTable(title, maxSteps, minAbs, maxAbs, curveType);
    }

    /** 耳机模式（有线+外放）逐档映射表：显示 0~127、落在 minW~maxW 之间。 */
    public static String buildWiredMappingTable(int maxSteps, int minAbs, int maxAbs,
            int curveType) {
        if (maxSteps <= 0) {
            return "";
        }
        return renderMappingTable("耳机模式 · 档位 → 音量（0~127）：\n",
                maxSteps, minAbs, maxAbs, curveType);
    }

    /** 按所选曲线渲染「档位 → 音量(0~127)」等宽行，6 列/行。 */
    private static String renderMappingTable(String title, int maxSteps, int minAbs, int maxAbs,
            int curveType) {
        StringBuilder sb = new StringBuilder();
        sb.append(title);
        int stepWidth = String.valueOf(maxSteps).length();
        int valWidth = String.valueOf(Prefs.AVRCP_MAX_VOLUME).length();
        String entryFmt = "%" + stepWidth + "d→%" + valWidth + "d";
        int perLine = 6;
        StringBuilder line = new StringBuilder();
        for (int step = 0; step <= maxSteps; step++) {
            int value = curveToAbsoluteVolume(step, maxSteps, minAbs, maxAbs, curveType);
            int pos = step % perLine;
            if (pos == 0) {
                line.setLength(0);
            }
            line.append(String.format(entryFmt, step, value));
            if (pos == perLine - 1 || step == maxSteps) {
                sb.append(line).append('\n');
            } else {
                line.append("  ");
            }
        }
        return sb.toString();
    }
}
