package com.willflood.rayneovrcontroller;

public final class NativeUInput {
    static { System.loadLibrary("uinputjni"); }
    private NativeUInput() {}

    public static native int openGamepad();
    public static native int setGamepadState(int fd, int lx, int ly, int rx, int ry, int lt, int rt, int hatX, int hatY, int axisMode);
    public static native int gamepadButton(int fd, int code, boolean pressed);
    public static native void closeGamepad(int fd);
}
