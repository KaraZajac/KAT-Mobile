#![allow(dead_code,unused_imports,unused_variables)]
mod capture;
mod protocols;
mod keystore;
mod storage {
    pub fn resolve_config_dir()->Option<std::path::PathBuf>{None}
}
mod radio {
    pub use demodulator::LevelDuration;
    pub mod preset;
    pub mod dsp;
    pub mod pipeline;
    pub mod tx;
    /// Desktop KAT's Flipper Zero console session and receive stream, unchanged (`mobile_flipper`).
    pub mod flipper_console;
    pub mod demodulator {
        #[derive(Debug,Clone,Copy,PartialEq,Eq)]
        pub struct LevelDuration {pub level:bool,pub duration_us:u32}
        impl LevelDuration {pub fn new(level:bool,duration_us:u32)->Self{Self{level,duration_us}}}
    }
}

mod mobile_rx;
pub use mobile_rx::{ReceiveSession,ReceiveOutput};
pub use radio::dsp::IqFormat;
pub mod rtlsdr;
pub mod mobile_rtlsdr;
pub mod mobile_flipper;
pub mod interop_fob;
fn plural(n:usize)->&'static str{if n==1{""}else{"s"}}
fn has_extension(path:&std::path::Path,extension:&str)->bool{
    path.extension().and_then(|s|s.to_str()).is_some_and(|s|s.eq_ignore_ascii_case(extension))
}

use std::sync::{Mutex,OnceLock};
use serde::Serialize;
use protocols::ProtocolRegistry;
use radio::demodulator::LevelDuration;

#[derive(Debug,Serialize)]
pub struct ProtocolEntry {pub name:String,pub rf:String,pub coding:String,pub format:String}

#[derive(Debug,Serialize)]
pub struct Recognition {pub name:String,pub bits:usize,pub integrity:String}

static ENGINE:OnceLock<Mutex<ProtocolRegistry>>=OnceLock::new();
fn engine()->&'static Mutex<ProtocolRegistry>{ENGINE.get_or_init(||Mutex::new(new_registry()))}

/// A registry with KAT's embedded keys loaded first, as desktop KAT loads them at start
/// (`App::new`): the Kia V3/V4, V5 and V6, VAG, PSA, Ford V1, BMW V3, Audi V3 and AUT64 decoders
/// decrypt with them, and their encoders encrypt with them.
pub(crate) fn new_registry()->ProtocolRegistry{
    static KEYS:std::sync::Once=std::sync::Once::new();
    KEYS.call_once(protocols::keys::load_keystore_from_embedded);
    ProtocolRegistry::new()
}

pub fn catalog()->Vec<ProtocolEntry>{
    let registry=engine().lock().unwrap_or_else(|e|e.into_inner());
    registry.list_protocols().into_iter().map(|name|{
        let info=protocols::protocol_info(name);
        ProtocolEntry{name:name.into(),rf:info.map(|i|format!("{:?}",i.rf)).unwrap_or_default(),
            coding:info.map(|i|format!("{:?}",i.encoding)).unwrap_or_default(),format:info.map(|i|i.encryption.to_string()).unwrap_or_default()}
    }).collect()
}

/// Format identity only: no keys, counters, serials or payloads ([mobile_tx::decode] has those).
pub fn identify(durations:&[i32],frequency:u64,fm:bool)->Result<Vec<Recognition>,String>{
    Ok(scan(durations,frequency,fm,false)?.into_iter().map(|(r,_,_)|r).collect())
}
pub fn identify_receive(durations:&[i32],frequency:u64,fm:bool)->Result<Vec<Recognition>,String>{
    Ok(scan(durations,frequency,fm,true)?.into_iter().map(|(r,_,_)|r).collect())
}
/// The protocols that read a burst, one per decoder: its identity, and the decode with the name
/// KAT gives the capture (the decode's display name, e.g. "KeeLoq (DoorHan)").
fn scan(durations:&[i32],frequency:u64,fm:bool,receive_path:bool)->Result<Vec<(Recognition,String,protocols::DecodedSignal)>,String>{
    if durations.len()>32768||frequency>u32::MAX as u64{return Err("Input outside decoder bounds".into())}
    let mut pulses=Vec::with_capacity(durations.len());
    for &value in durations {
        let duration=value.unsigned_abs();
        if duration==0||duration>60_000_000{return Err("Invalid pulse".into())}
        pulses.push(LevelDuration::new(value>0,duration));
    }
    let mut registry=engine().lock().unwrap_or_else(|e|e.into_inner());
    registry.set_auto_key_search(false);
    let modulation=Some(if fm{capture::RfModulation::FM}else{capture::RfModulation::AM});
    let decoded=if receive_path{registry.process_signal_stream_path(&pulses,frequency as u32,modulation)}else{registry.process_signal_stream_rf(&pulses,frequency as u32,modulation)};
    let mut results:Vec<(Recognition,String,protocols::DecodedSignal)>=Vec::new();
    for (name,data,_) in decoded {
        if name.contains("unchecked"){continue}
        // The listed name is the decoder's; the manufacturer or key in a display name stays in the decode.
        let canonical=protocols::protocol_info(&name).map(|i|i.decoder.to_string())
            .unwrap_or_else(||name.split(" (").next().unwrap_or("Unknown").to_string());
        if canonical.contains("unchecked"){continue}
        let checksum=protocols::has_checksum_for(&name,data.data_count_bit,data.extra);
        if checksum&&!data.crc_valid{continue}
        if results.iter().any(|(r,_,_)|r.name==canonical){continue}
        results.push((Recognition{name:canonical,bits:data.data_count_bit,
            integrity:if checksum{"CRC/check OK"}else{"Format match; no checksum"}.into()},name,data));
        if results.len()==16{break}
    }
    Ok(results)
}

pub mod mobile_tx;

use jni::{JNIEnv,objects::{JClass,JIntArray},sys::{jstring,jlong,jboolean}};

#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeDecoder_nativeCatalog(env:JNIEnv,_class:JClass)->jstring{
    let json=serde_json::to_string(&catalog()).unwrap_or_else(|_|"[]".into());
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeDecoder_nativeIdentify(env:JNIEnv,_class:JClass,input:JIntArray,frequency:jlong,fm:jboolean,receive_path:jboolean)->jstring{
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{
        let len=env.get_array_length(&input).map_err(|e|e.to_string())? as usize;
        if len>32768||frequency<0{return Err("Invalid input".to_string())}
        let mut values=vec![0i32;len];env.get_int_array_region(&input,0,&mut values).map_err(|e|e.to_string())?;
        if receive_path!=0{identify_receive(&values,frequency as u64,fm!=0)}else{identify(&values,frequency as u64,fm!=0)}
    })).unwrap_or_else(|_|Err("Decoder input was rejected".into()));
    let json=match result{Ok(found)=>serde_json::to_string(&found).unwrap_or_else(|_|"[]".into()),Err(_)=>"[]".into()};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_katmobile_NativeDecoder_nativeReset(_env:JNIEnv,_class:JClass){
    let mut registry=engine().lock().unwrap_or_else(|e|e.into_inner());
    *registry=new_registry();
}
