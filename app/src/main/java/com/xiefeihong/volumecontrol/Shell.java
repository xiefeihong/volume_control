package com.xiefeihong.volumecontrol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * root shell 执行工具。
 *
 * <p>所有方法都会阻塞当前线程，请在后台线程调用。</p>
 */
public final class Shell {

    /** su 命令执行超时（毫秒）。 */
    private static final long DEFAULT_TIMEOUT_MS = 20_000;

    /** 开发者选项「停用绝对音量功能」对应的系统设置键（1=停用）。 */
    public static final String SETTING_AV_DISABLE = "bluetooth_disable_absolute_volume";

    /** 模块自写日志文件（系统框架侧，root 可读写）。 */
    private static final String SYS_LOG_FILE = "/data/system/volumecontrol_sys.log";

    /** post-fs-data 开机脚本（Magisk / KernelSU 最早执行阶段，先于 system_server 启动）。 */
    private static final String BOOT_SCRIPT_FILE = "/data/adb/post-fs-data.d/volumecontrol.sh";

    /** HyperOS AudioService 初始化读取的媒体档位属性。 */
    private static final String PROP_MEDIA_VOL_STEPS = "ro.config.media_vol_steps";

    private Shell() {
    }

    // 命令执行结果值对象 ShellResult 已提取为独立顶层类（见 ShellResult.java）。

    /** 在 root 身份下执行一条 shell 命令。 */
    public static ShellResult su(String command) {
        return exec("su", "-c", command);
    }

    /** 执行命令并读取合并后的输出（stdout + stderr）。 */
    public static ShellResult exec(String... command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }

            if (!process.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ShellResult(-1, output + "\n[timeout]");
            }
            return new ShellResult(process.exitValue(), output.toString());
        } catch (Exception e) {
            return new ShellResult(-1, e.toString());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 检测 root 是否可用（su 已授权且可执行）。 */
    public static boolean isRootAvailable() {
        ShellResult result = su("id");
        return result.isSuccess() && result.output.contains("uid=0");
    }

    /** 检测 LSPosed 是否存在（安装目录存在性检查）。 */
    public static boolean isLsposedPresent() {
        ShellResult result = su("ls -d /data/adb/lspd 2>/dev/null");
        return result.isSuccess() && !result.output.trim().isEmpty();
    }

    /**
     * 写入 Settings.Global 配置（供 Hook 端在 system_server 中读取）。
     * 注意：值必须单引号包裹，避免分号被 shell 解析为命令分隔符。
     */
    public static ShellResult putGlobalConfig(String key, String value) {
        return su("settings put global " + key + " '" + value + "'");
    }

    /** 读取 Settings.Global 中的值（root 方式，用于校验写入结果）。 */
    public static ShellResult getGlobalConfig(String key) {
        return su("settings get global " + key);
    }

    /**
     * 写入配置镜像文件（root）。Hook 端在 Settings.Global 读取失败时直读该文件兜底：
     * chmod 644 使 system 用户可读；restorecon 修正 SELinux 上下文（不可用时静默跳过）。
     */
    public static ShellResult writeGlobalMirror(String value) {
        return su("echo '" + value + "' > " + Prefs.MIRROR_CONFIG_FILE
                + "; chmod 644 " + Prefs.MIRROR_CONFIG_FILE
                + "; restorecon " + Prefs.MIRROR_CONFIG_FILE + " 2>/dev/null");
    }
    
    /**
     * 写入 post-fs-data 开机脚本（档位开机生效的终极保险，不依赖任何 Hook）：
     * 开机最早阶段（system_server 启动前）以 resetprop 直接设置
     * ro.config.media_vol_steps，AudioService 初始化时读到的就是用户档位数。
     *
     * <p>生效标志与档位数以字面量烘分进脚本（配置已改为 XML，shell 无法用 cut 解析）；写入后立即对齐当前属性值——本周期内任何原因的
     * system_server 重启都会读到正确档位。停用或镜像异常时删除属性恢复 ROM 默认。
     * resetprop 对 ro. 属性的修改只能通过 Magisk / KernelSU 的 resetprop 工具
     * （setprop 不允许改 ro.），两者都不可用时静默失败，不影响其他保险。</p>
     */
    public static ShellResult writeBootScript(boolean active, int mediaSteps) {
        int steps = Prefs.clampMediaSteps(mediaSteps);
        String[] lines = {
                "#!/system/bin/sh",
                "# VolumeControl boot script: set media volume steps before system_server starts.",
                "if [ \"" + (active ? 1 : 0) + "\" = \"1\" ] && [ " + steps
                        + " -ge 10 ] && [ " + steps + " -le 100 ]; then",
                "  resetprop " + PROP_MEDIA_VOL_STEPS + " " + steps + " 2>/dev/null"
                        + " || magisk resetprop " + PROP_MEDIA_VOL_STEPS + " " + steps + " 2>/dev/null",
                "else",
                "  resetprop --delete " + PROP_MEDIA_VOL_STEPS + " 2>/dev/null"
                        + " || magisk resetprop --delete " + PROP_MEDIA_VOL_STEPS + " 2>/dev/null",
                "fi",
        };
        StringBuilder command = new StringBuilder("printf '%s\\n'");
        for (String line : lines) {
            command.append(" '").append(line).append("'");
        }
        command.append(" > ").append(BOOT_SCRIPT_FILE)
                .append("; chmod 755 ").append(BOOT_SCRIPT_FILE)
                .append("; restorecon ").append(BOOT_SCRIPT_FILE).append(" 2>/dev/null");
        ShellResult write = su(command.toString());

        // 立即对齐当前属性值（供本周期内任何 system_server 重启使用）
        if (active) {
            su("resetprop " + PROP_MEDIA_VOL_STEPS + " " + steps
                    + " 2>/dev/null || magisk resetprop " + PROP_MEDIA_VOL_STEPS + " "
                    + steps + " 2>/dev/null");
        } else {
            su("resetprop --delete " + PROP_MEDIA_VOL_STEPS + " 2>/dev/null"
                    + " || magisk resetprop --delete " + PROP_MEDIA_VOL_STEPS + " 2>/dev/null");
        }
        return write;
    }

    /**
     * 清除本应用写入系统侧的全部配置，使数据不可恢复：
     * 删除 Settings.Global 配置键、配置镜像文件、post-fs-data 开机脚本，
     * 并 resetprop 删除被改写的媒体档位属性以回落 ROM 默认。
     *
     * <p>须在 root 下调用；各删除均静默容错（文件/键不存在时忽略）。</p>
     */
    public static void clearWrittenConfig() {
        su("settings delete global " + Prefs.GLOBAL_KEY + " 2>/dev/null; "
                + "rm -f " + Prefs.MIRROR_CONFIG_FILE + " 2>/dev/null; "
                + "rm -f " + BOOT_SCRIPT_FILE + " 2>/dev/null; "
                + "resetprop --delete " + PROP_MEDIA_VOL_STEPS + " 2>/dev/null"
                + " || magisk resetprop --delete " + PROP_MEDIA_VOL_STEPS + " 2>/dev/null");
    }

    /**
     * 重启蓝牙（关闭再打开），使模块在蓝牙进程中的 Hook 重新加载。
     * 蓝牙耳机等设备会断开，需要重新连接。
     */
    public static ShellResult restartBluetooth() {
        return su("echo \"[$(date '+%m-%d %H:%M:%S')][App] restart bluetooth requested\" "
                + ">> " + SYS_LOG_FILE + " 2>/dev/null; "
                + "svc bluetooth disable 2>/dev/null || cmd bluetooth_manager disable; "
                + "sleep 1.5; "
                + "svc bluetooth enable 2>/dev/null || cmd bluetooth_manager enable");
    }

    /**
     * 软重启系统框架（system_server 进程退出后由 zygote 自动拉起），
     * 使新的音量档位数生效（AudioService 只在启动时创建流档位）。
     *
     * <p>重启请求与新旧进程号会写入模块日志（/data/system/volumecontrol_sys.log），
     * 可在应用内「查看模块日志」核对是否真正重启（old != new 即已重启）。</p>
     */
    public static ShellResult restartSystemServer() {
        return su("echo \"[$(date '+%m-%d %H:%M:%S')][App] restart system_server requested\" "
                + ">> " + SYS_LOG_FILE + " 2>/dev/null; "
                + "OLD=$(pidof system_server); "
                + "kill -9 $OLD 2>/dev/null || killall -9 system_server 2>/dev/null; "
                + "sleep 3; "
                + "NEW=$(pidof system_server); "
                + "echo \"[$(date '+%m-%d %H:%M:%S')][App] system_server old=$OLD new=$NEW\" "
                + ">> " + SYS_LOG_FILE + " 2>/dev/null");
    }

    /**
     * 向系统框架模块日志追加一条应用侧记录（root 写入）。
     * 用于在「查看模块日志」中核对应用推送的配置内容与结果。
     */
    public static void appendSysLog(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String safe = message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
        su("echo \"[$(date '+%m-%d %H:%M:%S')][App] " + safe + "\" >> "
                + SYS_LOG_FILE + " 2>/dev/null");
    }

    /**
     * 读取模块日志。
     *
     * <p>模块自写日志文件优先（不依赖 LSPosed 日志实现）：
     * system_server → /data/system/volumecontrol_sys.log；
     * 蓝牙进程 → 蓝牙应用数据目录 volumecontrol_bt.log。
     * 再合并 LSPosed 日志文件与 logcat 结果；全部为空时附上诊断信息。</p>
     */
    public static String readModuleLogs() {
        ShellResult result = su(
                "echo '@SYS@'; "
                        + "tail -n 200 /data/system/volumecontrol_sys.log 2>/dev/null; "
                        + "tail -n 200 /data/misc/volumecontrol_sys.log 2>/dev/null; "
                        + "echo '@BT@'; tail -n 200 /data/data/com.android.bluetooth/files/"
                        + "volumecontrol_bt.log 2>/dev/null; "
                        + "echo '@LSPD@'; grep -a -h 'VolumeControl' /data/adb/lspd/log/* 2>/dev/null "
                        + "| tail -n 150; "
                        + "echo '@LOGCAT@'; logcat -d -t 500 2>/dev/null "
                        + "| grep -a 'VolumeControl' | tail -n 150");
        String output = result.output == null ? "" : result.output;
        String sys = section(output, "@SYS@", "@BT@");
        String bt = section(output, "@BT@", "@LSPD@");
        String lspd = section(output, "@LSPD@", "@LOGCAT@");
        String logcat = section(output, "@LOGCAT@", null);

        StringBuilder report = new StringBuilder();
        if (!sys.isEmpty()) {
            report.append("[系统框架日志]").append('\n').append(sys).append("\n\n");
        }
        if (!bt.isEmpty()) {
            report.append("[蓝牙日志]").append('\n').append(bt).append("\n\n");
        }
        if (!lspd.isEmpty()) {
            report.append("[LSPosed 日志]").append('\n').append(lspd).append("\n\n");
        }
        if (!logcat.isEmpty()) {
            report.append("[logcat]").append('\n').append(logcat);
        }

        if (report.length() == 0) {
            report.append("未找到任何日志。\n")
                    .append("请检查以下路径的文件是否存在：\n")
                    .append("• /data/system/volumecontrol_sys.log\n")
                    .append("• /data/adb/lspd/log/\n")
                    .append("• /data/data/com.android.bluetooth/files/volumecontrol_bt.log");
        }
        return report.toString().trim();
    }

    /**
     * 清理模块日志：删除模块自写的专用文件 + 清除 logcat。
     *
     * <p>LSPosed 日志（/data/adb/lspd/log/）是多模块共享的追加文件，不能整体删除；
     * 这里用 {@code sed} 只剔除含本模块标记 {@code VolumeControl} 的行，保留其他模块日志。</p>
     */
    public static void clearModuleLogs() {
        su("rm -f /data/system/volumecontrol_sys.log "
                + "/data/misc/volumecontrol_sys.log "
                + "/data/data/com.android.bluetooth/files/volumecontrol_bt.log 2>/dev/null; "
                + "for f in /data/adb/lspd/log/*; do "
                + "[ -f \"$f\" ] && sed -i '/VolumeControl/d' \"$f\" 2>/dev/null; done; "
                + "logcat -c 2>/dev/null");
    }

    /** 截取 {@code startKey} 与 {@code endKey} 之间的输出段（endKey 为 null 时到结尾）。 */
    private static String section(String output, String startKey, String endKey) {
        int start = output.indexOf(startKey);
        if (start < 0) {
            return "";
        }
        start += startKey.length();
        int end = endKey == null ? output.length() : output.indexOf(endKey, start);
        if (end < 0) {
            end = output.length();
        }
        return output.substring(start, end).trim();
    }
}
