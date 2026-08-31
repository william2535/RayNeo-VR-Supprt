package com.willflood.rayneovrcontroller;

import android.content.Context;
import android.system.Os;

/** Shizuku UserService: runs as shell/root and owns the physical evdev grab. */
public class InputBridgeService extends IInputBridge.Stub {
    private int fd = -1;
    private String name = "";

    public InputBridgeService() {}
    public InputBridgeService(Context context) {}

    @Override public synchronized String listControllers() {
        return NativeInputBridge.listControllers();
    }

    @Override public synchronized int openDevice(String path, boolean grab) {
        releaseDevice();
        fd = NativeInputBridge.openDevice(path, grab);
        if (fd >= 0) name = NativeInputBridge.getDeviceName(fd);
        return fd;
    }

    @Override public synchronized int[] readEvent(int timeoutMs) {
        if (fd < 0) return new int[]{-19, 0, 0}; // -ENODEV
        return NativeInputBridge.readEvent(fd, timeoutMs);
    }

    @Override public synchronized int[] getAbsInfo(int code) {
        if (fd < 0) return new int[]{-19,0,0,0,0,0};
        return NativeInputBridge.getAbsInfo(fd, code);
    }

    @Override public synchronized String currentDeviceName() { return name; }
    @Override public synchronized int currentFd() { return fd; }
    @Override public int serviceUid() { return Os.getuid(); }

    @Override public synchronized void releaseDevice() {
        if (fd >= 0) NativeInputBridge.closeDevice(fd);
        fd = -1;
        name = "";
    }

    @Override public void destroy() {
        releaseDevice();
        System.exit(0);
    }
}
