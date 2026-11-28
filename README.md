# KAT Mobile

The Android edition of [KAT](https://github.com/KaraZajac/KAT), the Keyfob Analysis Toolkit. A phone
with a **HackRF**, an **RTL-SDR** or a **Flipper Zero** on USB OTG receives, records and identifies car
key fob, gate, garage door and sensor signals with KAT's own code, without a computer — and transmits
with a HackRF or a Flipper Zero: Replay, and KAT's encoders for "Send next code" and the car and gate
buttons, a rolling code's counter advancing with every press. Key recovery is not part of the app.

KAT Mobile was written by **CooKie** as an Android adaptation of KAT, and is maintained here as
KAT's official Android app.

---

## Security and legal disclaimer

**Use KAT Mobile only on systems and vehicles you own or have explicit, written permission to
test.** Capturing, decoding, or transmitting keyfob and vehicle-access signals without authorization may be
illegal in your jurisdiction (e.g. computer misuse, unauthorized access, or radio regulations). You
are solely responsible for ensuring your use complies with all applicable laws. KAT Mobile is
intended for security research, authorized penetration testing, education, and legitimate testing
on your own equipment. The authors and contributors assume no liability for misuse or damage
arising from use of this software.

---

## Features

- **HackRF over USB OTG** — a HackRF One, Jawbreaker or rad1o through Android's USB host API: no
  root and no driver app. Frequency, sample rate (2–20 MS/s), LNA and VGA gains, the RF amplifier,
  the baseband filter and AM, FM or both (AUTO).
- **RTL-SDR over USB OTG (receive-only)** — direct support for RTL2832U dongles with R820T, R820T2
  or R828D tuners (including RTL-SDR Blog V3/V4 and Nooelec NESDR) through Android's USB host API:
  no root and no external driver app. Sample rate (1.024–2.4 MS/s, 2.0 MS/s default), auto or manual
  tuner gain, PPM frequency correction, optional bias-tee, CU8 live spectrum/waterfall and `.cu8` IQ
  recording.
- **Flipper Zero over USB OTG** — the Flipper's command line, as desktop KAT uses it: `subghz rx_raw`
  streams its CC1101's pulses, KAT's decoders read them as they come, and each frame they find is cut
  out of the noise around it and listed like an SDR's burst. Transmitting writes the transmission as a
  RAW `.sub` on its SD card and plays it (`subghz tx_from_file`: OOK, or 2-FSK at the protocol's
  deviation; the firmware's region lock applies). No IQ, so no spectrum, signal level, recording or
  hopping, and OOK reception (the firmware's own preset). Official firmware, Momentum, RogueMaster,
  Unleashed and the others desktop KAT follows; close any app on the Flipper first. Over USB only, as
  desktop KAT: the Flipper's Bluetooth link carries its app protocol, not the command line. Not yet
  tried with a real Flipper on a phone.
- **KAT's core** — KAT 1.32.0's receive pipeline (burst detection, channel filtering, the AM and
  FM slicers), its protocol decoders and encoders and its embedded keys, built from KAT's Rust
  source and called over JNI. A signal is listed with its protocol, bit count, whether its
  checksum holds, and its serial, button and counter, as KAT lists them; frames that fail their
  checksum are dropped.
- **Transmit (HackRF or Flipper Zero)** — from a signal's details, as KAT's signal menu offers it: **Replay** sends the
  recorded pulses; a car remote gets **TX Lock, Unlock, Trunk and Panic**, a gate, garage, barrier
  or alarm remote **Send next code** and its own buttons, from KAT's encoders with the copies KAT
  sends. A rolling code's capture keeps the frame sent, so the next press sends the code after.
  The waveform is KAT's (OOK or 2-FSK at the protocol's deviation, 500 kHz above the HackRF's LO,
  so its LO leakage stays out of the receiver's channel), at the TX power set in Settings
  (desktop KAT's steps, 47 dB + amp by default). Receiving pauses for a transmission. A capture goes
  out where its remote was heard: the frequency the radio was tuned to plus the offset KAT's receiver
  measured. Without a HackRF, a Flipper Zero sends it (desktop KAT's order).
- **A few more formats** — Nexus/TFA and Rubicson/Solight weather sensors, and Princeton, CAME and
  Nice FLO fixed codes when the core reads nothing, from a small recognizer in Kotlin.
- **Live view** — spectrum, waterfall, signal level and data rate, the latest signal received, and
  the history grouped by protocol, with repeats of the same signal counted instead of listed.
- **Frequency hopping** — a fixed time on each frequency (Flipper's hopper list by default), which
  does not wait for a signal to end.
- **IQ recording and analysis** — record raw CS8 (HackRF) or CU8 (RTL-SDR) IQ at one frequency (with
  a storage quota), and analyze CS8 or CU8 recordings later, from the phone or from KAT on a computer.
- **Import and export** — KAT `.fob` files (every saved pulse, readable by desktop KAT), Flipper RAW
  `.sub` files, JSON, whole sessions, and KAT's `config.ini` (its radio, hopping and history
  settings).
- **Private by default** — no internet permission. The session is stored encrypted (AES-GCM, with
  a key in the Android Keystore), Android backups are off, and files leave the app only through the
  system's file picker.

## Installing

Download `KAT-Mobile-<version>.apk` from the [releases page](https://github.com/KaraZajac/KAT-Mobile/releases)
(each has a `.sha256` checksum), allow your browser or file manager to install apps, and open it.
It needs a 64-bit ARM phone with Android 8.0 or later and USB OTG.

Connect a HackRF, RTL-SDR or Flipper Zero with an OTG adapter or cable and press **Start RX**; Android
asks for USB access the first time. A HackRF draws up to 500 mA and an RTL-SDR ~250–300 mA: if the
phone cannot power it, use a powered USB hub. A Flipper runs on its own battery.

Releases are debug-signed for now. When KAT Mobile moves to a release key, the new build will not
install over the old one: export your session first (Signals, Export session), uninstall, install
the new build and import the session.

## Building

You need JDK 17, the Android SDK with platform 36 and NDK 27.2.12479018, and Rust:

```sh
sdkmanager "platforms;android-36" "ndk;27.2.12479018"
rustup target add aarch64-linux-android x86_64-linux-android
```

Then, on Linux, macOS or Windows:

```sh
./gradlew assembleDebug                         # the QA app (io.karazajac.katmobile.qa), arm64 and x86_64
./gradlew -PkatAbis=arm64-v8a assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Gradle builds the receive core in `native/` with cargo for each ABI (`-PkatAbis` picks them) and
links it with the NDK's clang. It finds cargo through `$CARGO`, rustup's `~/.cargo/bin` or the
`PATH`, and the NDK in the SDK or `$ANDROID_NDK_HOME`.

On Windows, `Build.ps1` (or `BUILD-APK.cmd`) also runs the tests and checks the APK; `-Install
-Serial <device>` installs it and `-DeviceTests -Serial <device>` runs the instrumentation tests.

### Tests

```sh
./gradlew testDebugUnitTest            # first: it writes the .fob that fob_interop reads
(cd native && cargo test --locked)     # the core, on the computer: receiving, KAT's encoders, the IQ received back
```

The instrumentation tests reset the saved session, so they run only in the QA app. With a phone or
emulator connected:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant io.karazajac.katmobile.qa android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -e class app.katmobile.AppTest,app.katmobile.FlipperDeviceTest io.karazajac.katmobile.qa.test/androidx.test.runner.AndroidJUnitRunner
```

CI runs all of them on every push, the instrumentation tests on an Android 14 emulator. The
transmit tests send nothing on air: the encoders' frames are decoded back, the IQ goes through
KAT's receiver, and the USB sequence runs against a simulated HackRF; the Flipper Zero session runs
against a simulated Flipper command line (`native/tests/flipper_session.rs`, and over JNI on the
device). Test a transmission on air with a receiver you own (KAT with an RTL-SDR, for instance).

## How it is built

- `app/` — the Kotlin and Jetpack Compose app: the USB SDR abstraction (`SdrUsb.kt`), HackRF over
  USB (`HackRfUsb.kt`, and `Transmit.kt` for desktop KAT's transmit sequence), RTL-SDR over USB
  (`RtlSdrUsb.kt`), the Flipper Zero's console over USB CDC (`FlipperUsb.kt`), the radio service,
  storage, import and export, and the tests.
- `native/src/` — KAT 1.32.0's code: its receive pipeline, decoders, encoders and transmit waveform
  (`radio/tx.rs`). 222 files are KAT's unchanged; the other four leave out what the app does not
  have (key recovery, the user's key files, the vulnerability database). `lib.rs`, `mobile_rx.rs`,
  `mobile_tx.rs` and `mobile_rtlsdr.rs` are the JNI layer (`mobile_tx.rs` does what desktop KAT's
  app does around its encoders), `rtlsdr/` is the embedded MPL-2.0 RTL2832U + R820T/R820T2/R828D
  control driver adapted from desktop KAT's `rtl-sdr-rs`, and `interop_fob.rs` is KAT's `.fob`
  reader.
- `tools/sync-kat-core.sh` brings a KAT release into `native/src`: `tools/sync-kat-core.sh ../KAT
  v1.31.0 v1.32.0` merges KAT's changes in and keeps KAT Mobile's edits. A conflict means KAT
  changed code that KAT Mobile edits. KAT Mobile's version follows KAT's.

## Credits

- **CooKie** — the Android app and its adaptation of KAT's core.
- **KAT** — [github.com/KaraZajac/KAT](https://github.com/KaraZajac/KAT), whose decoders follow
  ProtoPirate, Flipper-ARF, doctor-firmware, Momentum, RogueMaster, FoxFW and rtl_433 (see KAT's
  credits).

## License

BSD 3-Clause, like KAT: see [LICENSE](LICENSE). The embedded RTL-SDR control driver in
`native/src/rtlsdr/` is adapted from `rtl-sdr-rs` under MPL-2.0 (see
`app/src/main/assets/RTL-SDR-RS-LICENSE.txt`).
