package com.xiefeihong.volumecontrol;

/**
 * 蓝牙 AVRCP 绝对音量映射计算与预览文本。
 *
 * <p>AOSP 蓝牙模块（packages/modules/Bluetooth，com.android.bluetooth.avrcp.AvrcpVolumeManager）
 * 的默认换算为线性公式：</p>
 *
 * <pre>absVolume = round(档位 * 127 / 系统最大档位)</pre>
 *
 * <p>纯线性映射存在两个问题：</p>
 * <ul>
 *   <li>第 1 档 AVRCP 值过低（如 30 档时仅 4），部分耳机在极低音量下直接无声；</li>
 *   <li>低音量区相邻档位的 AVRCP 差值小（约 127/档数），容易被耳机内部档位量化到
 *       同一格，表现为「5% 和 10% 听感相同」。</li>
 * </ul>
 *
 * <p>因此模式A 改用低音量增强曲线（平方根映射）：</p>
 *
 * <pre>absVolume = 最小值 + (最大值 - 最小值) * (档位 / 最大档位)^0.5</pre>
 *
 * <p>低音量区每档跨度明显增大（避免无声与相邻重复），高档位自然趋近最大值。</p>
 */
public final class Avrcp {

    /** 低音量增强曲线的指数（0.5 = 平方根映射）。 */
    public static final double CURVE_EXPONENT = 0.5;

    private Avrcp() {
    }

    /** AOSP 原生线性换算公式（保持系统行为时使用）。 */
    public static int toAbsoluteVolume(int step, int maxSteps) {
        if (maxSteps <= 0) {
            return 0;
        }
        return (int) Math.round((double) step * Prefs.AVRCP_MAX_VOLUME / maxSteps);
    }

    /** 规范化绝对音量范围：值域 0~127，且 最小值 &lt;= 最大值。 */
    private static int[] normalizedRange(int minAbs, int maxAbs) {
        int lo = Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, Math.min(minAbs, maxAbs)));
        int hi = Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, Math.max(minAbs, maxAbs)));
        return new int[]{lo, hi};
    }

    /**
     * 模式A 映射：手机档位 → AVRCP 绝对音量（低音量增强曲线）。
     *
     * <p>档位 0 保持静音；达到最大档位时等于范围上限。</p>
     */
    public static int curveToAbsoluteVolume(int step, int maxSteps, int minAbs, int maxAbs) {
        if (maxSteps <= 0 || step <= 0) {
            return 0;
        }
        int[] range = normalizedRange(minAbs, maxAbs);
        double ratio = Math.min(1.0, (double) step / maxSteps);
        int value = (int) Math.round(range[0] + (range[1] - range[0])
                * Math.pow(ratio, CURVE_EXPONENT));
        return Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, value));
    }

    /** 曲线反函数：AVRCP 绝对音量 → 手机档位（耳机音量键回调时保持映射一致）。 */
    public static int curveToSystemStep(int absVolume, int maxSteps, int minAbs, int maxAbs) {
        if (maxSteps <= 0) {
            return 0;
        }
        int[] range = normalizedRange(minAbs, maxAbs);
        if (range[1] <= range[0]) {
            return 0;
        }
        double ratio = (absVolume - range[0]) / (double) (range[1] - range[0]);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        return (int) Math.round(maxSteps * Math.pow(ratio, 1.0 / CURVE_EXPONENT));
    }

    /**
     * 模式B 映射：手机档位 → 应用到手机端软件衰减的系统音量档位（低音量增强曲线）。
     *
     * <p>「音量范围」在模式B 下表示手机端软件衰减的百分比范围（127 = 100% 不衰减）：
     * 档位经低音量增强曲线映射为范围百分比，再直接换算为该百分比对应的系统音量
     * 档位（百分比 = 音量条位置语义，低设置值下音量更小，符合直觉）。档位 0 始终
     * 为静音；最高档 = 范围上限百分比对应的系统档位。</p>
     */
    public static int curveToSystemIndex(int step, int maxSteps, int minAbs, int maxAbs) {
        if (maxSteps <= 0 || step <= 0) {
            return 0;
        }
        int[] range = normalizedRange(minAbs, maxAbs);
        double ratio = Math.min(1.0, (double) step / maxSteps);
        double scaled = (range[0] + (range[1] - range[0]) * Math.pow(ratio, CURVE_EXPONENT))
                / Prefs.AVRCP_MAX_VOLUME;
        int value = (int) Math.round(maxSteps * scaled);
        return Math.max(0, Math.min(maxSteps, value));
    }

    /**
     * 模式B 综合映射：曲线映射 + 衰减乘数。
     *
     * <p>先按 maxAbs 做曲线映射得到系统音量档位，再乘衰减乘数百分比。
     * 例如：maxAbs=62, multiplier=50% → 滑块 100% 时实际音量约 24%。</p>
     */
    public static int curveToEffectiveIndex(int step, int maxSteps, int minAbs, int maxAbs,
            int attenMultiplier) {
        int mapped = curveToSystemIndex(step, maxSteps, minAbs, maxAbs);
        if (attenMultiplier != 100 && attenMultiplier >= 0) {
            mapped = (int) Math.round(mapped * attenMultiplier / 100.0);
            mapped = Math.max(0, Math.min(maxSteps, mapped));
        }
        return mapped;
    }

    /** 统计模式A 曲线映射后「相邻档位数值相同」的档位对数量（不含静音档 0）。 */
    public static int countDuplicatePairs(int maxSteps, int minAbs, int maxAbs) {
        int duplicates = 0;
        for (int step = 2; step <= maxSteps; step++) {
            if (curveToAbsoluteVolume(step, maxSteps, minAbs, maxAbs)
                    == curveToAbsoluteVolume(step - 1, maxSteps, minAbs, maxAbs)) {
                duplicates++;
            }
        }
        return duplicates;
    }

    /**
     * 生成蓝牙音量预览文本（供界面直接显示）。
     *
     * @param maxSteps         生效后的媒体档位数
     * @param btMode           {@link Prefs#BT_MODE_ABSOLUTE} 或 {@link Prefs#BT_MODE_SOFTWARE}
     * @param minAbs           最小绝对音量（模式A）
     * @param maxAbs           最大绝对音量（模式A）
     * @param attenMultiplier  模式B 衰减乘数（0~200，100=不衰减）
     */
    public static String buildPreview(int maxSteps, int btMode, int minAbs, int maxAbs,
            int attenMultiplier) {
        if (maxSteps <= 0) {
            return "暂无数据";
        }
        if (btMode == Prefs.BT_MODE_SOFTWARE) {
            return buildSoftwarePreview(maxSteps, minAbs, maxAbs, attenMultiplier);
        }
        return buildAbsolutePreview(maxSteps, minAbs, maxAbs);
    }

    /** 模式B：手机端软件衰减（衰减乘数 0~200）。 */
    private static String buildSoftwarePreview(int maxSteps, int minAbs, int maxAbs,
            int attenMultiplier) {
        // maxAbs 控制 curve 映射范围
        int[] range = normalizedRange(minAbs, maxAbs);
        int lowest = curveToSystemIndex(1, maxSteps, 0, maxAbs);
        int maxPercent = (int) Math.round(maxAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME);

        StringBuilder sb = new StringBuilder();
        sb.append("模式B：停用绝对音量（最大音量 ").append(maxAbs)
                .append(" · 衰减乘数 ").append(attenMultiplier).append("%）\n");
        sb.append("✓ 音量由手机软件曲线平滑控制，不经过耳机内部档位量化\n");
        sb.append("✓ 从根本上避免「相邻档位听感相同」与「低档位无声」\n");
        if (maxAbs < Prefs.AVRCP_MAX_VOLUME) {
            sb.append("最大音量：").append(maxAbs).append("（约 ").append(maxPercent)
                    .append("%，滑块上限）\n");
            sb.append("第 1 档 → ").append(lowest).append("/").append(maxSteps).append("\n");
        } else {
            sb.append("最大音量 127：不限制，滑块可达 100%\n");
        }
        if (attenMultiplier != 100) {
            int effectiveMax = (int) Math.round(maxAbs * attenMultiplier / 100.0);
            int effectivePercent = (int) Math.round(effectiveMax * 100.0 / Prefs.AVRCP_MAX_VOLUME);
            sb.append("衰减乘数：").append(attenMultiplier).append("%");
            if (attenMultiplier < 100) {
                sb.append("（降低音量，有效最大音量 ").append(effectiveMax)
                        .append(" ≈ ").append(effectivePercent).append("%）\n");
            } else {
                sb.append("（放大音量，有效最大音量 ").append(effectiveMax)
                        .append(" ≈ ").append(effectivePercent).append("%）\n");
            }
        }
        sb.append("· 耳机音量请用耳机自身的音量键调整\n");
        sb.append("· 耳机端音量同步显示会失效（正常现象）\n");
        sb.append("提示：调整后点「重启蓝牙」即可生效（设置会自动保存）。");
        return sb.toString();
    }

    /** 模式A：低音量增强曲线 + 音量范围。 */
    private static String buildAbsolutePreview(int maxSteps, int minAbs, int maxAbs) {
        int[] range = normalizedRange(minAbs, maxAbs);
        StringBuilder sb = new StringBuilder();

        int duplicates = countDuplicatePairs(maxSteps, range[0], range[1]);
        int lowest = curveToAbsoluteVolume(1, maxSteps, range[0], range[1]);
        int lowestPercent = (int) Math.round(lowest * 100.0 / Prefs.AVRCP_MAX_VOLUME);
        int spacing = maxSteps >= 2
                ? curveToAbsoluteVolume(2, maxSteps, range[0], range[1]) - lowest : 0;

        sb.append("模式A：保持绝对音量 · 低音量增强曲线（√）\n");
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
        sb.append("提示：耳机内部有效档位较少时（常见 8~19），手机档位多于它仍会重复；模式B 可彻底解决\n\n");

        sb.append("档位 → AVRCP 音量：\n");
        // 计算每个条目所需宽度（最大档位数 + "→" + 最大 AVRCP 值）
        int stepWidth = String.valueOf(maxSteps).length();
        int avrcpWidth = String.valueOf(Prefs.AVRCP_MAX_VOLUME).length();
        String entryFmt = "%" + stepWidth + "d→%-" + avrcpWidth + "d";
        int perLine = 6;
        StringBuilder line = new StringBuilder();
        for (int step = 0; step <= maxSteps; step++) {
            int pos = step % perLine;
            if (pos == 0) {
                line.setLength(0);
            }
            line.append(String.format(entryFmt, step,
                    curveToAbsoluteVolume(step, maxSteps, range[0], range[1])));
            if (pos == perLine - 1 || step == maxSteps) {
                sb.append(line).append('\n');
            } else {
                line.append("  ");
            }
        }
        return sb.toString();
    }
}
