package com.xiefeihong.volumecontrol;

import android.content.Context;
import android.net.Uri;
import android.provider.Settings;

/**
 * {@code android.provider.Settings.Global} 的薄封装（隔离 import，便于阅读）。
 *
 * <p>从 {@link XposedKit} 的内部类提取为独立顶层类（同包），供配置读取通道1
 * （Settings.Global，各进程均可访问）使用。冷启动早期 SettingsProvider 未就绪时
 * 本调用可能抛错，由 {@code XposedKit.readConfig} 捕获后回退镜像文件通道。</p>
 */
final class SettingsGlobal {

    private SettingsGlobal() {
    }

    static String getString(Context context, String key) {
        return Settings.Global.getString(
                context.getContentResolver(), key);
    }

    /** 该 Global 键对应的通知 Uri，供 {@code registerContentObserver} 监听配置变更实时失效缓存。 */
    static Uri getUriFor(String key) {
        return Settings.Global.getUriFor(key);
    }

}
