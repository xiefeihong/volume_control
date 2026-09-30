package com.xiefeihong.volumecontrol;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 模块共享基础层（libxposed 新 API，作用域：系统框架 + 蓝牙）。
 *
 * <p>集中提供：模块实例与进程状态、分级日志（自写文件 + 错误外发）、反射工具
 * （新 API 不再提供 XposedHelpers）、配置读取（Settings.Global + 镜像文件双通道）、
 * 两个进程各自的 Context 解析。</p>
 */
public final class XposedKit {

    /** 错误日志前缀：仅错误日志以此外发（LSPosed 日志页），可在日志页直接检索。 */
    static final String LOG_PREFIX = "VolumeControl: ";
    /** LSPosed 日志 / logcat 的 tag。 */
    static final String LOG_TAG = "VolumeControl";

    /** 系统框架与蓝牙的 AudioService / 相关类路径。 */
    static final String AUDIO_SERVICE_CLASS = "com.android.server.audio.AudioService";
    static final String AUDIO_DEVICE_BROKER_CLASS = "com.android.server.audio.AudioDeviceBroker";
    static final String VOLUME_STREAM_STATE_CLASS =
            "com.android.server.audio.AudioService$VolumeStreamState";

    /** 模块自写日志：系统框架侧文件（system_server 可写，root 可读）。 */
    static final String SYS_LOG_FILE = "/data/system/volumecontrol_sys.log";
    /** 备用日志路径（当 /data/system/ 被 SELinux 阻止时使用）。 */
    private static final String SYS_LOG_FILE_FALLBACK = "/data/misc/volumecontrol_sys.log";
    /** 模块自写日志：蓝牙进程侧文件名（写入蓝牙应用数据目录）。 */
    static final String BT_LOG_FILE_NAME = "volumecontrol_bt.log";
    private static final String BT_LOG_FILE_FALLBACK =
            "/data/data/com.android.bluetooth/files/" + BT_LOG_FILE_NAME;
    /** 单个日志文件大小上限（超过后清空重写，避免无限增长）。 */
    private static final long LOG_FILE_MAX_BYTES = 512L * 1024L;

    // ==================== 模块实例与进程状态 ====================

    /** 模块入口实例（onModuleLoaded 时记录；错误日志经它外发到 LSPosed 日志页）。 */
    private static volatile XposedModule sModule;
    /** 是否系统框架进程（决定日志文件位置与 Context 解析方式）。 */
    private static volatile boolean sIsSystemProcess;
    /** 蓝牙进程缓存的 Application Context（用于读取 Settings.Global）。 */
    private static volatile Context sBtContext;
    /** system_server 缓存的 Context（AudioService 构造参数 / mContext）。 */
    private static volatile Context sSysContext;

    private XposedKit() {
    }

    /** 记录模块实例与进程类型（每个进程的 onModuleLoaded 调用一次）。 */
    static void init(XposedModule module, boolean isSystemServer) {
        sModule = module;
        sIsSystemProcess = isSystemServer;
    }

    // ==================== 分级日志 ====================

    /** 已记录过的一次性日志键集合（同键只记录一次，避免高频路径刷屏）。 */
    private static final Set<String> sLoggedOnceKeys = ConcurrentHashMap.newKeySet();
    /** 日志文件路径（一旦解析成功即固定）；写入连续失败 3 次后放弃文件日志。 */
    private static volatile String sLogFilePath;
    private static int sLogFileFailureCount;
    private static final Object LOG_LOCK = new Object();
    private static final SimpleDateFormat LOG_TIME_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /**
     * 普通日志（INFO）：写自写文件 + LSPosed 日志文件 + logcat。
     * 三通道全写，确保至少一个通道可见：
     * - 文件日志可能被 SELinux 阻止
     * - {@code Log.i()} 从 system_server 可能被 Android 15 日志策略过滤
     * - {@code module.log()} 直接写入 LSPosed 日志文件（/data/adb/lspd/log/），最可靠
     */
    static void log(String message) {
        writeLogFile("I", message);
        try {
            XposedModule module = sModule;
            if (module != null) {
                module.log(Log.INFO, LOG_TAG, LOG_PREFIX + message);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
        try {
            Log.i(LOG_TAG, LOG_PREFIX + message);
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
    }

    /**
     * 错误日志（ERROR）：写自写文件，并以 E 级输出到 LSPosed 日志页
     * （新 API {@code log(priority, tag, msg)}）与 logcat，消息带统一前缀便于检索。
     */
    static void logError(String message) {
        writeLogFile("E", message);
        try {
            XposedModule module = sModule;
            if (module != null) {
                module.log(Log.ERROR, LOG_TAG, LOG_PREFIX + message);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
        try {
            Log.e(LOG_TAG, LOG_PREFIX + message);
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
    }

    /** 一次性日志（INFO）：同键只记录一次，避免高频路径刷屏。 */
    static void logOnce(String key, String message) {
        if (sLoggedOnceKeys.add(key)) {
            log(message);
        }
    }

    /** 一次性日志（ERROR）：同键只记录一次。 */
    static void logErrorOnce(String key, String message) {
        if (sLoggedOnceKeys.add(key)) {
            logError(message);
        }
    }

    /** 追加写入当前进程的日志文件（带级别标记）；文件过大时清空重写，连续失败 3 次后放弃文件日志。 */
    private static void writeLogFile(String level, String message) {
        synchronized (LOG_LOCK) {
            try {
                String path = resolveLogPath();
                if (path == null) {
                    return;
                }
                File file = new File(path);
                if (file.length() > LOG_FILE_MAX_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                String time;
                synchronized (LOG_TIME_FORMAT) {
                    time = LOG_TIME_FORMAT.format(new Date());
                }
                byte[] bytes = ("[" + level + "] " + time + " " + message + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                }
                sLogFileFailureCount = 0;
            } catch (Throwable t) {
                if (++sLogFileFailureCount >= 3 && sLogFilePath != null) {
                    // 连续失败（如 SELinux 限制）：放弃文件日志，依赖 logcat 兜底
                    logErrorOnce("logfile-give-up",
                            "log file write failed " + sLogFileFailureCount
                                    + " times, switching to logcat only");
                    sLogFilePath = null;
                    sLogFileFailureCount = 4;
                }
            }
        }
    }

    /** 解析当前进程的日志文件路径（系统框架固定路径；蓝牙优先应用数据目录）。 */
    private static String resolveLogPath() {
        String cached = sLogFilePath;
        if (cached != null) {
            return cached;
        }
        if (sLogFileFailureCount > 3) {
            return null;
        }
        String path = null;
        if (sIsSystemProcess) {
            // 先尝试主路径，失败后用备用路径
            path = SYS_LOG_FILE;
            File testFile = new File(path);
            try {
                File parent = testFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                // 测试是否可写
                try (FileOutputStream test = new FileOutputStream(testFile, true)) {
                    test.write(0);
                }
            } catch (Throwable t) {
                // 主路径不可写，尝试备用路径
                path = SYS_LOG_FILE_FALLBACK;
                try {
                    File fallback = new File(path);
                    File parent = fallback.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    try (FileOutputStream test = new FileOutputStream(fallback, true)) {
                        test.write(0);
                    }
                } catch (Throwable t2) {
                    // 备用路径也不可用，依赖 logcat 兜底
                    path = null;
                }
            }
        } else {
            Context context = bluetoothContext();
            if (context != null) {
                File dir = context.getFilesDir();
                if (dir != null) {
                    path = new File(dir, BT_LOG_FILE_NAME).getAbsolutePath();
                }
            }
            if (path == null) {
                path = BT_LOG_FILE_FALLBACK;
            }
        }
        sLogFilePath = path;
        return path;
    }

    // ==================== Hook 工具（新 API 无 XposedHelpers，统一走反射） ====================

    /** 挂载指定名称的全部方法（等价于旧 API 的 hookAllMethods）；返回挂载数量。 */
    static int hookAllMethodsNamed(XposedModule module, Class<?> clazz, String name,
            XposedInterface.Hooker hooker) {
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            try {
                module.hook(method).intercept(hooker);
                count++;
            } catch (Throwable t) {
                logError("hook " + clazz.getName() + "#" + name + " failed: " + t);
            }
        }
        return count;
    }

    /** 挂载指定类的全部构造器（等价于旧 API 的 hookAllConstructors）；返回挂载数量。 */
    static int hookAllConstructors(XposedModule module, Class<?> clazz,
            XposedInterface.Hooker hooker) {
        int count = 0;
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            try {
                module.hook(constructor).intercept(hooker);
                count++;
            } catch (Throwable t) {
                logError("hook " + clazz.getName() + " constructor failed: " + t);
            }
        }
        return count;
    }

    /** 读实例字段（任意可见性）；失败返回 null / 抛出异常由调用方决定。 */
    static Object getField(Object object, String name) throws Exception {
        Class<?> c = object.getClass();
        while (c != null && c != Object.class) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 读实例 int 字段；失败抛出异常。 */
    static int getIntField(Object object, String name) throws Exception {
        Object value = getField(object, name);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        throw new NoSuchFieldException(name + " not int");
    }

    /** 写实例 int 字段；失败抛出异常。 */
    static void setIntField(Object object, String name, int value) throws Exception {
        Class<?> c = object.getClass();
        while (c != null && c != Object.class) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(object, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 读静态字段（任意可见性）。 */
    static Object getStaticField(Class<?> clazz, String name) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /** 读静态 int 字段。 */
    static int getStaticIntField(Class<?> clazz, String name) throws Exception {
        Object value = getStaticField(clazz, name);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        throw new NoSuchFieldException(name + " not int");
    }

    /** 判断静态字段是否为 int[] 并读取（用于 MAX_STREAM_VOLUME）。 */
    static int[] getStaticIntArrayField(Class<?> clazz, String name) {
        try {
            Object value = getStaticField(clazz, name);
            if (value instanceof int[]) {
                return (int[]) value;
            }
        } catch (Throwable t) {
            logError("getStaticIntArrayField(" + clazz.getSimpleName() + "." + name + ") failed: " + t);
        }
        return null;
    }

    // ==================== 配置读取（双通道） ====================

    /**
     * 读取配置（多重通道，任一成功即返回）。
     *
     * <p>通道1 Settings.Global（各进程均可访问，冷启动早期 SettingsProvider 未就绪时失败）
     * → 通道2 配置镜像文件（App 以 root 写入 /data/system，不依赖 SettingsProvider，
     * 开机全程可读——冷启动构造期的关键通道）。</p>
     *
     * @return 解析后的 {@link VolumeConfig}（任一通道成功即返回），全部失败返回 null
     */
    static VolumeConfig readConfig(Context context) {
        // 通道1：Settings.Global
        if (context != null) {
            try {
                String raw = SettingsGlobal.getString(context, Prefs.GLOBAL_KEY);
                VolumeConfig config = VolumeConfig.fromRaw(raw);
                if (config != null) {
                    return config;
                }
                if (raw != null) {
                    String trimmed = raw.trim();
                    if (!trimmed.isEmpty() && !"null".equals(trimmed)) {
                        logErrorOnce("global-invalid", "global config invalid: [" + trimmed + "]");
                    }
                }
            } catch (Throwable t) {
                logOnce("global-failed", "read global config failed: " + t);
            }
        }
        // 通道2：配置镜像文件（不依赖 SettingsProvider；冷启动早期唯一可靠通道）
        try {
            VolumeConfig config = VolumeConfig.fromRaw(readMirrorConfig());
            if (config != null) {
                logOnce("mirror-used", "config loaded from mirror file");
                return config;
            }
        } catch (Throwable t) {
            logErrorOnce("mirror-failed", "read mirror config failed: " + t);
        }
        return null;
    }

    /** Settings.Global 的薄封装（隔离 import，便于阅读）。 */
    private static final class SettingsGlobal {
        static String getString(Context context, String key) {
            return android.provider.Settings.Global.getString(
                    context.getContentResolver(), key);
        }
    }

    /** 读取 App 以 root 写入的配置镜像（{@link Prefs#MIRROR_CONFIG_FILE}）。 */
    private static String readMirrorConfig() {
        try {
            File file = new File(Prefs.MIRROR_CONFIG_FILE);
            long length = file.length();
            if (!file.isFile() || length <= 0 || length > 4096) {
                return null;
            }
            byte[] buffer = new byte[(int) length];
            try (FileInputStream in = new FileInputStream(file)) {
                int offset = 0;
                while (offset < buffer.length) {
                    int count = in.read(buffer, offset, buffer.length - offset);
                    if (count <= 0) {
                        break;
                    }
                    offset += count;
                }
            }
            return new String(buffer, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== Context 解析 ====================

    /**
     * system_server 进程内解析 Context（多重兜底，不依赖类名判断）：
     * 先用构造器缓存；再取传入对象的 mContext（AudioService 与 AudioDeviceBroker
     * 均有该字段）；最后回退 ActivityThread 的系统上下文（system_server 进程必有）。
     */
    static Context systemServerContext(Object thisObject) {
        Context cached = sSysContext;
        if (cached != null) {
            return cached;
        }
        Throwable lastError = null;
        try {
            if (thisObject != null) {
                Object context = getField(thisObject, "mContext");
                if (context instanceof Context) {
                    sSysContext = (Context) context;
                    return sSysContext;
                }
            }
        } catch (Throwable t) {
            lastError = t;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object context = thread.getClass().getMethod("getSystemContext").invoke(thread);
                if (context instanceof Context) {
                    sSysContext = (Context) context;
                    return sSysContext;
                }
            }
        } catch (Throwable t) {
            lastError = t;
        }
        logErrorOnce("sys-context", "system context unavailable"
                + (lastError == null ? "" : ": " + lastError));
        return null;
    }

    /** 缓存 system_server 的 Context（AudioService 构造器参数即系统上下文）。 */
    static void cacheSystemContext(Context context) {
        if (context != null) {
            sSysContext = context;
        }
    }

    /** 蓝牙进程的上下文（ActivityThread 反射，不缓存失败结果，下次继续尝试）。 */
    static Context bluetoothContext() {
        Context cached = sBtContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object app = thread.getClass().getMethod("getApplication").invoke(thread);
                if (app instanceof Context) {
                    sBtContext = (Context) app;
                    return sBtContext;
                }
            }
        } catch (Throwable t) {
            logErrorOnce("bt-context", "get bluetooth app context failed: " + t);
        }
        return null;
    }
}
