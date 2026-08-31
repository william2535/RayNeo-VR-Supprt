package com.willflood.rayneovrcontroller;

interface IInputBridge {
    String listControllers() = 1;
    int openDevice(String path, boolean grab) = 2;
    int[] readEvent(int timeoutMs) = 3;
    int[] getAbsInfo(int code) = 4;
    String currentDeviceName() = 5;
    int currentFd() = 6;
    int serviceUid() = 7;
    void releaseDevice() = 8;
    void destroy() = 16777114; // Reserved destroy method defined by Shizuku server
}
