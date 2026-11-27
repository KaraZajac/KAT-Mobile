package app.katmobile

import com.google.gson.Gson
import java.util.Locale
import kotlin.math.abs

private data class RxBurst(val modulation:String="",val pulses:IntArray=intArrayOf(),val snr_db:Float=0f,val offset_hz:Float=0f)
private data class RxOutput(val active:Boolean=false,val powerDb:Float=-100f,val bursts:List<RxBurst> = emptyList())

/** Uses the original desktop KAT IQ pipeline, including channel filtering and both slicers. */
class ReceivePipeline(
    private val settings:RadioSettings,
    private val inputOffsetHz:Double,
    private val iqFormat:IqFormat=IqFormat.CS8,
    private val source:String=RadioKind.HACKRF.label,
    private val capture:(Capture)->Unit
):AutoCloseable {
    private val recognizer=ProtocolRecognizer()
    private val gson=Gson()
    private var handle:Long
    var frequency=settings.frequency
        set(value){if(field!=value){field=value;reset()}}
    var active=false;private set
    var powerDb=-100f;private set
    constructor(settings:RadioSettings,capture:(Capture)->Unit):this(settings,0.0,IqFormat.CS8,RadioKind.HACKRF.label,capture)
    constructor(settings:RadioSettings,inputOffsetHz:Double,capture:(Capture)->Unit):this(settings,inputOffsetHz,IqFormat.CS8,RadioKind.HACKRF.label,capture)
    init{check(NativeDecoder.available){"The KAT receive core is unavailable"};handle=NativeRx.create(settings.sampleRate,frequency,inputOffsetHz,iqFormat.unsigned);NativeDecoder.reset()}
    fun process(bytes:ByteArray,length:Int=bytes.size){check(handle!=0L);require(length in 0..bytes.size);accept(NativeRx.process(handle,bytes,length,false))}
    fun flush(){if(handle!=0L)try{accept(NativeRx.process(handle,byteArrayOf(),0,true))}finally{close()}}
    fun reset(){close();handle=NativeRx.create(settings.sampleRate,frequency,inputOffsetHz,iqFormat.unsigned);NativeDecoder.reset()}
    override fun close(){if(handle!=0L){NativeRx.close(handle);handle=0};active=false}
    private fun accept(json:String){
        val out=gson.fromJson(json,RxOutput::class.java);active=out.active;powerDb=out.powerDb
        for(b in out.bursts){
            if(settings.modulation!="AUTO"&&settings.modulation!=b.modulation)continue
            if(b.pulses.size !in 8..32768||b.pulses.any{it==0||it==Int.MIN_VALUE})continue
            val pulses=b.pulses.map{Pulse(it>0,abs(it))}
            val match=recognizer.identify(pulses,b.modulation,frequency,receiverPath=true)
            if(match!=null||settings.showUnknown){
                val diagnostic=String.format(Locale.US,"KAT DSP · SNR %.1f dB · offset %+.1f kHz",b.snr_db,b.offset_hz/1000)
                capture(Capture(frequency=frequency,modulation=b.modulation,protocol=match?.name?:"Unknown",source=source,
                    recognition=listOfNotNull(match?.evidence,diagnostic).joinToString(" · "),pulses=pulses,
                    offsetHz=b.offset_hz.toDouble().takeIf{it.isFinite()}).withDecode(match?.decode.orEmpty()))
            }
        }
    }
}

internal object NativeRx {
    fun create(rate:Int,frequency:Long,offset:Double,unsigned:Boolean=false)=nativeCreate(rate,frequency,offset,unsigned)
    fun process(handle:Long,input:ByteArray,length:Int,flush:Boolean)=nativeProcess(handle,input,length,flush)
    fun close(handle:Long)=nativeClose(handle)
    @JvmStatic private external fun nativeCreate(rate:Int,frequency:Long,offset:Double,unsigned:Boolean):Long
    @JvmStatic private external fun nativeProcess(handle:Long,input:ByteArray,length:Int,flush:Boolean):String
    @JvmStatic private external fun nativeClose(handle:Long)
}
