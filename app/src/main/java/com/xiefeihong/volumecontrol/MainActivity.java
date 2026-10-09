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
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.tabs.TabLayout;
import com.xiefeihong.volumecontrol.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：按「外放 / 有线耳机 / 蓝牙」三设备各自独立配置音量模式与范围，外加全局
 * 启用开关、媒体档位数、音量键步进。
 *
 * <p>顶部设备选项卡是主维度：切换 {@link #editingDevice} 后，模式芯片（绝对/相对/默认）与
 * 共享的曲线/最小/最大滑条反映并编辑该设备在 {@link #model} 中的切片。绝对音量（模式A）为
 * 蓝牙专属，外放/有线只显示相对与默认。界面所有编辑先落到内存 {@link VolumeConfig} 对象，
 * 整体以 {@code toXml()} 暂存于单一 SharedPreferences 键；须点「保存修改」才推送到系统
 * （Settings.Global + 镜像文件 + boot 脚本），再按需重启蓝牙 / 系统框架使其生效。</p>
 *
 * <p>打开界面时按当前音频输出（{@link #detectCurrentDevice()}）定位选中对应设备标签（仅一次），
 * 之后编辑其它设备时不再因当前输出变化而自动切走。</p>
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private SharedPreferences prefs;
    private AudioManager audioManager;

    /**
     * 上一次成功写入系统（Settings.Global）的生效配置 XML，作为「未保存修改」比较基线。
     */
    private String lastSavedXml = "";

    /** {@code tvPending} 在「已保存」状态下显示的（重启待生效）文案，由 renderStatus 写入。 */
    private String pendingStatusText = "";

    /** 串行后台线程：所有 root / 系统查询操作都在此执行。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** App 侧日志 tag：catch 兜底记录，保证异常可见。 */
    private static final String LOG_TAG = "VolumeControlUI";

    /**
     * 程序化把值载入共享控件期间置为 true，抑制所有监听回调，避免 setProgress/check 触发
     * onConfigChanged 把载入中的中间值误写回模型。
     */
    private boolean suppressListeners = false;

    /** 权威配置模型：界面编辑即时写入此处，持久化/推送都以此为准。 */
    private VolumeConfig model;

    /** 当前编辑的设备维度（对应顶部选项卡）。 */
    private OutputDevice editingDevice = OutputDevice.SPEAKER;

    /** 当前滑条/曲线所代表的模式（ABSOLUTE 编辑 absolute 范围，SOFTWARE 编辑 software 范围）。 */
    private VolumeMode editingMode = VolumeMode.SOFTWARE;

    /** 当前设备是否处于「默认直通」（true 时隐藏可编辑控件、只读展示该设备 ROM 原生曲线）。 */
    private boolean showingDefault = false;

    /** 默认预览纵轴换算口径（感知响度 / dB 线性），Spinner 切换。 */
    private NativeVolumeCurve.Mode selectedNativeMode = NativeVolumeCurve.Mode.PERCEPTUAL;

    /** Spinner 选项位置 → 口径（与 native_mode_options 数组同序：感知响度、dB 线性）。 */
    private static final NativeVolumeCurve.Mode[] NATIVE_MODES = {
            NativeVolumeCurve.Mode.PERCEPTUAL, NativeVolumeCurve.Mode.DB_LINEAR,
    };

    /** 默认预览横轴口径（档位 / 百分比），Spinner 切换。 */
    private NativeAxis selectedNativeAxis = NativeAxis.STEPS;

    /** 默认预览横轴：档位（用当前档位数 + 按键次数）或 百分比（用 XML 配置的档位百分比）。 */
    private enum NativeAxis { STEPS, PERCENT }

    /** UI 设备标签显示顺序：蓝牙 → 有线耳机 → 外放（与 {@link OutputDevice} 序数解耦）。 */
    private static final OutputDevice[] DEVICE_BY_TAB = {
            OutputDevice.BT, OutputDevice.WIRED, OutputDevice.SPEAKER,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = Prefs.get(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setupListeners();
        loadConfigIntoUi();
        updatePreview();
        // 首次布局前 getWidth()==0，排版完成后按实测宽度重算一次每行个数。
        binding.getRoot().post(this::updatePreview);
        // 后台读取 ROM audio policy 媒体曲线（root），完成后可让默认设备以真实曲线重绘。
        executor.execute(() -> {
            NativeVolumeCurve.load();
            mainHandler.post(() -> {
                if (showingDefault) {
                    updatePreview();
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台仅刷新状态；设备标签只在 onCreate 定位一次，不随当前输出自动切走（req-5）。
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    // ==================== 初始化 ====================

    private void setupListeners() {
        binding.switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> onEnableToggled(isChecked));

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 音量范围最小/最大：拖动时禁止交叉——即将令 min>max 时把当前滑块停在对侧边界。
                if (fromUser && !suppressListeners) {
                    if (seekBar == binding.seekMinAbs && progress > binding.seekMaxAbs.getProgress()) {
                        seekBar.setProgress(binding.seekMaxAbs.getProgress());
                        return;
                    }
                    if (seekBar == binding.seekMaxAbs && progress < binding.seekMinAbs.getProgress()) {
                        seekBar.setProgress(binding.seekMinAbs.getProgress());
                        return;
                    }
                    // 档位 >= 音量键步进（req-6）：档位滑条不能向左拖到低于步进，回退到步进值。
                    if (seekBar == binding.seekMediaSteps && currentMediaSteps() < currentKeySteps()) {
                        seekBar.setProgress(currentKeySteps() - Prefs.MEDIA_STEPS_MIN);
                        return;
                    }
                    // 步进上调超过档位时，把档位滑条顶到步进值（保持档位>=步进）。
                    if (seekBar == binding.seekKeySteps && currentKeySteps() > currentMediaSteps()) {
                        binding.seekMediaSteps.setProgress(currentKeySteps() - Prefs.MEDIA_STEPS_MIN);
                    }
                }
                // 仅 min/max 滑条变更才回写设备范围；改档位/步进只同步 steps，避免把中立满量程范围压扁。
                boolean rangeSource = seekBar == binding.seekMinAbs || seekBar == binding.seekMaxAbs;
                onConfigChanged(rangeSource);
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

        // 模式芯片（单选）：切换当前设备查看/编辑哪个模式；「默认」芯片为只读标签。
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

        // 设备主维度选项卡：蓝牙 / 有线耳机 / 外放（显示顺序由 DEVICE_BY_TAB 决定）。
        for (OutputDevice d : DEVICE_BY_TAB) {
            binding.tabDevice.addTab(binding.tabDevice.newTab().setText(getString(deviceTabTitle(d))));
        }
        binding.tabDevice.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                onDeviceTabSelected(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // 默认预览纵轴口径切换（仅默认设备可见）。
        ArrayAdapter<CharSequence> modeAdapter = ArrayAdapter.createFromResource(this,
                R.array.native_mode_options, android.R.layout.simple_spinner_item);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerNativeMode.setAdapter(modeAdapter);
        binding.spinnerNativeMode.setSelection(modeSpinnerIndex(selectedNativeMode));
        binding.spinnerNativeMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedNativeMode = NATIVE_MODES[position];
                if (showingDefault) {
                    updatePreview();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 默认预览横轴口径切换（档位 / 百分比，仅默认设备可见）。
        ArrayAdapter<CharSequence> axisAdapter = ArrayAdapter.createFromResource(this,
                R.array.native_axis_options, android.R.layout.simple_spinner_item);
        axisAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerNativeAxis.setAdapter(axisAdapter);
        binding.spinnerNativeAxis.setSelection(selectedNativeAxis == NativeAxis.PERCENT ? 1 : 0);
        binding.spinnerNativeAxis.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedNativeAxis = position == 1 ? NativeAxis.PERCENT : NativeAxis.STEPS;
                if (showingDefault) {
                    updatePreview();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        binding.btnRefresh.setOnClickListener(v -> refreshStatus());
        binding.btnLogs.setOnClickListener(v -> showModuleLogs());
        binding.btnRestartBt.setOnClickListener(v -> restartBluetoothNow());
        binding.btnApplyRestart.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_restart_title)
                .setMessage(R.string.dlg_restart_msg)
                .setPositiveButton(R.string.dlg_ok, (dialog, which) -> restartSystemServerNow())
                .setNegativeButton(R.string.dlg_cancel, null)
                .show());
        binding.btnSave.setOnClickListener(v -> runSave(false));
        binding.btnReset.setOnClickListener(v -> runSave(true));
    }

    // ==================== 载入 / 设备切片 ====================

    /** 从 prefs 载入整份配置到内存模型，按当前输出选中设备标签，并同步全部共享控件。 */
    private void loadConfigIntoUi() {
        suppressListeners = true;
        model = loadModelFromPrefs();

        binding.switchEnable.setChecked(model.enabled);
        boolean enabled = model.enabled;
        int mediaToShow = enabled ? model.mediaSteps : systemDefaultSteps();
        int keyToShow = enabled ? model.keySteps : defaultKeySteps();
        binding.seekMediaSteps.setProgress(mediaToShow - Prefs.MEDIA_STEPS_MIN);
        binding.seekKeySteps.setProgress(keyToShow - Prefs.KEY_STEP_MIN);
        clampStepsFloor();
        applyStepsEditable(enabled);

        // 设备标签：selectTab 触发 onDeviceTabSelected 载入对应切片（suppress 保护下仅载入不落盘）。
        editingDevice = detectCurrentDevice();
        selectDeviceTab(editingDevice);
        loadDeviceSliceIntoUi();

        suppressListeners = false;
        lastSavedXml = model.toXml();
        updateTvPending();
    }

    /** 载入 {@link #editingDevice} 的模式芯片可见性 + 所选模式 + 范围到共享控件（调用方负责 suppress）。 */
    private void loadDeviceSliceIntoUi() {
        DeviceConfig dc = model.deviceFor(editingDevice);
        boolean allowAbs = editingDevice.supportsAbsolute();
        binding.chipModeA.setVisibility(allowAbs ? View.VISIBLE : View.GONE);
        int mode = clampModeForDevice(editingDevice, dc.mode);
        if (mode == Prefs.BT_MODE_DEFAULT) {
            showingDefault = true;
            editingMode = VolumeMode.SOFTWARE;   // 默认用系统档位口径编辑最小/最大
            binding.chipGroupRange.check(R.id.chipDefault);
            loadRangeIntoUi();
        } else {
            showingDefault = false;
            editingMode = (mode == Prefs.BT_MODE_ABSOLUTE && allowAbs)
                    ? VolumeMode.ABSOLUTE : VolumeMode.SOFTWARE;
            binding.chipGroupRange.check(modeToChipId(editingMode));
            loadRangeIntoUi();
        }
    }

    /** 当前编辑口径下 min/max 滑条的满量程值：模式A=127（AVRCP），模式B/默认=当前档位数。 */
    private int rangeValueMax() {
        boolean absolute = editingMode == VolumeMode.ABSOLUTE && editingDevice.supportsAbsolute();
        return absolute ? Prefs.AVRCP_MAX_VOLUME : currentMediaSteps();
    }

    /** 把 {@link #editingDevice} 在 {@link #editingMode} 下的范围载入滑条/曲线（调用方负责 suppress）。 */
    private void loadRangeIntoUi() {
        DeviceConfig dc = model.deviceFor(editingDevice);
        Range r = showingDefault ? dc.defaultRange
                : (editingMode == VolumeMode.ABSOLUTE) ? dc.absolute : dc.software;
        int valueMax = rangeValueMax();
        binding.seekMinAbs.setMax(valueMax);
        binding.seekMaxAbs.setMax(valueMax);
        binding.seekMinAbs.setProgress(Prefs.clampAbs(Math.min(valueMax, r.min)));
        binding.seekMaxAbs.setProgress(Prefs.clampAbs(Math.min(valueMax, r.max)));
        binding.radioCurveType.check(curveRadioId(r.curve));
    }

    /** 设备标签切换：更新 {@link #editingDevice} 并载入其切片（标签切换本身不落盘）。 */
    private void onDeviceTabSelected(int position) {
        OutputDevice d = DEVICE_BY_TAB[position];
        if (model == null) {
            editingDevice = d;
            return;
        }
        boolean outer = suppressListeners;
        suppressListeners = true;
        editingDevice = d;
        loadDeviceSliceIntoUi();
        suppressListeners = outer;
        updatePreview();
    }

    /** 程序化选中设备标签（触发 onDeviceTabSelected）。 */
    private void selectDeviceTab(OutputDevice d) {
        TabLayout.Tab tab = binding.tabDevice.getTabAt(tabIndexForDevice(d));
        if (tab != null) {
            tab.select();
        }
    }

    /** 设备在选项卡中的显示位置（按 {@link #DEVICE_BY_TAB} 顺序查找）。 */
    private int tabIndexForDevice(OutputDevice d) {
        for (int i = 0; i < DEVICE_BY_TAB.length; i++) {
            if (DEVICE_BY_TAB[i] == d) {
                return i;
            }
        }
        return DEVICE_BY_TAB.length - 1;
    }

    /** {@link OutputDevice} → 选项卡标题资源 id。 */
    private int deviceTabTitle(OutputDevice d) {
        switch (d) {
            case BT:
                return R.string.tab_bt;
            case WIRED:
                return R.string.tab_wired;
            case SPEAKER:
            default:
                return R.string.tab_speaker;
        }
    }

    /**
     * 启用开关切换：关闭时把档位/音量键步进滑条锁到系统默认并禁用（音量范围编辑区仍可用），
     * 开启后从模型恢复已存的档位/步进并重新启用（不丢配置）。
     */
    private void onEnableToggled(boolean enabled) {
        final boolean outer = suppressListeners;
        suppressListeners = true;
        int mediaSteps = enabled ? model.mediaSteps : systemDefaultSteps();
        int keySteps = enabled ? model.keySteps : defaultKeySteps();
        binding.seekMediaSteps.setProgress(mediaSteps - Prefs.MEDIA_STEPS_MIN);
        binding.seekKeySteps.setProgress(keySteps - Prefs.KEY_STEP_MIN);
        clampStepsFloor();
        applyStepsEditable(enabled);
        suppressListeners = outer;
        // 仅用户真实点击开关时落盘/刷新；loadConfigIntoUi 里程序化 setChecked 也会触发本方法，
        // 此刻切片尚未载入，若落盘会用中间态覆盖模型，故 suppress 期间跳过。
        if (!outer) {
            onConfigChanged(false);
        }
    }

    /** 关闭态仅锁定档位/音量键步进滑条到系统默认；音量范围(最小/最大/曲线)保持可编辑。 */
    private void applyStepsEditable(boolean enabled) {
        binding.seekMediaSteps.setEnabled(enabled);
        binding.seekKeySteps.setEnabled(enabled);
    }

    /** 切换到目标编辑模式（由选中模式芯片决定）：改该设备模式并载入对应范围。 */
    private void switchEditingTab(VolumeMode target) {
        if (suppressListeners) {
            editingMode = target;   // 载入/初始阶段（check 触发）：仅切换 editingMode，不回写。
            return;
        }
        showingDefault = false;
        binding.groupRangeEditors.setVisibility(View.VISIBLE);
        suppressListeners = true;
        editingMode = target;
        DeviceConfig cur = model.deviceFor(editingDevice);
        int newMode = clampModeForDevice(editingDevice, target.modeId());
        model = model.withDevice(editingDevice, cur.withMode(newMode));
        loadRangeIntoUi();
        suppressListeners = false;
        updatePreview();
        persistToPrefs(false);
        updateTvPending();
    }

    /** 选中「默认」芯片：把当前设备设为默认（可编辑最小/最大、保留窗内原生形状），隐藏曲线区。 */
    private void showDefaultTab() {
        if (suppressListeners) {
            return;
        }
        showingDefault = true;
        editingMode = VolumeMode.SOFTWARE;
        suppressListeners = true;
        DeviceConfig cur = model.deviceFor(editingDevice);
        model = model.withDevice(editingDevice, cur.withMode(Prefs.BT_MODE_DEFAULT));
        loadRangeIntoUi();
        suppressListeners = false;
        persistToPrefs(false);
        updatePreview();
        updateTvPending();
    }

    /** 芯片 id → 编辑模式。 */
    private VolumeMode chipIdToMode(int id) {
        if (id == R.id.chipModeB) {
            return VolumeMode.SOFTWARE;
        }
        if (id == R.id.chipDefault) {
            return VolumeMode.DEFAULT;
        }
        return VolumeMode.ABSOLUTE;
    }

    /** 编辑模式 → 芯片 id。 */
    private int modeToChipId(VolumeMode mode) {
        if (mode == VolumeMode.SOFTWARE) {
            return R.id.chipModeB;
        }
        if (mode == VolumeMode.DEFAULT) {
            return R.id.chipDefault;
        }
        return R.id.chipModeA;
    }

    /** 外放/有线不支持绝对音量：把非法的 ABSOLUTE 归一为 SOFTWARE，其余原样。 */
    private int clampModeForDevice(OutputDevice d, int mode) {
        if (!d.supportsAbsolute() && mode == Prefs.BT_MODE_ABSOLUTE) {
            return Prefs.BT_MODE_SOFTWARE;
        }
        return mode;
    }

    // ==================== 配置计算与预览 ====================

    /** 当前选择的媒体音量级数（10~100）。 */
    private int currentMediaSteps() {
        return Prefs.MEDIA_STEPS_MIN + binding.seekMediaSteps.getProgress();
    }

    /** 当前选择的音量键步进（按键段数 10~29）。 */
    private int currentKeySteps() {
        return Prefs.KEY_STEP_MIN + binding.seekKeySteps.getProgress();
    }

    /** 保证档位滑条不低于音量键步进（req-6 初始态自洽）：当前档位<步进时把档位顶到步进。 */
    private void clampStepsFloor() {
        if (currentMediaSteps() < currentKeySteps()) {
            binding.seekMediaSteps.setProgress(currentKeySteps() - Prefs.MEDIA_STEPS_MIN);
        }
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

    /**
     * 系统原生媒体档位数。来源优先级：已捕获的系统服务实测值 &gt; ROM 引擎表 XML
     * ({@code <indexMax>}) &gt; 内置默认 {@link Prefs#MEDIA_STEPS_DEFAULT}。
     */
    private int systemDefaultStepsRaw() {
        if (prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)) {
            return prefs.getInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, Prefs.MEDIA_STEPS_DEFAULT);
        }
        int xml = NativeVolumeCurve.nativeMusicIndexMax();
        return xml > 0 ? xml : Prefs.MEDIA_STEPS_DEFAULT;
    }

    /** 「默认档位数」取值来源的展示文案（与 {@link #systemDefaultStepsRaw()} 判定链一致）。 */
    private int defaultStepsSourceLabel() {
        if (prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)) {
            return R.string.src_steps_service;
        }
        if (NativeVolumeCurve.nativeMusicIndexMax() > 0) {
            return R.string.src_steps_xml;
        }
        return R.string.src_steps_builtin;
    }

    /** 用作滑块默认/恢复目标的值（限制在合法区间）。 */
    private int systemDefaultSteps() {
        return Prefs.clampMediaSteps(systemDefaultStepsRaw());
    }

    /**
     * 指定配置生效后系统实际物理媒体档位数：启用时=用户 {@code mediaSteps}，关闭时=系统默认。
     * 用于判定「保存后是否真的需要重启系统框架」——只有此值相对上次变化才需重启。
     */
    private int targetPhysicalSteps(VolumeConfig cfg) {
        if (cfg == null) {
            return systemDefaultSteps();
        }
        return cfg.enabled ? Prefs.clampMediaSteps(cfg.mediaSteps) : systemDefaultSteps();
    }

    /** 音量键步进（段数）的默认/恢复目标：等于媒体级数（再 clampKeySteps 限制到 10~29）。 */
    private int defaultKeySteps() {
        return Prefs.clampKeySteps(systemDefaultSteps());
    }

    /** 模式默认曲线：绝对音量用对数增强拉开低档间距；相对音量用线性区间。 */
    private int defaultCurveType(VolumeMode mode) {
        return mode == VolumeMode.SOFTWARE ? Prefs.CURVE_LINEAR : Prefs.CURVE_TYPE_DEFAULT;
    }

    /** 模式默认最小音量＝AVRCP 满量程 × 可闻下限比例。 */
    private int defaultMinAbs() {
        return Prefs.clampAbs((int) Math.round(
                Prefs.AVRCP_MAX_VOLUME * Avrcp.MIN_VOLUME_FLOOR_RATIO));
    }

    /** 读取媒体流当前生效的档位上限（读取失败返回 0）。 */
    private int readStreamMaxSafe(int streamIndex) {
        try {
            if (audioManager == null) {
                return 0;
            }
            return audioManager.getStreamMaxVolume(streamIndex);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void updatePreview() {
        binding.groupNativeSelectors.setVisibility(showingDefault ? View.VISIBLE : View.GONE);
        // 编辑区常显；默认模式隐藏曲线选择区（无映射曲线）。
        binding.groupRangeEditors.setVisibility(View.VISIBLE);
        int curveBlockVis = showingDefault ? View.GONE : View.VISIBLE;
        binding.labelCurve.setVisibility(curveBlockVis);
        binding.radioCurveType.setVisibility(curveBlockVis);
        binding.tvCurveHint.setVisibility(curveBlockVis);
        // min/max 滑条满量程随编辑口径变化（模式A=127，B/默认=当前档位数）。
        int sliderMax = rangeValueMax();
        if (binding.seekMinAbs.getMax() != sliderMax) {
            binding.seekMinAbs.setMax(sliderMax);
        }
        if (binding.seekMaxAbs.getMax() != sliderMax) {
            binding.seekMaxAbs.setMax(sliderMax);
        }
        int mediaSteps = currentMediaSteps();
        binding.tvMediaSteps.setText(getString(R.string.label_media_steps_fmt, mediaSteps));
        int keySteps = currentKeySteps();
        binding.tvKeySteps.setText(getString(R.string.label_key_steps_fmt,
                keySteps, Prefs.keyDelta(mediaSteps, keySteps)));

        // 默认：与 A/B 同范式，按当前 [min,max] 把窗内原生响度曲线重算（目标档位线性等分）。
        if (showingDefault) {
            NativeVolumeCurve.Device device =
                    NativeVolumeCurve.Device.values()[editingDevice.ordinal()];
            boolean showDb = selectedNativeMode == NativeVolumeCurve.Mode.DB_LINEAR;
            String devTab = getString(deviceTabLabel(device));
            int minS = Prefs.clampSystemStep(currentMinAbs(), mediaSteps);
            int maxS = Prefs.clampSystemStep(currentMaxAbs(), mediaSteps);
            binding.tvMinAbs.setText(getString(R.string.label_min_abs_fmt, minS,
                    Math.round(minS * 100.0 / mediaSteps)));
            binding.tvMaxAbs.setText(getString(R.string.label_max_abs_fmt, maxS,
                    Math.round(maxS * 100.0 / mediaSteps)));
            binding.tvRangeHint.setText(getString(R.string.range_hint));
            binding.tvSummary.setText(getString(R.string.default_range_info, systemDefaultSteps())
                    + "\n" + Avrcp.defaultKeyStepHint(mediaSteps, minS, maxS, keySteps));
            if (selectedNativeAxis == NativeAxis.PERCENT) {
                NativeVolumeCurve.Anchors a =
                        NativeVolumeCurve.anchorsFor(device, selectedNativeMode);
                int minPct = (int) Math.round(minS * 100.0 / mediaSteps);
                int maxPct = (int) Math.round(maxS * 100.0 / mediaSteps);
                if (maxPct < minPct) {
                    int tmp = minPct;
                    minPct = maxPct;
                    maxPct = tmp;
                }
                // 仅取窗口 [minPct,maxPct] 内的点位：窗口内 XML 锚点 + 两端点（保证覆盖窗口）。
                java.util.TreeSet<Integer> pts = new java.util.TreeSet<>();
                pts.add(minPct);
                pts.add(maxPct);
                for (int p : a.percent) {
                    if (p >= minPct && p <= maxPct) {
                        pts.add(p);
                    }
                }
                int[] wp = new int[pts.size()];
                float[] wg = new float[pts.size()];
                float[] wdb = new float[pts.size()];
                int k = 0;
                for (int p : pts) {
                    wp[k] = p;
                    wg[k] = a.gainByPercent[p];
                    wdb[k] = a.gainDbByPercent[p];
                    k++;
                }
                String caption = devTab + " · " + getString(sourceLabel(a.source))
                        + " · 纵轴" + getString(modeLabel(a.mode))
                        + " · 范围" + minPct + "%~" + maxPct + "%";
                // 曲线图：横轴窗口化到 [minPct,maxPct]（从设定最小值开始），仅取窗内原生增益。
                binding.curveChart.configureNativeCurve(minPct, maxPct, a.gainByPercent,
                        a.percent, true, caption);
                binding.tvRangeMapping.setText(Avrcp.buildNativePercentTable(wp, wg, wdb, showDb,
                        computePercentTableColumns(wp[wp.length - 1], showDb)));
                return;
            }
            NativeVolumeCurve.Curve curve =
                    NativeVolumeCurve.curveFor(device, mediaSteps, selectedNativeMode);
            float[] effGain = new float[mediaSteps + 1];
            float[] effDb = curve.gainDb != null ? new float[mediaSteps + 1] : null;
            for (int i = 0; i <= mediaSteps; i++) {
                int t = Avrcp.defaultRangeToIndex(i, mediaSteps, minS, maxS);
                effGain[i] = curve.gainPercent[t];
                if (effDb != null) {
                    effDb[i] = curve.gainDb[t];
                }
            }
            String caption = devTab + " · " + getString(sourceLabel(curve.source))
                    + " · 纵轴" + getString(modeLabel(curve.mode))
                    + " · 范围" + minS + "~" + maxS;
            binding.curveChart.configureNativeCurve(0, mediaSteps, effGain,
                    buildPressLevels(mediaSteps, keySteps), false, caption);
            int segs = Prefs.clampKeySteps(keySteps);
            binding.tvRangeMapping.setText(Avrcp.buildNativeStepsTable(
                    mediaSteps, effGain, effDb, showDb, keySteps,
                    computeTableColumns(segs, showDb ? 999999 : 100, !showDb)));
            return;
        }

        int minAbs = currentMinAbs();
        int maxAbs = currentMaxAbs();
        boolean useSystemIndex = editingMode.attenuatesInSystemServer();
        int valueMax = useSystemIndex ? mediaSteps : Prefs.AVRCP_MAX_VOLUME;
        binding.tvMinAbs.setText(getString(R.string.label_min_abs_fmt, minAbs,
                Math.round(minAbs * 100.0 / valueMax)));
        binding.tvMaxAbs.setText(getString(R.string.label_max_abs_fmt, maxAbs,
                Math.round(maxAbs * 100.0 / valueMax)));
        binding.tvRangeHint.setText(getString(R.string.range_hint));

        // 关闭「启用档位修改」时档位/步进滑条锁到系统默认，故按 mediaSteps/keySteps（=系统默认）
        // + 当前 A/B 的最小/最大/曲线渲染预览；音量范围编辑区仍保持可用。
        int curveType = currentCurveType();
        int segs = Prefs.clampKeySteps(keySteps);
        binding.tvSummary.setText(Avrcp.buildPreview(
                mediaSteps, editingMode.modeId(), minAbs, maxAbs, curveType, keySteps));
        binding.curveChart.configure(
                mediaSteps, minAbs, maxAbs, curveType, useSystemIndex, keySteps);
        binding.tvRangeMapping.setText(Avrcp.buildMappingTable(
                mediaSteps, useSystemIndex, minAbs, maxAbs, curveType, keySteps,
                computeTableColumns(segs, valueMax, /*percent*/ false)));
    }

    /** 构造音量键逐次按下的落点数组（含 0 档）：{@code [0, keyStepLevel(1..segs)]}，供默认曲线图圆点。 */
    private int[] buildPressLevels(int maxSteps, int keySteps) {
        int segs = Prefs.clampKeySteps(keySteps);
        int[] levels = new int[segs + 1];
        levels[0] = 0;
        for (int i = 1; i <= segs; i++) {
            levels[i] = Prefs.keyStepLevel(i, maxSteps, keySteps);
        }
        return levels;
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
        return measureCellColumns(cell);
    }

    /**
     * 百分比映射表专用列宽估算：左列为档位百分比（带 {@code %} 后缀），右列 dB（{@code %6.1f}）
     * 或百分比（{@code %3d%%}）。与 {@link Avrcp#buildNativePercentTable} 的实际单元格格式严格对齐，
     * 避免宽度低估导致每行最后一个数据换行。
     */
    private int computePercentTableColumns(int maxAnchorPct, boolean showDb) {
        int pctWidth = Math.max(2, String.valueOf(maxAnchorPct).length());
        String cell = showDb
                ? String.format(java.util.Locale.US, "%" + pctWidth + "d%%→%6.1f  ", maxAnchorPct, -1.0)
                : String.format("%" + pctWidth + "d%%→%3d%%  ", maxAnchorPct, 100);
        return measureCellColumns(cell);
    }

    /** 以等宽字体测量代表单元格 {@code cell} 的像素宽，返回每行可容纳的列数（<=0 交回自然换行）。 */
    private int measureCellColumns(String cell) {
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

    /** 「默认」预览设备 Tab 的显示文案资源 id。 */
    private int deviceTabLabel(NativeVolumeCurve.Device device) {
        switch (device) {
            case WIRED:
                return R.string.tab_wired;
            case BT:
                return R.string.tab_bt;
            case SPEAKER:
            default:
                return R.string.tab_speaker;
        }
    }

    /** 曲线数据来源（引擎表/策略表/AOSP）的显示文案资源 id。 */
    private int sourceLabel(NativeVolumeCurve.Source source) {
        switch (source) {
            case ENGINE:
                return R.string.src_engine;
            case POLICY:
                return R.string.src_policy;
            case AOSP:
            default:
                return R.string.src_aosp;
        }
    }

    /** 纵轴换算口径（dB线性/振幅/感知）的显示文案资源 id。 */
    private int modeLabel(NativeVolumeCurve.Mode mode) {
        switch (mode) {
            case AMPLITUDE:
                return R.string.mode_amp;
            case PERCEPTUAL:
                return R.string.mode_percept;
            case DB_LINEAR:
            default:
                return R.string.mode_db;
        }
    }

    /** 口径在 Spinner 选项数组中的位置（未命中回 0）。 */
    private int modeSpinnerIndex(NativeVolumeCurve.Mode mode) {
        for (int i = 0; i < NATIVE_MODES.length; i++) {
            if (NATIVE_MODES[i] == mode) {
                return i;
            }
        }
        return 0;
    }

    // ==================== 持久化 ====================

    /** 把内存模型整体序列化到单一 SharedPreferences 键（整份配置一次写入）。 */
    private void persistToPrefs(boolean synchronous) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(Prefs.KEY_CONFIG_XML, model.toXml());
        if (synchronous) {
            editor.commit();
        } else {
            editor.apply();
        }
    }

    /** 界面任一设置变更（含曲线/范围）：写回 steps + 当前设备范围。 */
    private void onConfigChanged() {
        onConfigChanged(true);
    }

    /**
     * 界面任一设置变更：把控件当前值写回模型、暂存 Preferences 并刷新预览/未保存提示。
     * {@code writeRange=false} 时仅同步档位/步进、不动当前设备 min/max（改档位不应把中立满量程范围压扁），
     * 随后按新档位数重载 min/max 滑条。
     */
    private void onConfigChanged(boolean writeRange) {
        if (suppressListeners) {
            return;
        }
        writeUiIntoModel(writeRange);
        persistToPrefs(false);
        if (!writeRange) {
            suppressListeners = true;
            loadRangeIntoUi();   // 档位变了：滑条满量程随新档位数重载，进度读回未改动的存储范围。
            suppressListeners = false;
        }
        updatePreview();
        updateTvPending();
    }

    /** 把共享控件当前状态写回 {@link #model}；{@code writeRange=false} 时仅同步启用/档位/步进。 */
    private void writeUiIntoModel(boolean writeRange) {
        boolean enabled = binding.switchEnable.isChecked();
        VolumeConfig m = model.withEnabled(enabled);
        if (enabled) {
            m = m.withSteps(currentMediaSteps(), currentKeySteps());
        }
        if (!writeRange) {
            model = m;
            return;
        }
        // A/B/默认 各写自己独立的范围：模式A→absolute（AVRCP 0~127）；模式B→software；默认→defaultRange（均系统档位单位）。
        DeviceConfig cur = m.deviceFor(editingDevice);
        boolean absolute = editingMode == VolumeMode.ABSOLUTE && editingDevice.supportsAbsolute();
        int valueMax = absolute ? Prefs.AVRCP_MAX_VOLUME : currentMediaSteps();
        int lo = absolute ? Prefs.clampAbs(currentMinAbs())
                : Prefs.clampSystemStep(currentMinAbs(), valueMax);
        int hi = absolute ? Prefs.clampAbs(currentMaxAbs())
                : Prefs.clampSystemStep(currentMaxAbs(), valueMax);
        Range r = new Range(lo, hi, Prefs.clampCurve(currentCurveType()));
        DeviceConfig upd = showingDefault
                ? new DeviceConfig(cur.mode, cur.absolute, cur.software, r)
                : absolute
                        ? new DeviceConfig(cur.mode, r, cur.software, cur.defaultRange)
                        : new DeviceConfig(cur.mode, cur.absolute, r, cur.defaultRange);
        m = m.withDevice(editingDevice, upd);
        model = m;
    }

    /** 从 prefs 载入模型；缺失/非法回落系统默认配置。 */
    private VolumeConfig loadModelFromPrefs() {
        VolumeConfig cfg = VolumeConfig.fromXml(prefs.getString(Prefs.KEY_CONFIG_XML, ""));
        return cfg != null ? cfg : buildDefaultConfig();
    }

    /** 系统默认配置：总开关关闭、各设备默认直通、档位数/步进取原生级数、A/B 范围各自默认。 */
    private VolumeConfig buildDefaultConfig() {
        int steps = systemDefaultSteps();
        return new VolumeConfig(false, steps, defaultKeySteps(),
                defaultDeviceConfig(), defaultDeviceConfig(), defaultDeviceConfig());
    }

    /** 一个默认设备配置：A 范围取 AVRCP 默认；B/默认 各持一套独立的满量程(0~MEDIA_STEPS_MAX)中立直通起点。 */
    private DeviceConfig defaultDeviceConfig() {
        Range a = new Range(defaultMinAbs(), Prefs.AVRCP_MAX_VOLUME,
                defaultCurveType(VolumeMode.ABSOLUTE));
        // max 用 MEDIA_STEPS_MAX（≥任何档位数）→ 模式B/默认 初值恒为直通；用户拖动才会收窄。
        Range b = new Range(0, Prefs.MEDIA_STEPS_MAX, defaultCurveType(VolumeMode.SOFTWARE));
        Range d = new Range(0, Prefs.MEDIA_STEPS_MAX, defaultCurveType(VolumeMode.SOFTWARE));
        return new DeviceConfig(Prefs.BT_MODE_DEFAULT, a, b, d);
    }

    /** 当前待保存配置（模型即时反映界面）。 */
    private VolumeConfig buildPendingConfig() {
        return model;
    }

    /** 把一个完整配置写入 prefs（供保存/默认重置后统一回读界面）。 */
    private void applyConfigToPrefs(VolumeConfig c) {
        prefs.edit().putString(Prefs.KEY_CONFIG_XML, c.toXml()).commit();
    }

    // ==================== 未保存提示 ====================

    /** 当前模型与已生效基线不一致（存在未保存修改）。 */
    private boolean hasUnsavedChanges() {
        return !model.toXml().equals(lastSavedXml);
    }

    /** {@code tvPending}：有未保存修改时提示点保存，否则显示 renderStatus 的重启待生效文案。 */
    private void updateTvPending() {
        if (hasUnsavedChanges()) {
            binding.tvPending.setText(R.string.pending_unsaved);
        } else {
            binding.tvPending.setText(pendingStatusText);
        }
    }

    // ==================== 写入系统 ====================

    /**
     * 把当前模型写入系统设置（Settings.Global）供 Hook 端实时读取，同时写镜像文件与 boot 脚本；
     * 记录到模块日志便于核对。
     *
     * @param onSaved 校验成功后的后续动作（重启按钮使用）；null 表示自动保存（失败时短提示）
     */
    private void saveToSystem(Runnable onSaved) {
        persistToPrefs(true);
        final String configXml = model.toXml();
        final boolean softwareMode = model.modeFor(OutputDevice.BT) == Prefs.BT_MODE_SOFTWARE;
        final boolean active = model.remapActive();
        final int mediaSteps = model.mediaSteps;
        try {
            executor.execute(() -> {
                ShellResult putResult = Shell.putGlobalConfig(Prefs.GLOBAL_KEY, configXml);
                Shell.writeGlobalMirror(configXml);
                Shell.writeBootScript(active, mediaSteps);
                Shell.putGlobalConfig(Shell.SETTING_AV_DISABLE, (active && softwareMode) ? "1" : "0");
                boolean verified = false;
                if (putResult.isSuccess()) {
                    String readBack = Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output;
                    verified = readBack != null && readBack.contains(configXml);
                }
                Shell.appendSysLog("push config saved=" + verified);
                final boolean ok = verified;
                mainHandler.post(() -> {
                    if (!ok) {
                        Toast.makeText(this, onSaved != null
                                        ? R.string.toast_push_fail : R.string.toast_auto_save_fail,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    lastSavedXml = configXml;
                    updateTvPending();
                    if (onSaved != null) {
                        onSaved.run();
                    }
                });
            });
        } catch (RuntimeException ignored) {
            Log.i(LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
        }
    }

    // ==================== 保存修改（显式生效门控） ====================

    /**
     * 执行「保存修改」：与已生效配置比对后弹确认框逐设备列出改动，确认才落到 prefs 并推送系统；
     * 若需重启且包含系统框架，则只重启系统框架（蓝牙进程随框架重启）。
     *
     * @param forceDefault true=「恢复系统默认」完整重置（关闭总开关、各设备默认直通）。
     */
    private void runSave(boolean forceDefault) {
        writeUiIntoModel(true);
        VolumeConfig rawPending = forceDefault ? buildDefaultConfig() : model;
        // 关闭态选中 绝对/相对(A/B) 且尚未获取系统默认档位、且用户未确认过：无法可靠调档 →
        // 先弹警告框给出三种处理方式，用户选定后再进入常规保存确认框（此时不再重复警告）。
        // 是否仍弹由专用标志 KEY_SYSTEM_DEFAULT_ACK 控制（与 KEY_SYSTEM_DEFAULT_STEPS 解耦）。
        final boolean blocked = !forceDefault && !rawPending.enabled
                && rawPending.anyDeviceNonDefault()
                && !prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)
                && !prefs.getBoolean(Prefs.KEY_SYSTEM_DEFAULT_ACK, false);
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
        final int realSteps = readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX);
        final boolean capturedRealDefault = realSteps >= 1 && realSteps <= 100;
        final int steps = capturedRealDefault ? Prefs.clampMediaSteps(realSteps) : systemDefaultSteps();
        final int key = Prefs.clampKeySteps(steps);
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_need_system_default_title)
                .setMessage(getString(R.string.save_need_system_default,
                        modeLabelShort(warnModeId(rawPending))))
                .setNeutralButton(R.string.resolve_enable,
                        (d, w) -> proceedSave(rawPending.withEnabled(true), false))
                .setNegativeButton(R.string.resolve_default_mode,
                        (d, w) -> proceedSave(rawPending.withAllDevicesDefault(), false))
                .setPositiveButton(R.string.resolve_default_steps, (d, w) -> {
                    // 用户主动选了「使用档位默认值」→置 ACK 不再弹；但仅读到可信系统真实档位才固化 KEY。
                    SharedPreferences.Editor ed = prefs.edit();
                    if (capturedRealDefault) {
                        ed.putInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, steps);
                    }
                    ed.putBoolean(Prefs.KEY_SYSTEM_DEFAULT_ACK, true).commit();
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
        final VolumeConfig base = VolumeConfig.fromXml(lastSavedXml);
        if (base != null && base.toXml().equals(pending.toXml())) {
            Toast.makeText(this, R.string.dlg_save_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> changes = describeChanges(base, pending);
        // 是否需重启系统框架：仅当「实际物理档位数」相对上次生效值发生变化。
        // 启用 ON 但档位数=系统默认（或启用 OFF）时物理档位数不变 → 不提示重启。
        final boolean needsSystem = base == null
                || targetPhysicalSteps(pending) != targetPhysicalSteps(base);
        // 是否需重启蓝牙：仅当蓝牙设备模式发生变化影响 AVRCP 绝对音量协商（需重连）。
        // 有线/外放的模式或范围改动经 Hook 每次实时读配置生效，无需重启任何组件。
        final boolean needsBt = base == null
                || base.bluetooth.mode != pending.bluetooth.mode;
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_save_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.btn_save_confirm,
                        (d, w) -> commitSave(pending, restartBt, restartSystem, fromReset))
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    /** 门控警告里的模式名：优先取当前编辑设备的非默认模式，否则取首个非默认设备模式。 */
    private int warnModeId(VolumeConfig cfg) {
        int m = cfg.modeFor(editingDevice);
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
        model = pending;
        applyConfigToPrefs(pending);
        loadConfigIntoUi();
        updatePreview();
        if (thenClearData) {
            saveToSystem(() -> promptClearData(restartBt, restartSystem));
        } else {
            saveToSystem(() -> promptRestart(restartBt, restartSystem));
        }
    }

    /** 恢复系统默认保存后追加询问：是否清除此应用写入的数据（配置与日志）。 */
    private void promptClearData(boolean restartBt, boolean restartSystem) {
        new AlertDialog.Builder(this)
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

    /** 清除此应用写入的所有数据：本地 SharedPreferences + 系统侧配置(键/镜像/boot 脚本) + 模块日志，确保无残留不可恢复。 */
    private void clearAppData() {
        prefs.edit().clear().commit();
        lastSavedXml = model.toXml();
        updateTvPending();
        try {
            executor.execute(() -> {
                Shell.clearWrittenConfig();
                Shell.clearModuleLogs();
            });
        } catch (RuntimeException ignored) {
            Log.i(LOG_TAG, "clear data task rejected", ignored);
        }
        Toast.makeText(this, R.string.toast_data_cleared, Toast.LENGTH_SHORT).show();
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
        if (base.mediaSteps != pending.mediaSteps) {
            out.add(getString(R.string.change_media_steps, base.mediaSteps, pending.mediaSteps));
        }
        if (base.keySteps != pending.keySteps) {
            out.add(getString(R.string.change_key_steps, base.keySteps, pending.keySteps));
        }
        appendDeviceChanges(out, getString(R.string.tab_bt), base.bluetooth, pending.bluetooth);
        appendDeviceChanges(out, getString(R.string.tab_wired), base.wired, pending.wired);
        appendDeviceChanges(out, getString(R.string.tab_speaker), base.speaker, pending.speaker);
        return out;
    }

    /** 追加单设备的模式改动 + A/B 范围改动条目（前缀设备名）。 */
    private void appendDeviceChanges(List<String> out, String name, DeviceConfig from, DeviceConfig to) {
        if (from.mode != to.mode) {
            out.add(getString(R.string.change_device_mode, name,
                    modeLabelShort(from.mode), modeLabelShort(to.mode)));
        }
        addRangeChange(out, name + "·" + getString(R.string.mode_short_absolute), from.absolute, to.absolute);
        addRangeChange(out, name + "·" + getString(R.string.mode_short_software), from.software, to.software);
        addRangeChange(out, name + "·" + getString(R.string.mode_name_default), from.defaultRange, to.defaultRange);
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

    /** 对话框用的简名：绝对→「绝对音量」、相对→「相对音量」、默认→「默认」。 */
    private String modeLabelShort(int mode) {
        if (mode == Prefs.BT_MODE_SOFTWARE) {
            return getString(R.string.mode_short_software);
        }
        if (mode == Prefs.BT_MODE_DEFAULT) {
            return getString(R.string.mode_name_default);
        }
        return getString(R.string.mode_short_absolute);
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
            Log.i(LOG_TAG, "restart task rejected", ignored);
        }
    }

    /** 保存配置并重启蓝牙（模式 / 音量范围修改后的生效方式，不重启系统框架）。 */
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
                Log.i(LOG_TAG, "post-save/restart task rejected (executor shutdown / destroyed)", ignored);
            }
        });
    }

    // ==================== 设备自动选中 ====================

    /** 按当前可用输出设备判定应聚焦的设备标签：蓝牙 &gt; 有线 &gt; 外放。 */
    private OutputDevice detectCurrentDevice() {
        if (audioManager == null) {
            return OutputDevice.SPEAKER;
        }
        try {
            boolean wired = false;
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
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

    // ==================== 状态检测 ====================

    private void refreshStatus() {
        binding.tvStatusRoot.setText(R.string.status_detecting);
        binding.tvStatusLsposed.setText(R.string.status_detecting);
        binding.tvStatusVolume.setText(R.string.status_detecting);
        binding.tvStatusModule.setText(R.string.status_detecting);
        binding.tvStatusBt.setText(R.string.status_detecting);
        binding.tvStatusAv.setText(R.string.status_detecting);

        executor.execute(() -> {
            boolean root = Shell.isRootAvailable();
            boolean lsposed = Shell.isLsposedPresent();

            // 应用数据被清除后首次打开：以系统配置键为准恢复界面，避免误覆盖已生效的配置
            boolean adopted = false;
            if (root && !prefs.contains(Prefs.KEY_CONFIG_XML)) {
                VolumeConfig globalConfig = VolumeConfig.fromXml(
                        Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output);
                if (globalConfig != null) {
                    prefs.edit().putString(Prefs.KEY_CONFIG_XML, globalConfig.toXml()).commit();
                    adopted = true;
                }
            }

            int actualMedia = readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX);
            // 首次捕获系统原生媒体档位数：仅当本地与系统侧都未启用覆盖时，读到的才是未被
            // 模块改写过的真实原生值（req-3）；否则（已推送生效配置）不固化，避免把覆盖值当默认。
            VolumeConfig stored = VolumeConfig.fromXml(prefs.getString(Prefs.KEY_CONFIG_XML, ""));
            boolean enabledNow = stored != null && stored.enabled;
            VolumeConfig pushed = VolumeConfig.fromXml(
                    Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output);
            boolean systemActive = pushed != null && pushed.remapActive();
            if (!enabledNow && !systemActive
                    && !prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)
                    && actualMedia >= 1 && actualMedia <= 100) {
                prefs.edit().putInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, actualMedia).commit();
            }
            String btSummary = buildBluetoothSummary();

            final boolean adoptedConfig = adopted;
            mainHandler.post(() -> {
                if (adoptedConfig) {
                    loadConfigIntoUi();
                    updatePreview();
                    Toast.makeText(this, R.string.toast_config_adopted, Toast.LENGTH_LONG).show();
                }
                renderStatus(root, lsposed, actualMedia, btSummary);
            });
        });
    }

    private void renderStatus(boolean root, boolean lsposed, int actualMedia,
                              String btSummary) {
        binding.tvStatusRoot.setText(root ? R.string.status_root_ok : R.string.status_root_fail);
        binding.tvStatusLsposed.setText(lsposed
                ? R.string.status_lsposed_ok : R.string.status_lsposed_fail);

        int btMode = model.modeFor(OutputDevice.BT);
        binding.tvStatusAv.setText(getString(R.string.status_btmode_fmt, modeLabelShort(btMode)));

        boolean enabled = binding.switchEnable.isChecked();
        int targetMedia = enabled ? currentMediaSteps() : actualMedia;

        binding.tvStatusVolume.setText(getString(R.string.status_volume_line,
                actualMedia, systemDefaultStepsRaw(), currentKeySteps(),
                getString(defaultStepsSourceLabel())));

        if (enabled) {
            if (actualMedia == targetMedia) {
                binding.tvStatusModule.setText(R.string.status_module_on);
            } else {
                binding.tvStatusModule.setText(R.string.status_module_pending);
            }
        } else if (actualMedia == systemDefaultStepsRaw()
                || actualMedia < Prefs.MEDIA_STEPS_MIN
                || actualMedia > Prefs.MEDIA_STEPS_MAX) {
            binding.tvStatusModule.setText(R.string.status_module_off);
        } else {
            binding.tvStatusModule.setText(R.string.status_module_off_pending);
        }

        if (btSummary == null) {
            binding.tvStatusBt.setText(R.string.status_bt_none);
        } else {
            binding.tvStatusBt.setText(getString(R.string.status_bt_fmt, btSummary));
        }

        boolean effective = actualMedia == targetMedia;
        pendingStatusText = effective
                ? getString(R.string.pending_no)
                : getString(R.string.pending_yes_fmt, actualMedia, targetMedia);
        updateTvPending();
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
