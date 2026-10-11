package com.xiefeihong.volumecontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link Prefs} 纯整数计算（clamp / 按键网格 / 步进吸附）的 JVM 单元测试。
 * 覆盖边界：keySteps≥levels（网格退化）、round 碰撞、越界钳制。
 */
public class PrefsTest {

    @Test
    public void clampMediaSteps_boundsRange() {
        assertEquals(10, Prefs.clampMediaSteps(5));
        assertEquals(10, Prefs.clampMediaSteps(Prefs.MEDIA_STEPS_MIN));
        assertEquals(100, Prefs.clampMediaSteps(Prefs.MEDIA_STEPS_MAX));
        assertEquals(100, Prefs.clampMediaSteps(150));
        assertEquals(50, Prefs.clampMediaSteps(50));
    }

    @Test
    public void clampKeySteps_boundsRange() {
        assertEquals(10, Prefs.clampKeySteps(5));
        assertEquals(29, Prefs.clampKeySteps(40));
        assertEquals(15, Prefs.clampKeySteps(15));
    }

    @Test
    public void clampSystemStep_respectsMaxSteps() {
        assertEquals(0, Prefs.clampSystemStep(5, 0));      // maxSteps<=0 → 0
        assertEquals(0, Prefs.clampSystemStep(-3, 100));
        assertEquals(100, Prefs.clampSystemStep(150, 100));
        assertEquals(50, Prefs.clampSystemStep(50, 100));
    }

    @Test
    public void clampSystemStepAbs_fixedUpperBound() {
        assertEquals(0, Prefs.clampSystemStepAbs(-5));
        assertEquals(100, Prefs.clampSystemStepAbs(150));
        assertEquals(40, Prefs.clampSystemStepAbs(40));
    }

    @Test
    public void clampAbs_and_clampCurve() {
        assertEquals(0, Prefs.clampAbs(-1));
        assertEquals(127, Prefs.clampAbs(200));
        assertEquals(60, Prefs.clampAbs(60));
        assertEquals(0, Prefs.clampCurve(-1));
        assertEquals(Prefs.CURVE_SQRT, Prefs.clampCurve(9));
    }

    @Test
    public void keyDelta_basic() {
        assertEquals(4, Prefs.keyDelta(100, 25));
        assertEquals(10, Prefs.keyDelta(100, 10));
        assertEquals(1, Prefs.keyDelta(100, 200));   // round(0.5)=1，且下限 1
        assertEquals(100, Prefs.keyDelta(100, 0));   // keySteps<=0 → max(1,levels)
    }

    @Test
    public void keyStepLevel_endpointsAndGuards() {
        assertEquals(0, Prefs.keyStepLevel(0, 100, 25));
        assertEquals(100, Prefs.keyStepLevel(25, 100, 25));
        assertEquals(0, Prefs.keyStepLevel(5, 0, 25));          // levels<=0 → 0
        assertEquals(7, Prefs.keyStepLevel(7, 100, 0));         // keySteps<=0 → clamp(seg,0,levels)
    }

    @Test
    public void keyStepLevel_monotonicAndBounded() {
        int levels = 100;
        int keySteps = 25;
        int prev = -1;
        for (int seg = 0; seg <= keySteps; seg++) {
            int lvl = Prefs.keyStepLevel(seg, levels, keySteps);
            assertTrue("bounded: " + lvl, lvl >= 0 && lvl <= levels);
            assertTrue("monotonic at seg=" + seg, lvl >= prev);
            prev = lvl;
        }
        assertEquals(levels, prev);
    }

    @Test
    public void nextKeyStepUp_strictlyIncreasesWithinBounds() {
        int levels = 100;
        int keySteps = 20;
        for (int cur = 0; cur < levels; cur++) {
            int nxt = Prefs.nextKeyStepUp(cur, levels, keySteps);
            assertTrue(cur + " -> " + nxt, nxt > cur && nxt <= levels);
        }
        assertEquals(levels, Prefs.nextKeyStepUp(levels, levels, keySteps));
    }

    @Test
    public void nextKeyStepDown_strictlyDecreasesWithinBounds() {
        int levels = 100;
        int keySteps = 20;
        for (int cur = 1; cur <= levels; cur++) {
            int nxt = Prefs.nextKeyStepDown(cur, levels, keySteps);
            assertTrue(cur + " -> " + nxt, nxt < cur && nxt >= 0);
        }
        assertEquals(0, Prefs.nextKeyStepDown(0, levels, keySteps));
    }

    @Test
    public void nextKeyStep_gridDegenerateStepsOneByOne() {
        // keySteps>=levels → 网格间距<1，退化为逐级 ±1
        assertEquals(6, Prefs.nextKeyStepUp(5, 10, 10));
        assertEquals(4, Prefs.nextKeyStepDown(5, 10, 10));
        assertEquals(10, Prefs.nextKeyStepUp(10, 10, 10));   // 到顶
        assertEquals(0, Prefs.nextKeyStepDown(0, 10, 10));   // 到底
    }
}
