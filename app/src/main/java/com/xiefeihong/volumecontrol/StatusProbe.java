package com.xiefeihong.volumecontrol;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity 的状态检测与环境探测协作者（D2 职责拆分）。
 *
 * <p>负责 root/LSPosed/当前档位数/蓝牙连接等运行态探测、模块日志查看，以及依赖
 * {@link AudioManager} 的输出设备识别与系统原生档位数读取。所有可变状态集中在
 * {@link MainActivity}，编辑态刷新（loadConfigIntoUi/updatePreview/updateTvPending）与
 * 展示文案（modeLabelShort）经宿主门面回调对应协作者，语义与原实现一致。</p>
 */
class StatusProbe {

    private final MainActivity host;

    StatusProbe(MainActivity host) {
        this.host = host;
    }

    // ==================== 系统原生档位数探测 ====================

    /**
     * 系统原生媒体档位数。来源优先级：已捕获的系统服务实测值 &gt; ROM 引擎表 XML
     * ({@code <indexMax>}) &gt; 内置默认 {@link Prefs#MEDIA_STEPS_DEFAULT}。
     */
    int systemDefaultStepsRaw() {
        if (host.prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)) {
            return host.prefs.getInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, Prefs.MEDIA_STEPS_DEFAULT);
        }
        int xml = NativeVolumeCurve.nativeMusicIndexMax();
        return xml > 0 ? xml : Prefs.MEDIA_STEPS_DEFAULT;
    }

    /** 读取媒体流当前生效的档位上限（读取失败返回 0）。 */
    int readStreamMaxSafe(int streamIndex) {
        try {
            if (host.audioManager == null) {
                return 0;
            }
            return host.audioManager.getStreamMaxVolume(streamIndex);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ==================== 设备自动选中 ====================

    /** 按当前可用输出设备判定应聚焦的设备标签：蓝牙 &gt; 有线 &gt; 外放。 */
    OutputDevice detectCurrentDevice() {
        if (host.audioManager == null) {
            return OutputDevice.SPEAKER;
        }
        try {
            boolean wired = false;
            for (AudioDeviceInfo d : host.audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = d.getType();
                if (bluetoothTypeName(type) != null) {
                    return OutputDevice.BT;              // 蓝牙优先
                }
                if (type == 3 || type == 4 || type == 13) {
                    wired = true;                        // 有线耳麦(3)/耳机(4)/USB 耳机(13)
                }
            }
            if (wired) {
                return OutputDevice.WIRED;
            }
        } catch (Throwable t) {
            return OutputDevice.SPEAKER;
        }
        return OutputDevice.SPEAKER;
    }

    /** 汇总当前已连接的蓝牙音频输出设备；未连接返回 null。 */
    private String buildBluetoothSummary() {
        try {
            if (host.audioManager == null) {
                return null;
            }
            List<String> found = new ArrayList<>();
            for (AudioDeviceInfo device : host.audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                String typeName = bluetoothTypeName(device.getType());
                if (typeName == null) {
                    continue;
                }
                found.add(deviceLabel(device) + "（" + typeName + "）");
            }
            if (found.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < found.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(found.get(i));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private String deviceLabel(AudioDeviceInfo device) {
        CharSequence name = device.getProductName();
        if (name == null || name.length() == 0) {
            return "蓝牙设备";
        }
        return name.toString();
    }

    /** 判断是否为蓝牙相关输出设备并返回类型名（使用常量字面值以兼容低版本 API）。 */
    private String bluetoothTypeName(int type) {
        switch (type) {
            case 7:
                return "蓝牙通话 SCO"; // AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            case 8:
                return "蓝牙音乐 A2DP"; // AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            case 23:
                return "助听器"; // AudioDeviceInfo.TYPE_HEARING_AID
            case 26:
                return "蓝牙 LE 耳机"; // TYPE_BLE_HEADSET（API 31+）
            case 27:
                return "蓝牙 LE 音箱"; // TYPE_BLE_SPEAKER（API 31+）
            case 30:
                return "蓝牙 LE 广播"; // TYPE_BLE_BROADCAST（API 33+）
            default:
                return null;
        }
    }

    // ==================== 状态检测 ====================

    void refreshStatus() {
        host.binding.tvStatusRoot.setText(R.string.status_detecting);
        host.binding.tvStatusLsposed.setText(R.string.status_detecting);
        host.binding.tvStatusVolume.setText(R.string.status_detecting);
        host.binding.tvStatusModule.setText(R.string.status_detecting);
        host.binding.tvStatusBt.setText(R.string.status_detecting);
        host.binding.tvStatusAv.setText(R.string.status_detecting);

        host.executor.execute(() -> {
            boolean root = Shell.isRootAvailable();
            boolean lsposed = Shell.isLsposedPresent();

            // 应用数据被清除后首次打开：以系统配置键为准恢复界面，避免误覆盖已生效的配置
            boolean adopted = false;
            if (root && !host.prefs.contains(Prefs.KEY_CONFIG_XML)) {
                VolumeConfig globalConfig = VolumeConfig.fromXml(
                        Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output);
                if (globalConfig != null) {
                    host.prefs.edit().putString(Prefs.KEY_CONFIG_XML, globalConfig.toXml()).commit();
                    adopted = true;
                }
            }

            int actualMedia = readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX);
            // 首次捕获系统原生媒体档位数：仅当本地与系统侧都未启用覆盖时，读到的才是未被
            // 模块改写过的真实原生值（req-3）；否则（已推送生效配置）不固化，避免把覆盖值当默认。
            VolumeConfig stored = VolumeConfig.fromXml(host.prefs.getString(Prefs.KEY_CONFIG_XML, ""));
            boolean enabledNow = stored != null && stored.enabled;
            VolumeConfig pushed = VolumeConfig.fromXml(
                    Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output);
            boolean systemActive = pushed != null && pushed.remapActive();
            if (!enabledNow && !systemActive
                    && !host.prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)
                    && actualMedia >= 1 && actualMedia <= 100) {
                host.prefs.edit().putInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, actualMedia).commit();
            }
            String btSummary = buildBluetoothSummary();

            final boolean adoptedConfig = adopted;
            host.mainHandler.post(() -> {
                if (adoptedConfig) {
                    host.loadConfigIntoUi();
                    host.updatePreview();
                    Toast.makeText(host, R.string.toast_config_adopted, Toast.LENGTH_LONG).show();
                }
                renderStatus(root, lsposed, actualMedia, btSummary);
            });
        });
    }

    private void renderStatus(boolean root, boolean lsposed, int actualMedia,
                              String btSummary) {
        host.binding.tvStatusRoot.setText(root ? R.string.status_root_ok : R.string.status_root_fail);
        host.binding.tvStatusLsposed.setText(lsposed
                ? R.string.status_lsposed_ok : R.string.status_lsposed_fail);

        int btMode = host.model.modeFor(OutputDevice.BT);
        host.binding.tvStatusAv.setText(host.getString(R.string.status_btmode_fmt,
                host.modeLabelShort(btMode)));

        boolean enabled = host.binding.switchEnable.isChecked();
        int targetMedia = enabled ? host.currentMediaSteps() : actualMedia;

        host.binding.tvStatusVolume.setText(host.getString(R.string.status_volume_line,
                actualMedia, systemDefaultStepsRaw(), host.currentKeySteps(),
                host.getString(host.defaultStepsSourceLabel())));

        if (enabled) {
            if (actualMedia == targetMedia) {
                host.binding.tvStatusModule.setText(R.string.status_module_on);
            } else {
                host.binding.tvStatusModule.setText(R.string.status_module_pending);
            }
        } else if (actualMedia == systemDefaultStepsRaw()
                || actualMedia < Prefs.MEDIA_STEPS_MIN
                || actualMedia > Prefs.MEDIA_STEPS_MAX) {
            host.binding.tvStatusModule.setText(R.string.status_module_off);
        } else {
            host.binding.tvStatusModule.setText(R.string.status_module_off_pending);
        }

        if (btSummary == null) {
            host.binding.tvStatusBt.setText(R.string.status_bt_none);
        } else {
            host.binding.tvStatusBt.setText(host.getString(R.string.status_bt_fmt, btSummary));
        }

        boolean effective = actualMedia == targetMedia;
        host.pendingStatusText = effective
                ? host.getString(R.string.pending_no)
                : host.getString(R.string.pending_yes_fmt, actualMedia, targetMedia);
        host.updateTvPending();
    }

    // ==================== 模块日志 ====================

    /**
     * 读取模块日志：模块自写日志文件优先（系统框架 + 蓝牙两份，root 直读），
     * 再合并 LSPosed 日志与 logcat；全部为空时附上诊断信息。
     */
    void showModuleLogs() {
        host.executor.execute(() -> {
            final String text = Shell.readModuleLogs();
            host.mainHandler.post(() -> {
                final String message = text.isEmpty() ? host.getString(R.string.log_empty) : text;
                new AlertDialog.Builder(host)
                        .setTitle(R.string.log_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.dlg_copy, (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager)
                                    host.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(
                                        ClipData.newPlainText(Prefs.GLOBAL_KEY, message));
                                Toast.makeText(host, R.string.toast_log_copied,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNeutralButton(R.string.dlg_clear, (dialog, which) -> {
                            host.executor.execute(() -> {
                                Shell.clearModuleLogs();
                                host.mainHandler.post(() -> Toast.makeText(host,
                                        R.string.toast_logs_cleared, Toast.LENGTH_LONG).show());
                            });
                        })
                        .setNegativeButton(R.string.dlg_close, null)
                        .show();
            });
        });
    }

    /** 供宿主门面复用：Log tag 输出异常兜底（保留 App 侧日志可见性）。 */
    void logRejected(String msg, Throwable t) {
        Log.i(MainActivity.LOG_TAG, msg, t);
    }
}
