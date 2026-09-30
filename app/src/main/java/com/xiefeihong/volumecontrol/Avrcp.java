package com.xiefeihong.volumecontrol;

/**
 * 蓝牙 AVRCP 绝对音量映射计算与预览文本。
 *
 * <p>AOSP 蓝牙模块（packages/modules/Bluetooth，com.android.bluetooth.avrcp.AvrcpVolumeManager）
 * 的默认换算为线性公式：{@code absVolume = round(档位 * 127 / 系统最大档位)}。该公式第 1 档
 * AVRCP 值过低（部分耳机无声），且低音量区相邻档位差值小，易被耳机内部档位量化到同一格。</p>
 *
 * <p>因此模式A 改用以 [minAbs, maxAbs] 为端点的对数（等比倍增）曲线：</p>
 *
 * <pre>absVolume = lo * (maxAbs / lo)^((档位 - 1) / (最大档位 - 1))，lo = max(1, minAbs)</pre>
 *
 * <p>保证：档位 1 精确命中 minAbs（minAbs=0 时忽略最小值、lo 取 1），最大档位精确命中
 * maxAbs；每按一次音量键输出按恒定比例增长（符合百分比直觉）。档位 0 恒为静音。
 * 预览与实际发送（{@code BtHooks.SystemToAvrcpHooker}）调用同一函数，天然一致。
 * 模式B 复用同一曲线（{@code curveToAbsoluteVolume}）再换算回系统档位
 * （{@code avrcp / 127 * maxSteps}）。</p>
 */
public final class Avrcp {

    private Avrcp() {
    }

    /** 规范化绝对音量范围：值域 0~127，且 最小值 &lt;= 最大值。 */
    private static int[] normalizedRange(int minAbs, int maxAbs) {
        int lo = Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, Math.min(minAbs, maxAbs)));
        int hi = Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, Math.max(minAbs, maxAbs)));
        return new int[]{lo, hi};
    }

    /**
     * 对数曲线使用的区间：{@code {lo, hi}}，其中 {@code lo = max(1, 归一下限)}。
     * 对数曲线无法从 0 起，故 minAbs=0 时忽略该最小值，lo 回退为几何下限 1（仅锚定 maxAbs）。
     */
    private static int[] logRange(int minAbs, int maxAbs) {
        int[] range = normalizedRange(minAbs, maxAbs);
        return new int[]{Math.max(1, range[0]), range[1]};
    }

    /**
     * 模式A 映射：手机档位 → AVRCP 绝对音量（对数等比曲线）。
     *
     * <p>档位 0 保持静音；step=1 精确命中 minAbs（minAbs=0 时取 1），
     * step=maxSteps 精确命中 maxAbs。</p>
     */
    public static int curveToAbsoluteVolume(int step, int maxSteps, int minAbs, int maxAbs) {
        if (maxSteps <= 0 || step <= 0) {
            return 0;
        }
        int[] range = logRange(minAbs, maxAbs);
        int lo = range[0];
        int hi = range[1];
        // 退化（maxSteps<=1 或 min>=max）：直接输出上限
        if (maxSteps <= 1 || hi <= lo) {
            return Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, hi));
        }
        double exponent = Math.max(0.0, Math.min(1.0,
                (double) (step - 1) / (maxSteps - 1)));
        double value = lo * Math.pow((double) hi / lo, exponent);
        return (int) Math.max(0, Math.min(Prefs.AVRCP_MAX_VOLUME, Math.round(value)));
    }

    /** 曲线反函数：AVRCP 绝对音量 → 手机档位（耳机音量键回调时保持映射一致）。 */
    public static int curveToSystemStep(int absVolume, int maxSteps, int minAbs, int maxAbs) {
        if (maxSteps <= 0 || absVolume <= 0) {
            return 0;
        }
        int[] range = logRange(minAbs, maxAbs);
        int lo = range[0];
        int hi = range[1];
        if (maxSteps <= 1 || hi <= lo) {
            return maxSteps;
        }
        double ratio = Math.log((double) absVolume / lo) / Math.log((double) hi / lo);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int step = (int) Math.round(1 + (maxSteps - 1) * ratio);
        return Math.max(0, Math.min(maxSteps, step));
    }

    /**
     * 模式B 映射：与模式A 相同算法——手机档位经 {@link #curveToAbsoluteVolume}
     * 映射为 AVRCP 值，再换算回系统音量档位（{@code avrcp / 127 * maxSteps}）。
     *
     * <p>双模式曲线统一；maxAbs&lt;127 时最高系统档位被压缩（滑块上限受限），
     * 为软件衰减固有行为。档位 0 恒为静音。</p>
     */
    public static int curveToSystemIndex(int step, int maxSteps, int minAbs, int maxAbs) {
        if (maxSteps <= 0 || step <= 0) {
            return 0;
        }
        int avrcp = curveToAbsoluteVolume(step, maxSteps, minAbs, maxAbs);
        int value = (int) Math.round(avrcp / (double) Prefs.AVRCP_MAX_VOLUME * maxSteps);
        return Math.max(0, Math.min(maxSteps, value));
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
     * @param maxSteps  生效后的媒体档位数
     * @param btMode    {@link Prefs#BT_MODE_ABSOLUTE} 或 {@link Prefs#BT_MODE_SOFTWARE}
     * @param minAbs    最小音量（当前模式）
     * @param maxAbs    最大音量（当前模式）
     */
    public static String buildPreview(int maxSteps, int btMode, int minAbs, int maxAbs) {
        if (maxSteps <= 0) {
            return "暂无数据";
        }
        if (btMode == Prefs.BT_MODE_SOFTWARE) {
            return buildSoftwarePreview(maxSteps, minAbs, maxAbs);
        }
        return buildAbsolutePreview(maxSteps, minAbs, maxAbs);
    }

    /** 模式B：手机端软件衰减。 */
    private static String buildSoftwarePreview(int maxSteps, int minAbs, int maxAbs) {
        int lowest = curveToSystemIndex(1, maxSteps, minAbs, maxAbs);
        int maxPercent = (int) Math.round(maxAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME);

        StringBuilder sb = new StringBuilder();
        sb.append("模式B：停用绝对音量（最大音量 ").append(maxAbs).append("）\n");
        sb.append("✓ 档位经与模式A 相同的对数曲线映射为 AVRCP，再换算回系统音量\n");
        sb.append("✓ 从根本上避免「相邻档位听感相同」与「低档位无声」\n");
        if (maxAbs < Prefs.AVRCP_MAX_VOLUME) {
            sb.append("最大音量：").append(maxAbs).append("（约 ").append(maxPercent)
                    .append("%，滑块上限）\n");
            sb.append("第 1 档 → ").append(lowest).append("/").append(maxSteps).append("\n");
        } else {
            sb.append("最大音量 127：不限制，滑块可达 100%\n");
        }
        sb.append("· 耳机音量请用耳机自身的音量键调整\n");
        sb.append("· 耳机端音量同步显示会失效（正常现象）\n");
        sb.append("提示：调整后点「重启蓝牙」即可生效（设置会自动保存）。");
        return sb.toString();
    }

    /** 模式A：对数（等比）曲线 + 音量范围。 */
    private static String buildAbsolutePreview(int maxSteps, int minAbs, int maxAbs) {
        int[] range = normalizedRange(minAbs, maxAbs);
        StringBuilder sb = new StringBuilder();

        int duplicates = countDuplicatePairs(maxSteps, range[0], range[1]);
        int lowest = curveToAbsoluteVolume(1, maxSteps, range[0], range[1]);
        int lowestPercent = (int) Math.round(lowest * 100.0 / Prefs.AVRCP_MAX_VOLUME);
        int spacing = maxSteps >= 2
                ? curveToAbsoluteVolume(2, maxSteps, range[0], range[1]) - lowest : 0;

        sb.append("模式A：保持绝对音量 · 对数曲线（等比）\n");
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
