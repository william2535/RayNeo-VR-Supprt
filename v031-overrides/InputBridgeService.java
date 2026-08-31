package com.willflood.rayneovrcontroller;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Shizuku UserService: runs as shell/root and owns the physical evdev grab. */
public class InputBridgeService extends IInputBridge.Stub {
    private static final int EBUSY = 16;
    private static final String APP_PROCESS = "com.willflood.rayneovrcontroller";
    private static final String BRIDGE_SUFFIX = "input_bridge";

    private int fd = -1;
    private String name = "";

    public InputBridgeService() {}
    public InputBridgeService(Context context) {}

    @Override public synchronized String listControllers() {
        return NativeInputBridge.listControllers();
    }

    @Override public synchronized int openDevice(String path, boolean grab) {
        releaseDevice();
        int result = NativeInputBridge.openDevice(path, grab);

        // v0.3 could leave an older Shizuku UserService process holding EVIOCGRAB.
        // If Linux reports EBUSY, remove only stale RayNeo input_bridge sibling
        // processes, wait for their file descriptors to close, then retry once.
        if (grab && result == -EBUSY) {
            int killed = killStaleBridgeSiblings();
            if (killed > 0) {
                try { Thread.sleep(220); } catch (InterruptedException ignored) {}
                result = NativeInputBridge.openDevice(path, true);
            }
        }

        fd = result;
        if (fd >= 0) name = NativeInputBridge.getDeviceName(fd);
        else name = "";
        return result;
    }

    private int killStaleBridgeSiblings() {
        int self = android.os.Process.myPid();
        int killed = 0;
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return 0;

        for (File dir : entries) {
            int pid;
            try { pid = Integer.parseInt(dir.getName()); }
            catch (NumberFormatException ignored) { continue; }
            if (pid <= 1 || pid == self) continue;

            String cmd = readCmdline(new File(dir, "cmdline"));
            if (!cmd.contains(APP_PROCESS) || !cmd.contains(BRIDGE_SUFFIX)) continue;

            try {
                Os.kill(pid, OsConstants.SIGKILL);
                killed++;
            } catch (Throwable ignored) {}
        }
        return killed;
    }

    private static String readCmdline(File f) {
        byte[] buf = new byte[512];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(buf);
            if (n <= 0) return "";
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            return new String(buf, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
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

    // Reserved Shizuku UserService destroy transaction. The AIDL assigns this
    // method transaction id 16777114 so Shizuku can actually stop old bridges.
    @Override public void destroy() {
        releaseDevice();
        System.exit(0);
    }
}
