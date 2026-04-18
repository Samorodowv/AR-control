# UVCCamera upstream provenance

Source: https://github.com/saki4510t/UVCCamera
Module copied from local snapshot: `vendor/uvccamera.upstream/libuvccamera`
Upstream commit: `c9399e63dfab4b6d260d8cbef92182abc6de0ee0`

Vendored scope:
- `src/main/jni/` native tree from upstream `libuvccamera`
- `com.serenegiant.usb` core classes only:
  `UVCCamera`, `USBMonitor`, `DeviceFilter`, `Size`, `USBVendorId`,
  `IStatusCallback`, `IButtonCallback`, `IFrameCallback`
- minimal local replacements for upstream common helpers:
  `com.serenegiant.utils.BuildCheck`, `HandlerThreadHandler`

Local adjustments for this repo:
- rewired as an AGP 8 Android library module with namespace `com.serenegiant.uvccamera`
- removed upstream support-library and `usbCameraCommon` dependency chain
- limited `Application.mk` to `arm64-v8a` for the Huawei P60 Pro target
- patched `USBMonitor` for modern `PendingIntent` flags and dynamic receiver registration
