package com.xiefeihong.volumecontrol;

/**
 * root shell 命令执行结果（退出码 + 合并输出）。不可变值对象。
 *
 * <p>从 {@link Shell} 的内部类提取为独立顶层类（同包）。仅由 {@link Shell} 构造，
 * 调用方通过 {@link #isSuccess()} 判断 {@code su} 退出码是否为 0。</p>
 */
public final class ShellResult {
    public final int code;
    public final String output;

    ShellResult(int code, String output) {
        this.code = code;
        this.output = output;
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
