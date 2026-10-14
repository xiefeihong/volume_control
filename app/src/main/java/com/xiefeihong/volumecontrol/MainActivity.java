package com.xiefeihong.volumecontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.tabs.TabLayout;
import com.xiefeihong.volumecontrol.databinding.ActivityMainBinding;

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
 *
 * <p>D2 职责拆分：本类作为 Activity 宿主，保留共享状态（单一权威）、生命周期与控件监听器接线，
 * 具体职责下沉到三个同包协作者——{@link ConfigEditor}（编辑/预览渲染）、
 * {@link StatusProbe}（状态检测/日志/设备探测）、{@link SaveFlow}（保存/重启/改动描述）。
 * 下方同名方法是一层薄门面：监听器与协作者都经此调用，跨簇调用统一走宿主门面避免重复定义。</p>
 */
public class MainActivity extends AppCompatActivity {

    ActivityMainBinding binding;
    SharedPreferences prefs;
    AudioManager audioManager;

    /**
     * 上一次成功写入系统（Settings.Global）的生效配置 XML，作为「未保存修改」比较基线。
     */
    String lastSavedXml = "";

    /** {@code measureCellColumns} 复用的文本测量 Paint（避免每次测量新建对象）。 */
    final Paint measurePaint = new Paint();

    /**
     * dB 模式映射表右列用 {@code "%6.1f"} 格式；{@code computeTableColumns} 靠 valueMax 的位数
     * 估算列宽，传此 6 位哨兵值使估宽贴近 dB 列实际宽度（其数值不参与 dB 表渲染）。
     */
    static final int DB_COLUMN_WIDTH_SENTINEL = 999999;

    /** {@code tvPending} 在「已保存」状态下显示的（重启待生效）文案，由 renderStatus 写入。 */
    String pendingStatusText = "";

    /** 串行后台线程：所有 root / 系统查询操作都在此执行。 */
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** App 侧日志 tag：catch 兜底记录，保证异常可见。 */
    static final String LOG_TAG = "VolumeControlUI";

    /**
     * 程序化把值载入共享控件期间置为 true，抑制所有监听回调，避免 setProgress/check 触发
     * onConfigChanged 把载入中的中间值误写回模型。
     */
    boolean suppressListeners = false;

    /** 权威配置模型：界面编辑即时写入此处，持久化/推送都以此为准。 */
    VolumeConfig model;

    /** 当前编辑的设备维度（对应顶部选项卡）。 */
    OutputDevice editingDevice = OutputDevice.SPEAKER;

    /** 当前滑条/曲线所代表的模式（ABSOLUTE 编辑 absolute 范围，SOFTWARE 编辑 software 范围）。 */
    VolumeMode editingMode = VolumeMode.SOFTWARE;

    /** 当前设备是否处于「默认直通」（true 时隐藏可编辑控件、只读展示该设备 ROM 原生曲线）。 */
    boolean showingDefault = false;

    /** 默认预览纵轴换算口径（感知响度 / dB 线性），Spinner 切换。 */
    NativeVolumeCurve.Mode selectedNativeMode = NativeVolumeCurve.Mode.PERCEPTUAL;

    /** Spinner 选项位置 → 口径（与 native_mode_options 数组同序：感知响度、dB 线性）。 */
    static final NativeVolumeCurve.Mode[] NATIVE_MODES = {
            NativeVolumeCurve.Mode.PERCEPTUAL, NativeVolumeCurve.Mode.DB_LINEAR,
    };

    /** 默认预览横轴口径（档位 / 百分比），Spinner 切换。 */
    NativeAxis selectedNativeAxis = NativeAxis.STEPS;

    /** 默认预览横轴：档位（用当前档位数 + 按键次数）或 百分比（用 XML 配置的档位百分比）。 */
    enum NativeAxis { STEPS, PERCENT }

    /** UI 设备标签显示顺序：蓝牙 → 有线耳机 → 外放（与 {@link OutputDevice} 序数解耦）。 */
    static final OutputDevice[] DEVICE_BY_TAB = {
            OutputDevice.BT, OutputDevice.WIRED, OutputDevice.SPEAKER,
    };

    // ==================== 职责协作者（D2） ====================

    private ConfigEditor configEditor;
    private StatusProbe statusProbe;
    private SaveFlow saveFlow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = Prefs.get(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        configEditor = new ConfigEditor(this);
        statusProbe = new StatusProbe(this);
        saveFlow = new SaveFlow(this);
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

    // ==================== 门面：委托 ConfigEditor ====================

    void loadConfigIntoUi() {
        configEditor.loadConfigIntoUi();
    }

    void loadConfigIntoUi(boolean detectDevice) {
        configEditor.loadConfigIntoUi(detectDevice);
    }

    private void onDeviceTabSelected(int position) {
        configEditor.onDeviceTabSelected(position);
    }

    private int deviceTabTitle(OutputDevice d) {
        return configEditor.deviceTabTitle(d);
    }

    private void onEnableToggled(boolean enabled) {
        configEditor.onEnableToggled(enabled);
    }

    private void switchEditingTab(VolumeMode target) {
        configEditor.switchEditingTab(target);
    }

    private void showDefaultTab() {
        configEditor.showDefaultTab();
    }

    private VolumeMode chipIdToMode(int id) {
        return configEditor.chipIdToMode(id);
    }

    private int modeSpinnerIndex(NativeVolumeCurve.Mode mode) {
        return configEditor.modeSpinnerIndex(mode);
    }

    int currentMediaSteps() {
        return configEditor.currentMediaSteps();
    }

    int currentKeySteps() {
        return configEditor.currentKeySteps();
    }

    private void onConfigChanged() {
        configEditor.onConfigChanged();
    }

    private void onConfigChanged(boolean writeRange) {
        configEditor.onConfigChanged(writeRange);
    }

    void updatePreview() {
        configEditor.updatePreview();
    }

    void persistToPrefs() {
        configEditor.persistToPrefs();
    }

    void applyConfigToPrefs(VolumeConfig c) {
        configEditor.applyConfigToPrefs(c);
    }

    void writeUiIntoModel(boolean writeRange) {
        configEditor.writeUiIntoModel(writeRange);
    }

    VolumeConfig buildDefaultConfig() {
        return configEditor.buildDefaultConfig();
    }

    int systemDefaultSteps() {
        return configEditor.systemDefaultSteps();
    }

    int targetPhysicalSteps(VolumeConfig cfg) {
        return configEditor.targetPhysicalSteps(cfg);
    }

    void updateTvPending() {
        configEditor.updateTvPending();
    }

    int defaultStepsSourceLabel() {
        return configEditor.defaultStepsSourceLabel();
    }

    // ==================== 门面：委托 StatusProbe ====================

    void refreshStatus() {
        statusProbe.refreshStatus();
    }

    private void showModuleLogs() {
        statusProbe.showModuleLogs();
    }

    OutputDevice detectCurrentDevice() {
        return statusProbe.detectCurrentDevice();
    }

    int systemDefaultStepsRaw() {
        return statusProbe.systemDefaultStepsRaw();
    }

    int readStreamMaxSafe(int streamIndex) {
        return statusProbe.readStreamMaxSafe(streamIndex);
    }

    // ==================== 门面：委托 SaveFlow ====================

    private void runSave(boolean forceDefault) {
        saveFlow.runSave(forceDefault);
    }

    private void restartBluetoothNow() {
        saveFlow.restartBluetoothNow();
    }

    private void restartSystemServerNow() {
        saveFlow.restartSystemServerNow();
    }

    String modeLabelShort(int mode) {
        return saveFlow.modeLabelShort(mode);
    }

}
