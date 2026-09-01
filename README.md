# RayNeo Air 4 Pro Controller / VR Project

This repository now intentionally contains **two separate Android apps**.

## 1. RayNeo RS Input • Stable

This is the saved/frozen daily-driver build for the simple control method that felt best in testing:

- Air 4 Pro gyro **RATE → virtual RIGHT STICK**.
- Physical controller passthrough and RayNeo head input merged into one virtual gamepad.
- v0.3 Daily Driver behaviour retained.
- Proven Shizuku stale-service / `-16 EBUSY` recovery retained.
- Correct Android physical right-stick auto mapping retained.
- Correct LT / RT mapping retained.
- No SBS, MediaProjection, AI depth or experimental VR rendering.

Android package: `com.willflood.rayneorsinput`

Stable source/build overrides live under `stable-rs/` and are built by:

`.github/workflows/build-rs-stable.yml`

Artifact name:

`RayNeo-RS-Input-v1.0-STABLE.apk`

**Treat this as a frozen fallback. Do not change its tracking behaviour unless making an explicit new Stable version.**

## 2. RayNeo VR Lab

This is the experimental app and keeps the existing package:

`com.willflood.rayneovrcontroller`

It is where SBS, native Air 4 Pro 3D, MediaProjection, AI depth, comfort-motion head look and future VR experiments continue.

The current experimental build is produced by:

`.github/workflows/build-apk.yml`

Because the two apps use different Android application IDs, **RS Input Stable and VR Lab can be installed side-by-side on the same device**.

---

## Proven hardware / controller base

- RayNeo Air 4 Pro USB IMU input.
- Virtual Linux/Android gamepad through `/dev/uinput`.
- Physical controller input through a Shizuku UserService bridge.
- Xbox Cloud Gaming and NVIDIA/GeForce NOW have accepted the merged virtual controller in testing.
- RayNeo Air 4 Pro native SBS is switched by the glasses hardware; the Android app formats/captures content for the two halves but does not enable the glasses' SBS hardware mode itself.

## Original controller-lab goal

Use the Air 4 Pro IMU stream to create a conventional virtual gamepad and test whether controller-compatible games and streaming clients accept it. This conventional gamepad method is not native OpenXR headset-pose injection; the Stable RS Input app deliberately stays with the proven gamepad/right-stick approach.
