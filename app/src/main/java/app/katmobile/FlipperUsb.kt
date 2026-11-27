package app.katmobile

import android.hardware.usb.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What KAT's core reads and writes a Flipper Zero's console through: native/src/mobile_flipper.rs
 * calls these two methods by name over JNI. [FlipperUsb] is the Flipper; the device tests stand a
 * simulated console in its place.
 */
internal interface FlipperSerial {
    /** Bytes read into [buf]: 0 when none came within [timeoutMs], −1 once the connection is gone. */
    fun read(buf:ByteArray,timeoutMs:Int):Int
    /** [length] bytes of [data] written: how many, −1 on failure. */
    fun write(data:ByteArray,length:Int):Int
}

/** Bytes as they arrive, read in any amounts: what [FlipperUsb]'s reader thread hands [FlipperUsb.read]. */
internal class ByteQueue {
    private val chunks=LinkedBlockingQueue<ByteArray>()
    private var head:ByteArray?=null
    private var offset=0
    @Volatile var closed=false
        private set
    fun put(bytes:ByteArray,length:Int){if(length>0)chunks.put(bytes.copyOf(length))}
    /** No more bytes come; a waiting read wakes up. */
    fun close(){closed=true;chunks.offer(ByteArray(0))}
    /** Up to [buf]'s size: what has arrived, waiting up to [timeoutMs] for the first byte; 0 when none came, −1 once closed and read out. */
    fun read(buf:ByteArray,timeoutMs:Int):Int {
        var n=0
        while(n<buf.size){
            var h=head
            if(h==null){
                h=if(n==0)chunks.poll(timeoutMs.toLong().coerceAtLeast(0),TimeUnit.MILLISECONDS)else chunks.poll()
                if(h==null||h.isEmpty())break
                head=h;offset=0
            }
            val take=minOf(buf.size-n,h.size-offset)
            System.arraycopy(h,offset,buf,n,take);n+=take;offset+=take
            if(offset==h.size)head=null
        }
        return if(n==0&&closed)-1 else n
    }
}

/**
 * A Flipper Zero's console (USB CDC: ST's virtual COM port, 0483:5740) over Android's USB host
 * API, as desktop KAT opens it as a serial port (`src/radio/flipper.rs`): 115200 8N1 and DTR
 * raised, on which the Flipper starts a console session. A thread keeps reads queued on the bulk
 * IN endpoint (a read that times out would lose what part of it had arrived), so the console's
 * bytes reach [read] in order and complete; [write] sends on bulk OUT.
 */
class FlipperUsb(manager:UsbManager,private val device:UsbDevice):FlipperSerial,AutoCloseable {
    private val interfaces=(0 until device.interfaceCount).map(device::getInterface)
    private val dataIface=interfaces.firstOrNull{i->i.interfaceClass==UsbConstants.USB_CLASS_CDC_DATA&&bulk(i,UsbConstants.USB_DIR_IN)!=null&&bulk(i,UsbConstants.USB_DIR_OUT)!=null}
        ?:error("This USB device has no serial console")
    private val controlIface=interfaces.firstOrNull{it.interfaceClass==UsbConstants.USB_CLASS_COMM}
    private val epIn=bulk(dataIface,UsbConstants.USB_DIR_IN)!!
    private val epOut=bulk(dataIface,UsbConstants.USB_DIR_OUT)!!
    private val connection=manager.openDevice(device)?:error("Android could not open the Flipper Zero")
    private val inbox=ByteQueue()
    private val stopped=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private val received=AtomicLong(0)
    private val requests=ArrayList<UsbRequest>()
    private var reader:Thread?=null
    val description:String=runCatching{device.productName}.getOrNull()?.takeIf{it.isNotBlank()}?:"Flipper Zero"
    /** Bytes received since it was opened: how fast the console streams. */
    val bytesReceived get()=received.get()

    init {
        try{
            controlIface?.let{check(connection.claimInterface(it,true)){"The Flipper's USB interface is busy"}}
            check(connection.claimInterface(dataIface,true)){"The Flipper's console is busy (another app has it)"}
            val index=(controlIface?:dataIface).id
            connection.controlTransfer(CDC_OUT,SET_LINE_CODING,0,index,lineCoding(115_200),7,1000)
            check(connection.controlTransfer(CDC_OUT,SET_CONTROL_LINE_STATE,DTR or RTS,index,null,0,1000)>=0){"The Flipper did not take DTR, so its console did not open"}
            repeat(QUEUED){
                val r=UsbRequest()
                check(r.initialize(connection,epIn)){"Could not read from the Flipper"}
                val b=ByteBuffer.allocateDirect(BUFFER)
                r.clientData=b
                requests.add(r)
                check(r.queue(b)){"Could not read from the Flipper"}
            }
            reader=Thread(::readLoop,"kat-flipper-usb").apply{isDaemon=true;start()}
        }catch(e:Exception){close();throw e}
    }

    private fun readLoop(){
        val bytes=ByteArray(BUFFER)
        try{
            while(!stopped.get()){
                val r=try{connection.requestWait(250)}catch(_:java.util.concurrent.TimeoutException){continue}?:break
                if(stopped.get())break
                val b=r.clientData as ByteBuffer
                val n=b.position()
                if(n>0){b.flip();b.get(bytes,0,n);inbox.put(bytes,n);received.addAndGet(n.toLong())}
                b.clear()
                if(!r.queue(b))break
            }
        }catch(_:Exception){}
        finally{inbox.close()}
    }

    override fun read(buf:ByteArray,timeoutMs:Int):Int=inbox.read(buf,timeoutMs)

    override fun write(data:ByteArray,length:Int):Int {
        if(stopped.get())return -1
        var sent=0
        while(sent<length){
            val n=connection.bulkTransfer(epOut,data,sent,minOf(length-sent,BUFFER),2000)
            if(n<0)return if(sent>0)sent else -1
            sent+=n
        }
        return sent
    }

    /** Close the connection (dropping DTR, as closing a serial port does). The core's session stops `rx_raw` before this. */
    override fun close(){
        stopped.set(true)
        if(!closed.compareAndSet(false,true))return
        requests.forEach{runCatching{it.cancel()}}
        runCatching{reader?.join(500)}
        requests.forEach{runCatching{it.close()}}
        runCatching{connection.controlTransfer(CDC_OUT,SET_CONTROL_LINE_STATE,0,(controlIface?:dataIface).id,null,0,500)}
        controlIface?.let{runCatching{connection.releaseInterface(it)}}
        runCatching{connection.releaseInterface(dataIface)}
        runCatching{connection.close()}
        inbox.close()
    }

    companion object {
        /** The Flipper Zero's USB IDs (ST's virtual COM port, as desktop KAT finds it when the system gives no names). */
        const val VENDOR_ID=0x0483
        const val PRODUCT_ID=0x5740
        fun isFlipper(d:UsbDevice)=d.vendorId==VENDOR_ID&&d.productId==PRODUCT_ID
        /** CDC class request, host to device, to an interface. */
        private const val CDC_OUT=0x21
        private const val SET_LINE_CODING=0x20
        private const val SET_CONTROL_LINE_STATE=0x22
        private const val DTR=0x01
        private const val RTS=0x02
        private const val BUFFER=16384
        private const val QUEUED=4
        private fun bulk(i:UsbInterface,direction:Int)=(0 until i.endpointCount).map(i::getEndpoint).firstOrNull{it.type==UsbConstants.USB_ENDPOINT_XFER_BULK&&it.direction==direction}
        /** CDC SET_LINE_CODING's 7 bytes: the rate (little endian), 1 stop bit, no parity, 8 data bits. */
        fun lineCoding(baud:Int)=byteArrayOf(baud.toByte(),(baud shr 8).toByte(),(baud shr 16).toByte(),(baud shr 24).toByte(),0,0,8)
    }
}

/** JNI bindings to native/src/mobile_flipper.rs: a session on a Flipper Zero's command line, as desktop KAT drives one. */
internal object NativeFlipper {
    private val gson=Gson()
    /** A burst the core cut out of the stream: pulses (+ HIGH, − LOW, µs), and when it was on air (µs into the receive session). */
    data class Burst(val pulses:IntArray=intArrayOf(),val start_us:Long=0,val end_us:Long=0)
    /** One read of the stream: the bursts it completed; why receiving ended, if it did; whether the console is gone. */
    data class Pump(val bursts:List<Burst> = emptyList(),val error:String?=null,val closed:Boolean=false)

    class Session internal constructor(private val handle:Long):AutoCloseable {
        /** `subghz rx_raw` at [frequency]; the Flipper's refusal, in words, as the exception. */
        fun startRx(frequency:Long){val e=nativeStartRx(handle,frequency);if(e.isNotEmpty())error(e)}
        fun pump():Pump=gson.fromJson(nativePump(handle),Pump::class.java)
        fun stopRx():Pump=gson.fromJson(nativeStopRx(handle),Pump::class.java)
        /** [plan] at [carrier]: written to the SD card and played (receiving pauses and resumes); the Flipper's refusal as the exception. */
        fun transmit(plan:TxPlan,carrier:Long){val e=nativeTransmit(handle,plan.pulses,carrier,plan.fsk,plan.deviation_hz);if(e.isNotEmpty())error(e)}
        override fun close()=nativeClose(handle)
    }

    fun open(serial:FlipperSerial):Session {
        check(NativeDecoder.available){"The KAT core is unavailable"}
        val out=JsonParser.parseString(nativeOpen(serial)).asJsonObject
        out["error"]?.takeIf{it.isJsonPrimitive}?.let{error("Flipper Zero: ${it.asString}")}
        return Session(out["handle"].asLong)
    }

    @JvmStatic private external fun nativeOpen(bridge:Any):String
    @JvmStatic private external fun nativeStartRx(handle:Long,frequency:Long):String
    @JvmStatic private external fun nativePump(handle:Long):String
    @JvmStatic private external fun nativeStopRx(handle:Long):String
    @JvmStatic private external fun nativeTransmit(handle:Long,pulses:IntArray,frequency:Long,fsk:Boolean,deviationHz:Double):String
    @JvmStatic private external fun nativeClose(handle:Long)
}
