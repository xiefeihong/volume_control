package com.xiefeihong.volumecontrol;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * LSPosed 模块入口（libxposed 新 API，minApi 102）。
 *
 * <p>静态作用域见 {@code META-INF/xposed/scope.list}：系统框架（android）+ 蓝牙
 * （com.android.bluetooth）+ SystemUI（com.android.systemui）。生命周期分发：</p>
 * <ul>
 *   <li>{@link #onSystemServerStarting}：system_server 启动关键服务之前——
 *       AudioService 尚未构造，正是改写档位数的完整时机（旧 API 无此保证，
 *       这是冷启动档位未生效的关键修复之一）；</li>
 *   <li>{@link #onPackageLoaded}：蓝牙进程加载 Avrcp 音量管理器相关 Hook；SystemUI 进程
 *       加载音量键绕过定位诊断（{@link SystemUiHooks}）。</li>
 * </ul>
 *
 * <p>具体逻辑见 {@link AudioHooks}（系统框架侧）与 {@link BtHooks}（蓝牙侧）；
 * 共享工具（日志 / 反射 / 配置读取）见 {@link XposedKit}。</p>
 */
public class XposedEntry extends XposedModule {

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        XposedKit.init(this, param.isSystemServer());
        XposedKit.log("module loaded in " + param.getProcessName()
                + " (framework " + getFrameworkName() + " " + getFrameworkVersion()
                + ", api " + getApiVersion() + ")");
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedKit.log("system_server starting, installing hooks");
        AudioHooks.install(param.getClassLoader(), this);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (BtHooks.BT_PACKAGE.equals(param.getPackageName())) {
            XposedKit.log("bluetooth package loaded, installing hooks");
            BtHooks.install(param.getDefaultClassLoader(), this);
        } else if (SystemUiHooks.SYSTEMUI_PACKAGE.equals(param.getPackageName())) {
            XposedKit.log("systemui package loaded, installing volume-key origin diag");
            SystemUiHooks.install(param.getDefaultClassLoader(), this);
        }
    }
}
