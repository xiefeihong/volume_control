package com.xiefeihong.volumecontrol;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.xiefeihong.volumecontrol.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：设置媒体音量档位（15~29）、选择蓝牙音量模式与音量范围。
 * 设置变更后自动保存；重启蓝牙 / 系统框架使其生效。
 */
public class MainActivity extends AppCompatActivity {

    /** 设置变更后自动写入系统设置的防抖延迟（毫秒），避免拖动滑条时高频写入。 */
    private static final long AUTO_SAVE_DELAY_MS = 600;

    private ActivityMainBinding binding;
    private SharedPreferences prefs;

    /** 串行后台线程：所有 root / 系统查询操作都在此执行。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 防抖后的自动保存任务（设置变更 → 写入 Settings.Global）。 */
    private final Runnable autoSaveRunnable = () -> saveToSystem(null);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = Prefs.get(this);
        setupListeners();
        loadConfigIntoUi();
        updatePreview();
        // 状态刷新交给 onResume（onCreate 后紧随一次，避免重复执行）
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到界面自动刷新状态（覆盖软重启后应用未被关闭、状态显示陈旧的情况）
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(autoSaveRunnable);
        executor.shutdown();
        super.onDestroy();
    }

    // ==================== 初始化 ====================

    private void setupListeners() {
        binding.switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> onConfigChanged());

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                onConfigChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        binding.seekMediaSteps.setOnSeekBarChangeListener(listener);
        binding.seekMinAbs.setOnSeekBarChangeListener(listener);
        binding.seekMaxAbs.setOnSeekBarChangeListener(listener);

        binding.radioBtMode.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode = checkedId == R.id.radioModeSoftware
                    ? Prefs.BT_MODE_SOFTWARE : Prefs.BT_MODE_ABSOLUTE;
            int oldMode = prefs.getInt(Prefs.KEY_BT_MODE, Prefs.BT_MODE_ABSOLUTE);
            if (oldMode != Prefs.BT_MODE_SOFTWARE) oldMode = Prefs.BT_MODE_ABSOLUTE;
            if (newMode != oldMode) {
                // 先保存旧模式的值
                String oldMinKey = oldMode == Prefs.BT_MODE_SOFTWARE ? Prefs.KEY_MIN_ABS_B : Prefs.KEY_MIN_ABS_A;
                String oldMaxKey = oldMode == Prefs.BT_MODE_SOFTWARE ? Prefs.KEY_MAX_ABS_B : Prefs.KEY_MAX_ABS_A;
                prefs.edit()
                        .putInt(oldMinKey, currentMinAbs())
                        .putInt(oldMaxKey, currentMaxAbs())
                        .apply();
                // 加载新模式保存的值
                String newMinKey = newMode == Prefs.BT_MODE_SOFTWARE ? Prefs.KEY_MIN_ABS_B : Prefs.KEY_MIN_ABS_A;
                String newMaxKey = newMode == Prefs.BT_MODE_SOFTWARE ? Prefs.KEY_MAX_ABS_B : Prefs.KEY_MAX_ABS_A;
                binding.seekMinAbs.setProgress(Prefs.clampAbs(prefs.getInt(newMinKey, Prefs.ABS_VOLUME_MIN_DEFAULT)));
                binding.seekMaxAbs.setProgress(Prefs.clampAbs(prefs.getInt(newMaxKey, Prefs.ABS_VOLUME_MAX_DEFAULT)));
            }
            onConfigChanged();
        });

        binding.radioCurveType.setOnCheckedChangeListener((group, checkedId) -> onConfigChanged());

        binding.btnRefresh.setOnClickListener(v -> refreshStatus());

        binding.btnLogs.setOnClickListener(v -> showModuleLogs());

        binding.btnRestartBt.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_restart_bt_title)
                .setMessage(R.string.dlg_restart_bt_msg)
                .setPositiveButton(R.string.dlg_ok, (dialog, which) -> restartBluetoothNow())
                .setNegativeButton(R.string.dlg_cancel, null)
                .show());

        binding.btnApplyRestart.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_restart_title)
                .setMessage(R.string.dlg_restart_msg)
                .setPositiveButton(R.string.dlg_ok, (dialog, which) -> restartSystemServerNow())
                .setNegativeButton(R.string.dlg_cancel, null)
                .show());

        binding.btnReset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_reset_title)
                .setMessage(R.string.dlg_reset_msg)
                .setPositiveButton(R.string.dlg_ok, (dialog, which) -> {
                    binding.switchEnable.setChecked(false);
                    setMediaSteps(Prefs.MEDIA_STEPS_MIN);
                    binding.radioBtMode.check(R.id.radioModeAbsolute);
                    binding.radioCurveType.check(R.id.radioCurveLog);
                    binding.seekMinAbs.setProgress(Prefs.ABS_VOLUME_MIN_DEFAULT);
                    binding.seekMaxAbs.setProgress(Prefs.ABS_VOLUME_MAX_DEFAULT);
                    mainHandler.removeCallbacks(autoSaveRunnable);
                    saveToSystem(() -> Toast.makeText(this, R.string.toast_reset_done,
                            Toast.LENGTH_LONG).show());
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show());
    }

    private void loadConfigIntoUi() {
        boolean enabled = prefs.getBoolean(Prefs.KEY_ENABLED, false);
        int mediaSteps = Prefs.clampMediaSteps(
                prefs.getInt(Prefs.KEY_MEDIA_STEPS, Prefs.MEDIA_STEPS_MIN));
        int btMode = prefs.getInt(Prefs.KEY_BT_MODE, Prefs.BT_MODE_ABSOLUTE);
        if (btMode != Prefs.BT_MODE_SOFTWARE) btMode = Prefs.BT_MODE_ABSOLUTE;

        // 根据当前模式加载对应的音量范围
        String minKey = btMode == Prefs.BT_MODE_SOFTWARE ? Prefs.KEY_MIN_ABS_B : Prefs.KEY_MIN_ABS_A;
        String maxKey = btMode == Prefs.BT_MODE_SOFTWARE ? Prefs.KEY_MAX_ABS_B : Prefs.KEY_MAX_ABS_A;
        int minAbs = Prefs.clampAbs(prefs.getInt(minKey, Prefs.ABS_VOLUME_MIN_DEFAULT));
        int maxAbs = Prefs.clampAbs(prefs.getInt(maxKey, Prefs.ABS_VOLUME_MAX_DEFAULT));

        binding.switchEnable.setChecked(enabled);
        binding.seekMediaSteps.setProgress(mediaSteps - Prefs.MEDIA_STEPS_MIN);
        binding.radioBtMode.check(btMode == Prefs.BT_MODE_SOFTWARE
                ? R.id.radioModeSoftware : R.id.radioModeAbsolute);
        binding.seekMinAbs.setProgress(minAbs);
        binding.seekMaxAbs.setProgress(maxAbs);
        int curveType = Prefs.clampCurve(
                prefs.getInt(Prefs.KEY_CURVE_TYPE, Prefs.CURVE_TYPE_DEFAULT));
        binding.radioCurveType.check(curveRadioId(curveType));
    }

    // ==================== 配置计算与预览 ====================

    /** 当前选择的媒体档位数（10~29）。 */
    private int currentMediaSteps() {
        return Prefs.MEDIA_STEPS_MIN + binding.seekMediaSteps.getProgress();
    }

    /** 当前选择的蓝牙音量控制模式。 */
    private int currentBtMode() {
        return binding.radioBtMode.getCheckedRadioButtonId() == R.id.radioModeSoftware
                ? Prefs.BT_MODE_SOFTWARE : Prefs.BT_MODE_ABSOLUTE;
    }

    /** 当前选择的映射曲线类型（全局，A/B 共用）。 */
    private int currentCurveType() {
        int id = binding.radioCurveType.getCheckedRadioButtonId();
        if (id == R.id.radioCurveLinear) return Prefs.CURVE_LINEAR;
        if (id == R.id.radioCurveSqrt) return Prefs.CURVE_SQRT;
        return Prefs.CURVE_LOG;
    }

    /** 曲线类型 → RadioButton id。 */
    private int curveRadioId(int curveType) {
        if (curveType == Prefs.CURVE_LINEAR) return R.id.radioCurveLinear;
        if (curveType == Prefs.CURVE_SQRT) return R.id.radioCurveSqrt;
        return R.id.radioCurveLog;
    }

    /** 当前音量范围下限（自动保证 下限 <= 上限）。 */
    private int currentMinAbs() {
        return Math.min(binding.seekMinAbs.getProgress(), binding.seekMaxAbs.getProgress());
    }

    /** 当前音量范围上限（自动保证 上限 >= 下限）。 */
    private int currentMaxAbs() {
        return Math.max(binding.seekMinAbs.getProgress(), binding.seekMaxAbs.getProgress());
    }

    private void setMediaSteps(int steps) {
        binding.seekMediaSteps.setProgress(
                Prefs.clampMediaSteps(steps) - Prefs.MEDIA_STEPS_MIN);
    }

    /** 读取媒体流当前生效的档位上限（读取失败返回 0）。 */
    private int readStreamMaxSafe(int streamIndex) {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return 0;
            }
            return audioManager.getStreamMaxVolume(streamIndex);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void updatePreview() {
        int mediaSteps = currentMediaSteps();
        binding.tvMediaSteps.setText(getString(R.string.label_media_steps_fmt, mediaSteps));

        int minAbs = currentMinAbs();
        int maxAbs = currentMaxAbs();
        binding.tvMinAbs.setText(getString(R.string.label_min_abs_fmt, minAbs,
                Math.round(minAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME)));
        binding.tvMaxAbs.setText(getString(R.string.label_max_abs_fmt, maxAbs,
                Math.round(maxAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME)));
        binding.tvRangeHint.setText(getString(R.string.range_hint));

        String preview = Avrcp.buildPreview(mediaSteps, currentBtMode(), minAbs, maxAbs,
                currentCurveType());
        int split = preview.indexOf("\n\n");
        if (split > 0) {
            binding.tvAvrcpSummary.setText(preview.substring(0, split));
            binding.tvAvrcpDetail.setText(preview.substring(split + 2));
        } else {
            binding.tvAvrcpSummary.setText(preview);
            binding.tvAvrcpDetail.setText("");
        }
    }

    // ==================== 持久化与写入系统 ====================

    private void persistToPrefs(boolean synchronous) {
        int mode = currentBtMode();
        String minKey = mode == Prefs.BT_MODE_SOFTWARE ? Prefs.KEY_MIN_ABS_B : Prefs.KEY_MIN_ABS_A;
        String maxKey = mode == Prefs.BT_MODE_SOFTWARE ? Prefs.KEY_MAX_ABS_B : Prefs.KEY_MAX_ABS_A;
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(Prefs.KEY_ENABLED, binding.switchEnable.isChecked())
                .putInt(Prefs.KEY_MEDIA_STEPS, currentMediaSteps())
                .putInt(Prefs.KEY_BT_MODE, mode)
                .putInt(minKey, currentMinAbs())
                .putInt(maxKey, currentMaxAbs())
                .putInt(Prefs.KEY_CURVE_TYPE, currentCurveType());
        if (synchronous) {
            editor.commit();
        } else {
            editor.apply();
        }
    }

    private String currentConfigString() {
        int mode = currentBtMode();
        // 读取两个模式各自的范围值
        int minA = Prefs.clampAbs(prefs.getInt(Prefs.KEY_MIN_ABS_A, Prefs.ABS_VOLUME_MIN_DEFAULT));
        int maxA = Prefs.clampAbs(prefs.getInt(Prefs.KEY_MAX_ABS_A, Prefs.ABS_VOLUME_MAX_DEFAULT));
        int minB = Prefs.clampAbs(prefs.getInt(Prefs.KEY_MIN_ABS_B, Prefs.ABS_VOLUME_MIN_DEFAULT));
        int maxB = Prefs.clampAbs(prefs.getInt(Prefs.KEY_MAX_ABS_B, Prefs.ABS_VOLUME_MAX_DEFAULT));
        // 当前模式的值用 seekbar 实时值（可能尚未保存到 prefs）
        if (mode == Prefs.BT_MODE_SOFTWARE) {
            minB = currentMinAbs(); maxB = currentMaxAbs();
        } else {
            minA = currentMinAbs(); maxA = currentMaxAbs();
        }
        return Prefs.encodeConfig(binding.switchEnable.isChecked(), currentMediaSteps(),
                mode, minA, maxA, minB, maxB, currentCurveType());
    }

    /** 界面任一设置变更：立即落盘 Preferences，并防抖写入 Settings.Global。 */
    private void onConfigChanged() {
        persistToPrefs(false);
        updatePreview();
        scheduleAutoSave();
    }

    /** 防抖合并连续变更（拖动滑条），只写入一次 Settings.Global。 */
    private void scheduleAutoSave() {
        mainHandler.removeCallbacks(autoSaveRunnable);
        mainHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
    }

    /**
     * 把当前界面配置写入系统设置（Settings.Global），供 Hook 端实时读取；
     * 同时记录到模块日志，便于在「查看模块日志」中核对。
     *
     * @param onSaved 校验成功后的后续动作（重启按钮使用）；null 表示自动保存（失败时短提示）
     */
    private void saveToSystem(Runnable onSaved) {
        mainHandler.removeCallbacks(autoSaveRunnable);
        persistToPrefs(true);
        final String configString = currentConfigString();
        final boolean softwareMode = currentBtMode() == Prefs.BT_MODE_SOFTWARE;
        final boolean enabled = binding.switchEnable.isChecked();
        final int mediaSteps = currentMediaSteps();
        try {
            executor.execute(() -> {
                Shell.Result putResult = Shell.putGlobalConfig(Prefs.GLOBAL_KEY, configString);
                // 配置镜像备份：Hook 端可在 Settings.Global 读取失败时直读该文件（独立通道兑底）
                Shell.writeGlobalMirror(configString);
                // post-fs-data 开机脚本：开机最早阶段直接设置档位属性（不依赖任何 Hook 的终极保险）
                Shell.writeBootScript(enabled, mediaSteps);
                // 补充写入系统键：部分 ROM 的蓝牙栈会读取该开发者选项键，Hook 未生效时作为兑底
                Shell.putGlobalConfig(Shell.SETTING_AV_DISABLE, softwareMode ? "1" : "0");
                boolean verified = false;
                if (putResult.isSuccess()) {
                    String readBack = Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output;
                    verified = readBack != null && readBack.contains(configString);
                }
                Shell.appendSysLog("push config " + configString + " saved=" + verified);
                final boolean ok = verified;
                mainHandler.post(() -> {
                    if (onSaved == null) {
                        if (!ok) {
                            Toast.makeText(this, R.string.toast_auto_save_fail,
                                    Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    if (!ok) {
                        Toast.makeText(this, R.string.toast_push_fail, Toast.LENGTH_LONG).show();
                        return;
                    }
                    onSaved.run();
                });
            });
        } catch (RuntimeException ignored) {
            // 页面已销毁、线程池已关闭，忽略即可
        }
    }

    /** 保存配置并重启蓝牙（音量模式 / 音量范围修改后的生效方式，不重启系统框架）。 */
    private void restartBluetoothNow() {
        Toast.makeText(this, R.string.toast_bt_restarting, Toast.LENGTH_LONG).show();
        saveToSystem(() -> {
            try {
                executor.execute(() -> {
                    Shell.Result result = Shell.restartBluetooth();
                    if (!result.isSuccess()) {
                        mainHandler.post(() -> Toast.makeText(this, R.string.toast_bt_restart_fail,
                                Toast.LENGTH_LONG).show());
                    }
                });
            } catch (RuntimeException ignored) {
                // 页面已销毁、线程池已关闭，忽略即可
            }
        });
    }

    /** 保存配置并软重启系统框架（媒体档位数修改后的生效方式）。 */
    private void restartSystemServerNow() {
        Toast.makeText(this, R.string.toast_sys_restarting, Toast.LENGTH_LONG).show();
        saveToSystem(() -> {
            try {
                executor.execute(Shell::restartSystemServer);
            } catch (RuntimeException ignored) {
                // 页面已销毁、线程池已关闭，忽略即可
            }
        });
    }

    // ==================== 状态检测 ====================

    private void refreshStatus() {
        binding.tvStatusRoot.setText(R.string.status_detecting);
        binding.tvStatusLsposed.setText(R.string.status_detecting);
        binding.tvStatusVolume.setText(R.string.status_detecting);
        binding.tvStatusModule.setText(R.string.status_detecting);
        binding.tvStatusBt.setText(R.string.status_detecting);
        binding.tvStatusConfig.setText(R.string.status_detecting);
        binding.tvStatusAv.setText(R.string.status_detecting);

        executor.execute(() -> {
            boolean root = Shell.isRootAvailable();
            boolean lsposed = Shell.isLsposedPresent();

            // 应用数据被清除后首次打开：以系统配置键为准恢复界面，避免误覆盖已生效的配置
            boolean adopted = false;
            if (root && !prefs.contains(Prefs.KEY_ENABLED)) {
                int[] globalConfig = Prefs.decodeConfig(
                        Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output);
                if (globalConfig != null && globalConfig.length >= 7) {
                    prefs.edit()
                            .putBoolean(Prefs.KEY_ENABLED, globalConfig[0] != 0)
                            .putInt(Prefs.KEY_MEDIA_STEPS, globalConfig[1])
                            .putInt(Prefs.KEY_BT_MODE, globalConfig[2])
                            .putInt(Prefs.KEY_MIN_ABS_A, globalConfig[3])
                            .putInt(Prefs.KEY_MAX_ABS_A, globalConfig[4])
                            .putInt(Prefs.KEY_MIN_ABS_B, globalConfig[5])
                            .putInt(Prefs.KEY_MAX_ABS_B, globalConfig[6])
                            .putInt(Prefs.KEY_CURVE_TYPE, globalConfig.length >= 8
                                    ? globalConfig[7] : Prefs.CURVE_TYPE_DEFAULT)
                            .commit();
                    adopted = true;
                }
            }

            String globalRaw = root ? Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output.trim() : "";
            int actualMedia = readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX);
            String btSummary = buildBluetoothSummary();

            final boolean adoptedConfig = adopted;
            final String globalText = globalRaw;
            mainHandler.post(() -> {
                if (adoptedConfig) {
                    loadConfigIntoUi();
                    updatePreview();
                    Toast.makeText(this, R.string.toast_config_adopted, Toast.LENGTH_LONG).show();
                }
                renderStatus(root, lsposed, actualMedia, btSummary, globalText);
            });
        });
    }

    private void renderStatus(boolean root, boolean lsposed, int actualMedia,
                              String btSummary, String globalConfigRaw) {
        binding.tvStatusRoot.setText(root ? R.string.status_root_ok : R.string.status_root_fail);
        binding.tvStatusLsposed.setText(lsposed
                ? R.string.status_lsposed_ok : R.string.status_lsposed_fail);

        if (globalConfigRaw == null || globalConfigRaw.isEmpty()
                || "null".equals(globalConfigRaw)) {
            binding.tvStatusConfig.setText(R.string.status_config_none);
        } else {
            binding.tvStatusConfig.setText(getString(R.string.status_config_fmt, globalConfigRaw));
        }
        binding.tvStatusAv.setText(getString(R.string.status_btmode_fmt,
                getString(currentBtMode() == Prefs.BT_MODE_SOFTWARE
                        ? R.string.mode_name_software : R.string.mode_name_absolute)));

        boolean enabled = binding.switchEnable.isChecked();
        int targetMedia = enabled ? currentMediaSteps() : actualMedia;

        binding.tvStatusVolume.setText(getString(R.string.status_volume_line,
                actualMedia, targetMedia));

        if (enabled) {
            if (actualMedia == targetMedia) {
                binding.tvStatusModule.setText(getString(R.string.status_module_on_fmt, targetMedia));
            } else {
                binding.tvStatusModule.setText(
                        getString(R.string.status_module_pending_fmt, targetMedia));
            }
        } else if (actualMedia == Prefs.MEDIA_STEPS_MIN
                || actualMedia < Prefs.MEDIA_STEPS_MIN
                || actualMedia > Prefs.MEDIA_STEPS_MAX) {
            binding.tvStatusModule.setText(R.string.status_module_off);
        } else {
            binding.tvStatusModule.setText(
                    getString(R.string.status_module_off_pending_fmt, actualMedia));
        }

        if (btSummary == null) {
            binding.tvStatusBt.setText(R.string.status_bt_none);
        } else {
            binding.tvStatusBt.setText(getString(R.string.status_bt_fmt, btSummary));
        }

        boolean effective = actualMedia == targetMedia;
        binding.tvPending.setText(effective
                ? getString(R.string.pending_no)
                : getString(R.string.pending_yes_fmt, actualMedia, targetMedia));
    }

    // ==================== 模块日志 ====================

    /**
     * 读取模块日志：模块自写日志文件优先（系统框架 + 蓝牙两份，root 直读），
     * 再合并 LSPosed 日志与 logcat；全部为空时附上诊断信息。
     */
    private void showModuleLogs() {
        executor.execute(() -> {
            final String text = Shell.readModuleLogs();
            mainHandler.post(() -> {
                final String message = text.isEmpty() ? getString(R.string.log_empty) : text;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.log_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.dlg_copy, (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(
                                        ClipData.newPlainText(Prefs.GLOBAL_KEY, message));
                                Toast.makeText(this, R.string.toast_log_copied,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNeutralButton(R.string.dlg_clear, (dialog, which) -> {
                            executor.execute(() -> {
                                Shell.clearModuleLogs();
                                mainHandler.post(() -> Toast.makeText(this,
                                        R.string.toast_logs_cleared, Toast.LENGTH_LONG).show());
                            });
                        })
                        .setNegativeButton(R.string.dlg_close, null)
                        .show();
            });
        });
    }

    /** 汇总当前已连接的蓝牙音频输出设备；未连接返回 null。 */
    private String buildBluetoothSummary() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return null;
            }
            List<String> found = new ArrayList<>();
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
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
}
