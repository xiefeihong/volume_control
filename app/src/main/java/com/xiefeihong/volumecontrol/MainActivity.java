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
import android.util.Log;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.tabs.TabLayout;
import com.xiefeihong.volumecontrol.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：设置媒体音量档位、选择蓝牙生效模式（A/B）与查看/编辑各模式音量范围。
 *
 * <p>音量范围卡片以 {@link TabLayout} 三个标签（模式A / 模式B / 有线耳机）切换「当前
 * 编辑哪个范围」，共享同一组曲线/滑条/预览控件，按 {@link VolumeMode} 各自的键读写；
 * 各模式的值互不干扰。蓝牙生效模式（btMode）由独立的单选控件决定，切换仅保存、不实时
 * 生效。所有变更自动写入 Settings.Global，重启蓝牙 / 系统框架使其生效。</p>
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

    /**
     * 程序化把某模式的值载入共享控件期间置为 true，抑制所有监听回调。
     * 否则 setProgress/check 会触发 onConfigChanged → persistToPrefs，把载入中的旧值
     * 误写进当前编辑模式的键。
     */
    /** App 侧日志 tag：catch 兜底记录，保证异常可见。 */
    private static final String LOG_TAG = "VolumeControlUI";

    private boolean suppressListeners = false;

    /** 当前标签对应编辑/查看的音量模式（ABSOLUTE / SOFTWARE / WIRED）。 */
    private VolumeMode editingMode = VolumeMode.ABSOLUTE;

    /** 蓝牙生效模式（ABSOLUTE/SOFTWARE）：由「蓝牙A/蓝牙B」标签决定，有线标签不改；持久化保存。 */
    private int btMode = Prefs.BT_MODE_ABSOLUTE;

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
                // 音量范围最小/最大：拖动时禁止交叉——即将令 min>max 时把当前滑块停在对侧边界，使其无法继续滑动。
                if (fromUser && !suppressListeners) {
                    if (seekBar == binding.seekMinAbs && progress > binding.seekMaxAbs.getProgress()) {
                        seekBar.setProgress(binding.seekMaxAbs.getProgress());
                        return;
                    }
                    if (seekBar == binding.seekMaxAbs && progress < binding.seekMinAbs.getProgress()) {
                        seekBar.setProgress(binding.seekMinAbs.getProgress());
                        return;
                    }
                }
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
        binding.seekKeySteps.setOnSeekBarChangeListener(listener);
        binding.seekMinAbs.setOnSeekBarChangeListener(listener);
        binding.seekMaxAbs.setOnSeekBarChangeListener(listener);

        binding.radioCurveType.setOnCheckedChangeListener((group, checkedId) -> onConfigChanged());

        // 编辑标签：切换当前查看/编辑哪个模式的范围；蓝牙A/蓝牙B 同时切换生效模式。
        binding.tabsRange.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switchEditingTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        binding.btnRefresh.setOnClickListener(v -> refreshStatus());

        binding.btnLogs.setOnClickListener(v -> showModuleLogs());

        // 重启蓝牙：无需确认弹窗，直接保存并重启
        binding.btnRestartBt.setOnClickListener(v -> restartBluetoothNow());

        binding.btnApplyRestart.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_restart_title)
                .setMessage(R.string.dlg_restart_msg)
                .setPositiveButton(R.string.dlg_ok, (dialog, which) -> restartSystemServerNow())
                .setNegativeButton(R.string.dlg_cancel, null)
                .show());

        binding.btnReset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_reset_title)
                .setMessage(getString(R.string.dlg_reset_msg, systemDefaultStepsRaw()))
                .setPositiveButton(R.string.dlg_ok, (dialog, which) -> resetToDefaults())
                .setNegativeButton(R.string.dlg_cancel, null)
                .show());
    }

    private void loadConfigIntoUi() {
        suppressListeners = true;
        boolean enabled = prefs.getBoolean(Prefs.KEY_ENABLED, false);
        int mediaSteps = Prefs.clampMediaSteps(
                prefs.getInt(Prefs.KEY_MEDIA_STEPS, systemDefaultSteps()));
        btMode = normalizeBtMode(prefs.getInt(Prefs.KEY_BT_MODE, Prefs.BT_MODE_ABSOLUTE));

        binding.switchEnable.setChecked(enabled);
        binding.seekMediaSteps.setProgress(mediaSteps - Prefs.MEDIA_STEPS_MIN);
        int keySteps = Prefs.clampKeySteps(
                prefs.getInt(Prefs.KEY_KEY_STEPS, Prefs.KEY_STEP_DEFAULT));
        binding.seekKeySteps.setProgress(keySteps - Prefs.KEY_STEP_MIN);

        // 初始标签：当前为有线/USB/外放输出则显示「有线耳机」，否则显示当前生效的蓝牙模式（蓝牙A/蓝牙B）。
        editingMode = isWiredOrSpeakerOutput()
                ? VolumeMode.WIRED : VolumeMode.ofBtMode(btMode);
        binding.tabsRange.selectTab(binding.tabsRange.getTabAt(modeToTab(editingMode)));
        loadRangeIntoUi();
        suppressListeners = false;
    }

    /**
     * 按 {@link #editingMode} 从 prefs 读取该模式保存的最小/最大/曲线，载入共享控件。
     * 调用方负责在 {@code suppressListeners == true} 下调用，避免触发写回。
     */
    private void loadRangeIntoUi() {
        int min = Prefs.clampAbs(prefs.getInt(editingMode.minKey(), Prefs.ABS_VOLUME_MIN_DEFAULT));
        int max = Prefs.clampAbs(prefs.getInt(editingMode.maxKey(), Prefs.ABS_VOLUME_MAX_DEFAULT));
        int curve = Prefs.clampCurve(
                prefs.getInt(editingMode.curveKey(), Prefs.CURVE_TYPE_DEFAULT));
        binding.seekMinAbs.setProgress(min);
        binding.seekMaxAbs.setProgress(max);
        binding.radioCurveType.check(curveRadioId(curve));
    }

    /** 切换到 index 对应的编辑标签：载入该模式的值；蓝牙A/B 标签同时切换生效模式。 */
    private void switchEditingTab(int index) {
        VolumeMode target = tabToMode(index);
        if (suppressListeners) {
            // 载入/初始阶段（selectTab 触发）：仅切换 editingMode，不回写、不重复载入。
            editingMode = target;
            return;
        }
        // 蓝牙A/蓝牙B 标签即「生效模式」；有线耳机标签不改变生效模式。
        if (target == VolumeMode.ABSOLUTE) {
            btMode = Prefs.BT_MODE_ABSOLUTE;
        } else if (target == VolumeMode.SOFTWARE) {
            btMode = Prefs.BT_MODE_SOFTWARE;
        }
        suppressListeners = true;
        editingMode = target;
        loadRangeIntoUi();
        updatePreview();
        suppressListeners = false;
        // 生效模式随标签切换而变：落盘偏好并防抖写入系统（重启蓝牙后生效）。
        persistToPrefs(false);
        scheduleAutoSave();
    }

    /** 标签 index → 编辑模式（0=模式A，1=模式B，2=有线耳机）。 */
    private VolumeMode tabToMode(int index) {
        if (index == 1) {
            return VolumeMode.SOFTWARE;
        }
        if (index == 2) {
            return VolumeMode.WIRED;
        }
        return VolumeMode.ABSOLUTE;
    }

    /** 编辑模式 → 标签 index。 */
    private int modeToTab(VolumeMode mode) {
        if (mode == VolumeMode.SOFTWARE) {
            return 1;
        }
        if (mode == VolumeMode.WIRED) {
            return 2;
        }
        return 0;
    }

    /** 归一化蓝牙模式 id：非软件模式一律视为模式A。 */
    private int normalizeBtMode(int btMode) {
        return btMode == Prefs.BT_MODE_SOFTWARE ? Prefs.BT_MODE_SOFTWARE : Prefs.BT_MODE_ABSOLUTE;
    }

    /**
     * 打开 App 时是否默认显示「有线耳机」标签：当前输出为有线/USB 耳机或外放扬声器
     * （即模块「耳机模式」所覆盖的非蓝牙输出）时返回 true；蓝牙音频输出在用则返回 false
     * （回到当前生效的蓝牙模式标签）。type 取自 AudioDeviceInfo（字面值兼容低版本 API）。
     */
    private boolean isWiredOrSpeakerOutput() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return false;
            }
            boolean wiredOrSpeaker = false;
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = device.getType();
                // 蓝牙音频输出优先：显示蓝牙模式标签
                if (bluetoothTypeName(type) != null) {
                    return false;
                }
                // 外放扬声器(2)/有线耳麦(3,4)/线路(5,6)/USB 耳机·设备·配件(22,11,10)
                if (type == 2 || type == 3 || type == 4 || type == 5
                        || type == 6 || type == 10 || type == 11 || type == 22) {
                    wiredOrSpeaker = true;
                }
            }
            return wiredOrSpeaker;
        } catch (Throwable t) {
            Log.i(LOG_TAG, "isWiredOrSpeakerOutput failed", t);
            return false;
        }
    }

    // ==================== 配置计算与预览 ====================

    /** 当前选择的媒体音量级数（10~127）。 */
    private int currentMediaSteps() {
        return Prefs.MEDIA_STEPS_MIN + binding.seekMediaSteps.getProgress();
    }

    /** 当前选择的音量键步进（按键段数 10~29）。 */
    private int currentKeySteps() {
        return Prefs.KEY_STEP_MIN + binding.seekKeySteps.getProgress();
    }

    /** 当前蓝牙生效模式（由「蓝牙A/蓝牙B」标签决定，存于 {@link #btMode}）。 */
    private int currentBtMode() {
        return btMode;
    }

    /** 共享控件当前选中的映射曲线类型（恒代表 {@link #editingMode}）。 */
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

    /** 共享控件当前音量范围下限（自动保证 下限 <= 上限；恒代表 {@link #editingMode}）。 */
    private int currentMinAbs() {
        return Math.min(binding.seekMinAbs.getProgress(), binding.seekMaxAbs.getProgress());
    }

    /** 共享控件当前音量范围上限（自动保证 上限 >= 下限；恒代表 {@link #editingMode}）。 */
    private int currentMaxAbs() {
        return Math.max(binding.seekMinAbs.getProgress(), binding.seekMaxAbs.getProgress());
    }

    /** 系统原生媒体档位数（首次捕获值）；未捕获时回退 MEDIA_STEPS_DEFAULT。 */
    private int systemDefaultStepsRaw() {
        return prefs.getInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, Prefs.MEDIA_STEPS_DEFAULT);
    }

    /** 用作滑块默认/恢复目标的值（限制在合法区间）。 */
    private int systemDefaultSteps() {
        return Prefs.clampMediaSteps(systemDefaultStepsRaw());
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
        int keySteps = currentKeySteps();
        binding.tvKeySteps.setText(getString(R.string.label_key_steps_fmt,
                keySteps, Prefs.keyDelta(mediaSteps, keySteps)));

        int minAbs = currentMinAbs();
        int maxAbs = currentMaxAbs();
        binding.tvMinAbs.setText(getString(R.string.label_min_abs_fmt, minAbs,
                Math.round(minAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME)));
        binding.tvMaxAbs.setText(getString(R.string.label_max_abs_fmt, maxAbs,
                Math.round(maxAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME)));
        binding.tvRangeHint.setText(getString(R.string.range_hint));

        int curveType = currentCurveType();
        if (editingMode == VolumeMode.WIRED) {
            binding.tvSummary.setText(
                    Avrcp.buildWiredPreview(mediaSteps, minAbs, maxAbs, curveType));
            binding.tvRangeMapping.setText(
                    Avrcp.buildWiredMappingTable(mediaSteps, minAbs, maxAbs, curveType));
        } else {
            int btMode = editingMode.modeId();
            binding.tvSummary.setText(
                    Avrcp.buildPreview(mediaSteps, btMode, minAbs, maxAbs, curveType));
            binding.tvRangeMapping.setText(
                    Avrcp.buildMappingTable(mediaSteps, btMode, minAbs, maxAbs, curveType));
        }
    }

    // ==================== 持久化与写入系统 ====================

    /**
     * 把共享控件当前值写入 {@link #editingMode} 自己的键，外加启用开关、媒体档位、蓝牙生效模式。
     * 其余两个模式的范围值保持 prefs 中已存值不动（切换标签时才各自载入/写回）。
     */
    private void persistToPrefs(boolean synchronous) {
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(Prefs.KEY_ENABLED, binding.switchEnable.isChecked())
                .putInt(Prefs.KEY_MEDIA_STEPS, currentMediaSteps())
                .putInt(Prefs.KEY_KEY_STEPS, currentKeySteps())
                .putInt(Prefs.KEY_BT_MODE, currentBtMode())
                .putInt(editingMode.minKey(), currentMinAbs())
                .putInt(editingMode.maxKey(), currentMaxAbs())
                .putInt(editingMode.curveKey(), currentCurveType());
        if (synchronous) {
            editor.commit();
        } else {
            editor.apply();
        }
    }

    /** 由 prefs 中的三套范围 + 全局字段构造序列化配置（persistToPrefs 后调用，恒为最新）。 */
    private String currentConfigString() {
        boolean enabled = prefs.getBoolean(Prefs.KEY_ENABLED, false);
        int mediaSteps = Prefs.clampMediaSteps(
                prefs.getInt(Prefs.KEY_MEDIA_STEPS, systemDefaultSteps()));
        int keySteps = Prefs.clampKeySteps(
                prefs.getInt(Prefs.KEY_KEY_STEPS, Prefs.KEY_STEP_DEFAULT));
        int btMode = normalizeBtMode(prefs.getInt(Prefs.KEY_BT_MODE, Prefs.BT_MODE_ABSOLUTE));
        return new VolumeConfig(enabled, mediaSteps, keySteps, btMode,
                readRangeFromPrefs(VolumeMode.ABSOLUTE),
                readRangeFromPrefs(VolumeMode.SOFTWARE),
                readRangeFromPrefs(VolumeMode.WIRED)).toRaw();
    }

    /** 从 prefs 读取某模式的最小/最大/曲线三元组。 */
    private VolumeConfig.Range readRangeFromPrefs(VolumeMode mode) {
        int min = Prefs.clampAbs(prefs.getInt(mode.minKey(), Prefs.ABS_VOLUME_MIN_DEFAULT));
        int max = Prefs.clampAbs(prefs.getInt(mode.maxKey(), Prefs.ABS_VOLUME_MAX_DEFAULT));
        int curve = Prefs.clampCurve(prefs.getInt(mode.curveKey(), Prefs.CURVE_TYPE_DEFAULT));
        return new VolumeConfig.Range(min, max, curve);
    }

    /** 界面任一设置变更：立即落盘 Preferences，并防抖写入 Settings.Global。 */
    private void onConfigChanged() {
        if (suppressListeners) {
            return;
        }
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
            // 页面已销毁、线程池已关闭：记录后忽略（不影响已保存配置）
            Log.i(LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
        }
    }

    /** 关闭模块并恢复默认档位、模式A、以及三种模式各自默认音量范围，随后保存。 */
    private void resetToDefaults() {
        binding.switchEnable.setChecked(false);
        setMediaSteps(systemDefaultSteps());
        suppressListeners = true;
        btMode = Prefs.BT_MODE_ABSOLUTE;
        // 三种模式（A/B/有线）范围全部回到默认，写入 prefs
        for (VolumeMode mode : VolumeMode.values()) {
            prefs.edit()
                    .putInt(mode.minKey(), Prefs.ABS_VOLUME_MIN_DEFAULT)
                    .putInt(mode.maxKey(), Prefs.ABS_VOLUME_MAX_DEFAULT)
                    .putInt(mode.curveKey(), Prefs.CURVE_TYPE_DEFAULT)
                    .apply();
        }
        editingMode = VolumeMode.ofBtMode(Prefs.BT_MODE_ABSOLUTE);
        binding.tabsRange.selectTab(binding.tabsRange.getTabAt(modeToTab(editingMode)));
        loadRangeIntoUi();
        binding.seekKeySteps.setProgress(Prefs.KEY_STEP_DEFAULT - Prefs.KEY_STEP_MIN);
        suppressListeners = false;
        updatePreview();
        mainHandler.removeCallbacks(autoSaveRunnable);
        saveToSystem(() -> Toast.makeText(this, R.string.toast_reset_done,
                Toast.LENGTH_LONG).show());
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
                // 页面已销毁、线程池已关闭：记录后忽略（不影响已保存配置）
            Log.i(LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
            }
        });
    }

    /** 保存配置并软重启系统框架（媒体档位数修改后的生效方式）。 */
    private void restartSystemServerNow() {
        saveToSystem(() -> {
            try {
                executor.execute(Shell::restartSystemServer);
            } catch (RuntimeException ignored) {
                // 页面已销毁、线程池已关闭：记录后忽略（不影响已保存配置）
            Log.i(LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
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
                VolumeConfig globalConfig = VolumeConfig.fromRaw(
                        Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output);
                if (globalConfig != null) {
                    prefs.edit()
                            .putBoolean(Prefs.KEY_ENABLED, globalConfig.enabled)
                            .putInt(Prefs.KEY_MEDIA_STEPS, globalConfig.mediaSteps)
                            .putInt(Prefs.KEY_KEY_STEPS, globalConfig.keySteps)
                            .putInt(Prefs.KEY_BT_MODE, globalConfig.btMode)
                            .putInt(Prefs.KEY_MIN_ABS_A, globalConfig.absolute.min)
                            .putInt(Prefs.KEY_MAX_ABS_A, globalConfig.absolute.max)
                            .putInt(Prefs.KEY_CURVE_TYPE_A, globalConfig.absolute.curve)
                            .putInt(Prefs.KEY_MIN_ABS_B, globalConfig.software.min)
                            .putInt(Prefs.KEY_MAX_ABS_B, globalConfig.software.max)
                            .putInt(Prefs.KEY_CURVE_TYPE_B, globalConfig.software.curve)
                            .putInt(Prefs.KEY_MIN_ABS_W, globalConfig.wired.min)
                            .putInt(Prefs.KEY_MAX_ABS_W, globalConfig.wired.max)
                            .putInt(Prefs.KEY_CURVE_TYPE_W, globalConfig.wired.curve)
                            .commit();
                    adopted = true;
                }
            }

            String globalRaw = root ? Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output.trim() : "";
            int actualMedia = readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX);
            // 首次捕获系统原生媒体档位数：仅在模块未启用（读到的即原生值）且从未记录过时写入一次
            boolean enabledNow = prefs.getBoolean(Prefs.KEY_ENABLED, false);
            if (!enabledNow && !prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)
                    && actualMedia >= 1 && actualMedia <= 100) {
                prefs.edit().putInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, actualMedia).commit();
            }
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
        } else if (actualMedia == systemDefaultStepsRaw()
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
