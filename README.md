# RayNeo VR Controller LAB v0.1

This is a **new separate project** from RayNeo Spatial.

## Goal
Use the proven RayNeo Air 4 Pro IMU stream to create a real Android/Linux virtual gamepad through `/dev/uinput`, then test whether controller-compatible games and VR streaming clients accept it.

## v0.1 features
- Direct Air 4 Pro USB IMU input using the proven sensor protocol.
- Virtual controller named **RayNeo VR Gamepad**.
- Standard gamepad buttons and analogue axes.
- Default: filtered head gyro rate -> right analogue stick.
- Alternate: integrated head angle -> right analogue stick.
- Two right-stick compatibility layouts:
  - Android common `Z / RZ`
  - Xbox/Linux-style `RX / RY`
- Experimental head roll -> left-stick steering.
- A/B/X/Y, L1/R1, Select/Start output test buttons.
- Built-in Android input monitor so you can prove the OS itself sees the virtual joystick before opening a game.
- Recenter and recalibrate controls.

## First hardware test
1. Fully stop RayNeo Spatial/Termux trackers so only this app owns the glasses USB interface.
2. Connect the Air 4 Pro.
3. Install/open **RayNeo VR Controller LAB**.
4. Press **START GAMEPAD** and keep the glasses still for ~2 seconds.
5. Wait for **GAMEPAD READY**.
6. Confirm the app changes to **ANDROID GAMEPAD DETECTED**.
7. Turn your head. The built-in **ANDROID INPUT EVENT** box should show a joystick axis changing.
8. If Z/RZ do not behave as the target game's right stick, change compatibility to **RX/RY**.
9. Open a controller-compatible game or VR streaming client and test head look there.

## What this proves / does not prove
This build is conventional gamepad emulation. It can be useful for games that use a controller to steer/look and for streaming software that forwards Android controllers.

It is **not yet true VR headset pose injection**. A native/OpenXR VR title normally obtains headset orientation from its XR runtime rather than from a gamepad. If gamepad output is proven on the hardware, the next project stage can investigate an OpenXR/runtime bridge that exposes RayNeo 3DoF orientation as actual headset pose.
