package app.katmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Flipper Zero session (native/src/mobile_flipper.rs) through the JNI bridge it uses on a phone
 * (this debuggable app runs with CheckJNI), against a simulated Flipper command line: the console
 * native/tests/flipper_session.rs and desktop KAT's tests simulate. No USB: nothing is transmitted.
 */
@RunWith(AndroidJUnit4::class)
class FlipperDeviceTest {
    @Test fun aPressReachesTheAppFromTheStreamOverJni(){
        val (pulses,pressLength)=streamWithAPress()
        val console=FakeFlipperConsole(rxRawText(pulses))
        NativeFlipper.open(console).use{session->
            session.startRx(433_920_000)
            val bursts=ArrayList<NativeFlipper.Burst>()
            repeat(400){val out=session.pump();assertNull(out.error);assertFalse(out.closed);bursts+=out.bursts}
            bursts+=session.stopRx().bursts
            val recognizer=ProtocolRecognizer()
            val princeton=bursts.filter{b->recognizer.identify(b.pulses.map{Pulse(it>0,kotlin.math.abs(it))},"AM",433_920_000,receiverPath=true)?.name?.contains("Princeton")==true}
            assertTrue("No Princeton burst in ${bursts.size} bursts",princeton.isNotEmpty())
            assertTrue("${princeton[0].pulses.size} pulses: not cut out of the noise",princeton[0].pulses.size<=pressLength+8)
            assertTrue(princeton[0].end_us>princeton[0].start_us)
        }
        assertEquals(listOf("subghz rx_raw 433920000"),console.commands)
    }
    @Test fun aTransmissionIsWrittenToTheSdCardAndPlayedOverJni(){
        val console=FakeFlipperConsole(ByteArray(0))
        val press=princeton()
        NativeFlipper.open(console).use{session->
            session.startRx(433_920_000)
            session.transmit(TxPlan(pulses=press,fsk=false),433_591_000)
            session.transmit(TxPlan(pulses=press,fsk=true,deviation_hz=47_607.421875),433_920_000)
        }
        assertEquals(2,console.transmitted.size)
        assertTrue(console.transmitted[0],console.transmitted[0].startsWith("Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: 433591000\nPreset: FuriHalSubGhzPresetOok650Async\nProtocol: RAW\nRAW_Data: 1170 -390 390 -1170"))
        assertTrue(console.transmitted[1],console.transmitted[1].contains("\nPreset: FuriHalSubGhzPreset2FSKDev476Async\n"))
        // Receiving stopped for each transmission and started again; closing the session stopped it.
        assertEquals(3,console.commands.count{it.startsWith("subghz rx_raw")})
        assertTrue(console.commands.toString(),console.commands.indexOfFirst{it.startsWith("subghz tx_from_file /ext/apps_data/kat/tx.sub 1 0")}>0)
        assertFalse("rx_raw left running",console.streaming)
    }
    @Test fun refusalsAndAPortThatNeverAnswersAreErrorsNotCrashes(){
        val busy=FakeFlipperConsole(ByteArray(0)).apply{subghzRefusal="subghz command cannot be run while an application is open"}
        NativeFlipper.open(busy).use{session->
            val e=runCatching{session.startRx(433_920_000)}.exceptionOrNull()
            assertTrue("$e",e is IllegalStateException&&e.message.orEmpty().contains("an app is open on the Flipper"))
        }
        val locked=FakeFlipperConsole(ByteArray(0)).apply{refuseTx=true}
        NativeFlipper.open(locked).use{session->
            val e=runCatching{session.transmit(TxPlan(pulses=princeton()),433_920_000)}.exceptionOrNull()
            assertTrue("$e",e is IllegalStateException&&e.message.orEmpty().contains("restricted"))
        }
        assertTrue(locked.transmitted.isEmpty())
        val mute=FakeFlipperConsole(ByteArray(0)).apply{this.mute=true}
        val e=runCatching{NativeFlipper.open(mute)}.exceptionOrNull()
        assertTrue("$e",e is IllegalStateException&&e.message.orEmpty().contains("no answer from its console"))
    }
    @Test fun anUnpluggedFlipperEndsTheSession(){
        val (pulses,_)=streamWithAPress()
        val console=FakeFlipperConsole(rxRawText(pulses))
        NativeFlipper.open(console).use{session->
            session.startRx(433_920_000)
            assertFalse(session.pump().closed)
            console.unplugged=true
            val out=session.pump()
            assertTrue("${out.error}",out.closed&&out.error!=null)
            assertTrue(runCatching{session.startRx(433_920_000)}.isFailure)
        }
    }
}

/** A Princeton press (24 bits, 390/1170 µs, sent four times). */
private fun princeton():IntArray {
    val p=ArrayList<Int>()
    repeat(4){
        for(c in "101001011001011010010110"){val (high,low)=if(c=='1')1170 to 390 else 390 to 1170;p+=high;p+=-low}
        p+=390;p+=-11_700
    }
    return p.toIntArray()
}

/** Noise as a Flipper's CC1101 gives it between transmissions: short, irregular pulses. */
private fun noise(n:Int,seed:LongArray,level:BooleanArray):List<Int> = List(n){
    var x=seed[0];x=x xor (x shl 13);x=x xor (x ushr 7);x=x xor (x shl 17);seed[0]=x
    level[0]=!level[0]
    val d=15+java.lang.Long.remainderUnsigned(x,240).toInt()
    if(level[0])d else -d
}

/** Noise, a Princeton press, noise; and the press's length. */
private fun streamWithAPress():Pair<IntArray,Int> {
    val seed=longArrayOf(0x5851_F42D_4C95_7F2DL);val level=booleanArrayOf(false)
    val out=ArrayList(noise(3000,seed,level))
    val press=princeton()
    if(level[0])out+=-180
    out.addAll(press.toList())
    level[0]=false
    out.addAll(noise(3000,seed,level))
    return out.toIntArray() to press.size
}

/** `rx_raw`'s text: `+n`/`-n` words, a line break every 256 (as the firmware). */
private fun rxRawText(pulses:IntArray):ByteArray {
    val t=StringBuilder()
    pulses.forEachIndexed{i,p->t.append(if(p>0)"+$p " else "$p ");if(i%256==255)t.append("\r\n\n")}
    return t.toString().toByteArray()
}

/**
 * A Flipper's command line as the core sees it over JNI: the prompt after Ctrl+C and after every
 * command, `rx_raw` streaming [rxScript] until Ctrl+C, `storage` (the files kept), `tx_from_file`,
 * and the refusals of a firmware with an app open or a region lock.
 */
private class FakeFlipperConsole(val rxScript:ByteArray):FlipperSerial {
    val commands=ArrayList<String>()
    val transmitted=ArrayList<String>()
    var subghzRefusal:String?=null
    var refuseTx=false
    var mute=false
    @Volatile var unplugged=false
    private val files=HashMap<String,java.io.ByteArrayOutputStream>()
    private val out=ArrayDeque<Byte>()
    private val line=StringBuilder()
    private var stream:ArrayDeque<Byte>?=null
    private var chunk:Pair<String,Int>?=null
    val streaming get()=stream!=null

    override fun read(buf:ByteArray,timeoutMs:Int):Int {
        if(unplugged)return -1
        stream?.let{s->repeat(700){s.removeFirstOrNull()?.let(out::addLast)}}
        if(out.isEmpty()){Thread.sleep(1);return 0}
        val n=minOf(buf.size,out.size)
        for(i in 0 until n)buf[i]=out.removeFirst()
        return n
    }
    override fun write(data:ByteArray,length:Int):Int {
        if(unplugged)return -1
        for(i in 0 until length)byte(data[i])
        return length
    }
    private fun say(text:String)=text.toByteArray().forEach(out::addLast)
    private fun prompt()=say("\r\n>: ")
    private fun byte(b:Byte){
        if(mute)return
        chunk?.let{(path,left)->
            files.getOrPut(path){java.io.ByteArrayOutputStream()}.write(b.toInt())
            chunk=if(left>1)path to left-1 else null
            if(chunk==null)prompt()
            return
        }
        if(stream!=null){if(b.toInt()==3){stream=null;prompt()};return}
        when(b.toInt()){
            3->{line.clear();prompt()}
            '\r'.code->{val cmd=line.toString().trim();line.clear();handle(cmd)}
            '\n'.code->{}
            else->line.append(b.toInt().toChar())
        }
    }
    private fun handle(cmd:String){
        if(cmd.isNotEmpty())commands+=cmd
        val words=cmd.split(Regex("\\s+")).filter{it.isNotEmpty()}
        if(subghzRefusal!=null&&words.firstOrNull()=="subghz"){say("\u001b[31m$subghzRefusal\u001b[0m");prompt();return}
        when{
            words.isEmpty()->{}
            words.size==3&&words[0]=="subghz"&&words[1]=="rx_raw"->{say("Listening at ${words[2]}. Press CTRL+C to stop\r\n");stream=ArrayDeque(rxScript.toList());return}
            words.size==3&&words[0]=="storage"&&words[1]=="mkdir"->say("Storage error: File/Dir already exists\r\n")
            words.size==3&&words[0]=="storage"&&words[1]=="remove"->files.remove(words[2])
            words.size==4&&words[0]=="storage"&&words[1]=="write_chunk"->{say("Ready\r\n");chunk=words[2] to words[3].toInt();return}
            words.size==5&&words[0]=="subghz"&&words[1]=="tx_from_file"->{
                if(refuseTx)say("Transmission on this frequency is restricted in your settings/region\r\n")
                else{transmitted+=files[words[2]]?.toString(Charsets.UTF_8).orEmpty();say("Listening at \u001b[0;33m${words[2]}\u001b[0m. Frequency=433920000, Protocol=RAW\r\n\r\nPress CTRL+C to stop\r\n\r\n..")}
            }
            else->say("`$cmd` command not found\r\n")
        }
        prompt()
    }
}
