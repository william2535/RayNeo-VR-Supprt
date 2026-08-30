#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>

static int emit_ev(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    return write(fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev) ? 0 : -errno;
}

static int setup_abs(int fd, int code, int min, int max, int flat, int fuzz) {
    if (ioctl(fd, UI_SET_ABSBIT, code) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_willflood_rayneovrcontroller_NativeUInput_openGamepad(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -errno;

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        int e = errno; close(fd); return -1000-e;
    }

    int buttons[] = {
        BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST,
        BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
        BTN_SELECT, BTN_START, BTN_MODE,
        BTN_THUMBL, BTN_THUMBR
    };
    for (unsigned i=0; i<sizeof(buttons)/sizeof(buttons[0]); i++) {
        if (ioctl(fd, UI_SET_KEYBIT, buttons[i]) < 0) {
            int e=errno; close(fd); return -1100-e;
        }
    }

    int axes[] = { ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_RX, ABS_RY, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y };
    for (unsigned i=0; i<sizeof(axes)/sizeof(axes[0]); i++) {
        if (setup_abs(fd, axes[i], 0, 0, 0, 0) < 0) {
            int e=errno; close(fd); return -1200-e;
        }
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "RayNeo VR Gamepad");
    uidev.id.bustype = BUS_USB;
    uidev.id.vendor  = 0x1BBB;
    uidev.id.product = 0xAF52;
    uidev.id.version = 1;

    uidev.absmin[ABS_X] = -32767;  uidev.absmax[ABS_X] = 32767; uidev.absflat[ABS_X] = 1024; uidev.absfuzz[ABS_X] = 16;
    uidev.absmin[ABS_Y] = -32767;  uidev.absmax[ABS_Y] = 32767; uidev.absflat[ABS_Y] = 1024; uidev.absfuzz[ABS_Y] = 16;
    uidev.absmin[ABS_RX] = -32767; uidev.absmax[ABS_RX] = 32767; uidev.absflat[ABS_RX] = 1024; uidev.absfuzz[ABS_RX] = 16;
    uidev.absmin[ABS_RY] = -32767; uidev.absmax[ABS_RY] = 32767; uidev.absflat[ABS_RY] = 1024; uidev.absfuzz[ABS_RY] = 16;
    // Keep both common right-stick conventions available.
    uidev.absmin[ABS_Z] = -32767;  uidev.absmax[ABS_Z] = 32767; uidev.absflat[ABS_Z] = 1024; uidev.absfuzz[ABS_Z] = 16;
    uidev.absmin[ABS_RZ] = -32767; uidev.absmax[ABS_RZ] = 32767; uidev.absflat[ABS_RZ] = 1024; uidev.absfuzz[ABS_RZ] = 16;
    uidev.absmin[ABS_GAS] = 0;     uidev.absmax[ABS_GAS] = 255;
    uidev.absmin[ABS_BRAKE] = 0;   uidev.absmax[ABS_BRAKE] = 255;
    uidev.absmin[ABS_HAT0X] = -1;  uidev.absmax[ABS_HAT0X] = 1;
    uidev.absmin[ABS_HAT0Y] = -1;  uidev.absmax[ABS_HAT0Y] = 1;

    if (write(fd, &uidev, sizeof(uidev)) != (ssize_t)sizeof(uidev)) {
        int e=errno; close(fd); return -2000-e;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        int e=errno; close(fd); return -3000-e;
    }

    // Give Android InputReader a moment to enumerate the new device.
    usleep(300000);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_willflood_rayneovrcontroller_NativeUInput_setGamepadState(
        JNIEnv *env, jclass cls, jint fd,
        jint lx, jint ly, jint rx, jint ry,
        jint lt, jint rt, jint hatX, jint hatY, jint axisMode) {
    (void)env; (void)cls;
    if (fd < 0) return -1;
    emit_ev(fd, EV_ABS, ABS_X, lx);
    emit_ev(fd, EV_ABS, ABS_Y, ly);
    if (axisMode == 1) {
        // Linux/xpad style: right stick RX/RY. Keep Z/RZ neutral.
        emit_ev(fd, EV_ABS, ABS_RX, rx);
        emit_ev(fd, EV_ABS, ABS_RY, ry);
        emit_ev(fd, EV_ABS, ABS_Z, 0);
        emit_ev(fd, EV_ABS, ABS_RZ, 0);
    } else {
        // Android common convention: right stick Z/RZ. Keep RX/RY neutral.
        emit_ev(fd, EV_ABS, ABS_Z, rx);
        emit_ev(fd, EV_ABS, ABS_RZ, ry);
        emit_ev(fd, EV_ABS, ABS_RX, 0);
        emit_ev(fd, EV_ABS, ABS_RY, 0);
    }
    emit_ev(fd, EV_ABS, ABS_GAS, lt);
    emit_ev(fd, EV_ABS, ABS_BRAKE, rt);
    emit_ev(fd, EV_ABS, ABS_HAT0X, hatX);
    emit_ev(fd, EV_ABS, ABS_HAT0Y, hatY);
    emit_ev(fd, EV_SYN, SYN_REPORT, 0);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_willflood_rayneovrcontroller_NativeUInput_gamepadButton(
        JNIEnv *env, jclass cls, jint fd, jint code, jboolean pressed) {
    (void)env; (void)cls;
    if (fd < 0) return -1;
    emit_ev(fd, EV_KEY, (unsigned short)code, pressed ? 1 : 0);
    emit_ev(fd, EV_SYN, SYN_REPORT, 0);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_willflood_rayneovrcontroller_NativeUInput_closeGamepad(JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    if (fd >= 0) {
        // Return sticks/triggers/hat to neutral before destroying the device.
        emit_ev(fd, EV_ABS, ABS_X, 0); emit_ev(fd, EV_ABS, ABS_Y, 0);
        emit_ev(fd, EV_ABS, ABS_RX, 0); emit_ev(fd, EV_ABS, ABS_RY, 0);
        emit_ev(fd, EV_ABS, ABS_Z, 0); emit_ev(fd, EV_ABS, ABS_RZ, 0);
        emit_ev(fd, EV_ABS, ABS_GAS, 0); emit_ev(fd, EV_ABS, ABS_BRAKE, 0);
        emit_ev(fd, EV_ABS, ABS_HAT0X, 0); emit_ev(fd, EV_ABS, ABS_HAT0Y, 0);
        emit_ev(fd, EV_SYN, SYN_REPORT, 0);
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
    }
}
