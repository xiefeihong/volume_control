package com.xiefeihong.volumecontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * 音量映射曲线图（App 侧可视化，无 Hook 依赖）。
 *
 * <p>横轴＝音量级数档位（0~maxSteps），纵轴＝该档位经所选曲线映射后的输出值：
 * 模式A 为 AVRCP 绝对音量（0~127），模式B/耳机模式为换算后的系统音量档位（0~maxSteps）。
 * 曲线用 {@link Avrcp} 的同一映射函数绘制，与预览文本、实际下发完全一致；叠加的圆点是
 * 音量键每次按键（网格吸附 {@link Prefs#keyStepLevel}）的落点，直观呈现「按键段数」把连续
 * 级数量化成 100/keySteps 整数倍的停点。</p>
 *
 * <p>「默认（系统原生）」模式则按 {@link NativeVolumeCurve} 的真实增益曲线渲染，横轴可取
 * 档位或百分比、纵轴可取 dB 或归一增益。两条数据路径（A/B 与默认）在 {@link #configure} /
 * {@link #configureNativeCurve} 各自打包成统一的 {@link PlotModel}，{@link #onDraw} 只认
 * PlotModel 单一渲染，消除历史 {@code drawNative}/{@code onDraw} 两套重复绘制。</p>
 */
public final class CurveChartView extends View {

    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();

    private final float density;

    /**
     * 统一渲染模型：{@link #configure}/{@link #configureNativeCurve} 各自把数据打包成一份，
     * {@link #onDraw} 仅依赖它绘制。字段语义与两条历史数据路径逐一对齐，保证视觉输出不变。
     */
    private static final class PlotModel {
        /** 横轴左端（含），常规为 0；默认·百分比模式可为窗口起点。 */
        int xMin;
        /** 横轴右端（含），恒 &gt; {@link #xMin}。 */
        int xMax;
        /** 横轴标签是否带 {@code %} 后缀（默认·百分比模式）。 */
        boolean xIsPercent;
        /** 逐单位纵轴原始值：{@code samples[i]}＝横轴绝对值 {@code xMin+i} 的纵轴值，长度 {@code xMax-xMin+1}。 */
        float[] samples;
        /** 纵轴满量程：A/B＝127(AVRCP) 或档位数(系统档位)；默认＝100（归一增益百分比）。 */
        float yMax;
        /** 底端纵轴标签（仅 dB 口径非空，此时左边距加宽容纳宽标签）。 */
        String yLabelBottom;
        /** 中间（50% 高度）纵轴标签。 */
        String yLabelMid;
        /** 顶端（100% 高度）纵轴标签。 */
        String yLabelTop;
        /** 落点圆点的横轴绝对坐标（可空）。 */
        int[] dots;
        /** 纵向网格是否按 {@link #dots} 逐点绘制（false 则按绘图宽度三等分）。 */
        boolean dotsDriveGrid;
        /** 图注（含前缀，直接绘制）。 */
        String caption = "";
    }

    private PlotModel model = new PlotModel();

    public CurveChartView(Context context) {
        this(context, null);
    }

    public CurveChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CurveChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1.5f * density);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(2.5f * density);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextSize(11f * density);
        resolveTheme(context);
    }

    private void resolveTheme(Context context) {
        int accent = resolveAttr(context, androidx.appcompat.R.attr.colorPrimary, 0xFF1A73E8);
        int text = resolveAttr(context, android.R.attr.textColorSecondary, 0xFF616161);
        axisPaint.setColor(withAlpha(text, 0.55f));
        gridPaint.setColor(withAlpha(text, 0.15f));
        curvePaint.setColor(accent);
        dotPaint.setColor(accent);
        labelPaint.setColor(text);
    }

    /**
     * 设置 A/B（绝对/相对音量）映射参数并刷新。逐档位采样 {@link Avrcp} 映射函数，
     * 与预览文本、实际下发完全一致；圆点为音量键逐次按键落点。
     */
    public void configure(int maxSteps, int minAbs, int maxAbs, int curveType,
            boolean useSystemIndex, int keySteps) {
        int steps = Math.max(1, maxSteps);
        int segs = Prefs.clampKeySteps(keySteps);
        int yMax = useSystemIndex ? steps : Prefs.AVRCP_MAX_VOLUME;

        PlotModel m = new PlotModel();
        m.xMin = 0;
        m.xMax = steps;
        m.xIsPercent = false;
        m.yMax = yMax;
        m.samples = new float[steps + 1];
        for (int s = 0; s <= steps; s++) {
            m.samples[s] = abValueAt(s, steps, minAbs, maxAbs, curveType, useSystemIndex);
        }
        // 圆点：含 0 档 + 每次按键落点（网格吸附），与历史 onDraw 完全一致
        m.dots = new int[segs + 1];
        m.dots[0] = 0;
        for (int i = 1; i <= segs; i++) {
            m.dots[i] = Prefs.keyStepLevel(i, steps, segs);
        }
        m.dotsDriveGrid = true;
        m.yLabelBottom = null;
        m.yLabelMid = String.valueOf(yMax / 2);
        m.yLabelTop = String.valueOf(yMax);
        m.caption = Avrcp.curveLabel(curveType) + "曲线 · "
                + (useSystemIndex ? "系统档位" : "AVRCP")
                + " " + minAbs + "~" + maxAbs + " · ○=每次按键落点";
        this.model = m;
        invalidate();
    }

    /**
     * 「默认（系统原生）」专用：按 {@code gainByX} 逐单位连成真实默认曲线，横轴可窗口化到 {@code [xMin,xMax]}。
     *
     * @param xMin        横轴左端值（窗口起点：百分比模式可为设定最小值对应百分比；常规为 0）
     * @param xMax        横轴右端值（档位模式=当前档位数，百分比模式=100 或窗口上限）
     * @param gainByX     按绝对横轴索引的归一化增益（0~100%，长度至少 {@code xMax+1}，仅取 {@code [xMin,xMax]} 段）
     * @param dots        落点圆点横坐标（绝对值；超出 {@code [xMin,xMax]} 的被裁剪）
     * @param percentAxis 横轴是否取百分比（影响轴标签）
     * @param showDbAxis  纵轴是否按 dB 标注（仅「dB 线性」口径为 true，否则显示 0~100%）
     * @param dbFloor     纵轴底端（0%）对应 dB 值
     * @param dbCeil      纵轴顶端（100%）对应 dB 值
     * @param caption     图注
     */
    public void configureNativeCurve(int xMin, int xMax, float[] gainByX, int[] dots,
            boolean percentAxis, boolean showDbAxis, float dbFloor, float dbCeil, String caption) {
        PlotModel m = new PlotModel();
        m.xMin = Math.max(0, xMin);
        m.xMax = Math.max(m.xMin + 1, xMax);
        m.xIsPercent = percentAxis;
        m.yMax = 100f;   // 默认曲线纵轴＝归一增益 0~100%
        int count = m.xMax - m.xMin + 1;
        m.samples = new float[count];
        for (int x = m.xMin; x <= m.xMax; x++) {
            m.samples[x - m.xMin] = gainAt(gainByX, x);
        }
        m.dots = dots;
        // 档位模式：纵向网格按按键落点；百分比模式：无落点语义，三等分（与历史 drawNative 一致）
        m.dotsDriveGrid = !percentAxis && dots != null && dots.length > 0;
        if (showDbAxis) {
            m.yLabelBottom = fmtDb(dbFloor);
            m.yLabelMid = fmtDb((dbFloor + dbCeil) / 2f);
            m.yLabelTop = fmtDb(dbCeil);
        } else {
            m.yLabelBottom = null;
            m.yLabelMid = "50%";
            m.yLabelTop = "100%";
        }
        m.caption = "系统原生 · " + (caption == null ? "" : caption);
        this.model = m;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        PlotModel m = model;
        if (m.samples == null || m.samples.length < 2) {
            return;
        }
        // dB 纵轴需容纳“‑100dB”等较宽标签，适当加宽左边距，避免被夹到画布左边缘而与纵轴贴合
        float padL = (m.yLabelBottom != null ? 42f : 34f) * density;
        float padR = 8f * density;
        float padT = 18f * density;
        float padB = 20f * density;
        float left = getPaddingLeft() + padL;
        float right = getWidth() - getPaddingRight() - padR;
        float top = getPaddingTop() + padT;
        float bottom = getHeight() - getPaddingBottom() - padB;
        if (right <= left || bottom <= top) {
            return;
        }
        float spanX = m.xMax - m.xMin;

        // 纵向网格：dots 驱动时逐落点一条，否则按宽度三等分
        if (m.dotsDriveGrid) {
            for (int dx : m.dots) {
                if (dx < m.xMin || dx > m.xMax) {
                    continue;
                }
                float gx = mapX(dx, m, left, right, spanX);
                canvas.drawLine(gx, top, gx, bottom, gridPaint);
            }
        } else {
            for (int p = 0; p <= 2; p++) {
                float gx = left + (right - left) * p / 2f;
                canvas.drawLine(gx, top, gx, bottom, gridPaint);
            }
        }
        // 横向网格：0 / 50% / 100%
        for (int p = 0; p <= 2; p++) {
            float gy = bottom - (bottom - top) * (p / 2f);
            canvas.drawLine(left, gy, right, gy, gridPaint);
        }

        // 坐标轴
        canvas.drawRect(left, top, right, bottom, axisPaint);

        // 曲线：逐横轴单位连线
        float prevX = mapX(m.xMin, m, left, right, spanX);
        float prevY = mapY(sampleAt(m, m.xMin), top, bottom, m.yMax);
        for (int x = m.xMin + 1; x <= m.xMax; x++) {
            float x2 = mapX(x, m, left, right, spanX);
            float y2 = mapY(sampleAt(m, x), top, bottom, m.yMax);
            canvas.drawLine(prevX, prevY, x2, y2, curvePaint);
            prevX = x2;
            prevY = y2;
        }

        // 落点圆点（仅落在窗口内）
        if (m.dots != null) {
            float r = 3f * density;
            for (int dx : m.dots) {
                if (dx < m.xMin || dx > m.xMax) {
                    continue;
                }
                canvas.drawCircle(mapX(dx, m, left, right, spanX),
                        mapY(sampleAt(m, dx), top, bottom, m.yMax), r, dotPaint);
            }
        }

        // 横轴标签：端点反映窗口，百分比口径带 % 后缀
        drawLabel(canvas, m.xIsPercent ? m.xMin + "%" : String.valueOf(m.xMin),
                left, bottom + 3f * density, false);
        drawLabel(canvas, m.xIsPercent ? m.xMax + "%" : String.valueOf(m.xMax),
                right, bottom + 3f * density, true);
        // 纵轴标签：底/中/顶对应 0%/50%/100% 高度（底端仅在 dB 口径显示，否则与原点共用）
        if (m.yLabelBottom != null) {
            drawYText(canvas, m.yLabelBottom, bottom, left);
        }
        drawYText(canvas, m.yLabelMid, bottom - (bottom - top) * 0.5f, left);
        drawYText(canvas, m.yLabelTop, top, left);

        canvas.drawText(m.caption, left, top - 6f * density, labelPaint);
    }

    /** A/B 曲线：第 {@code step} 档经所选曲线映射后的纵轴输出值（AVRCP 或系统档位）。 */
    private static float abValueAt(int step, int maxSteps, int minAbs, int maxAbs,
            int curveType, boolean useSystemIndex) {
        return useSystemIndex
                ? Avrcp.curveToSystemIndex(step, maxSteps, minAbs, maxAbs, curveType)
                : Avrcp.curveToAbsoluteVolume(step, maxSteps, minAbs, maxAbs, curveType);
    }

    /** 取默认曲线第 {@code level} 档的归一化增益百分比（越界钳到数组末端）。 */
    private static float gainAt(float[] gainByX, int level) {
        if (gainByX == null || gainByX.length == 0) {
            return 0f;
        }
        int i = Math.min(level, gainByX.length - 1);
        return gainByX[Math.max(0, i)];
    }

    /** 按绝对横轴值取模型样本（越界由 {@link #gainAt}/{@link #abValueAt} 在打包时已保证）。 */
    private static float sampleAt(PlotModel m, int x) {
        int i = x - m.xMin;
        if (i < 0) {
            i = 0;
        } else if (i >= m.samples.length) {
            i = m.samples.length - 1;
        }
        return m.samples[i];
    }

    /** 横轴绝对值 → 画布 x：把 {@code x} 按窗口 {@code [xMin, xMax]} 线性映射。 */
    private static float mapX(int x, PlotModel m, float left, float right, float spanX) {
        return left + (right - left) * ((x - m.xMin) / spanX);
    }

    /** 纵轴原始值 → 画布 y：按 {@code yMax} 满量程钳制后线性映射（顶=底端 0、底=yMax）。 */
    private static float mapY(double value, float top, float bottom, float yMax) {
        double v = Math.max(0, Math.min(yMax, value));
        return bottom - (bottom - top) * (float) (v / yMax);
    }

    /** dB 值轴标签（四舍五入取整、带 dB 单位），如 {@code "-48dB"}、{@code "0dB"}。 */
    private static String fmtDb(float db) {
        return String.format(java.util.Locale.US, "%ddB", Math.round(db));
    }

    /** 在 y 轴左侧绘制右对齐、垂直居中于 {@code centerY} 的字符串标签。 */
    private void drawYText(Canvas canvas, String s, float centerY, float axisLeft) {
        labelPaint.getTextBounds(s, 0, s.length(), textBounds);
        float tx = axisLeft - 5f * density - textBounds.width();
        float baseline = centerY + textBounds.height() / 2f;
        canvas.drawText(s, Math.max(0, tx), baseline, labelPaint);
    }

    private void drawLabel(Canvas canvas, String s, float x, float y, boolean alignRight) {
        labelPaint.getTextBounds(s, 0, s.length(), textBounds);
        float tx = alignRight ? x - textBounds.width() : x;
        canvas.drawText(s, Math.max(0, tx), y + textBounds.height(), labelPaint);
    }

    private static int withAlpha(int color, float factor) {
        int a = Math.round(255 * factor);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int resolveAttr(Context context, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                return context.getColor(tv.resourceId);
            }
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        }
        return fallback;
    }
}
