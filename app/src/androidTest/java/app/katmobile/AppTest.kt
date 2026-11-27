package app.katmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun reset(){
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".qa")){"Destructive test setup is restricted to the separate QA app"}
        compose.runOnIdle{compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;Hub.notice.value="";Hub.restore(Saved())}
        compose.waitUntil(15000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
    }
    @After fun removeTestData(){if(!InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".qa"))return;compose.runOnIdle{Hub.notice.value="";Hub.restore(Saved())};runBlocking{Hub.flush()}}
    private fun capture(name:String){compose.mainClock.advanceTimeBy(800);compose.waitForIdle();Thread.sleep(1000);val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(compose.activity.filesDir,"qa").mkdirs();File(compose.activity.filesDir,"qa/$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    @Test fun hopTimerChangesEverySecondWithoutAnyReceivedSignal()=runBlocking{
        val events=kotlinx.coroutines.channels.Channel<Pair<Long,Long>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val start=android.os.SystemClock.elapsedRealtime()
        val schedule=HopSchedule(listOf(315000000L,433920000L,868350000L),1000,start)
        val timer=launch(Dispatchers.Default){runHopTimer(schedule,android.os.SystemClock::elapsedRealtime){hop->events.trySend(android.os.SystemClock.elapsedRealtime() to hop.frequency)}}
        try{
            val received=withTimeout(6000){List(3){events.receive()}}
            assertEquals(listOf(433920000L,868350000L,315000000L),received.map{it.second})
            var previous=start
            received.forEach{(at,_)->assertTrue("Timer interval ${at-previous} ms",at-previous in 900..1800);previous=at}
        }finally{timer.cancelAndJoin();events.close()}
    }
    @Test fun monitoringUpdatesAcrossTabsAndIncludesDuplicates(){
        val first=Capture(protocol="Monitor A",pulses=listOf(Pulse(true,400),Pulse(false,800)))
        compose.runOnIdle{Hub.add(first)}
        compose.onNodeWithTag("latest-reception").onChildren().filterToOne(hasText("Monitor A",substring=true)).assertExists()
        compose.onNodeWithText("Signals",useUnmergedTree=true).performClick()
        val second=Capture(protocol="Monitor B",pulses=listOf(Pulse(true,600),Pulse(false,1200)))
        compose.runOnIdle{Hub.add(second)}
        compose.onNodeWithTag("protocol-group-Monitor B").assertExists()
        val duplicate=second.copy(id="monitor-duplicate",time=second.time+1000)
        compose.runOnIdle{Hub.add(duplicate);assertEquals(2,Hub.captures.value.size);assertEquals(2,Hub.captures.value.last().repeats);assertEquals(duplicate,Hub.latestReception.value);assertEquals(second.pulses,Hub.captures.value.last().pulses)}
        compose.onNodeWithText("Receive",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("latest-reception").assertIsDisplayed()
        compose.onNodeWithText("Details",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Monitor B · HackRF",substring=true).assertExists()
        compose.onNodeWithText("Close").performClick()
        capture("07-monitoring")
    }
    @Test fun englishAboutShowsKaraThanksAndReleaseNotes(){
        compose.onNodeWithContentDescription("Settings",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("kara-thanks"))
        compose.onNodeWithTag("kara-thanks").assertIsDisplayed()
        capture("08-kara-thanks")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("What's new"))
        compose.onNodeWithText("What's new").performClick()
        compose.onNodeWithText("What's new · ${BuildConfig.VERSION_NAME}").assertExists()
        compose.onNodeWithText("English edition").assertExists()
        compose.onNodeWithText("KAT ${BuildConfig.KAT_CORE} receive core").assertExists()
        capture("09-whats-new")
        compose.onNodeWithText("Close").performClick()
    }
    @Test fun navigationAndSettingsAreUsable(){
        compose.onNodeWithText("Start RX").assertIsDisplayed();capture("01-receiver")
        compose.onNodeWithContentDescription("Settings",useUnmergedTree=true).performClick()
        capture("02-settings");compose.onNodeWithText("Frequency (MHz)").assertExists()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("TX power: 47 dB + amp"));compose.onNodeWithText("TX power: 47 dB + amp").assertExists()
        compose.onNodeWithText("Signals",useUnmergedTree=true).performClick();compose.onNodeWithText("Signals (0)").assertExists()
        compose.onNodeWithText("Archive",useUnmergedTree=true).performClick();compose.onNodeWithText("IQ archive").assertExists()
    }
    @Test fun noUsbDeviceCannotBecomeFakeLiveReception(){
        compose.onNodeWithText("Start RX").performClick()
        compose.onNodeWithText("No HackRF, RTL-SDR or Flipper Zero found.",substring=true).assertExists()
        assertFalse(Hub.status.value.running)
        compose.onNodeWithText("OK").performClick()
    }
    @Test fun importedMetadataSurvivesEncryptedSessionReload(){
        compose.runOnIdle{Hub.importCapture(StateFormat.importFob("""{"protocol":"Test signal","serial":42,"counter":171,"raw_pairs":[{"level":true,"duration_us":400},{"level":false,"duration_us":800}]}"""))}
        runBlocking{Hub.flush()}
        val restored=Vault(compose.activity).load();assertEquals("171",restored.captures.single().counter);assertEquals(2,restored.captures.single().pulses.size)
        val file=File(compose.activity.filesDir,"session.enc");assertFalse(String(file.readBytes()).contains("Test signal"))
        compose.onNodeWithText("Signals",useUnmergedTree=true).performClick();compose.onNodeWithText("Test signal").performClick();compose.onAllNodesWithTag("capture-row").onFirst().performClick();compose.onNodeWithText("Counter: 171",substring=true).assertExists();capture("03-signal")
    }
    @Test fun clearHistoryHasConfirmationAndUndo(){
        compose.runOnIdle{Hub.importCapture(Capture(protocol="Test history",pulses=listOf(Pulse(true,400))))}
        compose.onNodeWithText("Signals",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("Clear history").performClick();compose.onNodeWithText("Clear signal history?").assertExists()
        compose.onNodeWithText("Delete",useUnmergedTree=true).performClick();compose.waitUntil(5000){Hub.captures.value.isEmpty()}
        compose.onNodeWithContentDescription("Undo deletion").performClick();compose.waitUntil(5000){Hub.captures.value.size==1}
    }
    @Test fun landscapeShowsLiveListBesideReceiver(){
        val original=compose.activity.requestedOrientation
        try{
            compose.runOnIdle{compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(15000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.onNodeWithText("LIVE CAPTURES").assertExists();compose.onNodeWithText("Start RX").assertExists();Thread.sleep(1500);capture("04-landscape")
        }finally{compose.runOnIdle{compose.activity.requestedOrientation=original}}
    }
    @Test fun offlineIqUsesActualSamplesAndDoesNotClaimUsbReception(){
        val file=File(compose.activity.filesDir,"kat_433920000Hz_2000000sps_test.cs8")
        file.writeBytes(princetonIq(80000.0))
        try{
            compose.runOnIdle{Hub.configure(RadioSettings(sampleRate=4_000_000));Hub.analyze(compose.activity,android.net.Uri.fromFile(file))}
            compose.waitUntil(10000){!Hub.offlineBusy.value&&Hub.captures.value.isNotEmpty()}
            assertFalse(Hub.status.value.running);assertTrue(Hub.captures.value.all{it.source=="IQ file"})
            assertTrue(Hub.captures.value.any{it.modulation=="AM"&&it.pulses.size>=30&&it.protocol.split(" + ").contains("Princeton")})
            compose.onNodeWithText("Signals",useUnmergedTree=true).performClick();capture("06-iq-recognized")
        }finally{file.delete()}
    }
    @Test fun nativeCatalogContainsTheWholeDesktopRegistry(){
        assertTrue("Native KAT core unavailable: ${NativeDecoder.loadProblem}",NativeDecoder.available)
        assertEquals(167,NativeDecoder.protocols.size)
        assertTrue(NativeDecoder.protocols.any{it.name=="VAG"})
        assertTrue(NativeDecoder.protocols.any{it.name=="Princeton"})
        compose.onNodeWithContentDescription("Settings",useUnmergedTree=true).performClick()
        compose.onNodeWithText("View supported protocols").performClick()
        capture("05-protocols");compose.onNodeWithText("KAT RX · 167").assertExists()
    }
    @Test fun nativeCoreRecognizesSyntheticIqInsteadOfOnlyListingProtocols(){
        assertTrue(NativeDecoder.available)
        val captures=mutableListOf<Capture>();val engine=ReceivePipeline(RadioSettings(modulation="AM"),captures::add)
        val raw=princetonIq(0.0)
        for(start in raw.indices step 16384)engine.process(raw.copyOfRange(start,minOf(raw.size,start+16384)));engine.flush()
        assertTrue("Native RX did not identify format: ${captures.map{it.protocol}}",captures.any{it.protocol.split(" + ").contains("Princeton")&&it.recognition.startsWith("KAT RX")})
    }
    // A rotating RF carrier with nonzero receiver noise and DC bias, rather than constant-I DC steps.
    private fun princetonIq(offset:Double):ByteArray {
        val bytes=java.io.ByteArrayOutputStream();var sample=0L;val noise=java.util.Random(1901)
        fun segment(us:Int,on:Boolean){repeat(us*2){
            val phase=2*Math.PI*offset*sample++/2_000_000
            for(v in doubleArrayOf(kotlin.math.cos(phase),kotlin.math.sin(phase))){bytes.write((4+(noise.nextDouble()-.5)*12+(if(on)v*65 else 0.0)).toInt().toByte().toInt())}
        }}
        segment(30000,false)
        repeat(4){"101001011001011010010110".forEach{bit->segment(if(bit=='1')1170 else 390,true);segment(if(bit=='1')390 else 1170,false)};segment(390,true);segment(11700,false)}
        segment(100000,false)
        return bytes.toByteArray()
    }
    /** A virtual DoorHan remote that KAT's "Add manually" creates (serial 0x1234; not a real remote). */
    private val doorhan="""{"protocol":"KeeLoq (DoorHan)","serial":4660,"button":2,"counter":3,"crc_valid":true,"data":"B910EF112C480004","bits":64,"encoder_capable":true}"""
    /** Feed IQ as the HackRF sends it (2 MS/s, the waveform 500 kHz above the LO) through KAT's receiver. */
    private fun receiveBack(iq:ByteArray):List<Capture>{
        val heard=mutableListOf<Capture>()
        ReceivePipeline(RadioSettings(),500_000.0,heard::add).use{rx->
            val silence=ByteArray(160_000)
            for(block in listOf(silence,iq,silence,silence))for(start in block.indices step 16384)rx.process(block.copyOfRange(start,minOf(block.size,start+16384)))
            rx.flush()
        }
        return heard
    }
    @Test fun transmissionsAreReceivedBackThroughKatsReceiverOnDevice(){
        assertTrue(NativeDecoder.available)
        val captures=mutableListOf<Capture>()
        ReceivePipeline(RadioSettings(modulation="AM"),captures::add).use{rx->val raw=princetonIq(0.0);for(start in raw.indices step 16384)rx.process(raw.copyOfRange(start,minOf(raw.size,start+16384)));rx.flush()}
        val c=captures.first{it.protocol.split(" + ").contains("Princeton")}
        assertTrue("decode: ${c.decode}",c.decode.contains("\"protocol\":\"Princeton\""))
        val actions=NativeTx.actions(c.decode);assertTrue("$actions",actions.encodes&&actions.gate)
        for(action in listOf("replay","next")){
            val plan=NativeTx.plan(c,action);val iq=NativeTx.iq(plan)
            assertEquals(action,4*plan.air_us,iq.size.toLong());assertFalse(plan.fsk);assertNull("a fixed code keeps its capture",plan.sent)
            val heard=receiveBack(iq)
            assertTrue("$action: ${heard.map{it.protocol}}",heard.any{it.protocol.split(" + ").contains("Princeton")})
        }
    }
    @Test fun aRollingCodeAdvancesWithEveryPressOnDevice(){
        val remote=Capture(protocol="KeeLoq",frequency=433_920_000,modulation="AM",source="Test").withDecode(doorhan)
        val actions=NativeTx.actions(remote.decode);assertTrue("$actions",actions.encodes&&actions.gate&&actions.buttons.isNotEmpty())
        compose.runOnIdle{Hub.importCapture(remote)}
        val counters=mutableListOf<Int>()
        repeat(3){press->
            val c=Hub.captures.value.single{it.id==remote.id}
            val plan=NativeTx.plan(c,"next");assertNotNull("press ${press+1}",plan.sent);assertTrue(NativeTx.iq(plan).isNotEmpty())
            // What the radio service does once the transmission went out.
            Hub.transmitted(Hub.TxRequest(c.id,"the next code",c.frequency,plan))
            counters+=Hub.captures.value.single{it.id==remote.id}.counter.toInt(16)
        }
        assertTrue("$counters",counters[0]>3&&counters.zipWithNext().all{(a,b)->b>a})
        assertTrue(Hub.txMessage.value.startsWith("Sent the next code · counter"))
        val button=NativeTx.plan(Hub.captures.value.single{it.id==remote.id},"button",actions.buttons.first().code)
        assertEquals(actions.buttons.first().code,button.button)
    }
    @Test fun transmitControlsFollowTheRemoteAndNeedAHackRf(){
        val gate=Capture(protocol="KeeLoq",frequency=433_920_000,modulation="AM",source="Test",pulses=listOf(Pulse(true,400),Pulse(false,800))).withDecode(doorhan)
        compose.runOnIdle{Hub.importCapture(gate)}
        compose.onNodeWithText("Signals",useUnmergedTree=true).performClick();compose.onNodeWithText("KeeLoq").performClick();compose.onAllNodesWithTag("capture-row").onFirst().performClick()
        compose.onNodeWithText("Serial: 0001234",substring=true).assertExists()
        compose.onNodeWithText("Replay").assertExists();compose.onNodeWithText("Send next code").assertExists();compose.onNodeWithText("Send button…").assertExists()
        capture("10-transmit")
        compose.onNodeWithText("Send next code").performScrollTo().performClick()
        compose.waitUntil(5000){Hub.notice.value.isNotBlank()}
        compose.onNodeWithText("No HackRF or Flipper Zero to transmit with.",substring=true).assertExists()
        compose.onNodeWithText("OK").performClick()
        assertFalse(Hub.status.value.transmitting);assertNull(Hub.pendingTx.get())
        compose.onNodeWithText("Close").performClick()
        // A car remote gets the car commands (desktop KAT's TX Lock … Panic).
        val car=Capture(protocol="Ford V0",frequency=315_000_000,modulation="FM",source="Test").withDecode("""{"protocol":"Ford V0","serial":1193046,"button":1,"counter":5,"crc_valid":true,"data":"0","bits":80,"encoder_capable":true}""")
        compose.runOnIdle{Hub.importCapture(car)}
        compose.onNodeWithText("All protocols").performClick();compose.onNodeWithText("Ford V0").performClick();compose.onAllNodesWithTag("capture-row").onFirst().performClick()
        for(command in listOf("TX Lock","TX Unlock","TX Trunk","TX Panic"))compose.onNodeWithText(command).assertExists()
        compose.onNodeWithText("Send next code").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
    }
    @Test fun desktopDspRecognizesNoisyOffsetPrincetonOnAndroid(){
        for(offset in listOf(-250000.0,150000.0)){
            val captures=mutableListOf<Capture>()
            ReceivePipeline(RadioSettings(),captures::add).use{rx->
                val raw=princetonIq(offset);for(start in raw.indices step 16384)rx.process(raw.copyOfRange(start,minOf(raw.size,start+16384)));rx.flush()
            }
            val recognized=captures.firstOrNull{it.protocol.split(" + ").contains("Princeton")}
            assertNotNull("No Princeton at $offset Hz: ${captures.map{it.protocol}}",recognized)
            assertTrue(recognized!!.recognition.contains("KAT DSP"))
            val highs=recognized.pulses.filter{it.level}
            assertTrue(highs.size>=24)
            assertTrue(highs.count{kotlin.math.abs(it.duration_us-390)<=24||kotlin.math.abs(it.duration_us-1170)<=24}*100/highs.size>=95)
        }
    }
    @Test fun desktopLiveOffsetAndFobExportPreserveTheWholeWaveform(){
        val captures=mutableListOf<Capture>()
        ReceivePipeline(RadioSettings(),250000.0,captures::add).use{rx->
            val raw=princetonIq(225000.0)
            for(start in raw.indices step 32768)rx.process(raw.copyOfRange(start,minOf(raw.size,start+32768)))
            rx.flush()
        }
        val c=captures.first{it.protocol.split(" + ").contains("Princeton")}
        val fob=StateFormat.fob(c);val restored=StateFormat.importFob(fob)
        assertEquals(c.pulses,restored.pulses);assertEquals(c.modulation,restored.modulation)
        // The remote was 25 kHz below the tuned frequency: the .fob (which has no offset field) says where it was heard, and the import sends it there too.
        assertEquals(-25_000.0,c.offsetHz!!,2_000.0)
        assertEquals(c.heardFrequency,restored.frequency);assertEquals(c.heardFrequency,restored.heardFrequency)
        val root=com.google.gson.JsonParser.parseString(fob).asJsonObject
        assertEquals(c.pulses.size,root["capture"].asJsonObject["raw_pair_count"].asInt)
        assertTrue(c.pulses.size>=190)
        compose.runOnIdle{Hub.importCapture(c)}
        compose.onNodeWithText("Signals",useUnmergedTree=true).performClick();compose.onNodeWithText("Princeton").performClick();compose.onAllNodesWithTag("capture-row").onFirst().performClick()
        compose.onNodeWithText("KAT FOB").assertExists();capture("07-fob-export")
    }
    @Test fun protocolsGroupManySignalsAndKeepDateAndExportAccessible(){
        val stamp=java.time.Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()
        compose.runOnIdle{
            repeat(80){Hub.importCapture(Capture(time=stamp+it*1000,protocol="Nexus / TFA TH",pulses=listOf(Pulse(true,500),Pulse(false,1000))))}
            Hub.importCapture(Capture(time=stamp,protocol="Princeton",pulses=listOf(Pulse(true,390),Pulse(false,1170))))
            Hub.importCapture(Capture(time=stamp,protocol="Unknown",pulses=listOf(Pulse(true,230),Pulse(false,450))))
        }
        compose.onNodeWithText("Signals",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Signals (82)").assertExists()
        compose.onNodeWithTag("protocol-group-Nexus / TFA TH").assertExists()
        compose.onNodeWithTag("protocol-group-Princeton").assertExists();capture("08-protocol-groups")
        compose.onNodeWithTag("protocol-group-Nexus / TFA TH").performClick()
        compose.onNodeWithText("80 captures",substring=true).assertExists()
        compose.onNodeWithText("Nexus / TFA TH").assertExists()
        compose.onNodeWithText("Princeton").assertDoesNotExist()
        capture("09-group-records")
        compose.onAllNodesWithTag("capture-row").onFirst().performClick()
        compose.onNodeWithText("Received: 11.11.2026.",substring=true).assertExists()
        compose.onNodeWithText("KAT FOB").assertExists();compose.onNodeWithText("RAW SUB").assertExists();compose.onNodeWithText("JSON",substring=false).assertExists()
        compose.onNodeWithText("Close").performClick();compose.onNodeWithText("All protocols").performClick()
        compose.onNodeWithText("Search protocols").performTextInput("Princeton")
        compose.onNodeWithTag("protocol-group-Nexus / TFA TH").assertDoesNotExist()
        compose.onNodeWithTag("protocol-group-Princeton").assertExists()
    }
    @Test fun rtlSdrSettingsAreVisibleAndEditable(){
        compose.onNodeWithContentDescription("Settings",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("rtlsdr-settings"))
        compose.onNodeWithTag("rtlsdr-settings").assertIsDisplayed()
        compose.onNodeWithText("RTL-SDR sample rate: 2000000").assertExists()
        compose.onNodeWithText("Auto tuner gain (AGC)").assertExists()
    }
    @Test fun nativeCoreRecognizesUnsignedCu8IqForRtlSdr(){
        assertTrue(NativeDecoder.available)
        val cs8=princetonIq(225000.0)
        val cu8=ByteArray(cs8.size){i->(cs8[i].toInt()+128).coerceIn(0,255).toByte()}
        val captures=mutableListOf<Capture>()
        ReceivePipeline(RadioSettings(modulation="AM"),250000.0,IqFormat.CU8,RadioKind.RTLSDR.label,captures::add).use{rx->
            for(start in cu8.indices step 16384)rx.process(cu8.copyOfRange(start,minOf(cu8.size,start+16384)))
            rx.flush()
        }
        val recognized=captures.firstOrNull{it.protocol.split(" + ").contains("Princeton")}
        assertNotNull("No CU8 Princeton decoded: ${captures.map{it.protocol}}",recognized)
        assertEquals("RTL-SDR",recognized!!.source)
    }
    /** The RTL-SDR driver (Rust) through the JNI bridge it uses on a phone, against a simulated R820T dongle; this debuggable app runs with CheckJNI. */
    @Test fun rtlSdrDriverRunsOverJniAgainstASimulatedR820t(){
        val dongle=FakeRtlDongle(tuner=0x34)
        val h=NativeRtlSdr.open(dongle,"Realtek","RTL2838UHIDIR","00000001")
        assertNotEquals(0L,h)
        try{
            assertEquals("Rafael Micro R820T",NativeRtlSdr.tunerName(h))
            assertEquals("Settings offer the driver's gains",RtlSdrUsb.R82XX_GAINS_TENTHS_DB,NativeRtlSdr.tunerGains(h).toList())
            dongle.ops.clear()
            NativeRtlSdr.configure(h,433_670_000,2_000_000,false,402,-12,false)
            assertTrue("No RX buffer reset",dongle.ops.any{!it.input&&it.block==1&&it.value==0x2148&&it.data==listOf<Byte>(0x10,0x02)})
            assertEquals("Bias-tee (GPIO 0) on",0,dongle.gpo and 1)
            NativeRtlSdr.setCenterFreq(h,314_750_000)
            NativeRtlSdr.resetBuffer(h)
            val outOfRange=runCatching{NativeRtlSdr.setCenterFreq(h,10_000_000)}.exceptionOrNull()
            assertTrue("$outOfRange",outOfRange is IllegalStateException&&outOfRange.message.orEmpty().contains("outside the RTL-SDR tuner range"))
            NativeRtlSdr.configure(h,433_670_000,2_400_000,true,0,0,true)
            assertEquals("Bias-tee (GPIO 0) off",1,dongle.gpo and 1)
        }finally{NativeRtlSdr.close(h)}
        assertEquals("Bias-tee left on after close",0,dongle.gpo and 1)
        assertEquals("Demodulator not powered down",listOf<Byte>(0x20),dongle.lastWrite(2,0x3000))
        assertTrue(runCatching{NativeRtlSdr.tunerName(h)}.exceptionOrNull() is IllegalStateException)
        NativeRtlSdr.close(h)
    }
    @Test fun rtlSdrBlogV4IsRecognizedOverJniAndTunesHf(){
        val dongle=FakeRtlDongle(tuner=0x74)
        val h=NativeRtlSdr.open(dongle,"RTLSDRBlog","Blog V4","00000001")
        try{
            assertEquals("Rafael Micro R828D (Blog V4)",NativeRtlSdr.tunerName(h))
            NativeRtlSdr.configure(h,433_670_000,2_000_000,true,402,0,false)
            NativeRtlSdr.setCenterFreq(h,10_000_000)
        }finally{NativeRtlSdr.close(h)}
    }
    /** A dongle that does not answer, or whose USB callback throws, fails the open with an error; the app goes on. */
    @Test fun rtlSdrOpenFailuresAreErrorsNotCrashes(){
        fun openError(d:FakeRtlDongle)=runCatching{NativeRtlSdr.open(d,"Realtek","RTL2838UHIDIR","")}.exceptionOrNull()
        val noTuner=openError(FakeRtlDongle(tuner=null))
        assertTrue("$noTuner",noTuner is IllegalStateException&&noTuner.message.orEmpty().contains("No supported tuner"))
        val silent=openError(FakeRtlDongle(tuner=0x34,fail=true))
        assertTrue("$silent",silent is IllegalStateException&&silent.message.orEmpty().contains("USB control"))
        val throwing=openError(FakeRtlDongle(tuner=0x34,throws=true))
        assertTrue("$throwing",throwing is IllegalStateException&&throwing.message.orEmpty().contains("JNI USB control"))
        val h=NativeRtlSdr.open(FakeRtlDongle(tuner=0x34),"Realtek","RTL2838UHIDIR","")
        NativeRtlSdr.close(h)
    }
}

/**
 * An RTL2832U as the RTL-SDR driver sees it over JNI (the model native/tests/rtlsdr_driver.rs uses):
 * its registers read 0 and take any write, GPO keeps what was written, the EEPROM forces neither
 * bias-tee nor direct sampling, and the tuner at I2C address [tuner] (0x34 R820T, 0x74 R828D)
 * answers the probe with 0x69 and reports its PLL locked. Nothing else on the I2C bus answers.
 */
private class FakeRtlDongle(val tuner:Int?,val fail:Boolean=false,val throws:Boolean=false):RtlUsbControl{
    data class Op(val input:Boolean,val block:Int,val value:Int,val data:List<Byte>)
    val ops:MutableList<Op> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var gpo=0
    private var tunerRegister=-1
    override fun controlTransfer(requestType:Int,request:Int,value:Int,index:Int,data:ByteArray,length:Int,timeoutMs:Int):Int{
        if(throws)throw IllegalStateException("USB device detached")
        if(fail)return -1
        assertEquals(0,request);assertTrue(length in 0..data.size);assertTrue(timeoutMs>0)
        val input=requestType==0xC0
        assertTrue("Request type $requestType",input||requestType==0x40)
        val block=index shr 8
        when{
            block==6&&value==0xA0->{assertTrue("EEPROM writes only set the read address",input||length==1);if(input)data.fill(0x16,0,length)}
            block==6&&value==tuner->if(input)data.fill((if(length==1&&tunerRegister==0)0x69 else 0x02).toByte(),0,length) else if(length>0)tunerRegister=data[0].toInt() and 255
            block==6->return -1
            block==2&&value==0x3001->if(input)data[0]=gpo.toByte() else gpo=data[0].toInt() and 255
            input->data.fill(0,0,length)
        }
        ops.add(Op(input,block,value,data.take(length)))
        return length
    }
    override fun resetInterface()=!fail
    fun lastWrite(block:Int,value:Int)=ops.lastOrNull{!it.input&&it.block==block&&it.value==value}?.data
}
