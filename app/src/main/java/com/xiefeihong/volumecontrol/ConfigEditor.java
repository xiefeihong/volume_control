package com.xiefeihong.volumecontrol;

import android.view.View;

import com.google.android.material.tabs.TabLayout;

import java.util.TreeSet;

/**
 * MainActivity 的配置编辑与预览渲染协作者（D2 职责拆分）。
 *
 * <p>持有设备/模式/范围切片编辑、共享控件↔模型互载、默认值推导与曲线/映射表预览渲染。
 * 所有可变状态仍集中在 {@link MainActivity}（单一权威），本类经 {@code host} 读写其包内字段；
 * 跨职责簇（{@link StatusProbe} 探测、{@link SaveFlow} 保存）经宿主门面回调，避免重复定义。
 * {@code suppressListeners} 抑制语义与原实现完全一致。</p>
 */
class ConfigEditor {

    private final MainActivity host;

    ConfigEditor(MainActivity host) {
        this.host = host;
    }

    // ==================== 载入 / 设备切片 ====================

    /** 从 prefs 载入整份配置到内存模型，按当前输出选中设备标签，并同步全部共享控件。 */
    void loadConfigIntoUi() {
        loadConfigIntoUi(true);
    }

    /**
     * 从 prefs 载入整份配置到内存模型并同步全部共享控件。
     *
     * @param detectDevice true=按当前音频输出定位设备标签（仅冷启动时）；
     *                     false=保留当前 {@link MainActivity#editingDevice}（保存后重载，不因当前输出而切走，req-3）。
     */
    void loadConfigIntoUi(boolean detectDevice) {
        host.suppressListeners = true;
        host.model = loadModelFromPrefs();

        host.binding.switchEnable.setChecked(host.model.enabled);
        boolean enabled = host.model.enabled;
        int mediaToShow = enabled ? host.model.mediaSteps : systemDefaultSteps();
        int keyToShow = enabled ? host.model.keySteps : defaultKeySteps();
        host.binding.seekMediaSteps.setProgress(mediaToShow - Prefs.MEDIA_STEPS_MIN);
        host.binding.seekKeySteps.setProgress(keyToShow - Prefs.KEY_STEP_MIN);
        clampStepsFloor();
        applyStepsEditable(enabled);

        // 设备标签：冷启动按当前输出定位；保存后保留当前编辑设备（req-3）。
        // selectTab 触发 onDeviceTabSelected 载入对应切片（suppress 保护下仅载入不落盘）。
        if (detectDevice) {
            host.editingDevice = host.detectCurrentDevice();
        }
        selectDeviceTab(host.editingDevice);
        loadDeviceSliceIntoUi();

        host.suppressListeners = false;
        host.lastSavedXml = host.model.toXml();
        updateTvPending();
    }

    /** 载入 {@link MainActivity#editingDevice} 的模式芯片可见性 + 所选模式 + 范围到共享控件（调用方负责 suppress）。 */
    private void loadDeviceSliceIntoUi() {
        DeviceConfig dc = host.model.deviceFor(host.editingDevice);
        boolean allowAbs = host.editingDevice.supportsAbsolute();
        host.binding.chipModeA.setVisibility(allowAbs ? View.VISIBLE : View.GONE);
        int mode = clampModeForDevice(host.editingDevice, dc.mode);
        if (mode == Prefs.BT_MODE_DEFAULT) {
            host.showingDefault = true;
            host.editingMode = VolumeMode.SOFTWARE;   // 默认用系统档位口径编辑最小/最大
            host.binding.chipGroupRange.check(R.id.chipDefault);
            loadRangeIntoUi();
        } else {
            host.showingDefault = false;
            host.editingMode = (mode == Prefs.BT_MODE_ABSOLUTE && allowAbs)
                    ? VolumeMode.ABSOLUTE : VolumeMode.SOFTWARE;
            host.binding.chipGroupRange.check(modeToChipId(host.editingMode));
            loadRangeIntoUi();
        }
    }

    /** 当前编辑口径下 min/max 滑条的满量程值：模式A=127（AVRCP），模式B/默认=当前档位数。 */
    private int rangeValueMax() {
        boolean absolute = host.editingMode == VolumeMode.ABSOLUTE && host.editingDevice.supportsAbsolute();
        return absolute ? Prefs.AVRCP_MAX_VOLUME : currentMediaSteps();
    }

    /** 把 {@link MainActivity#editingDevice} 在 {@link MainActivity#editingMode} 下的范围载入滑条/曲线（调用方负责 suppress）。 */
    private void loadRangeIntoUi() {
        DeviceConfig dc = host.model.deviceFor(host.editingDevice);
        Range r = host.showingDefault ? dc.defaultRange
                : (host.editingMode == VolumeMode.ABSOLUTE) ? dc.absolute : dc.software;
        int valueMax = rangeValueMax();
        host.binding.seekMinAbs.setMax(valueMax);
        host.binding.seekMaxAbs.setMax(valueMax);
        host.binding.seekMinAbs.setProgress(Prefs.clampAbs(Math.min(valueMax, r.min)));
        host.binding.seekMaxAbs.setProgress(Prefs.clampAbs(Math.min(valueMax, r.max)));
        host.binding.radioCurveType.check(curveRadioId(r.curve));
    }

    /** 设备标签切换：更新 {@link MainActivity#editingDevice} 并载入其切片（标签切换本身不落盘）。 */
    void onDeviceTabSelected(int position) {
        OutputDevice d = MainActivity.DEVICE_BY_TAB[position];
        if (host.model == null) {
            host.editingDevice = d;
            return;
        }
        boolean outer = host.suppressListeners;
        host.suppressListeners = true;
        host.editingDevice = d;
        loadDeviceSliceIntoUi();
        host.suppressListeners = outer;
        updatePreview();
    }

    /** 程序化选中设备标签（触发 onDeviceTabSelected）。 */
    private void selectDeviceTab(OutputDevice d) {
        TabLayout.Tab tab = host.binding.tabDevice.getTabAt(tabIndexForDevice(d));
        if (tab != null) {
            tab.select();
        }
    }

    /** 设备在选项卡中的显示位置（按 {@link MainActivity#DEVICE_BY_TAB} 顺序查找）。 */
    private int tabIndexForDevice(OutputDevice d) {
        for (int i = 0; i < MainActivity.DEVICE_BY_TAB.length; i++) {
            if (MainActivity.DEVICE_BY_TAB[i] == d) {
                return i;
            }
        }
        return MainActivity.DEVICE_BY_TAB.length - 1;
    }

    /** {@link OutputDevice} → 选项卡标题资源 id。 */
    int deviceTabTitle(OutputDevice d) {
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
    void onEnableToggled(boolean enabled) {
        final boolean outer = host.suppressListeners;
        host.suppressListeners = true;
        int mediaSteps = enabled ? host.model.mediaSteps : systemDefaultSteps();
        int keySteps = enabled ? host.model.keySteps : defaultKeySteps();
        host.binding.seekMediaSteps.setProgress(mediaSteps - Prefs.MEDIA_STEPS_MIN);
        host.binding.seekKeySteps.setProgress(keySteps - Prefs.KEY_STEP_MIN);
        clampStepsFloor();
        applyStepsEditable(enabled);
        host.suppressListeners = outer;
        // 仅用户真实点击开关时落盘/刷新；loadConfigIntoUi 里程序化 setChecked 也会触发本方法，
        // 此刻切片尚未载入，若落盘会用中间态覆盖模型，故 suppress 期间跳过。
        if (!outer) {
            onConfigChanged(false);
        }
    }

    /** 关闭态仅锁定档位/音量键步进滑条到系统默认；音量范围(最小/最大/曲线)保持可编辑。 */
    private void applyStepsEditable(boolean enabled) {
        host.binding.seekMediaSteps.setEnabled(enabled);
        host.binding.seekKeySteps.setEnabled(enabled);
    }

    /** 切换到目标编辑模式（由选中模式芯片决定）：改该设备模式并载入对应范围。 */
    void switchEditingTab(VolumeMode target) {
        if (host.suppressListeners) {
            host.editingMode = target;   // 载入/初始阶段（check 触发）：仅切换 editingMode，不回写。
            return;
        }
        host.showingDefault = false;
        host.binding.groupRangeEditors.setVisibility(View.VISIBLE);
        host.suppressListeners = true;
        host.editingMode = target;
        DeviceConfig cur = host.model.deviceFor(host.editingDevice);
        int newMode = clampModeForDevice(host.editingDevice, target.modeId());
        host.model = host.model.withDevice(host.editingDevice, cur.withMode(newMode));
        loadRangeIntoUi();
        host.suppressListeners = false;
        updatePreview();
        persistToPrefs();
        updateTvPending();
    }

    /** 选中「默认」芯片：把当前设备设为默认（可编辑最小/最大、保留窗内原生形状），隐藏曲线区。 */
    void showDefaultTab() {
        if (host.suppressListeners) {
            return;
        }
        host.showingDefault = true;
        host.editingMode = VolumeMode.SOFTWARE;
        host.suppressListeners = true;
        DeviceConfig cur = host.model.deviceFor(host.editingDevice);
        host.model = host.model.withDevice(host.editingDevice, cur.withMode(Prefs.BT_MODE_DEFAULT));
        loadRangeIntoUi();
        host.suppressListeners = false;
        persistToPrefs();
        updatePreview();
        updateTvPending();
    }

    /** 芯片 id → 编辑模式。 */
    VolumeMode chipIdToMode(int id) {
        if (id == R.id.chipModeA) {
            return VolumeMode.ABSOLUTE;
        }
        if (id == R.id.chipModeB) {
            return VolumeMode.SOFTWARE;
        }
        return VolumeMode.DEFAULT;
    }

    /** 编辑模式 → 芯片 id。 */
    private int modeToChipId(VolumeMode mode) {
        if (mode == VolumeMode.ABSOLUTE) {
            return R.id.chipModeA;
        }
        if (mode == VolumeMode.SOFTWARE) {
            return R.id.chipModeB;
        }
        return R.id.chipDefault;
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
    int currentMediaSteps() {
        return Prefs.MEDIA_STEPS_MIN + host.binding.seekMediaSteps.getProgress();
    }

    /** 当前选择的音量键步进（按键段数 10~29）。 */
    int currentKeySteps() {
        return Prefs.KEY_STEP_MIN + host.binding.seekKeySteps.getProgress();
    }

    /** 保证档位滑条不低于音量键步进（req-6 初始态自洽）：当前档位<步进时把档位顶到步进。 */
    private void clampStepsFloor() {
        if (currentMediaSteps() < currentKeySteps()) {
            host.binding.seekMediaSteps.setProgress(currentKeySteps() - Prefs.MEDIA_STEPS_MIN);
        }
    }

    /** 共享控件当前选中的映射曲线类型（恒代表 {@link MainActivity#editingMode}）。 */
    private int currentCurveType() {
        int id = host.binding.radioCurveType.getCheckedRadioButtonId();
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

    /** 共享控件当前音量范围下限（自动保证 下限 <= 上限；恒代表 {@link MainActivity#editingMode}）。 */
    private int currentMinAbs() {
        return Math.min(host.binding.seekMinAbs.getProgress(), host.binding.seekMaxAbs.getProgress());
    }

    /** 共享控件当前音量范围上限（自动保证 上限 >= 下限；恒代表 {@link MainActivity#editingMode}）。 */
    private int currentMaxAbs() {
        return Math.max(host.binding.seekMinAbs.getProgress(), host.binding.seekMaxAbs.getProgress());
    }

    /** 「默认档位数」取值来源的展示文案（与 {@link StatusProbe#systemDefaultStepsRaw()} 判定链一致）。 */
    int defaultStepsSourceLabel() {
        if (host.prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)) {
            return R.string.src_steps_service;
        }
        if (NativeVolumeCurve.nativeMusicIndexMax() > 0) {
            return R.string.src_steps_xml;
        }
        return R.string.src_steps_builtin;
    }

    /** 用作滑块默认/恢复目标的值（限制在合法区间）。 */
    int systemDefaultSteps() {
        return Prefs.clampMediaSteps(host.systemDefaultStepsRaw());
    }

    /**
     * 指定配置生效后系统实际物理媒体档位数：启用时=用户 {@code mediaSteps}，关闭时=系统默认。
     * 用于判定「保存后是否真的需要重启系统框架」——只有此值相对上次变化才需重启。
     */
    int targetPhysicalSteps(VolumeConfig cfg) {
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

    void updatePreview() {
        host.binding.groupNativeSelectors.setVisibility(host.showingDefault ? View.VISIBLE : View.GONE);
        // 编辑区常显；默认模式隐藏曲线选择区（无映射曲线）。
        host.binding.groupRangeEditors.setVisibility(View.VISIBLE);
        int curveBlockVis = host.showingDefault ? View.GONE : View.VISIBLE;
        host.binding.labelCurve.setVisibility(curveBlockVis);
        host.binding.radioCurveType.setVisibility(curveBlockVis);
        host.binding.tvCurveHint.setVisibility(curveBlockVis);
        // min/max 滑条满量程随编辑口径变化（模式A=127，B/默认=当前档位数）。
        int sliderMax = rangeValueMax();
        if (host.binding.seekMinAbs.getMax() != sliderMax) {
            host.binding.seekMinAbs.setMax(sliderMax);
        }
        if (host.binding.seekMaxAbs.getMax() != sliderMax) {
            host.binding.seekMaxAbs.setMax(sliderMax);
        }
        int mediaSteps = currentMediaSteps();
        host.binding.tvMediaSteps.setText(host.getString(R.string.label_media_steps_fmt, mediaSteps));
        int keySteps = currentKeySteps();
        host.binding.tvKeySteps.setText(host.getString(R.string.label_key_steps_fmt,
                keySteps, Prefs.keyDelta(mediaSteps, keySteps)));

        // 默认：与 A/B 同范式，按当前 [min,max] 把窗内原生响度曲线重算（目标档位线性等分）。
        if (host.showingDefault) {
            NativeVolumeCurve.Device device = toNativeDevice(host.editingDevice);
            boolean showDb = host.selectedNativeMode == NativeVolumeCurve.Mode.DB_LINEAR;
            String devTab = host.getString(deviceTabLabel(device));
            int minS = Prefs.clampSystemStep(currentMinAbs(), mediaSteps);
            int maxS = Prefs.clampSystemStep(currentMaxAbs(), mediaSteps);
            NativeVolumeCurve.Curve curve =
                    NativeVolumeCurve.curveFor(device, mediaSteps, host.selectedNativeMode);
            host.binding.tvMinAbs.setText(host.getString(R.string.label_min_abs_fmt, minS,
                    Math.round(minS * 100.0 / mediaSteps)));
            host.binding.tvMaxAbs.setText(host.getString(R.string.label_max_abs_fmt, maxS,
                    Math.round(maxS * 100.0 / mediaSteps)));
            host.binding.tvRangeHint.setText(host.getString(R.string.range_hint));
            host.binding.tvSummary.setText(Avrcp.buildDefaultPreview(mediaSteps, minS, maxS, keySteps,
                    curve.gainDb));
            if (host.selectedNativeAxis == MainActivity.NativeAxis.PERCENT) {
                NativeVolumeCurve.Anchors a =
                        NativeVolumeCurve.anchorsFor(device, host.selectedNativeMode);
                int minPct = (int) Math.round(minS * 100.0 / mediaSteps);
                int maxPct = (int) Math.round(maxS * 100.0 / mediaSteps);
                if (maxPct < minPct) {
                    int tmp = minPct;
                    minPct = maxPct;
                    maxPct = tmp;
                }
                // 仅取窗口 [minPct,maxPct] 内的点位：窗口内 XML 锚点 + 两端点（保证覆盖窗口）。
                TreeSet<Integer> pts = new TreeSet<>();
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
                String caption = devTab + " · " + host.getString(sourceLabel(a.source))
                        + " · 纵轴" + host.getString(modeLabel(a.mode))
                        + " · 范围" + minPct + "%~" + maxPct + "%";
                // 曲线图：横轴窗口化到 [minPct,maxPct]（从设定最小值开始），仅取窗内原生增益。
                // 纵轴：dB 口径时以整条曲线 floor/ceil dB（百分 0/100 处）标注。
                host.binding.curveChart.configureNativeCurve(minPct, maxPct, a.gainByPercent,
                        a.percent, true, showDb, a.gainDbByPercent[0],
                        a.gainDbByPercent[a.gainDbByPercent.length - 1], caption);
                host.binding.tvRangeMapping.setText(Avrcp.buildNativePercentTable(wp, wg, wdb, showDb,
                        computePercentTableColumns(wp[wp.length - 1], showDb)));
                return;
            }
            float[] effGain = new float[mediaSteps + 1];
            float[] effDb = curve.gainDb != null ? new float[mediaSteps + 1] : null;
            for (int i = 0; i <= mediaSteps; i++) {
                int t = Avrcp.defaultRangeToIndex(i, mediaSteps, minS, maxS);
                effGain[i] = curve.gainPercent[t];
                if (effDb != null) {
                    effDb[i] = curve.gainDb[t];
                }
            }
            String caption = devTab + " · " + host.getString(sourceLabel(curve.source))
                    + " · 纵轴" + host.getString(modeLabel(curve.mode))
                    + " · 范围" + minS + "~" + maxS;
            float dbFloor = curve.gainDb != null ? curve.gainDb[0] : 0f;
            float dbCeil = curve.gainDb != null ? curve.gainDb[mediaSteps] : 0f;
            host.binding.curveChart.configureNativeCurve(0, mediaSteps, effGain,
                    buildPressLevels(mediaSteps, keySteps), false,
                    showDb && curve.gainDb != null, dbFloor, dbCeil, caption);
            int segs = Prefs.clampKeySteps(keySteps);
            host.binding.tvRangeMapping.setText(Avrcp.buildNativeStepsTable(
                    mediaSteps, effGain, effDb, showDb, keySteps,
                    computeTableColumns(segs, showDb ? MainActivity.DB_COLUMN_WIDTH_SENTINEL : 100, !showDb)));
            return;
        }

        int minAbs = currentMinAbs();
        int maxAbs = currentMaxAbs();
        boolean useSystemIndex = host.editingMode.attenuatesInSystemServer();
        int valueMax = useSystemIndex ? mediaSteps : Prefs.AVRCP_MAX_VOLUME;
        host.binding.tvMinAbs.setText(host.getString(R.string.label_min_abs_fmt, minAbs,
                Math.round(minAbs * 100.0 / valueMax)));
        host.binding.tvMaxAbs.setText(host.getString(R.string.label_max_abs_fmt, maxAbs,
                Math.round(maxAbs * 100.0 / valueMax)));
        host.binding.tvRangeHint.setText(host.getString(R.string.range_hint));

        // 关闭「启用档位修改」时档位/步进滑条锁到系统默认，故按 mediaSteps/keySteps（=系统默认）
        // + 当前 A/B 的最小/最大/曲线渲染预览；音量范围编辑区仍保持可用。
        int curveType = currentCurveType();
        int segs = Prefs.clampKeySteps(keySteps);
        host.binding.tvSummary.setText(Avrcp.buildPreview(
                mediaSteps, host.editingMode.modeId(), minAbs, maxAbs, curveType, keySteps));
        host.binding.curveChart.configure(
                mediaSteps, minAbs, maxAbs, curveType, useSystemIndex, keySteps);
        host.binding.tvRangeMapping.setText(Avrcp.buildMappingTable(
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
        // 复用成员 Paint：仅拷贝当前文本绘制属性到复用实例，避免每次测量新建 Paint。
        host.measurePaint.set(host.binding.tvRangeMapping.getPaint());
        float cellPx = host.measurePaint.measureText(cell);
        if (cellPx <= 0) {
            return 0;
        }
        int avail = host.binding.tvRangeMapping.getWidth();
        if (avail <= 0) {
            float density = host.getResources().getDisplayMetrics().density;
            avail = (int) (host.getResources().getDisplayMetrics().widthPixels - 72 * density);
        }
        return Math.max(1, (int) Math.floor(avail / cellPx));
    }

    /**
     * {@link OutputDevice}（UI/配置维度）→ {@link NativeVolumeCurve.Device}（原生曲线维度）显式映射。
     * 两个枚举当前恰好同序（SPEAKER/WIRED/BT），但刻意用显式 {@code switch} 而非 {@code values()[ordinal()]}
     * 解耦，避免任一枚举调序时静默错位。
     */
    private static NativeVolumeCurve.Device toNativeDevice(OutputDevice device) {
        switch (device) {
            case WIRED:
                return NativeVolumeCurve.Device.WIRED;
            case BT:
                return NativeVolumeCurve.Device.BT;
            case SPEAKER:
            default:
                return NativeVolumeCurve.Device.SPEAKER;
        }
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
    int modeSpinnerIndex(NativeVolumeCurve.Mode mode) {
        for (int i = 0; i < MainActivity.NATIVE_MODES.length; i++) {
            if (MainActivity.NATIVE_MODES[i] == mode) {
                return i;
            }
        }
        return 0;
    }

    // ==================== 持久化 ====================

    /** 把内存模型整体序列化到单一 SharedPreferences 键（整份配置一次写入）。
     *  用 apply()：内存即时更新、落盘异步，同进程回读立即可见；Hook 端读 Settings.Global/
     *  镜像文件而非本 App 私有 prefs，无「写完立刻另一进程读」依赖，无需阻塞主线程同步落盘。 */
    void persistToPrefs() {
        host.prefs.edit().putString(Prefs.KEY_CONFIG_XML, host.model.toXml()).apply();
    }

    /** 界面任一设置变更（含曲线/范围）：写回 steps + 当前设备范围。 */
    void onConfigChanged() {
        onConfigChanged(true);
    }

    /**
     * 界面任一设置变更：把控件当前值写回模型、暂存 Preferences 并刷新预览/未保存提示。
     * {@code writeRange=false} 时仅同步档位/步进、不动当前设备 min/max（改档位不应把中立满量程范围压扁），
     * 随后按新档位数重载 min/max 滑条。
     */
    void onConfigChanged(boolean writeRange) {
        if (host.suppressListeners) {
            return;
        }
        writeUiIntoModel(writeRange);
        persistToPrefs();
        if (!writeRange) {
            host.suppressListeners = true;
            loadRangeIntoUi();   // 档位变了：滑条满量程随新档位数重载，进度读回未改动的存储范围。
            host.suppressListeners = false;
        }
        updatePreview();
        updateTvPending();
    }

    /** 把共享控件当前状态写回 {@link MainActivity#model}；{@code writeRange=false} 时仅同步启用/档位/步进。 */
    void writeUiIntoModel(boolean writeRange) {
        boolean enabled = host.binding.switchEnable.isChecked();
        VolumeConfig m = host.model.withEnabled(enabled);
        if (enabled) {
            m = m.withSteps(currentMediaSteps(), currentKeySteps());
        }
        if (!writeRange) {
            host.model = m;
            return;
        }
        // A/B/默认 各写自己独立的范围：模式A→absolute（AVRCP 0~127）；模式B→software；默认→defaultRange（均系统档位单位）。
        DeviceConfig cur = m.deviceFor(host.editingDevice);
        boolean absolute = host.editingMode == VolumeMode.ABSOLUTE && host.editingDevice.supportsAbsolute();
        int valueMax = absolute ? Prefs.AVRCP_MAX_VOLUME : currentMediaSteps();
        int lo = absolute ? Prefs.clampAbs(currentMinAbs())
                : Prefs.clampSystemStep(currentMinAbs(), valueMax);
        int hi = absolute ? Prefs.clampAbs(currentMaxAbs())
                : Prefs.clampSystemStep(currentMaxAbs(), valueMax);
        Range r = new Range(lo, hi, Prefs.clampCurve(currentCurveType()));
        DeviceConfig upd = host.showingDefault
                ? new DeviceConfig(cur.mode, cur.absolute, cur.software, r)
                : absolute
                        ? new DeviceConfig(cur.mode, r, cur.software, cur.defaultRange)
                        : new DeviceConfig(cur.mode, cur.absolute, r, cur.defaultRange);
        m = m.withDevice(host.editingDevice, upd);
        host.model = m;
    }

    /** 从 prefs 载入模型；缺失/非法回落系统默认配置。 */
    private VolumeConfig loadModelFromPrefs() {
        VolumeConfig cfg = VolumeConfig.fromXml(host.prefs.getString(Prefs.KEY_CONFIG_XML, ""));
        return cfg != null ? cfg : buildDefaultConfig();
    }

    /** 系统默认配置：总开关关闭、各设备默认直通、档位数/步进取原生级数、A/B 范围各自默认。 */
    VolumeConfig buildDefaultConfig() {
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

    /** 把一个完整配置写入 prefs（供保存/默认重置后统一回读界面）。 */
    void applyConfigToPrefs(VolumeConfig c) {
        host.prefs.edit().putString(Prefs.KEY_CONFIG_XML, c.toXml()).apply();
    }

    // ==================== 未保存提示 ====================

    /** 当前模型与已生效基线不一致（存在未保存修改）。 */
    boolean hasUnsavedChanges() {
        return !host.model.toXml().equals(host.lastSavedXml);
    }

    /** {@code tvPending}：有未保存修改时提示点保存，否则显示 renderStatus 的重启待生效文案。 */
    void updateTvPending() {
        if (hasUnsavedChanges()) {
            host.binding.tvPending.setText(R.string.pending_unsaved);
        } else {
            host.binding.tvPending.setText(host.pendingStatusText);
        }
    }

}
