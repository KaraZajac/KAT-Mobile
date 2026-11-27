# Changelog

All notable changes to KAT Mobile are documented here.

## [Unreleased]

### Added

- **Flipper Zero over USB OTG**, receive and transmit, the way desktop KAT uses one (#2 asked for a
  CC1101 on a serial line; this is the Flipper's): its command line streams `subghz rx_raw`, KAT's
  decoders read the stream and each frame they find is cut out of the noise around it and listed
  like an SDR's burst; a transmission goes out as a RAW `.sub` written to its SD card and played with
  `subghz tx_from_file`, receiving paused for it. The console session and the stream are desktop
  KAT's code, unchanged (`radio/flipper_console.rs`); the USB serial connection (CDC: 115200 8N1,
  DTR raised) is the app's (`FlipperUsb.kt`). No IQ, so no spectrum, recording or hopping; OOK
  reception. Without a HackRF, the Flipper transmits. Tested against a simulated Flipper console, not
  yet with a real Flipper on a phone.
- **RTL-SDR over USB OTG** (receive only), contributed by @0magicinside (#1): RTL2832U dongles
  with an R820T, R820T2 or R828D tuner (RTL-SDR Blog V3 and V4 among them), through Android's USB
  host API with no root and no driver app. The dongle is set up by desktop KAT's RTL-SDR driver
  (its `rtl-sdr-rs`, MPL-2.0, in `native/src/rtlsdr/`), which talks to it through the app's USB
  connection, and its samples (CU8) go through the same receive pipeline as the HackRF's. Sample
  rate (1.024, 2.0, 2.048 or 2.4 MS/s), auto or manual tuner gain, PPM correction and the bias-tee
  are in Settings; hopping and IQ recording (`.cu8`) work as with the HackRF. With two radios
  attached, Start RX asks which one. Transmitting still needs a HackRF. The driver's PPM correction
  now reaches the tuner too, as librtlsdr's does. Not yet tested with a real dongle.

### Fixed

From a tester's session with a Ford ute, whose car ignored the codes the app sent it.

- **Every press of a remote is its own signal.** Two receptions counted as one signal (one entry,
  its repeat count raised) when their first 256 pulses matched. A Ford V1 burst's first 256 pulses
  are all preamble, so every press and every button of the ute's remote became one entry (×34),
  showing the first press's pulses and decode but the last one's SNR and offset. Now, as in desktop
  KAT, a decoded signal is the same one only with the same protocol, frame, serial, button and
  counter, and an undecoded one only with the same whole pulse train; a repeat keeps the first
  reception's measurement, which its pulses are.
- **A capture is sent where its remote was heard**, as desktop KAT now does: on the frequency the
  radio was tuned to plus the offset the receiver measured, to the nearest kHz. The ute's remote was
  heard 330 kHz below and 480 kHz above 433.92 MHz and sent back on 433.92 MHz. The signal's details
  say where it will go when that differs, and the `.sub`, `.fob` and file names of an export use it.
  Captures from earlier versions have no measurement and are sent as before.
- From KAT's core (KAT's Unreleased changes): Ford V1 sends the remote's whole press, three sends
  with the flags bytes 00, 08 and 10, where it sent the first of ProtoPirate's bursts three times;
  and noise is no longer read as a Siemens-5WY72XX, Microchip HCS361 or HCS362 remote (eleven such
  decodes in the session).

## [1.30.0] - 2026-11-26

KAT Mobile transmits, as KAT does, and its version now follows KAT's: its core is KAT 1.30.0's.

### Added

- **Transmit** through the HackRF, from a signal's details, as desktop KAT's signal menu offers it:
  Replay (the recorded pulses); TX Lock, Unlock, Trunk and Panic for a car remote; Send next code
  and the remote's own buttons for a gate, garage, barrier or alarm remote. The frames come from
  KAT's encoders with the copies KAT sends, and a rolling code's capture keeps the frame sent, so
  the next press sends the code after (desktop KAT's `app/tx.rs`). The HackRF sends KAT's waveform
  (OOK or 2-FSK at the protocol's deviation, 2 MS/s, 500 kHz above the LO) the way desktop KAT's
  `transmit_signal_hackrf` does, and receiving pauses for it. TX power (desktop KAT's steps, 47 dB
  + amp by default) is in Settings; `tx_vga_gain` and `tx_amp` import from KAT's `config.ini`. A
  transmission is refused during an IQ recording and above 10 s on air.
- Captures show their serial, button and counter, as KAT lists them, and `.fob` files carry the
  decode both ways, so a remote's codes continue from desktop KAT to the phone and back.

### Changed

- The core is KAT 1.30.0's. 219 of its files are KAT's unchanged, with `radio/tx.rs` and "Add
  manually" (`add_manually.rs`, which the tests use) among them; before, it was KAT 1.29.0's with
  the encoders and Flipper key files cut out. The other four leave out key recovery, the user's key
  files and the vulnerability database.
- The core loads KAT's embedded keys at start, as desktop KAT does: the Kia V3/V4, V5 and V6, VAG,
  PSA, Ford V1, BMW V3, Audi V3 and AUT64 decoders decrypt with them. Before, the phone had none.
- The version is KAT's, 1.30.0 (version code 13000, from major × 10000 + minor × 100 + patch). The
  About screen and What's new show the core's KAT version from `native/Cargo.toml`.
- `tools/sync-kat-core.sh` merges a KAT release into the core, keeping KAT Mobile's edits.

## [0.8.0] - 2026-11-26

The first release from this repository: CooKie's KAT Mobile RX 0.8.0-en, with KAT 1.29.0's
receive core, now built and tested in CI.

### Changed

- The application ID is `io.karazajac.katmobile` (CooKie's builds were `app.katmobile`), so it
  installs beside those builds instead of conflicting with them. The QA build is
  `io.karazajac.katmobile.qa`.
- The APK is built on Linux, macOS or Windows: Gradle finds cargo on its own and links the receive
  core with the Android SDK's NDK 27.2.12479018, instead of fixed Windows paths.
- The emulator build of the receive core is x86_64 (it was 32-bit x86). The release APK is
  arm64-v8a, as before.
- Releases are debug-signed with one fixed key, so later releases can update this one.

### Fixed

- The Stop button in the reception notification was not translated.
