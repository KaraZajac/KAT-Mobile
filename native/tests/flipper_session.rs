//! KAT Mobile's Flipper Zero session (`mobile_flipper.rs`) against a simulated Flipper command line,
//! as desktop KAT's tests simulate it (`src/radio/flipper.rs`): the prompt after Ctrl+C and after
//! every command, `rx_raw` streaming its text until Ctrl+C, `storage` and `tx_from_file`, and the
//! firmware's refusals.

use std::collections::{HashMap, VecDeque};
use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use kat_rx::mobile_flipper::{FlipperBurst, FlipperSession};

#[derive(Default)]
struct Log {
    commands: Vec<String>,
    files: HashMap<String, Vec<u8>>,
    transmitted: Vec<String>,
}

struct FakeFlipper {
    out: VecDeque<u8>,
    line: Vec<u8>,
    /// `rx_raw`'s text still to come (Some while it runs).
    streaming: Option<VecDeque<u8>>,
    rx_script: Vec<u8>,
    /// `storage write_chunk`: the file and the bytes still to take as its data.
    chunk: Option<(String, usize)>,
    /// What every `subghz` command answers instead of running (an app open, …).
    subghz_refusal: Option<&'static str>,
    refuse_tx: bool,
    /// Never answers at all (not a Flipper's console).
    mute: bool,
    unplugged: Arc<AtomicBool>,
    log: Arc<Mutex<Log>>,
}

impl FakeFlipper {
    fn new(rx_script: Vec<u8>) -> (Self, Arc<Mutex<Log>>, Arc<AtomicBool>) {
        let log = Arc::new(Mutex::new(Log::default()));
        let unplugged = Arc::new(AtomicBool::new(false));
        let fake = Self {
            out: VecDeque::new(),
            line: Vec::new(),
            streaming: None,
            rx_script,
            chunk: None,
            subghz_refusal: None,
            refuse_tx: false,
            mute: false,
            unplugged: Arc::clone(&unplugged),
            log: Arc::clone(&log),
        };
        (fake, log, unplugged)
    }

    fn say(&mut self, text: &str) {
        self.out.extend(text.as_bytes());
    }

    fn prompt(&mut self) {
        self.say("\r\n>: ");
    }

    fn byte(&mut self, b: u8) {
        if self.mute {
            return;
        }
        if let Some((path, left)) = self.chunk.as_mut() {
            self.log.lock().unwrap().files.entry(path.clone()).or_default().push(b);
            *left -= 1;
            if *left == 0 {
                self.chunk = None;
                self.prompt();
            }
            return;
        }
        if self.streaming.is_some() {
            if b == 0x03 {
                self.streaming = None;
                self.prompt();
            }
            return;
        }
        match b {
            0x03 => {
                self.line.clear();
                self.prompt();
            }
            b'\r' => {
                let cmd = String::from_utf8_lossy(&self.line).trim().to_string();
                self.line.clear();
                self.handle(&cmd);
            }
            b'\n' => {}
            _ => self.line.push(b),
        }
    }

    fn handle(&mut self, cmd: &str) {
        if !cmd.is_empty() {
            self.log.lock().unwrap().commands.push(cmd.to_string());
        }
        let words: Vec<&str> = cmd.split_whitespace().collect();
        if let (Some(text), ["subghz", ..]) = (self.subghz_refusal, words.as_slice()) {
            self.say(&format!("\x1b[31m{text}\x1b[0m"));
            self.prompt();
            return;
        }
        match words.as_slice() {
            [] => {}
            ["subghz", "rx_raw", f] => {
                self.say(&format!("Listening at {f}. Press CTRL+C to stop\r\n"));
                self.streaming = Some(self.rx_script.iter().copied().collect());
                return; // the prompt comes after Ctrl+C
            }
            ["storage", "mkdir", _] => self.say("Storage error: File/Dir already exists\r\n"),
            ["storage", "remove", path] => {
                self.log.lock().unwrap().files.remove(*path);
            }
            ["storage", "write_chunk", path, n] => {
                self.say("Ready\r\n");
                self.chunk = Some((path.to_string(), n.parse().unwrap()));
                return; // the prompt comes after the data
            }
            ["subghz", "tx_from_file", path, "1", "0"] => {
                if self.refuse_tx {
                    self.say("Transmission on this frequency is restricted in your settings/region\r\n");
                } else {
                    let mut log = self.log.lock().unwrap();
                    let file = String::from_utf8_lossy(log.files.get(*path).map_or(&[][..], |f| f)).to_string();
                    log.transmitted.push(file);
                    drop(log);
                    self.say(&format!(
                        "Listening at \x1b[0;33m{path}\x1b[0m. Frequency=433920000, Protocol=RAW\r\n\r\nPress CTRL+C to stop\r\n\r\n.."
                    ));
                }
            }
            _ => self.say(&format!("`{cmd}` command not found\r\n")),
        }
        self.prompt();
    }
}

impl Read for FakeFlipper {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.unplugged.load(Ordering::SeqCst) {
            return Ok(0);
        }
        // While rx_raw runs, a little more of its text each read, as the pulses arrive.
        if let Some(s) = self.streaming.as_mut() {
            for _ in 0..700 {
                match s.pop_front() {
                    Some(b) => self.out.push_back(b),
                    None => break,
                }
            }
        }
        if self.out.is_empty() {
            std::thread::sleep(Duration::from_millis(1));
            return Err(io::ErrorKind::TimedOut.into());
        }
        let n = buf.len().min(self.out.len());
        for b in &mut buf[..n] {
            *b = self.out.pop_front().unwrap();
        }
        Ok(n)
    }
}

impl Write for FakeFlipper {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        if self.unplugged.load(Ordering::SeqCst) {
            return Err(io::ErrorKind::BrokenPipe.into());
        }
        for &b in data {
            self.byte(b);
        }
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// A Princeton press (24 bits, 390/1170 µs, sent four times), as iq_pipeline.rs builds it.
fn princeton() -> Vec<i32> {
    let mut p = Vec::new();
    for _ in 0..4 {
        for c in "101001011001011010010110".chars() {
            let (high, low) = if c == '1' { (1170, 390) } else { (390, 1170) };
            p.push(high);
            p.push(-low);
        }
        p.push(390);
        p.push(-11_700);
    }
    p
}

/// Noise as a Flipper's CC1101 gives it between transmissions: short, irregular pulses.
fn noise(n: usize, seed: &mut u64, level: &mut bool) -> Vec<i32> {
    (0..n)
        .map(|_| {
            *seed ^= *seed << 13;
            *seed ^= *seed >> 7;
            *seed ^= *seed << 17;
            *level = !*level;
            let d = 15 + (*seed % 240) as i32;
            if *level {
                d
            } else {
                -d
            }
        })
        .collect()
}

/// `rx_raw`'s text for `pulses`: `+n`/`-n` words, a line break every 256 (as the firmware).
fn rx_raw_text(pulses: &[i32]) -> Vec<u8> {
    let mut t = String::new();
    for (i, p) in pulses.iter().enumerate() {
        t.push_str(&if *p > 0 { format!("+{p} ") } else { format!("{p} ") });
        if i % 256 == 255 {
            t.push_str("\r\n\n");
        }
    }
    t.into_bytes()
}

/// Noise, a Princeton press, noise.
fn stream_with_a_press() -> (Vec<i32>, usize) {
    let (mut seed, mut level) = (0x5851_F42D_4C95_7F2Du64, false);
    let mut pulses = noise(3000, &mut seed, &mut level);
    let press = princeton();
    if level {
        pulses.push(-180); // the noise ends LOW before the press starts HIGH
    }
    pulses.extend_from_slice(&press);
    level = false;
    pulses.extend(noise(3000, &mut seed, &mut level));
    (pulses, press.len())
}

fn is_princeton(b: &FlipperBurst) -> bool {
    kat_rx::identify_receive(&b.pulses, 433_920_000, false).unwrap().iter().any(|r| r.name == "Princeton")
}

/// Receive until the stream has been read and a while longer, then stop.
fn receive_all(s: &mut FlipperSession) -> Vec<FlipperBurst> {
    let mut bursts = Vec::new();
    for _ in 0..400 {
        let out = s.pump();
        assert!(out.error.is_none() && !out.closed, "{:?}", out.error);
        bursts.extend(out.bursts);
    }
    let out = s.stop_rx();
    assert!(out.error.is_none(), "{:?}", out.error);
    bursts.extend(out.bursts);
    bursts
}

#[test]
fn a_press_in_the_stream_reaches_the_app_cut_out_of_the_noise() {
    let (pulses, press_len) = stream_with_a_press();
    let (fake, log, _) = FakeFlipper::new(rx_raw_text(&pulses));
    let mut s = FlipperSession::open(fake).expect("a session past the banner");
    s.start_rx(433_920_000).unwrap();
    assert_eq!(s.receiving(), Some(433_920_000));
    let bursts = receive_all(&mut s);
    let princeton: Vec<&FlipperBurst> = bursts.iter().filter(|b| is_princeton(b)).collect();
    assert!(!princeton.is_empty(), "no Princeton burst in {} bursts", bursts.len());
    // Cut out of the noise: about the press, not the 6000 noise pulses around it.
    assert!(princeton[0].pulses.len() <= press_len + 8, "{} pulses", princeton[0].pulses.len());
    assert!(princeton[0].end_us > princeton[0].start_us);
    // Noise alone gives no burst: every burst decodes.
    assert!(bursts.iter().all(|b| !kat_rx::identify_receive(&b.pulses, 433_920_000, false).unwrap().is_empty()));
    assert_eq!(s.receiving(), None);
    assert_eq!(log.lock().unwrap().commands, vec!["subghz rx_raw 433920000"]);
}

#[test]
fn a_transmission_goes_through_the_sd_card_and_receiving_resumes() {
    let (fake, log, _) = FakeFlipper::new(Vec::new());
    let mut s = FlipperSession::open(fake).unwrap();
    s.start_rx(433_920_000).unwrap();
    let press = princeton();
    s.transmit(&press, 433_591_000, None).expect("sent");
    assert_eq!(s.receiving(), Some(433_920_000), "receiving starts again");
    {
        let log = log.lock().unwrap();
        assert_eq!(log.transmitted.len(), 1);
        let file = &log.transmitted[0];
        assert!(
            file.starts_with(
                "Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: 433591000\nPreset: FuriHalSubGhzPresetOok650Async\nProtocol: RAW\nRAW_Data: 1170 -390 390 -1170"
            ),
            "{file}"
        );
        let values: Vec<i64> =
            file.lines().filter_map(|l| l.strip_prefix("RAW_Data:")).flat_map(|l| l.split_whitespace().map(|v| v.parse::<i64>().unwrap())).collect();
        assert_eq!(values.iter().map(|v| v.unsigned_abs()).sum::<u64>(), press.iter().map(|v| u64::from(v.unsigned_abs())).sum::<u64>());
        let c = &log.commands;
        let at = |prefix: &str| c.iter().position(|x| x.starts_with(prefix)).unwrap_or_else(|| panic!("no {prefix} in {c:?}"));
        assert!(at("subghz rx_raw") < at("storage mkdir"));
        assert!(at("storage remove /ext/apps_data/kat/tx.sub") < at("storage write_chunk /ext/apps_data/kat/tx.sub"));
        assert_eq!(c.iter().filter(|x| x.starts_with("subghz rx_raw")).count(), 2, "{c:?}");
        assert!(c.last().unwrap().starts_with("subghz rx_raw"), "{c:?}");
        assert!(at("subghz tx_from_file /ext/apps_data/kat/tx.sub 1 0") < c.len() - 1);
    }
    // 2-FSK: the file names the preset of its deviation (FM476's, which every firmware has).
    s.transmit(&press, 433_920_000, Some(47_607.421875)).unwrap();
    assert!(log.lock().unwrap().transmitted[1].contains("\nPreset: FuriHalSubGhzPreset2FSKDev476Async\n"));
}

#[test]
fn refusals_come_back_in_words() {
    let (mut fake, _, _) = FakeFlipper::new(Vec::new());
    fake.subghz_refusal = Some("subghz command cannot be run while an application is open");
    let mut s = FlipperSession::open(fake).unwrap();
    let e = s.start_rx(433_920_000).unwrap_err().to_string();
    assert!(e.contains("an app is open on the Flipper"), "{e}");
    assert_eq!(s.receiving(), None);

    let (mut fake, log, _) = FakeFlipper::new(Vec::new());
    fake.refuse_tx = true;
    let mut s = FlipperSession::open(fake).unwrap();
    let e = s.transmit(&princeton(), 433_920_000, None).unwrap_err().to_string();
    assert!(e.contains("restricted"), "{e}");
    assert!(log.lock().unwrap().transmitted.is_empty());
    assert!(s.transmit(&[], 433_920_000, None).is_err(), "nothing to send");
}

#[test]
fn a_port_that_never_answers_is_no_flipper() {
    let (mut fake, _, _) = FakeFlipper::new(Vec::new());
    fake.mute = true;
    let e = FlipperSession::open(fake).err().expect("refused").to_string();
    assert!(e.contains("no answer from its console"), "{e}");
}

#[test]
fn unplugging_ends_the_session() {
    let (pulses, _) = stream_with_a_press();
    let (fake, _, unplugged) = FakeFlipper::new(rx_raw_text(&pulses));
    let mut s = FlipperSession::open(fake).unwrap();
    s.start_rx(433_920_000).unwrap();
    assert!(!s.pump().closed);
    unplugged.store(true, Ordering::SeqCst);
    let out = s.pump();
    assert!(out.closed && out.error.is_some(), "{:?}", out.error);
    assert!(s.is_closed() && s.receiving().is_none());
    assert!(s.start_rx(433_920_000).is_err());
    assert!(s.transmit(&princeton(), 433_920_000, None).is_err());
}
