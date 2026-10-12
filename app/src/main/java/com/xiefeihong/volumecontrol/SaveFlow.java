package com.xiefeihong.volumecontrol;

import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity 的保存生效与重启流程协作者（D2 职责拆分）。
 *
 * <p>承载「保存修改」门控链路：模型回写→改动比对→确认对话框→写入系统
 * （Settings.Global + 镜像文件 + boot 脚本）→按需重启蓝牙 / 系统框架，以及
 * 「恢复系统默认」追加的清数据询问。编辑态（writeUiIntoModel/buildDefaultConfig/
 * loadConfigIntoUi/updatePreview/updateTvPending/persistToPrefs/applyConfigToPrefs/
 * systemDefaultSteps/targetPhysicalSteps）与探测（readStreamMaxSafe/refreshStatus）
 * 经宿主门面回调对应协作者，逻辑与原实现逐字一致。</p>
 */
class SaveFlow {

    private final MainActivity host;

    SaveFlow(MainActivity host) {
        this.host = host;
    }

    // ==================== 写入系统 ====================

    /**
     * 把当前模型写入系统设置（Settings.Global）供 Hook 端实时读取，同时写镜像文件与 boot 脚本；
     * 记录到模块日志便于核对。
     *
     * @param onSaved 校验成功后的后续动作（重启按钮使用）；null 表示自动保存（失败时短提示）
     */
    private void saveToSystem(Runnable onSaved) {
        host.persistToPrefs();
        final String configXml = host.model.toXml();          // 紧凑：暂存与差异比对基线
        final String pushXml = host.model.toPrettyXml();      // 格式化：写入 Settings.Global 与镜像，便于查看
        final boolean softwareMode = host.model.modeFor(OutputDevice.BT) == Prefs.BT_MODE_SOFTWARE;
        final boolean active = host.model.remapActive();
        final int mediaSteps = host.model.mediaSteps;
        try {
            host.executor.execute(() -> {
                ShellResult putResult = Shell.putGlobalConfig(Prefs.GLOBAL_KEY, pushXml);
                Shell.writeGlobalMirror(pushXml);
                Shell.writeBootScript(active, mediaSteps);
                Shell.putGlobalConfig(Shell.SETTING_AV_DISABLE, (active && softwareMode) ? "1" : "0");
                boolean verified = false;
                if (putResult.isSuccess()) {
                    // 解析回读再序列化比对：与排版（换行/缩进）无关，兼容格式化写入
                    VolumeConfig readBack = VolumeConfig.fromXml(
                            Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output);
                    verified = readBack != null && readBack.toXml().equals(configXml);
                }
                Shell.appendSysLog("push config saved=" + verified);
                final boolean ok = verified;
                host.mainHandler.post(() -> {
                    if (!ok) {
                        Toast.makeText(host, onSaved != null
                                        ? R.string.toast_push_fail : R.string.toast_auto_save_fail,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    host.lastSavedXml = configXml;
                    host.updateTvPending();
                    if (onSaved != null) {
                        onSaved.run();
                    }
                });
            });
        } catch (RuntimeException ignored) {
            Log.i(MainActivity.LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
        }
    }

    // ==================== 保存修改（显式生效门控） ====================

    /**
     * 执行「保存修改」：与已生效配置比对后弹确认框逐设备列出改动，确认才落到 prefs 并推送系统；
     * 若需重启且包含系统框架，则只重启系统框架（蓝牙进程随框架重启）。
     *
     * @param forceDefault true=「恢复系统默认」完整重置（关闭总开关、各设备默认直通）。
     */
    void runSave(boolean forceDefault) {
        host.writeUiIntoModel(true);
        VolumeConfig rawPending = forceDefault ? host.buildDefaultConfig() : host.model;
        // 关闭态选中 绝对/相对(A/B) 且尚未获取系统默认档位、且用户未确认过：无法可靠调档 →
        // 先弹警告框给出三种处理方式，用户选定后再进入常规保存确认框（此时不再重复警告）。
        // 是否仍弹由专用标志 KEY_SYSTEM_DEFAULT_ACK 控制（与 KEY_SYSTEM_DEFAULT_STEPS 解耦）。
        final boolean blocked = !forceDefault && !rawPending.enabled
                && rawPending.anyDeviceNonDefault()
                && !host.prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)
                && !host.prefs.getBoolean(Prefs.KEY_SYSTEM_DEFAULT_ACK, false);
        if (blocked) {
            showBlockedResolveDialog(rawPending);
            return;
        }
        proceedSave(rawPending, forceDefault);
    }

    /**
     * 门控警告框：未获取系统默认档位时给出三个处理入口——
     * 启用档位修改（打开总开关）／使用默认模式（各设备回落默认直通）／
     * 使用档位默认值（保留 A/B、把档位设为系统真实默认档位）。
     * 选「使用档位默认值」后置 KEY_SYSTEM_DEFAULT_ACK 不再弹警告；但 KEY_SYSTEM_DEFAULT_STEPS
     * 只在读到系统服务(AudioManager.getStreamMaxVolume)的真实档位时才固化——本机引擎表
     * (nativeMusicIndexMax)的值不一定被系统采用，绝不用于固化；读不到可信真实值则不写、留待再探测。
     * 选定后进入常规保存确认框。
     */
    private void showBlockedResolveDialog(VolumeConfig rawPending) {
        // 唯一可信来源：系统服务实时读到的媒体档位上限（与 refreshStatus 捕获口径一致：
        // 1~100 视为原生值，>100 可能被模块放大过、不予固化）。引擎表值不可信，不用。
        final int realSteps = host.readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX);
        final boolean capturedRealDefault = realSteps >= 1 && realSteps <= 100;
        final int steps = capturedRealDefault ? Prefs.clampMediaSteps(realSteps) : host.systemDefaultSteps();
        final int key = Prefs.clampKeySteps(steps);
        new AlertDialog.Builder(host)
                .setTitle(R.string.save_need_system_default_title)
                .setMessage(host.getString(R.string.save_need_system_default,
                        modeLabelShort(warnModeId(rawPending))))
                .setNeutralButton(R.string.resolve_enable,
                        (d, w) -> proceedSave(rawPending.withEnabled(true), false))
                .setNegativeButton(R.string.resolve_default_mode,
                        (d, w) -> proceedSave(rawPending.withAllDevicesDefault(), false))
                .setPositiveButton(R.string.resolve_default_steps, (d, w) -> {
                    // 用户主动选了「使用档位默认值」→置 ACK 不再弹；但仅读到可信系统真实档位才固化 KEY。
                    SharedPreferences.Editor ed = host.prefs.edit();
                    if (capturedRealDefault) {
                        ed.putInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, steps);
                    }
                    ed.putBoolean(Prefs.KEY_SYSTEM_DEFAULT_ACK, true).apply();
                    proceedSave(rawPending.withSteps(steps, key), false);
                })
                .show();
    }

    /**
     * 常规保存确认框：与已生效配置比对列出改动，确认才落到 prefs 并推送系统。
     *
     * @param fromReset true=「恢复系统默认」路径，保存成功后追加询问是否清除数据。
     */
    private void proceedSave(VolumeConfig pending, boolean fromReset) {
        final VolumeConfig base = VolumeConfig.fromXml(host.lastSavedXml);
        if (base != null && base.toXml().equals(pending.toXml())) {
            Toast.makeText(host, R.string.dlg_save_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> changes = describeChanges(base, pending);
        // 是否需重启系统框架：仅当「生效后的物理档位数」与「当前系统实际档位数」不同才需重启。
        // 优先以实时读取的当前档位数（getStreamMaxVolume）为准——档位值等于当前档位值时不提示重启；
        // 读不到（<=0）才回退到与上次基线比对。
        final int currentPhysical = host.readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX);
        final int targetSteps = host.targetPhysicalSteps(pending);
        final boolean needsSystem = currentPhysical > 0
                ? targetSteps != currentPhysical
                : (base == null || targetSteps != host.targetPhysicalSteps(base));
        // 是否需重启蓝牙：仅当蓝牙设备模式发生变化影响 AVRCP 绝对音量协商（需重连）。
        // 有线/外放的模式或范围改动经 Hook 每次实时读配置生效，无需重启任何组件。
        final boolean needsBt = base == null
                || base.bluetooth.mode != pending.bluetooth.mode;
        final boolean restartSystem = needsSystem;
        final boolean restartBt = !needsSystem && needsBt;

        StringBuilder sb = new StringBuilder();
        if (changes.isEmpty()) {
            sb.append(host.getString(R.string.dlg_save_first));
        } else {
            for (String line : changes) {
                sb.append("• ").append(line).append('\n');
            }
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.dlg_save_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.btn_save_confirm,
                        (d, w) -> commitSave(pending, restartBt, restartSystem, fromReset))
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    /** 门控警告里的模式名：优先取当前编辑设备的非默认模式，否则取首个非默认设备模式。 */
    private int warnModeId(VolumeConfig cfg) {
        int m = cfg.modeFor(host.editingDevice);
        if (m != Prefs.BT_MODE_DEFAULT) {
            return m;
        }
        for (OutputDevice d : OutputDevice.values()) {
            int dm = cfg.modeFor(d);
            if (dm != Prefs.BT_MODE_DEFAULT) {
                return dm;
            }
        }
        return m;
    }

    /**
     * 确认保存：把待写配置落到 prefs、统一回读界面，再推送系统。
     *
     * @param thenClearData true=「恢复系统默认」路径，推送成功后追加询问是否清除数据；否则直接走重启提示。
     */
    private void commitSave(VolumeConfig pending, boolean restartBt, boolean restartSystem,
            boolean thenClearData) {
        host.model = pending;
        host.applyConfigToPrefs(pending);
        host.loadConfigIntoUi(false);
        host.updatePreview();
        if (thenClearData) {
            saveToSystem(() -> promptClearData(restartBt, restartSystem));
        } else {
            saveToSystem(() -> promptRestart(restartBt, restartSystem));
        }
    }

    /** 恢复系统默认保存后追加询问：是否清除此应用写入的数据（配置与日志）。 */
    private void promptClearData(boolean restartBt, boolean restartSystem) {
        new AlertDialog.Builder(host)
                .setTitle(R.string.dlg_clear_data_title)
                .setMessage(R.string.dlg_clear_data_msg)
                .setPositiveButton(R.string.dlg_clear_data_confirm,
                        (d, w) -> {
                            clearAppData();
                            promptRestart(restartBt, restartSystem);
                        })
                .setNegativeButton(R.string.dlg_cancel,
                        (d, w) -> promptRestart(restartBt, restartSystem))
                .show();
    }

    /**
     * 清除此应用写入的系统侧数据：配置(Settings.Global 键/镜像/boot 脚本/被改写的 ro 属性)与模块日志。
     *
     * <p>保留本地 SharedPreferences（{@code settings.xml}）：其中的原生档位数等属设备环境事实，
     * 且清除后框架未重启时 getStreamMaxVolume 仍返回刚退下的旧覆盖值，一并抹掉会导致重启前把它重新误固化。</p>
     */
    private void clearAppData() {
        host.lastSavedXml = host.model.toXml();
        host.updateTvPending();
        try {
            host.executor.execute(() -> {
                Shell.clearWrittenConfig();
                Shell.clearModuleLogs();
            });
        } catch (RuntimeException ignored) {
            Log.i(MainActivity.LOG_TAG, "clear data task rejected", ignored);
        }
        Toast.makeText(host, R.string.toast_data_cleared, Toast.LENGTH_SHORT).show();
    }

    /** 列出待保存配置相对已生效配置的改动（可读条目）；{@code base} 为 null 时返回空。 */
    private List<String> describeChanges(VolumeConfig base, VolumeConfig pending) {
        List<String> out = new ArrayList<>();
        if (base == null) {
            return out;
        }
        if (base.enabled != pending.enabled) {
            out.add(host.getString(R.string.change_enable,
                    enabledLabel(base.enabled), enabledLabel(pending.enabled)));
        }
        if (base.mediaSteps != pending.mediaSteps) {
            out.add(host.getString(R.string.change_media_steps, base.mediaSteps, pending.mediaSteps));
        }
        if (base.keySteps != pending.keySteps) {
            out.add(host.getString(R.string.change_key_steps, base.keySteps, pending.keySteps));
        }
        appendDeviceChanges(out, host.getString(R.string.tab_bt), base.bluetooth, pending.bluetooth);
        appendDeviceChanges(out, host.getString(R.string.tab_wired), base.wired, pending.wired);
        appendDeviceChanges(out, host.getString(R.string.tab_speaker), base.speaker, pending.speaker);
        return out;
    }

    /** 追加单设备的模式改动 + A/B 范围改动条目（前缀设备名）。 */
    private void appendDeviceChanges(List<String> out, String name, DeviceConfig from, DeviceConfig to) {
        if (from.mode != to.mode) {
            out.add(host.getString(R.string.change_device_mode, name,
                    modeLabelShort(from.mode), modeLabelShort(to.mode)));
        }
        addRangeChange(out, name + "·" + host.getString(R.string.mode_short_absolute), from.absolute, to.absolute);
        addRangeChange(out, name + "·" + host.getString(R.string.mode_short_software), from.software, to.software);
        addRangeChange(out, name + "·" + host.getString(R.string.mode_name_default), from.defaultRange, to.defaultRange);
    }

    private void addRangeChange(List<String> out, String name, Range from, Range to) {
        if (from.equals(to)) {
            return;
        }
        out.add(host.getString(R.string.change_range, name, rangeLabel(from), rangeLabel(to)));
    }

    private String rangeLabel(Range r) {
        return r.min + "~" + r.max + "/" + Avrcp.curveLabel(r.curve);
    }

    private String enabledLabel(boolean enabled) {
        return host.getString(enabled ? R.string.enable_on : R.string.enable_off);
    }

    /** 对话框用的简名：绝对→「绝对音量」、相对→「相对音量」、默认→「默认」。 */
    String modeLabelShort(int mode) {
        if (mode == Prefs.BT_MODE_SOFTWARE) {
            return host.getString(R.string.mode_short_software);
        }
        if (mode == Prefs.BT_MODE_DEFAULT) {
            return host.getString(R.string.mode_name_default);
        }
        return host.getString(R.string.mode_short_absolute);
    }

    /** 保存成功后，如需要则询问是否立即重启对应组件。 */
    private void promptRestart(boolean needsBt, boolean needsSystem) {
        host.refreshStatus();
        if (!needsBt && !needsSystem) {
            Toast.makeText(host, R.string.toast_save_done, Toast.LENGTH_SHORT).show();
            return;
        }
        String what = (needsBt && needsSystem)
                ? host.getString(R.string.restart_both)
                : needsBt ? host.getString(R.string.restart_bt) : host.getString(R.string.restart_system);
        new AlertDialog.Builder(host)
                .setTitle(R.string.dlg_save_done_title)
                .setMessage(host.getString(R.string.dlg_restart_now_msg, what))
                .setPositiveButton(R.string.dlg_restart_now,
                        (d, w) -> performRestart(needsBt, needsSystem))
                .setNegativeButton(R.string.dlg_restart_later, null)
                .show();
    }

    /** 按需重启蓝牙与/或系统框架（不重复写配置，配置已在保存时推送）。 */
    private void performRestart(boolean needsBt, boolean needsSystem) {
        Toast.makeText(host, R.string.toast_restarting, Toast.LENGTH_LONG).show();
        try {
            host.executor.execute(() -> {
                if (needsBt) {
                    Shell.restartBluetooth();
                }
                if (needsSystem) {
                    Shell.restartSystemServer();
                }
            });
        } catch (RuntimeException ignored) {
            Log.i(MainActivity.LOG_TAG, "restart task rejected", ignored);
        }
    }

    /** 保存配置并重启蓝牙（模式 / 音量范围修改后的生效方式，不重启系统框架）。 */
    void restartBluetoothNow() {
        Toast.makeText(host, R.string.toast_bt_restarting, Toast.LENGTH_LONG).show();
        saveToSystem(() -> {
            try {
                host.executor.execute(() -> {
                    ShellResult result = Shell.restartBluetooth();
                    if (!result.isSuccess()) {
                        host.mainHandler.post(() -> Toast.makeText(host, R.string.toast_bt_restart_fail,
                                Toast.LENGTH_LONG).show());
                    }
                });
            } catch (RuntimeException ignored) {
                Log.i(MainActivity.LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
            }
        });
    }

    /** 保存配置并软重启系统框架（媒体档位数修改后的生效方式）。 */
    void restartSystemServerNow() {
        saveToSystem(() -> {
            try {
                host.executor.execute(Shell::restartSystemServer);
            } catch (RuntimeException ignored) {
                Log.i(MainActivity.LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
            }
        });
    }
}
