package app.katmobile

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class RadioSettings(
    val frequency:Long=433_920_000, val sampleRate:Int=2_000_000,
    val lna:Int=32, val vga:Int=32, val amp:Boolean=false,
    val bandwidth:Int=1_750_000, val modulation:String="AUTO",
    val hopping:Boolean=false, val hopFrequencies:String="315,390,430.50,433.92,434.25,434.42,868.35",
    val hopDwellMs:Int=1000, val thresholdDb:Int=10, val minimumPulseUs:Int=20,
    val burstGapUs:Int=20000, val maxCaptures:Int=500,
    val showUnknown:Boolean=true, val ignoreDuplicates:Boolean=true,
    val autosave:Boolean=true, val includeRaw:Boolean=true, val recordingQuotaMb:Int=1024,
    val fontSize:Int=13, val keepScreen:Boolean=true,
    /** TX power, desktop KAT's `tx_vga_gain` / `tx_amp` (its default: full power). */
    val txVga:Int=47, val txAmp:Boolean=true,
    /** RTL-SDR receive-only settings (2.0 MS/s matches desktop KAT; gain in tenths of dB). */
    val rtlSampleRate:Int=2_000_000, val rtlAutoGain:Boolean=true,
    val rtlGainTenthsDb:Int=402, val rtlPpm:Int=0, val rtlBiasTee:Boolean=false
) {
    fun frequencies()=hopFrequencies.split(',').filter{it.isNotBlank()}.map{mhz(it)}.also{require(it.size in 1..32)}
    fun validate(){
        require(frequency in 1_000_000L..6_000_000_000L){"Frequency must be 1–6000 MHz"}
        require(sampleRate in listOf(2_000_000,4_000_000,8_000_000,10_000_000,16_000_000,20_000_000)){"Unsupported sample rate"}
        require(lna in 0..40&&lna%8==0&&vga in 0..62&&vga%2==0){"LNA: 0–40 / 8 dB; VGA: 0–62 / 2 dB"}
        require(bandwidth in Wire.bandwidths&&bandwidth<=sampleRate){"Choose a filter no wider than the sample rate"}
        require(modulation in listOf("AUTO","AM","FM"))
        frequencies();require(hopDwellMs in 250..30000)
        require(thresholdDb in 3..40&&minimumPulseUs in 4..1000&&burstGapUs in 1000..200000)
        require(maxCaptures in 10..2000&&recordingQuotaMb in 100..100000&&fontSize in 10..22)
        require(txVga in 0..47){"TX gain: 0–47 dB"}
        require(rtlSampleRate in RtlSdrUsb.SAMPLE_RATES){"Unsupported RTL-SDR sample rate"}
        require(rtlGainTenthsDb in RtlSdrUsb.R82XX_GAINS_TENTHS_DB){"Unsupported RTL-SDR tuner gain"}
        require(rtlPpm in -100..100){"RTL-SDR PPM correction must be −100 to 100"}
    }
    companion object {fun mhz(s:String):Long=s.trim().replace(',','.').toBigDecimal().multiply(1_000_000.toBigDecimal()).longValueExact().also{require(it in 1_000_000L..6_000_000_000L)}}
}
data class Pulse(val level:Boolean=false,val duration_us:Int=0)
data class Capture(
    val id:String=UUID.randomUUID().toString(),val time:Long=System.currentTimeMillis(),
    val frequency:Long=433_920_000,val modulation:String="AM",val protocol:String="Unknown",
    val source:String="HackRF",val pulses:List<Pulse> = emptyList(),val repeats:Int=1,
    val serial:String="",val button:String="",val counter:String="",val metadata:String="",val recognition:String="",
    /** KAT's decode of the remote (native/src/mobile_tx.rs `Decode`, JSON): what its encoder sends from. A rolling code's advances with every transmission. */
    val decode:String="",
    /** Where KAT's receiver measured the burst, in Hz from [frequency] (the carrier, or the centre of the two FSK tones); null when nothing measured it (an imported file, a session saved by an earlier version). */
    val offsetHz:Double?=null
){
    val durationUs get()=pulses.sumOf{it.duration_us.toLong()}
    /** The whole pulse train (from the first pulse, without the trailing gap) in 20 µs steps. */
    val fingerprint get()=pulses.dropWhile{!it.level}.dropLastWhile{!it.level&&it.duration_us>=3000}.map{(if(it.level)1 else -1)*(it.duration_us/20)}.hashCode()
    /** What makes two receptions one signal, as desktop KAT's duplicate check: a decode's protocol, data (its first 64 bits), serial, button and counter, so every press, and every send of a press whose sends differ (Ford V1's flags), is its own; for a signal KAT does not decode, the whole pulse train. (It used to be the first 256 pulses for every signal: a Ford V1 press is all preamble there, so every press and button of a remote became one entry.) */
    val identity:String get(){
        val data=decode.takeIf{it.isNotBlank()}?.let{runCatching{JsonParser.parseString(it).asJsonObject["data"]?.takeIf{d->d.isJsonPrimitive}?.asString}.getOrNull()}
        return if(protocol!="Unknown"&&data!=null)"decode|$protocol|$data|$serial|$button|$counter" else "pulses|$fingerprint"
    }
    /** Where the remote sent, as heard (desktop KAT's `heard_frequency`): [frequency] plus the measured offset, to the nearest kHz. Transmissions and exports use it: a remote heard 330 kHz from the tuned frequency is on a channel of its own, which its car's receiver listens on. */
    val heardFrequency:Long get()=offsetHz?.takeIf{it.isFinite()}?.let{Math.round((frequency+it)/1000.0)*1000L}?:frequency
}

/** The capture with KAT's decode [json], and the serial, button and counter desktop KAT lists from it. */
fun Capture.withDecode(json:String):Capture{
    val d=json.takeIf{it.isNotBlank()}?.let{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}?:return this
    fun number(key:String)=d[key]?.takeIf{it.isJsonPrimitive}?.asLong
    return copy(decode=json,serial=number("serial")?.let{"%07X".format(java.util.Locale.US,it)}?:serial,
        button=number("button")?.let{"0x%02X".format(java.util.Locale.US,it)}?:button,
        counter=number("counter")?.let{"%04X".format(java.util.Locale.US,it)}?:counter)
}

/** Desktop KAT's TX power steps (TX gain dB, RF amplifier). */
val TX_POWER_STEPS=listOf(47 to true,40 to true,30 to true,47 to false,40 to false,30 to false,20 to false,10 to false,0 to false)
fun txPowerLabel(gain:Int,amp:Boolean)="$gain dB"+if(amp)" + amp" else ""
data class Saved(val settings:RadioSettings=RadioSettings(),val captures:List<Capture> = emptyList())
data class IqSpec(val rate:Int,val center:Long,val target:Long){val offsetHz get()=(target-center).toDouble()}
data class RadioStatus(val running:Boolean=false,val device:String="No device",val firmware:String="",
    val radioKind:RadioKind?=null,val activeSampleRate:Int=2_000_000,
    val message:String="Connect HackRF or RTL-SDR using USB OTG",val frequency:Long=433_920_000,
    val hopIndex:Int=0,val hopTotal:Int=0,val hopSwitches:Long=0,
    val bytes:Long=0,val bytesPerSecond:Long=0,val gaps:Long=0,val powerDb:Float=-100f,
    val spectrum:List<Float> = emptyList(),val recording:String="",val recordingBytes:Long=0,val transmitting:Boolean=false)

object StateFormat {
    val gson=Gson()
    private fun merge(default:JsonObject,incoming:JsonObject):JsonObject=default.deepCopy().also{out->incoming.entrySet().forEach{(k,v)->if(!v.isJsonNull)out.add(k,if(v.isJsonObject&&out[k]?.isJsonObject==true)merge(out[k].asJsonObject,v.asJsonObject)else v)}}
    fun decode(text:String):Saved {
        require(text.length<32*1024*1024)
        val root=merge(gson.toJsonTree(Saved()).asJsonObject,JsonParser.parseString(text).asJsonObject)
        root.add("settings",merge(gson.toJsonTree(RadioSettings()).asJsonObject,root["settings"].asJsonObject))
        val items=com.google.gson.JsonArray()
        root["captures"].asJsonArray.forEach{items.add(merge(gson.toJsonTree(Capture()).asJsonObject,it.asJsonObject))}
        root.add("captures",items)
        val state=gson.fromJson(root,Saved::class.java);state.settings.validate()
        require(state.captures.size<=2000)
        state.captures.forEach{require(it.pulses.size<=32768&&it.pulses.all{p->p.duration_us in 1..60_000_000})}
        return state
    }
    fun importFob(text:String):Capture {
        require(text.length<=4*1024*1024)
        val root=JsonParser.parseString(text).asJsonObject
        val c=if(root["capture"]?.isJsonObject==true)root["capture"].asJsonObject else root
        val signal=root["signal"]?.takeIf{it.isJsonObject}?.asJsonObject
        fun str(k:String)=(signal?.get(k)?:c[k])?.takeIf{it.isJsonPrimitive}?.asString.orEmpty()
        val pulses=(c["raw_pairs"]?:c["pulses"])?.takeIf{it.isJsonArray}?.asJsonArray?.map{val p=it.asJsonObject;Pulse(p["level"].asBoolean,p["duration_us"].asInt)}.orEmpty()
        require(pulses.size<=32768&&pulses.all{it.duration_us in 1..60_000_000})
        val recordedTime=str("time").toLongOrNull()?:runCatching{java.time.Instant.parse(str("timestamp")).toEpochMilli()}.getOrNull()?:System.currentTimeMillis()
        // A desktop KAT .fob holds its capture's decode (after each transmission's counter too), which the encoders send from.
        return Capture(time=recordedTime,frequency=str("frequency").toLongOrNull()?:433_920_000,protocol=str("protocol").ifBlank{"Imported"},
            modulation=str("received_rf").ifBlank{str("modulation")}.ifBlank{"AM"}.uppercase(java.util.Locale.US),source="FOB import",pulses=pulses,
            serial=str("serial"),button=str("button"),counter=str("counter"),metadata=text).withDecode(NativeTx.fobDecode(text))
    }
    /** KAT v2 interchange. A waveform export always carries every saved pair. */
    fun fob(c:Capture):String {
        require(c.pulses.isNotEmpty()){"No saved pulses for FOB export"}
        val existing=runCatching{JsonParser.parseString(c.metadata).asJsonObject}.getOrNull()
        val root=if(existing?.get("format")?.asString=="kat-fob"&&existing.get("signal")?.isJsonObject==true)existing.deepCopy()else JsonObject().apply{
            addProperty("version","2.0");addProperty("format","kat-fob")
            add("signal",JsonObject().apply{
                addProperty("protocol",c.protocol);addProperty("frequency",c.heardFrequency)
                addProperty("frequency_mhz",java.lang.String.format(java.util.Locale.US,"%.3f",c.heardFrequency/1e6))
                addProperty("modulation","Unknown");addProperty("received_rf",c.modulation)
                addProperty("encryption","Unknown");addProperty("data_bits",0);addProperty("data_hex","")
                addProperty("serial","-");addProperty("key","");addProperty("button_name","-")
                addProperty("crc_valid",false);addProperty("encoder_capable",false)
                if(c.modulation=="AM")addProperty("preset","FuriHalSubGhzPresetOok650Async")
            })
            add("vehicle",JsonObject().apply{addProperty("make","")})
            add("capture",JsonObject().apply{addProperty("timestamp",java.time.Instant.ofEpochMilli(c.time).toString())})
        }
        root.getAsJsonObject("capture").apply{add("raw_pairs",gson.toJsonTree(c.pulses));addProperty("raw_pair_count",c.pulses.size)}
        // KAT's decode, as desktop KAT writes it, so desktop KAT continues from it (a rolling code's last counter sent).
        val d=c.decode.takeIf{it.isNotBlank()}?.let{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}
        if(d!=null)root.getAsJsonObject("signal").apply{
            fun number(key:String)=d[key]?.takeIf{it.isJsonPrimitive}?.asLong
            addProperty("protocol",d["protocol"].asString);addProperty("data_bits",d["bits"].asInt)
            addProperty("data_hex",d["data"].asString);addProperty("key",d["data"].asString)
            addProperty("serial",number("serial")?.let{"%07X".format(java.util.Locale.US,it)}?:"-")
            number("button")?.let{addProperty("button",it)}?:remove("button")
            number("counter")?.let{addProperty("counter",it)}?:remove("counter")
            addProperty("crc_valid",d["crc_valid"].asBoolean);addProperty("encoder_capable",d["encoder_capable"].asBoolean)
            d["extra"]?.takeIf{it.isJsonPrimitive}?.let{addProperty("data_extra",it.asString.padStart(16,'0'))}?:remove("data_extra")
        }
        return gson.toJson(root)
    }
    fun iqSpec(name:String,s:RadioSettings):IqSpec {
        val rate=Regex("_(\\d+)sps",RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()?:s.sampleRate
        val center=Regex("_(\\d+)Hz",RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toLongOrNull()?:s.frequency
        val target=Regex("_rx(\\d+)Hz",RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toLongOrNull()?:center
        require(rate in 250_000..20_000_000&&center in 0..6_000_000_000L&&target in 1_000_000L..6_000_000_000L)
        require(kotlin.math.abs(target-center)<=rate/2){"Target frequency is outside the IQ recording bandwidth"}
        return IqSpec(rate,center,target)
    }
    fun importRawSub(text:String):Capture {
        require(text.length<=4*1024*1024)
        val frequency=Regex("(?m)^Frequency:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLong()?:error("Missing frequency")
        require(Regex("(?m)^Protocol:\\s*RAW\\s*$").containsMatchIn(text)){"Analysis supports RAW .sub files"}
        val values=text.lineSequence().filter{it.startsWith("RAW_Data:")}.flatMap{it.substringAfter(':').trim().split(Regex("\\s+")).asSequence()}.map{it.toLong()}.toList()
        require(values.size in 2..32768&&values.all{it in -60_000_000L..60_000_000L&&it!=0L})
        val preset=Regex("(?m)^Preset:\\s*(.*)$").find(text)?.groupValues?.get(1).orEmpty()
        val modulation=if(preset.contains("FSK",true)||preset.contains("FM",true))"FM"else"AM"
        val c=Capture(frequency=frequency,modulation=modulation,source="RAW import",pulses=values.map{Pulse(it>0,kotlin.math.abs(it).toInt())})
        val match=ProtocolRecognizer().identify(c.pulses,c.modulation,c.frequency)
        return if(match==null)c else c.copy(protocol=match.name,recognition=match.evidence).withDecode(match.decode)
    }
    fun rawSub(c:Capture):String="Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: ${c.heardFrequency}\nPreset: FuriHalSubGhzPresetOok650Async\nProtocol: RAW\n"+
        c.pulses.chunked(512).joinToString("\n"){batch->"RAW_Data: "+batch.joinToString(" "){((if(it.level)1 else -1)*it.duration_us).toString()}}+"\n"
    fun captureJson(c:Capture,includeRaw:Boolean):String {
        val root=if(c.metadata.isNotBlank())JsonParser.parseString(c.metadata).asJsonObject else gson.toJsonTree(c).asJsonObject
        if(!includeRaw){val target=if(root["capture"]?.isJsonObject==true)root["capture"].asJsonObject else root;target.remove("raw_pairs");target.remove("pulses")}
        return gson.toJson(root)
    }
    fun ini(text:String,base:RadioSettings):RadioSettings {
        require(text.length<=128*1024)
        val values=text.lineSequence().map{it.substringBefore(';').substringBefore('#').trim()}.filter{'=' in it}.associate{it.substringBefore('=').trim() to it.substringAfter('=').trim()}
        fun i(k:String,d:Int)=values[k]?.toIntOrNull()?:d
        fun b(k:String,d:Boolean)=values[k]?.lowercase()?.let{it in listOf("true","yes","1","on")}?:d
        return base.copy(frequency=values["default_frequency"]?.toLongOrNull()?:base.frequency,
            lna=i("default_lna_gain",base.lna),vga=i("default_vga_gain",base.vga),amp=b("default_amp",base.amp),
            hopFrequencies=values["hop_frequencies"]?.split(',')?.joinToString(","){(it.trim().toBigDecimal()/1_000_000.toBigDecimal()).toPlainString()}?:base.hopFrequencies,
            hopDwellMs=i("hop_dwell_ms",base.hopDwellMs),maxCaptures=i("max_captures",base.maxCaptures).let{if(it==0)2000 else it},
            showUnknown=b("show_unknown_signals",base.showUnknown),ignoreDuplicates=b("ignore_duplicates",base.ignoreDuplicates),
            autosave=b("session_autosave",base.autosave),includeRaw=b("include_raw_pairs",base.includeRaw),
            txVga=i("tx_vga_gain",base.txVga),txAmp=b("tx_amp",base.txAmp)).also{it.validate()}
    }
}

data class Control(val input:Boolean,val request:Int,val value:Int=0,val index:Int=0,val data:ByteArray=byteArrayOf())
object Wire {
    fun liveCenter(frequency:Long,rate:Int)=(frequency-rate/8L).coerceAtLeast(0L)
    val bandwidths=listOf(1_750_000,2_500_000,3_500_000,5_000_000,5_500_000,6_000_000,7_000_000,8_000_000,9_000_000,10_000_000,12_000_000,14_000_000,15_000_000,20_000_000)
    private fun pair(a:Int,b:Int)=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(a).putInt(b).array()
    fun frequency(hz:Long)=Control(false,16,data=pair((hz/1_000_000).toInt(),(hz%1_000_000).toInt()))
    fun configuration(s:RadioSettings):List<Control>{s.validate();return listOf(Control(false,1),Control(false,6,data=pair(s.sampleRate,1)),
        Control(false,7,s.bandwidth and 65535,s.bandwidth ushr 16),frequency(s.frequency),
        Control(true,19,index=s.lna,data=ByteArray(1)),Control(true,20,index=s.vga,data=ByteArray(1)),Control(false,17,if(s.amp)1 else 0))}

    const val MODE_OFF=0
    const val MODE_RX=1
    const val MODE_TX=2
    /** Desktop KAT's TX: 2 MS/s, the LO 500 kHz below the carrier, a 1.75 MHz baseband filter. */
    const val TX_SAMPLE_RATE=2_000_000
    const val TX_LO_OFFSET_HZ=500_000L
    const val TX_FILTER_HZ=1_750_000
    /** SET_TRANSCEIVER_MODE: off, receive or transmit. */
    fun transceiver(mode:Int)=Control(false,1,mode)
    /** Everything before a transmission keys the HackRF (desktop KAT's `transmit_signal_hackrf`): transceiver off, sample rate, baseband filter, LO, TX gain (SET_TXVGA_GAIN, 0–47 dB), RF amplifier. */
    fun txConfiguration(carrierHz:Long,vgaGain:Int,amp:Boolean):List<Control>{
        require(carrierHz-TX_LO_OFFSET_HZ in 1_000_000L..6_000_000_000L){"Frequency outside the HackRF's range"}
        require(vgaGain in 0..47){"TX gain: 0–47 dB"}
        return listOf(transceiver(MODE_OFF),Control(false,6,data=pair(TX_SAMPLE_RATE,1)),Control(false,7,TX_FILTER_HZ and 65535,TX_FILTER_HZ ushr 16),
            frequency(carrierHz-TX_LO_OFFSET_HZ),Control(true,21,index=vgaGain,data=ByteArray(1)),Control(false,17,if(amp)1 else 0))
    }
}
