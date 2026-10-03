package com.xiefeihong.volumecontrol;

import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 服务端「拖后按键」网格吸附 Hooker（顶层类，避免内部类）。
 *
 * <p>背景（adb 实测 + SystemUI 抓栈定位）：未拖动时音量键走
 * {@code AudioService#adjustStreamVolume}，由 {@link AudioHooks} 的接管逻辑吸附到网格；
 * 但一旦拖过一次滑条，HyperOS 会把音量对话框置为「直接落值」状态，随后的音量键改走
 * {@code SystemUI → Binder → AudioService#setStreamVolumeWithAttribution(绝对档位)}，
 * 绕开 {@code adjustStreamVolume} 接管，导致落点偏离网格（步长约 8~9）。</p>
 *
 * <p>{@code setStreamVolumeWithAttribution} 是拖动与「拖后按键」在服务端唯一共同经过的<b>同步</b>
 * 入口（SystemUI 内部两者汇聚为协程 continuation，栈塌缩无法命名 hook，故只能在此拦）。二者的
 * 唯一区别是<b>时间形态</b>：拖动是密集连发（相邻两次 &lt;~100ms），按键是孤立一次（与上一次间隔
 * &gt;~450ms）。本类据此判定：</p>
 * <ul>
 *   <li>媒体档位变更且与上一次媒体变更间隔 &lt; {@link #KEY_SNAP_GAP_NS}（密集）→ 判为拖动，
 *       直接放行，<b>保留拖动全精度</b>（不读配置，热路径仅整数比较）；</li>
 *   <li>间隔足够大（孤立）且目标档位不在 {@code keySteps} 网格上 → 吸附到最近网格档再放行，
 *       修复「拖后按键跑偏」。</li>
 * </ul>
 *
 * <p>时间戳 {@link #sLastSetNanos} 在每次媒体档位变更时刷新，使连续拖动期间判据稳定为「密集」。</p>
 */
final class MediaVolumeGridSnapHooker implements XposedInterface.Hooker {

    /** 判定「拖动 vs 按键」的相邻媒体变更最小间隔：小于此值视为拖动密集连发，放行不动。 */
    private static final long KEY_SNAP_GAP_NS = 450_000_000L;

    /** system_server 最近一次媒体档位 setStreamVolume(WithAttribution) 的时间戳（纳秒）。 */
    private static volatile long sLastSetNanos;

    /** 上次已记录的吸附落点，用于吸附日志去重（避免同值重复刷屏）。 */
    private static int sLastSnapLogged = -1;

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        List<Object> args = chain.getArgs();
        try {
            // 目标方法签名：setStreamVolumeWithAttribution(int streamType, int index, int flags, ...)
            if (args.size() < 3 || !(args.get(0) instanceof Integer)
                    || !(args.get(1) instanceof Integer)) {
                return chain.proceed();
            }
            if ((Integer) args.get(0) != Prefs.STREAM_MUSIC_INDEX) {
                return chain.proceed();
            }
            int index = (Integer) args.get(1);
            long now = System.nanoTime();
            long prev = sLastSetNanos;
            sLastSetNanos = now;   // 每次媒体变更都刷新，使拖动期间判据稳定为「密集」
            if (index <= 0) {
                return chain.proceed();
            }
            // 热路径优先：密集连发（拖动）直接放行，连配置都不读，保留全精度。
            if (now - prev < KEY_SNAP_GAP_NS) {
                return chain.proceed();
            }
            // 孤立变更 → 读配置判断是否需要吸附到网格。
            VolumeConfig config = XposedKit.readConfig(
                    XposedKit.systemServerContext(chain.getThisObject()));
            if (config == null || !config.enabled) {
                return chain.proceed();
            }
            int levels = Prefs.clampMediaSteps(config.mediaSteps);
            int segs = Prefs.clampKeySteps(config.keySteps);
            if (levels <= 0 || segs >= levels) {
                return chain.proceed();   // 网格不稀疏，逐级即正确
            }
            int nearest = (int) Math.round(index * (double) segs / levels);
            int gridIndex = Prefs.keyStepLevel(nearest, levels, segs);
            if (gridIndex == index) {
                return chain.proceed();   // 已在网格上
            }
            if (gridIndex != sLastSnapLogged) {
                sLastSnapLogged = gridIndex;
                XposedKit.log("post-drag key snap: media " + index + " -> " + gridIndex
                        + "/" + levels + " (~" + Math.round(index * 100.0 / levels)
                        + "% → " + Math.round(gridIndex * 100.0 / levels) + "%)");
            }
            Object[] newArgs = args.toArray();
            newArgs[1] = gridIndex;
            return chain.proceed(newArgs);
        } catch (Throwable t) {
            XposedKit.logErrorOnce("grid-snap", "media grid snap error: " + t);
            return chain.proceed();
        }
    }
}
