package app.katmobile

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The signal list: what makes two receptions one entry, and where a capture is sent. */
class CaptureListTest {
    @Before fun emptyList(){Hub.restore(Saved())}

    /** A Ford V1 burst: 400 preamble pairs (more than the first 256 pulses), then 136 data half-bits that differ with `tail`. */
    private fun fordV1(tail:Int):List<Pulse> = List(400){listOf(Pulse(true,130),Pulse(false,130))}.flatten()+List(136){Pulse(it%3==tail%3,65)}+Pulse(false,50_000)
    private fun press(button:Int,counter:Int,flags:Int=0,offset:Double=-329_500.0,time:Long=1_000):Capture{
        val data="%014X".format(java.util.Locale.US,0x0026_0000_0000L or (flags.toLong() shl 16))
        val decode="""{"protocol":"Ford V1","data":"$data","extra":"0011223344556677","serial":1193046,"button":$button,"counter":$counter,"bits":136,"crc_valid":true,"encoder_capable":true}"""
        return Capture(time=time,modulation="FM",protocol="Ford V1",pulses=fordV1(counter),recognition="KAT DSP · offset $offset",offsetHz=offset).withDecode(decode)
    }

    @Test fun everyPressButtonAndSendOfAFordV1RemoteIsItsOwnEntry(){
        // Their first 256 pulses are all preamble, which made one entry of every press and button.
        val lock=press(1,0x10);val unlock=press(2,0x11);val lock2=press(1,0x12)
        val sameFrameAgain=press(1,0x12,offset=331_200.0,time=5_000)
        val thirdSend=press(1,0x12,flags=0x10,offset=477_000.0)
        listOf(lock,unlock,lock2,sameFrameAgain,thirdSend).forEach(Hub::add)
        val list=Hub.captures.value
        assertEquals(listOf(lock.id,unlock.id,lock2.id,thirdSend.id),list.map{it.id})
        assertEquals(listOf(1,1,2,1),list.map{it.repeats})
        // A repeat counts on the first reception, whose pulses, measurement and decode the entry keeps.
        assertEquals(-329_500.0,list[2].offsetHz!!,0.0)
        assertEquals(lock2.recognition,list[2].recognition)
        assertEquals(lock2.pulses,list[2].pulses)
        assertEquals(thirdSend,Hub.latestReception.value)
    }

    @Test fun undecodedSignalsAreComparedOverTheirWholePulseTrain(){
        val a=Capture(pulses=fordV1(0));val b=Capture(pulses=fordV1(1))
        assertNotEquals(a.identity,b.identity)
        Hub.add(a);Hub.add(b);Hub.add(a.copy(id="again",time=a.time+2_000))
        assertEquals(listOf(a.id to 2,b.id to 1),Hub.captures.value.map{it.id to it.repeats})
    }

    @Test fun aCaptureIsSentAndExportedWhereItWasHeard(){
        val c=Capture(frequency=433_920_000,pulses=listOf(Pulse(true,400),Pulse(false,800)))
        assertEquals(433_920_000L,c.heardFrequency)
        assertEquals(433_591_000L,c.copy(offsetHz=-329_500.0).heardFrequency)
        assertEquals(434_397_000L,c.copy(offsetHz=477_200.0).heardFrequency)
        assertEquals(433_906_000L,c.copy(offsetHz=-14_449.0).heardFrequency)
        assertEquals(433_920_000L,c.copy(offsetHz=Double.NaN).heardFrequency)
        val heard=c.copy(offsetHz=-329_500.0)
        assertTrue(StateFormat.rawSub(heard).contains("\nFrequency: 433591000\n"))
        assertTrue(StateFormat.fob(heard).contains("\"frequency\":433591000"))
    }

    @Test fun theMeasuredOffsetIsSavedAndOlderSessionsLoadWithoutIt(){
        assertNull(StateFormat.decode("""{"captures":[{"id":"old","frequency":433920000,"pulses":[]}]}""").captures.single().offsetHz)
        val saved=StateFormat.gson.toJson(Saved(captures=listOf(Capture(offsetHz=-329_500.0))))
        assertEquals(-329_500.0,StateFormat.decode(saved).captures.single().offsetHz!!,0.0)
    }
}
