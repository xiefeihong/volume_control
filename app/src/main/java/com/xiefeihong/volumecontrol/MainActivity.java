package com.xiefeihong.volumecontrol;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.ChipGroup;
import com.xiefeihong.volumecontrol.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：设置媒体音量档位、选择蓝牙生效模式（A/B）与查看/编辑各模式音量范围。
 *
 * <p>音量范围卡片以 {@link ChipGroup} 三个单选芯片（模式A / 模式B / 有线耳机）切换「当前
 * 编辑哪个范围」，共享同一组曲线/滑条/预览控件，按 {@link VolumeMode} 各自的键读写；
 * 各模式的值互不干扰。蓝牙生效模式（btMode）由「蓝牙A/蓝牙B」标签决定，切换仅暂存、不实时
 * 生效。所有变更只暂存到界面/Preferences，须点「保存修改」确认后才写入 Settings.Global，
 * 再按需重启蓝牙 / 系统框架使其生效（需重启含系统框架时只重启框架）；「默认」标签的保存仅关闭启用模块（其余保留），
 * 「恢复系统默认」按钮则完整重置。</p>
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private SharedPreferences prefs;

    /**
     * 上一次成功写入系统（Settings.Global）的生效配置原文，作为「未保存修改」比较基线。
     * 界面编辑只暂存到 {@code prefs}（工作副本），必须点「保存修改」才推送到系统；启动时先按
     * prefs 初始化，状态刷新读到系统实际值后不再重置（以保存成功时的回写为准）。
     */
    private String lastSavedRaw = "";

    /** {@code tvPending} 在「已保存」状态下显示的（重启待生效）文案，由 renderStatus 写入。 */
    private String pendingStatusText = "";

    /** 串行后台线程：所有 root / 系统查询操作都在此执行。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    /**
     * 是否处于「默认」只读标签：为 true 时隐藏可交互编辑控件（{@code groupRangeEditors}：曲线单选/最小最大滑条），
     * 保留 {@code tvSummary} + 曲线图 + 映射表，均以 ROM 原生直通参数只读展示；不改变生效模式、不落盘任何音量范围。
     */
    private boolean showingDefault = false;

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
        // 首次布局前 getWidth()==0，排版完成后按实测宽度重算一次每行个数。
        binding.getRoot().post(this::updatePreview);
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

        // 编辑芯片（单选）：切换当前查看/编辑哪个模式的范围；蓝牙A/蓝牙B 同时切换生效模式；
        // 「默认」芯片为只读信息标签，隐藏编辑控件、只显示系统默认信息。
        binding.chipGroupRange.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.chipDefault) {
                showDefaultTab();
            } else {
                switchEditingTab(chipIdToMode(checkedId));
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

        // 「保存修改」：列出相对已生效配置的改动，确认后才写入并推送系统；需重启时再询问。
        binding.btnSave.setOnClickListener(v -> runSave(false));

        // 「恢复系统默认」与「选中默认后保存修改」使用同一逻辑：强制默认态保存。
        binding.btnReset.setOnClickListener(v -> runSave(true));
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
                prefs.getInt(Prefs.KEY_KEY_STEPS, defaultKeySteps()));
        binding.seekKeySteps.setProgress(keySteps - Prefs.KEY_STEP_MIN);

        // 上次若保存为「默认」模式，打开即停留在只读「默认」标签（需求：记住默认选择）；
        // 否则初始选中：当前为有线/USB/外放输出选「有线耳机」，否则选当前生效的蓝牙模式。
        boolean defaultMode = prefs.getBoolean(Prefs.KEY_DEFAULT_MODE, false);
        editingMode = isWiredOrSpeakerOutput()
                ? VolumeMode.WIRED : VolumeMode.ofBtMode(btMode);
        loadRangeIntoUi();
        showingDefault = defaultMode;
        if (defaultMode) {
            binding.chipGroupRange.check(R.id.chipDefault);
            binding.groupRangeEditors.setVisibility(View.GONE);
        } else {
            binding.chipGroupRange.check(modeToChipId(editingMode));
            binding.groupRangeEditors.setVisibility(View.VISIBLE);
        }
        suppressListeners = false;
        lastSavedRaw = currentConfigString();
        updateTvPending();
    }

    /**
     * 按 {@link #editingMode} 从 prefs 读取该模式保存的最小/最大/曲线，载入共享控件。
     * 调用方负责在 {@code suppressListeners == true} 下调用，避免触发写回。
     */
    private void loadRangeIntoUi() {
        int min = Prefs.clampAbs(prefs.getInt(editingMode.minKey, Prefs.ABS_VOLUME_MIN_DEFAULT));
        int max = Prefs.clampAbs(prefs.getInt(editingMode.maxKey, Prefs.ABS_VOLUME_MAX_DEFAULT));
        int curve = Prefs.clampCurve(
                prefs.getInt(editingMode.curveKey, Prefs.CURVE_TYPE_DEFAULT));
        binding.seekMinAbs.setProgress(min);
        binding.seekMaxAbs.setProgress(max);
        binding.radioCurveType.check(curveRadioId(curve));
    }

    /** 切换到目标编辑模式（由选中芯片决定）：载入该模式的值；蓝牙A/B 同时切换生效模式。 */
    private void switchEditingTab(VolumeMode target) {
        if (suppressListeners) {
            // 载入/初始阶段（check 触发）：仅切换 editingMode，不回写、不重复载入。
            editingMode = target;
            return;
        }
        // 蓝牙A/蓝牙B 芯片即「生效模式」；有线耳机芯片不改变生效模式。
        showingDefault = false;
        binding.groupRangeEditors.setVisibility(View.VISIBLE);
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
        // 生效模式随标签切换而变：仅暂存偏好（改动只在「保存修改」后才推送系统）。
        persistToPrefs(false);
        updateTvPending();
    }

    /**
     * 选中「默认」标签：只读展示系统默认音量信息。隐藏曲线/最小/最大编辑控件，仅在
     * {@code tvSummary} 显示说明；不调 {@code btMode}、不落盘、不改动各模式已保存的范围。
     */
    private void showDefaultTab() {
        if (suppressListeners) {
            // 程序化 check 触发（初始/恢复阶段）：不切换到只读展示。
            return;
        }
        showingDefault = true;
        binding.groupRangeEditors.setVisibility(View.GONE);
        persistToPrefs(false);   // 记录「默认」选择（工作副本），供未保存提示与下次打开记忆
        updatePreview();
        updateTvPending();
    }

    /** 芯片 id → 编辑模式。 */
    private VolumeMode chipIdToMode(int id) {
        if (id == R.id.chipModeB) {
            return VolumeMode.SOFTWARE;
        }
        if (id == R.id.chipWired) {
            return VolumeMode.WIRED;
        }
        return VolumeMode.ABSOLUTE;
    }

    /** 编辑模式 → 芯片 id。 */
    private int modeToChipId(VolumeMode mode) {
        if (mode == VolumeMode.SOFTWARE) {
            return R.id.chipModeB;
        }
        if (mode == VolumeMode.WIRED) {
            return R.id.chipWired;
        }
        return R.id.chipModeA;
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

    /**
     * 音量键步进（段数）的默认/恢复目标：与媒体级数一致，未启用/恢复默认时都取手机
     * 默认音量级数（再 {@code clampKeySteps} 限制到 10~29），使两者默认值统一为原生级数。
     */
    private int defaultKeySteps() {
        return Prefs.clampKeySteps(systemDefaultSteps());
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

        // 「默认」只读标签：不重算自定义范围/曲线（编辑控件已隐藏）。曲线图以「横轴＝系统档位、
        // 纵轴＝音量百分比」展示 ROM 原生直通；映射表为直通恒等（第 i 次→round(i*100/nativeSteps)%）。
        if (showingDefault) {
            int nativeSteps = systemDefaultSteps();
            binding.tvSummary.setText(getString(R.string.default_range_info, nativeSteps));
            binding.curveChart.configureNativeVolume(nativeSteps);
            binding.tvRangeMapping.setText(Avrcp.buildNativePassthroughTable(
                    nativeSteps, computeTableColumns(nativeSteps, 100, /*percent*/ true)));
            return;
        }

        int minAbs = currentMinAbs();
        int maxAbs = currentMaxAbs();
        binding.tvMinAbs.setText(getString(R.string.label_min_abs_fmt, minAbs,
                Math.round(minAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME)));
        binding.tvMaxAbs.setText(getString(R.string.label_max_abs_fmt, maxAbs,
                Math.round(maxAbs * 100.0 / Prefs.AVRCP_MAX_VOLUME)));
        binding.tvRangeHint.setText(getString(R.string.range_hint));

        int curveType = currentCurveType();
        int segs = Prefs.clampKeySteps(keySteps);
        boolean useSystemIndex = editingMode.attenuatesInSystemServer();
        if (editingMode == VolumeMode.WIRED) {
            binding.tvSummary.setText(
                    Avrcp.buildWiredPreview(mediaSteps, minAbs, maxAbs, curveType, keySteps));
        } else {
            binding.tvSummary.setText(Avrcp.buildPreview(
                    mediaSteps, editingMode.modeId(), minAbs, maxAbs, curveType, keySteps));
        }
        // 映射表三模式共用同一构建器（模式B 与耳机模式走同一系统档位代码路径）。
        int valueMax = useSystemIndex ? mediaSteps : Prefs.AVRCP_MAX_VOLUME;
        binding.curveChart.configure(
                mediaSteps, minAbs, maxAbs, curveType, useSystemIndex, keySteps);
        binding.tvRangeMapping.setText(Avrcp.buildMappingTable(
                mediaSteps, useSystemIndex, minAbs, maxAbs, curveType, keySteps,
                computeTableColumns(segs, valueMax, /*percent*/ false)));
    }

    /** 依 TextView 实测宽度与等宽单元宽度，估算映射表每行可容纳的单元个数（<=0 表示交回自然换行）。 */
    private int computeTableColumns(int segs, int valueMax, boolean percent) {
        if (segs <= 0) {
            return 0;
        }
        int pressWidth = Math.max(2, String.valueOf(segs).length());
        int valWidth = Math.max(2, String.valueOf(valueMax).length());
        String cell = String.format(
                "%" + pressWidth + "d→%" + valWidth + "d" + (percent ? "%%" : "") + "  ",
                segs, valueMax);
        Paint paint = new Paint(binding.tvRangeMapping.getPaint());
        float cellPx = paint.measureText(cell);
        if (cellPx <= 0) {
            return 0;
        }
        int avail = binding.tvRangeMapping.getWidth();
        if (avail <= 0) {
            float density = getResources().getDisplayMetrics().density;
            avail = (int) (getResources().getDisplayMetrics().widthPixels - 72 * density);
        }
        return Math.max(1, (int) Math.floor(avail / cellPx));
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
                .putBoolean(Prefs.KEY_DEFAULT_MODE, showingDefault)
                .putInt(editingMode.minKey, currentMinAbs())
                .putInt(editingMode.maxKey, currentMaxAbs())
                .putInt(editingMode.curveKey, currentCurveType());
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
                prefs.getInt(Prefs.KEY_KEY_STEPS, defaultKeySteps()));
        int btMode = normalizeBtMode(prefs.getInt(Prefs.KEY_BT_MODE, Prefs.BT_MODE_ABSOLUTE));
        boolean defaultMode = prefs.getBoolean(Prefs.KEY_DEFAULT_MODE, false);
        return new VolumeConfig(enabled, mediaSteps, keySteps, btMode,
                readRangeFromPrefs(VolumeMode.ABSOLUTE),
                readRangeFromPrefs(VolumeMode.SOFTWARE),
                readRangeFromPrefs(VolumeMode.WIRED), defaultMode).toRaw();
    }

    /** 从 prefs 读取某模式的最小/最大/曲线三元组。 */
    private Range readRangeFromPrefs(VolumeMode mode) {
        int min = Prefs.clampAbs(prefs.getInt(mode.minKey, Prefs.ABS_VOLUME_MIN_DEFAULT));
        int max = Prefs.clampAbs(prefs.getInt(mode.maxKey, Prefs.ABS_VOLUME_MAX_DEFAULT));
        int curve = Prefs.clampCurve(prefs.getInt(mode.curveKey, Prefs.CURVE_TYPE_DEFAULT));
        return new Range(min, max, curve);
    }

    /** 界面任一设置变更：仅暂存到 Preferences 并刷新预览/未保存提示；不写系统（推送由于「保存修改」触发）。 */
    private void onConfigChanged() {
        if (suppressListeners) {
            return;
        }
        persistToPrefs(false);
        updatePreview();
        updateTvPending();
    }

    /** 待写入系统的配置与已生效基线不一致（存在未保存修改）。 */
    private boolean hasUnsavedChanges() {
        return !buildPendingConfig().toRaw().equals(lastSavedRaw);
    }

    /** {@code tvPending}：有未保存修改时提示点保存，否则显示 renderStatus 的重启待生效文案。 */
    private void updateTvPending() {
        if (hasUnsavedChanges()) {
            binding.tvPending.setText(R.string.pending_unsaved);
        } else {
            binding.tvPending.setText(pendingStatusText);
        }
    }

    /**
     * 构造当前待保存配置：直接取暂存 prefs（已与控件同步，含「默认」标签写回的
     * {@code defaultMode}）。选中「默认」时不再改启用开关，仅由 {@code defaultMode=true} 生效直通。
     */
    private VolumeConfig buildPendingConfig() {
        VolumeConfig c = VolumeConfig.fromRaw(currentConfigString());
        return c != null ? c : buildDefaultConfig();
    }

    /** 系统默认配置：模块关闭、媒体/键步进取原生级数、模式A、三种范围各自默认。 */
    private VolumeConfig buildDefaultConfig() {
        Range def = new Range(Prefs.ABS_VOLUME_MIN_DEFAULT,
                Prefs.ABS_VOLUME_MAX_DEFAULT, Prefs.CURVE_TYPE_DEFAULT);
        return new VolumeConfig(false, systemDefaultSteps(), defaultKeySteps(),
                Prefs.BT_MODE_ABSOLUTE, def, def, def, false);
    }

    /**
     * 把当前界面配置写入系统设置（Settings.Global），供 Hook 端实时读取；
     * 同时记录到模块日志，便于在「查看模块日志」中核对。
     *
     * @param onSaved 校验成功后的后续动作（重启按钮使用）；null 表示自动保存（失败时短提示）
     */
    private void saveToSystem(Runnable onSaved) {
        persistToPrefs(true);
        final String configString = currentConfigString();
        final boolean softwareMode = currentBtMode() == Prefs.BT_MODE_SOFTWARE;
        final boolean enabled = binding.switchEnable.isChecked();
        // 生效开关：启用且非默认直通；默认模式下即使开关开着，也不改写 boot 属性/AVRCP 抑制。
        final boolean active = enabled && !showingDefault;
        final int mediaSteps = currentMediaSteps();
        try {
            executor.execute(() -> {
                ShellResult putResult = Shell.putGlobalConfig(Prefs.GLOBAL_KEY, configString);
                // 配置镜像备份：Hook 端可在 Settings.Global 读取失败时直读该文件（独立通道兑底）
                Shell.writeGlobalMirror(configString);
                // post-fs-data 开机脚本：开机最早阶段直接设置档位属性（不依赖任何 Hook 的终极保险）
                Shell.writeBootScript(active, mediaSteps);
                // 补充写入系统键：部分 ROM 的蓝牙栈会读取该开发者选项键，Hook 未生效时作为兑底
                Shell.putGlobalConfig(Shell.SETTING_AV_DISABLE,
                        (active && softwareMode) ? "1" : "0");
                boolean verified = false;
                if (putResult.isSuccess()) {
                    String readBack = Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output;
                    verified = readBack != null && readBack.contains(configString);
                }
                Shell.appendSysLog("push config " + configString + " saved=" + verified);
                final boolean ok = verified;
                mainHandler.post(() -> {
                    if (!ok) {
                        Toast.makeText(this, onSaved != null
                                        ? R.string.toast_push_fail : R.string.toast_auto_save_fail,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    lastSavedRaw = configString;
                    updateTvPending();
                    if (onSaved != null) {
                        onSaved.run();
                    }
                });
            });
        } catch (RuntimeException ignored) {
            // 页面已销毁、线程池已关闭：记录后忽略（不影响已保存配置）
            Log.i(LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
        }
    }

    // ==================== 保存修改（显式生效门控） ====================

    /**
     * 执行「保存修改」：与已生效配置比对后弹确认框列出改动，确认才写入 prefs 并推送系统；
     * 若需重启且包含系统框架，则只重启系统框架（蓝牙进程随框架重启）。
     *
     * @param forceDefault true=「恢复系统默认」完整重置；false=按当前标签保存（选中「默认」
     *        时仅置 defaultMode=true，不改启用开关、保留其余设置，并停留在默认标签）。
     */
    private void runSave(boolean forceDefault) {
        final boolean keepDefaultTab = showingDefault && !forceDefault;
        final VolumeConfig pending = forceDefault ? buildDefaultConfig() : buildPendingConfig();
        final VolumeConfig base = VolumeConfig.fromRaw(lastSavedRaw);
        if (base != null && base.toRaw().equals(pending.toRaw())) {
            Toast.makeText(this, R.string.dlg_save_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> changes = describeChanges(base, pending);
        final boolean modeOrRangeChanged = base == null
                || pending.btMode != base.btMode
                || !pending.absolute.equals(base.absolute)
                || !pending.software.equals(base.software)
                || !pending.wired.equals(base.wired);
        // 真正影响 Hook 的是 remapActive()（启用 且 非默认直通）；启用/默认切换都可能使其翻转。
        final boolean activeToggled = base == null || pending.remapActive() != base.remapActive();
        final boolean needsSystem = activeToggled
                || (base != null && pending.mediaSteps != base.mediaSteps);
        final boolean needsBt = activeToggled || modeOrRangeChanged;
        // 需重启的组件含系统框架时只重启系统框架（蓝牙进程随之重启），不再单独重启蓝牙。
        final boolean restartSystem = needsSystem;
        final boolean restartBt = !needsSystem && needsBt;

        StringBuilder sb = new StringBuilder();
        if (changes.isEmpty()) {
            sb.append(getString(R.string.dlg_save_first));
        } else {
            for (String line : changes) {
                sb.append("• ").append(line).append('\n');
            }
        }
        if (keepDefaultTab) {
            sb.append(getString(R.string.save_default_note));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_save_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.btn_save,
                        (d, w) -> commitSave(pending, keepDefaultTab, restartBt, restartSystem))
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    /** 确认保存：把待写配置落到 prefs 并同步界面（默认标签保存后停留该标签），再推送系统。 */
    private void commitSave(VolumeConfig pending, boolean keepDefaultTab,
            boolean restartBt, boolean restartSystem) {
        applyConfigToPrefs(pending);
        if (keepDefaultTab) {
            // 选中「默认」保存：不动启用开关、不改其余设置，仅记录默认模式并停留在默认标签。
            lastSavedRaw = pending.toRaw();
            updatePreview();
            updateTvPending();
        } else {
            loadConfigIntoUi();   // 完整重置/常规保存：回读界面（退出默认标签）
            updatePreview();
        }
        saveToSystem(() -> promptRestart(restartBt, restartSystem));
    }

    /** 把一个完整配置写入 prefs（供保存/默认重置后统一回读界面）。 */
    private void applyConfigToPrefs(VolumeConfig c) {
        prefs.edit()
                .putBoolean(Prefs.KEY_ENABLED, c.enabled)
                .putInt(Prefs.KEY_MEDIA_STEPS, c.mediaSteps)
                .putInt(Prefs.KEY_KEY_STEPS, c.keySteps)
                .putInt(Prefs.KEY_BT_MODE, c.btMode)
                .putBoolean(Prefs.KEY_DEFAULT_MODE, c.defaultMode)
                .putInt(VolumeMode.ABSOLUTE.minKey, c.absolute.min)
                .putInt(VolumeMode.ABSOLUTE.maxKey, c.absolute.max)
                .putInt(VolumeMode.ABSOLUTE.curveKey, c.absolute.curve)
                .putInt(VolumeMode.SOFTWARE.minKey, c.software.min)
                .putInt(VolumeMode.SOFTWARE.maxKey, c.software.max)
                .putInt(VolumeMode.SOFTWARE.curveKey, c.software.curve)
                .putInt(VolumeMode.WIRED.minKey, c.wired.min)
                .putInt(VolumeMode.WIRED.maxKey, c.wired.max)
                .putInt(VolumeMode.WIRED.curveKey, c.wired.curve)
                .commit();
    }

    /** 列出待保存配置相对已生效配置的改动（可读条目）；{@code base} 为 null 时返回空。 */
    private List<String> describeChanges(VolumeConfig base, VolumeConfig pending) {
        List<String> out = new ArrayList<>();
        if (base == null) {
            return out;
        }
        if (base.enabled != pending.enabled) {
            out.add(getString(R.string.change_enable,
                    enabledLabel(base.enabled), enabledLabel(pending.enabled)));
        }
        if (base.defaultMode != pending.defaultMode) {
            out.add(getString(R.string.change_default_mode,
                    defaultModeLabel(base.defaultMode), defaultModeLabel(pending.defaultMode)));
        }
        if (base.mediaSteps != pending.mediaSteps) {
            out.add(getString(R.string.change_media_steps, base.mediaSteps, pending.mediaSteps));
        }
        if (base.keySteps != pending.keySteps) {
            out.add(getString(R.string.change_key_steps, base.keySteps, pending.keySteps));
        }
        if (base.btMode != pending.btMode) {
            out.add(getString(R.string.change_bt_mode,
                    modeLabel(base.btMode), modeLabel(pending.btMode)));
        }
        addRangeChange(out, getString(R.string.mode_name_absolute), base.absolute, pending.absolute);
        addRangeChange(out, getString(R.string.mode_name_software), base.software, pending.software);
        addRangeChange(out, getString(R.string.mode_name_wired), base.wired, pending.wired);
        return out;
    }

    private void addRangeChange(List<String> out, String name, Range from, Range to) {
        if (from.equals(to)) {
            return;
        }
        out.add(getString(R.string.change_range, name, rangeLabel(from), rangeLabel(to)));
    }

    private String rangeLabel(Range r) {
        return r.min + "~" + r.max + "/" + Avrcp.curveLabel(r.curve);
    }

    private String enabledLabel(boolean enabled) {
        return getString(enabled ? R.string.enable_on : R.string.enable_off);
    }

    private String defaultModeLabel(boolean defaultMode) {
        return getString(defaultMode
                ? R.string.mode_name_default : R.string.mode_name_custom);
    }

    private String modeLabel(int mode) {
        return getString(mode == Prefs.BT_MODE_SOFTWARE
                ? R.string.mode_name_software : R.string.mode_name_absolute);
    }

    /** 保存成功后，如需要则询问是否立即重启对应组件。 */
    private void promptRestart(boolean needsBt, boolean needsSystem) {
        refreshStatus();
        if (!needsBt && !needsSystem) {
            Toast.makeText(this, R.string.toast_save_done, Toast.LENGTH_SHORT).show();
            return;
        }
        String what = (needsBt && needsSystem)
                ? getString(R.string.restart_both)
                : needsBt ? getString(R.string.restart_bt) : getString(R.string.restart_system);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_save_done_title)
                .setMessage(getString(R.string.dlg_restart_now_msg, what))
                .setPositiveButton(R.string.dlg_restart_now,
                        (d, w) -> performRestart(needsBt, needsSystem))
                .setNegativeButton(R.string.dlg_restart_later, null)
                .show();
    }

    /** 按需重启蓝牙与/或系统框架（不重复写配置，配置已在保存时推送）。 */
    private void performRestart(boolean needsBt, boolean needsSystem) {
        Toast.makeText(this, R.string.toast_restarting, Toast.LENGTH_LONG).show();
        try {
            executor.execute(() -> {
                if (needsBt) {
                    Shell.restartBluetooth();
                }
                if (needsSystem) {
                    Shell.restartSystemServer();
                }
            });
        } catch (RuntimeException ignored) {
            // 页面已销毁、线程池已关闭：记录后忽略（配置已保存）
            Log.i(LOG_TAG, "restart task rejected", ignored);
        }
    }

    /** 保存配置并重启蓝牙（音量模式 / 音量范围修改后的生效方式，不重启系统框架）。 */
    private void restartBluetoothNow() {
        Toast.makeText(this, R.string.toast_bt_restarting, Toast.LENGTH_LONG).show();
        saveToSystem(() -> {
            try {
                executor.execute(() -> {
                    ShellResult result = Shell.restartBluetooth();
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
