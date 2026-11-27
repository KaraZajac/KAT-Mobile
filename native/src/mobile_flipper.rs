//! A Flipper Zero as KAT Mobile's radio, the way desktop KAT uses one (its `src/radio/flipper.rs`):
//! the Flipper's command line over USB, `subghz rx_raw` to receive (KAT's decoders read the
//! stream and each frame they find is cut out of the noise around it), and a RAW `.sub` written to
//! its SD card and played with `subghz tx_from_file` to transmit.
//!
//! The console session and the receive stream are desktop KAT's (`radio::flipper_console`, copied
//! unchanged). The port is the app's USB serial connection (`FlipperUsb.kt`, USB CDC), which
//! [JniSerial] reads and writes over JNI. There is no thread here: the app's receive loop calls
//! [FlipperSession::pump], and a transmission stops `rx_raw`, plays the file and starts it again,
//! as desktop's worker does.

use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use anyhow::{anyhow, Result};
use jni::objects::{GlobalRef, JClass, JIntArray, JObject, JValue};
use jni::sys::{jboolean, jdouble, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use serde::Serialize;

use crate::capture::{Capture, RfModulation};
use crate::radio::flipper_console::{tx_sub_text, Console, Receiving, RxStream};
use crate::radio::preset::TxModulation;
use crate::radio::tx::check_tx_air_time;
use crate::radio::LevelDuration;

/// How long one read of the console waits for bytes (desktop KAT's `READ_TIMEOUT`).
const READ_WAIT_MS: jint = 20;

/// A burst the stream cut out of the noise: its pulses (+ HIGH, − LOW, µs) and when it was on the
/// air, in µs from the start of its receive session.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct FlipperBurst {
    pub pulses: Vec<i32>,
    pub start_us: u64,
    pub end_us: u64,
}

/// What one [FlipperSession::pump] (or stop) gives the app.
#[derive(Debug, Default, Serialize)]
pub struct PumpOutput {
    /// Bursts completed since the last call.
    pub bursts: Vec<FlipperBurst>,
    /// Why receiving ended, when it did (the Flipper ended `rx_raw`, or the console closed), or why
    /// it did not start again after a transmission.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// The console is closed (unplugged): the session is over.
    pub closed: bool,
}

/// A session on a Flipper Zero's command line.
pub struct FlipperSession {
    console: Console,
    rx: Option<Receiving>,
    /// Where the receive stream puts the captures of its bursts.
    bursts: Arc<Mutex<Vec<Capture>>>,
    /// Receive sessions started (each `rx_raw` counts its bursts' times from its start).
    session: u64,
    /// `rx_raw` did not start again after a transmission: said by the next pump.
    restart_error: Option<String>,
}

impl FlipperSession {
    /// A session on the Flipper's console `port`, past its banner to a fresh prompt. Err: no
    /// prompt within 3 s ("no answer from its console").
    pub fn open<P: Read + Write + Send + 'static>(port: P) -> Result<Self> {
        Ok(Self {
            console: Console::with_port(Box::new(port))?,
            rx: None,
            bursts: Arc::default(),
            session: 0,
            restart_error: None,
        })
    }

    /// Receive at `frequency` (the firmware's own OOK preset), stopping a running `rx_raw` first.
    /// Err: the Flipper's refusal, in words (an app open on it, no `subghz` command, …).
    pub fn start_rx(&mut self, frequency: u32) -> Result<()> {
        if let Some(r) = self.rx.take() {
            let _ = r.stop(&mut self.console);
        }
        if self.console.broken {
            return Err(anyhow!("the Flipper's console is closed"));
        }
        self.session += 1;
        let sink = Arc::clone(&self.bursts);
        let stream = RxStream::new(
            frequency,
            RfModulation::AM,
            false,
            self.session,
            Box::new(move |capture| sink.lock().unwrap_or_else(|e| e.into_inner()).push(capture)),
        );
        self.rx = Some(Receiving::start(&mut self.console, frequency, None, stream)?);
        Ok(())
    }

    /// The frequency `rx_raw` runs at, if it does.
    pub fn receiving(&self) -> Option<u32> {
        self.rx.as_ref().map(|r| r.frequency)
    }

    /// Read what arrived (waiting at most [READ_WAIT_MS]) and hand over the bursts it completed.
    pub fn pump(&mut self) -> PumpOutput {
        let mut error = self.restart_error.take();
        if let Some(r) = self.rx.as_mut() {
            if let Err(e) = r.pump(&mut self.console) {
                self.rx = None;
                error = Some(format!("{e:#}"));
            }
        }
        self.output(error)
    }

    /// Stop `rx_raw` (Ctrl+C); the bursts it completed.
    pub fn stop_rx(&mut self) -> PumpOutput {
        let error = match self.rx.take() {
            Some(r) => r.stop(&mut self.console).err().map(|e| format!("{e:#}")),
            None => None,
        };
        self.output(error)
    }

    /// Transmit `pulses` (+ HIGH, − LOW, µs) at `frequency`: OOK, or 2-FSK at `fsk_deviation_hz`.
    /// Receiving stops for it and starts again on its frequency. Err: the Flipper did not transmit
    /// (its refusal in words: the region lock, an app open, …); it counts as sent only once the
    /// Flipper says it is sending.
    pub fn transmit(&mut self, pulses: &[i32], frequency: u32, fsk_deviation_hz: Option<f64>) -> Result<()> {
        let signal: Vec<LevelDuration> =
            pulses.iter().filter(|&&p| p != 0).map(|&p| LevelDuration::new(p > 0, p.unsigned_abs())).collect();
        if signal.is_empty() {
            return Err(anyhow!("nothing to transmit"));
        }
        let air_us = check_tx_air_time(&signal)?;
        let modulation = match fsk_deviation_hz {
            Some(deviation_hz) => TxModulation::Fsk { deviation_hz },
            None => TxModulation::Ook,
        };
        let resume = self.rx.take().map(|r| {
            let f = r.frequency;
            let _ = r.stop(&mut self.console);
            f
        });
        let result = if self.console.broken {
            Err(anyhow!("the Flipper's console is closed"))
        } else {
            self.console.transmit(&tx_sub_text(&signal, frequency, modulation), air_us)
        };
        if let Some(f) = resume {
            if let Err(e) = self.start_rx(f) {
                self.restart_error = Some(format!("{e:#}"));
            }
        }
        result
    }

    /// The console closed (unplugged, or a read failed).
    pub fn is_closed(&self) -> bool {
        self.console.broken
    }

    fn output(&mut self, error: Option<String>) -> PumpOutput {
        let captures = std::mem::take(&mut *self.bursts.lock().unwrap_or_else(|e| e.into_inner()));
        let bursts = captures
            .into_iter()
            .map(|c| FlipperBurst {
                pulses: c
                    .raw_pairs
                    .iter()
                    .map(|p| {
                        let d = p.duration_us.min(i32::MAX as u32) as i32;
                        if p.level {
                            d
                        } else {
                            -d
                        }
                    })
                    .collect(),
                start_us: c.on_air.map_or(0, |t| t.start_us),
                end_us: c.on_air.map_or(0, |t| t.end_us),
            })
            .collect();
        PumpOutput { bursts, error, closed: self.console.broken }
    }
}

impl Drop for FlipperSession {
    /// A running `rx_raw` stops, so the console is left at its prompt for the next program.
    fn drop(&mut self) {
        if let Some(r) = self.rx.take() {
            if !self.console.broken {
                let _ = r.stop(&mut self.console);
            }
        }
    }
}

// ---- the app's USB serial connection, over JNI ------------------------------------------------------

/// The Flipper's console as the app's `FlipperSerial` (`FlipperUsb.kt`): `read(byte[], int
/// timeoutMs)` gives the bytes read, 0 when none came in time, −1 once the connection is gone;
/// `write(byte[], int length)` the bytes written, −1 on failure.
struct JniSerial {
    vm: JavaVM,
    bridge: GlobalRef,
}

fn jni_error(env: &mut JNIEnv, what: &str, e: jni::errors::Error) -> io::Error {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    io::Error::other(format!("{what}: {e}"))
}

impl Read for JniSerial {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let mut env = self.vm.get_env().map_err(|e| io::Error::other(format!("JNI environment unavailable: {e}")))?;
        let len = buf.len().min(16_384) as jint;
        let bridge = self.bridge.as_obj();
        let result = env.with_local_frame(8, |env| -> jni::errors::Result<io::Result<usize>> {
            let arr = env.new_byte_array(len)?;
            let n = env.call_method(bridge, "read", "([BI)I", &[JValue::Object(&arr), JValue::Int(READ_WAIT_MS)])?.i()?;
            if n < 0 {
                return Ok(Ok(0)); // the connection is gone: the console reads it as closed
            }
            if n == 0 {
                return Ok(Err(io::ErrorKind::TimedOut.into()));
            }
            let n = (n as usize).min(buf.len());
            let mut signed = vec![0i8; n];
            env.get_byte_array_region(&arr, 0, &mut signed)?;
            for (dst, src) in buf[..n].iter_mut().zip(signed) {
                *dst = src as u8;
            }
            Ok(Ok(n))
        });
        match result {
            Ok(r) => r,
            Err(e) => Err(jni_error(&mut env, "reading the Flipper's console", e)),
        }
    }
}

impl Write for JniSerial {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let mut env = self.vm.get_env().map_err(|e| io::Error::other(format!("JNI environment unavailable: {e}")))?;
        let len = buf.len().min(16_384);
        let bridge = self.bridge.as_obj();
        let result = env.with_local_frame(8, |env| -> jni::errors::Result<i32> {
            let arr = env.new_byte_array(len as jint)?;
            let signed: Vec<i8> = buf[..len].iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &signed)?;
            env.call_method(bridge, "write", "([BI)I", &[JValue::Object(&arr), JValue::Int(len as jint)])?.i()
        });
        match result {
            Ok(n) if n > 0 => Ok((n as usize).min(len)),
            Ok(_) => Err(io::Error::new(io::ErrorKind::BrokenPipe, "writing to the Flipper failed (unplugged?)")),
            Err(e) => Err(jni_error(&mut env, "writing to the Flipper's console", e)),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

type Shared = Arc<Mutex<FlipperSession>>;

fn sessions() -> &'static Mutex<HashMap<i64, Shared>> {
    static SESSIONS: OnceLock<Mutex<HashMap<i64, Shared>>> = OnceLock::new();
    SESSIONS.get_or_init(Mutex::default)
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn session(handle: jlong) -> Option<Shared> {
    sessions().lock().unwrap_or_else(|e| e.into_inner()).get(&handle).cloned()
}

/// Run `f`; a panic becomes an error, never an unwind across JNI.
fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|_| Err("the KAT core failed".to_string()))
}

fn jstr(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn json<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|e| format!("{{\"error\":\"{e}\",\"bursts\":[],\"closed\":false}}"))
}

/// Open a session on `bridge` (the app's `FlipperSerial`): `{"handle": n}`, or `{"error": "…"}`.
#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeFlipper_nativeOpen(mut env: JNIEnv, _class: JClass, bridge: JObject) -> jstring {
    let opened = (|| -> Result<JniSerial, String> {
        let vm = env.get_java_vm().map_err(|e| e.to_string())?;
        let bridge = env.new_global_ref(&bridge).map_err(|e| e.to_string())?;
        Ok(JniSerial { vm, bridge })
    })()
    .and_then(|serial| guarded(|| FlipperSession::open(serial).map_err(|e| format!("{e:#}"))));
    let out = match opened {
        Ok(s) => {
            let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
            sessions().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, Arc::new(Mutex::new(s)));
            serde_json::json!({ "handle": handle })
        }
        Err(e) => serde_json::json!({ "error": e }),
    };
    jstr(&mut env, &out.to_string())
}

/// Start `rx_raw` at `frequency`: "" once the Flipper listens, else why not.
#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeFlipper_nativeStartRx(mut env: JNIEnv, _class: JClass, handle: jlong, frequency: jlong) -> jstring {
    let result = guarded(|| {
        let s = session(handle).ok_or("the Flipper session is closed")?;
        let frequency = u32::try_from(frequency).map_err(|_| "frequency out of range".to_string())?;
        let mut s = s.lock().unwrap_or_else(|e| e.into_inner());
        s.start_rx(frequency).map_err(|e| format!("{e:#}"))
    });
    jstr(&mut env, result.err().as_deref().unwrap_or(""))
}

/// One read of the stream: [PumpOutput] as JSON.
#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeFlipper_nativePump(mut env: JNIEnv, _class: JClass, handle: jlong) -> jstring {
    let out = guarded(|| {
        let s = session(handle).ok_or("the Flipper session is closed")?;
        let mut s = s.lock().unwrap_or_else(|e| e.into_inner());
        Ok(s.pump())
    })
    .unwrap_or_else(|e| PumpOutput { error: Some(e), closed: true, ..PumpOutput::default() });
    jstr(&mut env, &json(&out))
}

/// Stop `rx_raw`: the last bursts, as JSON.
#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeFlipper_nativeStopRx(mut env: JNIEnv, _class: JClass, handle: jlong) -> jstring {
    let out = guarded(|| {
        let s = session(handle).ok_or("the Flipper session is closed")?;
        let mut s = s.lock().unwrap_or_else(|e| e.into_inner());
        Ok(s.stop_rx())
    })
    .unwrap_or_else(|e| PumpOutput { error: Some(e), ..PumpOutput::default() });
    jstr(&mut env, &json(&out))
}

/// Transmit `pulses` at `frequency` (2-FSK at `deviation_hz` when `fsk`): "" once sent, else why
/// the Flipper did not.
#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeFlipper_nativeTransmit(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    pulses: JIntArray,
    frequency: jlong,
    fsk: jboolean,
    deviation_hz: jdouble,
) -> jstring {
    let pulses = (|| -> Result<Vec<i32>, String> {
        let n = env.get_array_length(&pulses).map_err(|e| e.to_string())? as usize;
        let mut out = vec![0i32; n];
        env.get_int_array_region(&pulses, 0, &mut out).map_err(|e| e.to_string())?;
        Ok(out)
    })();
    let result = pulses.and_then(|pulses| {
        guarded(|| {
            let s = session(handle).ok_or("the Flipper session is closed")?;
            let frequency = u32::try_from(frequency).map_err(|_| "frequency out of range".to_string())?;
            let mut s = s.lock().unwrap_or_else(|e| e.into_inner());
            let deviation = (fsk != 0).then_some(deviation_hz);
            s.transmit(&pulses, frequency, deviation).map_err(|e| format!("{e:#}"))
        })
    });
    jstr(&mut env, result.err().as_deref().unwrap_or(""))
}

/// End the session (a running `rx_raw` stops first).
#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeFlipper_nativeClose(_env: JNIEnv, _class: JClass, handle: jlong) {
    let removed = sessions().lock().unwrap_or_else(|e| e.into_inner()).remove(&handle);
    let _ = guarded(move || {
        drop(removed);
        Ok(())
    });
}
