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
 */
public final class CurveChartView extends View {

    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();

    private final float density;

    // 映射参数（configure 写入，onDraw 读取）
    private int maxSteps = 15;
    private int minAbs;
    private int maxAbs = Prefs.AVRCP_MAX_VOLUME;
    private int curveType = Prefs.CURVE_LOG;
    private boolean useSystemIndex;
    private int keySteps = Prefs.KEY_STEP_DEFAULT;

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

    /** 设置映射参数并刷新。 */
    public void configure(int maxSteps, int minAbs, int maxAbs, int curveType,
            boolean useSystemIndex, int keySteps) {
        this.maxSteps = Math.max(1, maxSteps);
        this.minAbs = minAbs;
        this.maxAbs = maxAbs;
        this.curveType = curveType;
        this.useSystemIndex = useSystemIndex;
        this.keySteps = keySteps;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padL = 34f * density;
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
        int yMax = useSystemIndex ? maxSteps : Prefs.AVRCP_MAX_VOLUME;
        int segs = Prefs.clampKeySteps(keySteps);

        // 纵向网格：每个按键落点一条（对应 100/keySteps 百分比刻度）
        for (int i = 0; i <= segs; i++) {
            float gx = mapX(Prefs.keyStepLevel(i, maxSteps, segs), left, right);
            canvas.drawLine(gx, top, gx, bottom, gridPaint);
        }
        // 横向网格：0 / 50% / 100%
        for (int p = 0; p <= 2; p++) {
            float gy = mapY(yMax * p / 2.0, top, bottom, yMax);
            canvas.drawLine(left, gy, right, gy, gridPaint);
        }

        // 坐标轴
        canvas.drawRect(left, top, right, bottom, axisPaint);

        // 曲线：逐档位采样，与映射函数完全一致
        float prevX = mapX(0, left, right);
        float prevY = mapY(valueAt(0), top, bottom, yMax);
        for (int step = 1; step <= maxSteps; step++) {
            float x = mapX(step, left, right);
            float y = mapY(valueAt(step), top, bottom, yMax);
            canvas.drawLine(prevX, prevY, x, y, curvePaint);
            prevX = x;
            prevY = y;
        }

        // 按键落点圆点（含 0 档）
        float r = 3f * density;
        for (int press = 0; press <= segs; press++) {
            int level = (press == 0) ? 0 : Prefs.keyStepLevel(press, maxSteps, segs);
            float x = mapX(level, left, right);
            float y = mapY(valueAt(level), top, bottom, yMax);
            canvas.drawCircle(x, y, r, dotPaint);
        }

        // 轴标签：x 轴（音量级数）起止
        drawLabel(canvas, "0", left, bottom + 3f * density, false);
        drawLabel(canvas, String.valueOf(maxSteps), right, bottom + 3f * density, true);
        // y 轴数值标签：靠左、右对齐，垂直居中于对应横向网格线（0 与 x 轴原点共用，故省略）
        drawYLabel(canvas, yMax / 2, mapY(yMax / 2.0, top, bottom, yMax), left);
        drawYLabel(canvas, yMax, mapY(yMax, top, bottom, yMax), left);
        String caption = Avrcp.curveLabel(curveType) + "曲线 · "
                + (useSystemIndex ? "系统档位" : "AVRCP")
                + " " + minAbs + "~" + maxAbs + " · ○=每次按键落点";
        canvas.drawText(caption, left, top - 6f * density, labelPaint);
    }

    private float valueAt(int step) {
        return useSystemIndex
                ? Avrcp.curveToSystemIndex(step, maxSteps, minAbs, maxAbs, curveType)
                : Avrcp.curveToAbsoluteVolume(step, maxSteps, minAbs, maxAbs, curveType);
    }

    private float mapX(int step, float left, float right) {
        return left + (right - left) * (step / (float) maxSteps);
    }

    private float mapY(double value, float top, float bottom, int yMax) {
        double v = Math.max(0, Math.min(yMax, value));
        return bottom - (bottom - top) * (float) (v / yMax);
    }

    private void drawLabel(Canvas canvas, String s, float x, float y, boolean alignRight) {
        labelPaint.getTextBounds(s, 0, s.length(), textBounds);
        float tx = alignRight ? x - textBounds.width() : x;
        canvas.drawText(s, Math.max(0, tx), y + textBounds.height(), labelPaint);
    }

    /** 在 y 轴左侧绘制右对齐、垂直居中于 {@code centerY} 的数值标签。 */
    private void drawYLabel(Canvas canvas, int v, float centerY, float axisLeft) {
        String s = String.valueOf(v);
        labelPaint.getTextBounds(s, 0, s.length(), textBounds);
        float tx = axisLeft - 5f * density - textBounds.width();
        float baseline = centerY + textBounds.height() / 2f;
        canvas.drawText(s, Math.max(0, tx), baseline, labelPaint);
    }

    private static int withAlpha(int color, float factor) {
        int a = Math.round(255 * factor);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int resolveAttr(Context context, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                return context.getResources().getColor(tv.resourceId);
            }
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        }
        return fallback;
    }
}
