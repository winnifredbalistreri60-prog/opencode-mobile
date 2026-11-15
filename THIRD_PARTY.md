# Third-party notices

This file is the same inventory as `NOTICE`, kept under the conventional
GitHub name as well. License texts shipped in the APK live under
`app/src/main/assets/licenses/`.

OpenCode Mobile
================

This is an unofficial Android client for OpenCode. It is not a product of the
OpenCode team and is not affiliated with Anomaly / SST / opencode.ai.

Third-party software
--------------------

- OpenCode (MIT) — https://github.com/anomalyco/opencode
  The official CLI is downloaded at install time (not bundled in the APK).
  Copyright (c) 2025 opencode.

- PRoot, Termux fork (GPL-2.0-or-later) — https://github.com/termux/proot
  Bundled as libproot.so / libproot-loader*.so, built by MURF
  (android/native/build-proot.sh, commit 52d65a4d).
  License text: app/src/main/assets/licenses/proot-GPL-2.0.txt
  Corresponding source: the Termux proot tree at that commit, and the MURF
  build script (copied only as a reference; this repository does not modify it).

- talloc 2.5.0 (LGPL-3.0-or-later) — https://talloc.samba.org
  Statically linked into libproot.so.
  License text: app/src/main/assets/licenses/talloc-LGPL-3.0.txt

- usb-serial-for-android (MIT) — https://github.com/mik3y/usb-serial-for-android

- AndroidX Camera / CameraX (Apache-2.0) — https://developer.android.com/jetpack/androidx/releases/camera

- termux-api-package CLI ideas/script names (MIT) — https://github.com/termux/termux-api-package
  Copyright (c) 2017-2021 Termux. This app does not include Termux:API
  (that Android app is GPLv3) and does not depend on it.

- Runtime approach (proot layout, resumable download, process-tree kill)
  adapted from MURF / Hermex (MIT) — Copyright (c) 2026 Uzair Ansar,
  Copyright (c) 2026 MURF contributors. Copied and reworked; the MURF tree
  was not modified.

- AndroidX, Jetpack Compose, OkHttp, Kotlin — their respective licenses
  (Apache-2.0 / MIT). See Gradle dependency metadata.

- Ubuntu base 24.04 (Canonical, CC-BY-SA and package licenses) is downloaded
  at install time into the app private directory, not redistributed in the APK.
