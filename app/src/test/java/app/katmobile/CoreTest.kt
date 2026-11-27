package app.katmobile

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class CoreTest {
    @Test fun independentHopTimerRotatesWithNoIqAndDoesNotAddCallbackTimeToDwell()=kotlinx.coroutines.runBlocking{
        var now=0L
        val seen=mutableListOf<Pair<Long,Long>>()
        val schedule=HopSchedule(listOf(315000000L,433920000L,868350000L),1000,now)
        try{
            runHopTimer(schedule,clock={now},wait={now+=it}){hop->
                seen+=now to hop.frequency
                if(seen.size==4)throw kotlinx.coroutines.CancellationException("test complete")
                now+=150 // Simulated tune/decoder work must not add 150 ms to each dwell.
            }
        }catch(_:kotlinx.coroutines.CancellationException){}
        assertEquals(listOf(1000L,2000L,3000L,4000L),seen.map{it.first})
        assertEquals(listOf(433920000L,868350000L,315000000L,433920000L),seen.map{it.second})
    }

    @Test fun hoppingFollowsDwellAndWrapsWithoutSignalGate(){
        val hop=HopSchedule(listOf(315000000L,433920000L,868350000L),1000,500)
        assertEquals(315000000L,hop.frequency)
        assertFalse(hop.advance(1499));assertTrue(hop.advance(1500));assertEquals(433920000L,hop.frequency)
        assertFalse(hop.advance(1500));assertTrue(hop.advance(2500));assertEquals(868350000L,hop.frequency)
        assertTrue(hop.advance(3500));assertEquals(315000000L,hop.frequency);assertEquals(3L,hop.switches)
        // A delayed USB callback advances one actual tune, without fake missed hops.
        assertTrue(hop.advance(10000));assertEquals(433920000L,hop.frequency);assertEquals(4L,hop.switches)
    }

    @Test fun desktopOffsetTuningAndIqNamesAgree(){
        assertEquals(433_670_000,Wire.liveCenter(433_920_000,2_000_000))
        val spec=StateFormat.iqSpec("kat_433670000Hz_2000000sps_rx433920000Hz_1794365554491.cs8",RadioSettings(sampleRate=4_000_000))
        assertEquals(2_000_000,spec.rate);assertEquals(433_670_000,spec.center);assertEquals(433_920_000,spec.target);assertEquals(250000.0,spec.offsetHz,0.0)
        val old=StateFormat.iqSpec("kat_433920000Hz_2000000sps_old.cs8",RadioSettings());assertEquals(0.0,old.offsetHz,0.0)
    }
    @Test fun fobV2PreservesEveryPulseAndUsesDesktopSignalObject(){
        val pulses=(0 until 170).map{Pulse(it%2==1,200+it*3)}
        val c=Capture(time=1794365015752,frequency=868350000,modulation="FM",protocol="Synthetic test",pulses=pulses)
        val text=StateFormat.fob(c);val root=com.google.gson.JsonParser.parseString(text).asJsonObject
        assertEquals("kat-fob",root["format"].asString);assertEquals("2.0",root["version"].asString)
        assertEquals(170,root["capture"].asJsonObject["raw_pair_count"].asInt)
        val imported=StateFormat.importFob(text);assertEquals(c.time,imported.time);assertEquals(c.frequency,imported.frequency);assertEquals(c.protocol,imported.protocol);assertEquals("FM",imported.modulation);assertEquals(pulses,imported.pulses)
        java.io.File("build/interop-tests").mkdirs();java.io.File("build/interop-tests/export.fob").writeText(text)
        val sub=StateFormat.rawSub(c.copy(modulation="AM"));val restored=StateFormat.importRawSub(sub);assertEquals(pulses,restored.pulses)
    }
    @Test fun frequencyPacketUsesMhzAndRemainderWithout32BitOverflow(){
        val request=Wire.frequency(5_800_125_000);val b=ByteBuffer.wrap(request.data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16,request.request);assertFalse(request.input);assertEquals(5800,b.int);assertEquals(125000,b.int)
    }
    @Test fun sampleRatePacketIncludesDivider(){val r=Wire.configuration(RadioSettings()).first{it.request==6};val b=ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN);assertEquals(2_000_000,b.int);assertEquals(1,b.int)}
    @Test fun gainsUseInboundAcknowledgmentAndIndex(){val plan=Wire.configuration(RadioSettings(lna=40,vga=62));assertTrue(plan.first{it.request==19}.input);assertEquals(40,plan.first{it.request==19}.index);assertEquals(62,plan.first{it.request==20}.index)}
    @Test fun configurationContainsNoTransmitOrFirmwareWrites(){val plan=Wire.configuration(RadioSettings());assertFalse(plan.any{it.request in listOf(10,11,21)||it.request==1&&it.value==2});assertEquals(0,plan.first{it.request==1}.value)}
    @Test fun invalidHardwareSettingsAreRejected(){listOf(RadioSettings(lna=9),RadioSettings(vga=63),RadioSettings(sampleRate=1),RadioSettings(frequency=0),RadioSettings(bandwidth=20_000_000),RadioSettings(hopDwellMs=1)).forEach{assertTrue(runCatching{it.validate()}.isFailure)}}
    @Test fun frequencyInputIsExactAndBounded(){assertEquals(433_920_000,RadioSettings.mhz("433.92"));assertEquals(6_000_000_000,RadioSettings.mhz("6000"));assertTrue(runCatching{RadioSettings.mhz("6000.1")}.isFailure)}
    @Test fun fftFindsSyntheticCarrierAtExpectedOffset(){val n=1024;val bytes=ByteArray(n*2);repeat(n){val phase=2*PI*32*it/n;bytes[it*2]=(cos(phase)*100).toInt().toByte();bytes[it*2+1]=(sin(phase)*100).toInt().toByte()};val bins=Spectrum.fft(bytes);assertEquals(256,bins.size);assertTrue(abs(bins.indices.maxBy{bins[it]}-136)<=1);assertTrue(bins.max()>-10f)}
    @Test fun zeroInputHasNoInventedSignal(){val captures=mutableListOf<Capture>();val engine=SignalEngine(RadioSettings(),captures::add);repeat(10){engine.process(ByteArray(16384))};engine.flush();assertTrue(captures.isEmpty());assertTrue(engine.powerDb<=-80)}
    @Test fun amBurstHasRealPulseDurationsAcrossUsbChunks(){
        val captures=mutableListOf<Capture>();val engine=SignalEngine(RadioSettings(),captures::add)
        val raw=java.io.ByteArrayOutputStream()
        repeat(16){repeat(800){raw.write(100);raw.write(0)};repeat(800){raw.write(0);raw.write(0)}}
        repeat(50000){raw.write(0);raw.write(0)}
        raw.toByteArray().toList().chunked(16384).forEach{engine.process(it.toByteArray())};engine.flush()
        assertEquals(1,captures.size);assertTrue(captures.single().pulses.size>=30)
        assertTrue(captures.single().pulses.filter{it.level}.all{abs(it.duration_us-400)<=8})
    }
    @Test fun rawSubRoundTripPreservesPulseLevels(){val c=Capture(pulses=listOf(Pulse(true,400),Pulse(false,800),Pulse(true,200)));val result=StateFormat.importRawSub(StateFormat.rawSub(c));assertEquals(c.frequency,result.frequency);assertEquals(c.pulses,result.pulses)}
    @Test fun fmDiscriminatorExtractsSyntheticFskTimings(){
        val captures=mutableListOf<Capture>();val engine=SignalEngine(RadioSettings(modulation="FM"),captures::add)
        val raw=java.io.ByteArrayOutputStream();var phase=0.0
        repeat(20){bit->repeat(800){phase+=(if(bit%2==0)1 else -1)*2*PI*30000/2_000_000;raw.write((cos(phase)*100).toInt().toByte().toInt());raw.write((sin(phase)*100).toInt().toByte().toInt())}}
        repeat(50000){raw.write(0);raw.write(0)}
        raw.toByteArray().toList().chunked(16384).forEach{engine.process(it.toByteArray())};engine.flush()
        assertEquals(1,captures.size);assertEquals("FM",captures.single().modulation)
        assertTrue(captures.single().pulses.size>=18)
        assertTrue(captures.single().pulses.take(18).all{abs(it.duration_us-400)<=16})
    }
    @Test fun rawImporterRejectsNonRawAndAbsurdPulse(){assertTrue(runCatching{StateFormat.importRawSub("Frequency: 433920000\nProtocol: KeeLoq\nRAW_Data: 100 -100")}.isFailure);assertTrue(runCatching{StateFormat.importRawSub("Frequency: 433920000\nProtocol: RAW\nRAW_Data: 999999999999 -100")}.isFailure)}
    @Test fun fobImportDoesNotRecomputeSavedCounterFromHistoricalPulses(){val text="""{"protocol":"Imported","frequency":433920000,"serial":42,"counter":171,"raw_pairs":[{"level":true,"duration_us":400},{"level":false,"duration_us":800}]}""";val c=StateFormat.importFob(text);assertEquals("171",c.counter);assertEquals(text,c.metadata);assertEquals("171",StateFormat.importFob(StateFormat.captureJson(c,true)).counter)}
    @Test fun jsonExportCanExcludeRawWithoutChangingMetadata(){val c=StateFormat.importFob("""{"counter":171,"raw_pairs":[{"level":true,"duration_us":400}]}""");val exported=StateFormat.captureJson(c,false);assertFalse(exported.contains("raw_pairs"));assertEquals("171",StateFormat.importFob(exported).counter);assertEquals(1,c.pulses.size)}
    @Test fun mobileCaptureJsonReimportsPulses(){val c=Capture(pulses=listOf(Pulse(true,20),Pulse(false,40)));assertEquals(c.pulses,StateFormat.importFob(StateFormat.captureJson(c,true)).pulses)}
    @Test fun sessionMigrationRetainsDefaultSettings(){val state=StateFormat.decode("""{"settings":{"frequency":315000000},"captures":[{"id":"old","pulses":[]}]}""");assertEquals(315_000_000,state.settings.frequency);assertEquals(32,state.settings.lna);assertTrue(state.settings.autosave);assertEquals(47,state.settings.txVga);assertTrue(state.settings.txAmp);assertEquals("",state.captures.single().decode)}

    @Test fun transmitConfigurationIsDesktopKatsAndWritesNoFirmware(){
        val plan=Wire.txConfiguration(433_920_000,30,true)
        assertEquals(listOf(1,6,7,16,21,17),plan.map{it.request})
        assertEquals("transceiver off first",0,plan.first().value)
        val lo=ByteBuffer.wrap(plan.first{it.request==16}.data).order(ByteOrder.LITTLE_ENDIAN);assertEquals(433,lo.int);assertEquals(420_000,lo.int)
        val rate=ByteBuffer.wrap(plan.first{it.request==6}.data).order(ByteOrder.LITTLE_ENDIAN);assertEquals(2_000_000,rate.int);assertEquals(1,rate.int)
        val filter=plan.first{it.request==7};assertEquals(1_750_000,filter.value or (filter.index shl 16))
        val gain=plan.first{it.request==21};assertTrue(gain.input);assertEquals(30,gain.index)
        assertEquals(1,plan.first{it.request==17}.value)
        assertFalse(plan.any{it.request in listOf(10,11,13)})
        assertEquals(2,Wire.transceiver(Wire.MODE_TX).value)
        assertTrue(runCatching{Wire.txConfiguration(433_920_000,48,false)}.isFailure)
        assertTrue(runCatching{Wire.txConfiguration(1_200_000,47,false)}.isFailure)
    }

    /** A HackRF that records what it is sent. */
    private class FakeHackRf:TxSink{
        val events=mutableListOf<String>();val sent=java.io.ByteArrayOutputStream();var failAt=-1;private var writes=0
        override val capacity=4*HackRfTx.CHUNK
        override fun write(data:ByteArray,offset:Int,length:Int){if(writes++==failAt)error("USB unplugged");events+="write $length";sent.write(data,offset,length)}
        override fun drain(){events+="drain"}
        fun control(c:Control){events+="control ${c.request}"+if(c.request==1)"=${c.value}" else ""}
    }
    @Test fun aTransmissionPrimesKeysSendsSilenceAndTurnsOff(){
        val hackrf=FakeHackRf();val iq=ByteArray(300_000){(it%251).toByte()}
        HackRfTx(hackrf::control,hackrf,pause={hackrf.events+="pause $it"}).send(iq,433_920_000,47,true)
        val e=hackrf.events
        assertEquals(listOf("control 1=0","control 6","control 7","control 16","control 21","control 17"),e.take(6))
        val keyed=e.indexOf("control 1=2");assertEquals("keyed once",1,e.count{it=="control 1=2"})
        assertEquals("libhackrf's order: transfers queued, then TX mode",List(4){"write 65536"},e.subList(6,keyed))
        assertEquals(listOf("drain","pause 25","control 1=0"),e.takeLast(3))
        val bytes=hackrf.sent.toByteArray()
        assertArrayEquals(iq,bytes.copyOfRange(0,iq.size))
        assertTrue("silence pushes the last samples out of the HackRF's 32 KB buffer",bytes.size-iq.size>=32768&&bytes.copyOfRange(iq.size,bytes.size).all{it==0.toByte()})
    }
    @Test fun aShortTransmissionIsKeyedOnceItsSamplesAreQueued(){
        val hackrf=FakeHackRf();HackRfTx(hackrf::control,hackrf,pause={}).send(ByteArray(1000){7},315_000_000,0,false)
        assertEquals(listOf("write 1000","control 1=2","write 65536","drain","control 1=0"),hackrf.events.drop(6))
    }
    @Test fun aFailedTransmissionStillTurnsTheTransmitterOff(){
        val hackrf=FakeHackRf().apply{failAt=5}
        val error=runCatching{HackRfTx(hackrf::control,hackrf,pause={}).send(ByteArray(600_000),433_920_000,40,false)}.exceptionOrNull()
        assertEquals("USB unplugged",error?.message)
        assertTrue(hackrf.events.contains("control 1=2"));assertEquals("control 1=0",hackrf.events.last())
        val cancelled=FakeHackRf();var calls=0
        assertTrue(runCatching{HackRfTx(cancelled::control,cancelled,pause={}).send(ByteArray(600_000),433_920_000,40,false){++calls>2}}.isFailure)
        assertEquals("control 1=0",cancelled.events.last())
    }
    @Test fun txPowerIsValidatedAndImportedFromKatsConfig(){
        assertTrue(runCatching{RadioSettings(txVga=48).validate()}.isFailure)
        val s=StateFormat.ini("[radio]\ntx_vga_gain=30\ntx_amp=false",RadioSettings());assertEquals(30,s.txVga);assertFalse(s.txAmp)
        assertEquals(listOf("47 dB + amp","40 dB + amp","30 dB + amp","47 dB","40 dB","30 dB","20 dB","10 dB","0 dB"),TX_POWER_STEPS.map{(g,a)->txPowerLabel(g,a)})
    }
    @Test fun aDecodeShowsDesktopKatsFieldsAndTravelsInTheFob(){
        val decode="""{"protocol":"KeeLoq (DoorHan)","serial":4660,"button":2,"counter":3,"crc_valid":true,"data":"B910EF112C480004","bits":64,"encoder_capable":true,"extra":"64"}"""
        val c=Capture(pulses=listOf(Pulse(true,400),Pulse(false,800))).withDecode(decode)
        assertEquals(listOf("0001234","0x02","0003"),listOf(c.serial,c.button,c.counter))
        val signal=com.google.gson.JsonParser.parseString(StateFormat.fob(c)).asJsonObject["signal"].asJsonObject
        assertEquals("KeeLoq (DoorHan)",signal["protocol"].asString);assertEquals("B910EF112C480004",signal["data_hex"].asString)
        assertEquals(64,signal["data_bits"].asInt);assertEquals(3,signal["counter"].asInt);assertEquals(2,signal["button"].asInt)
        assertEquals("0001234",signal["serial"].asString);assertEquals("0000000000000064",signal["data_extra"].asString);assertTrue(signal["encoder_capable"].asBoolean)
        assertEquals(c,c.withDecode(""))
    }
    @Test fun iniImportOnlyAppliesReceptionAndHistoryPreferences(){val s=StateFormat.ini("[radio]\ndefault_frequency=315000000\ndefault_lna_gain=40\ndefault_vga_gain=40\n[general]\nmax_captures=0\nignore_duplicates=false\nautobf=true",RadioSettings());assertEquals(315_000_000,s.frequency);assertEquals(40,s.lna);assertEquals(2000,s.maxCaptures);assertFalse(s.ignoreDuplicates)}

    @Test fun sdrDeviceClassificationMatchesHackRfAndRtlSdrVidPids(){
        assertEquals(RadioKind.HACKRF,SdrDevices.kindOf(0x1d50,0x6089))
        assertEquals(RadioKind.HACKRF,SdrDevices.kindOf(0x1d50,0x604b))
        assertEquals(RadioKind.HACKRF,SdrDevices.kindOf(0x1d50,0xcc15))
        assertEquals(RadioKind.RTLSDR,SdrDevices.kindOf(0x0bda,0x2832))
        assertEquals(RadioKind.RTLSDR,SdrDevices.kindOf(0x0bda,0x2838))
        assertEquals(RadioKind.RTLSDR,SdrDevices.kindOf(0x1f4d,0xd803))
        assertNull(SdrDevices.kindOf(0x1234,0x5678))
        assertEquals(42,SdrDevices.RTL_SDR_IDS.size)
        assertTrue(RadioKind.HACKRF.supportsTx)
        assertFalse(RadioKind.RTLSDR.supportsTx)
        // The Flipper Zero's console (ST's virtual COM port), a transmitter as on desktop KAT.
        assertEquals(RadioKind.FLIPPER,SdrDevices.kindOf(0x0483,0x5740))
        assertTrue(RadioKind.FLIPPER.supportsTx)
    }
    @Test fun fftFindsSyntheticCarrierInUnsignedCu8Samples(){
        val n=1024;val bytes=ByteArray(n*2)
        repeat(n){val phase=2*PI*32*it/n;bytes[it*2]=(127.5+cos(phase)*100).roundToInt().coerceIn(0,255).toByte();bytes[it*2+1]=(127.5+sin(phase)*100).roundToInt().coerceIn(0,255).toByte()}
        val bins=Spectrum.fft(bytes,bytes.size,unsigned=true)
        assertEquals(256,bins.size)
        assertTrue(abs(bins.indices.maxBy{bins[it]}-136)<=1)
        assertTrue(bins.max()>-10f)
        // Silent CU8 centered at 128 has near-zero power instead of a full-scale DC spike.
        val quiet=ByteArray(n*2){128.toByte()}
        val quietBins=Spectrum.fft(quiet,quiet.size,unsigned=true)
        assertTrue("Quiet CU8 spectrum max was ${quietBins.max()}",quietBins.max()<-40f)
    }
    @Test fun rtlSdrSettingsValidateAndMigrateCleanlyFromOldSessions(){
        val migrated=StateFormat.decode("""{"settings":{"frequency":433920000},"captures":[]}""")
        assertEquals(2_000_000,migrated.settings.rtlSampleRate)
        assertTrue(migrated.settings.rtlAutoGain)
        assertEquals(402,migrated.settings.rtlGainTenthsDb)
        assertEquals(0,migrated.settings.rtlPpm)
        assertFalse(migrated.settings.rtlBiasTee)
        assertTrue(runCatching{RadioSettings(rtlSampleRate=8_000_000).validate()}.isFailure)
        assertTrue(runCatching{RadioSettings(rtlGainTenthsDb=123).validate()}.isFailure)
        assertTrue(runCatching{RadioSettings(rtlPpm=150).validate()}.isFailure)
        assertTrue(runCatching{RadioSettings(rtlSampleRate=2_400_000,rtlAutoGain=false,rtlGainTenthsDb=496,rtlPpm=-15,rtlBiasTee=true).validate()}.isSuccess)
        assertEquals("Auto (AGC)",RtlSdrUsb.gainLabel(true,402))
        assertEquals("40.2 dB",RtlSdrUsb.gainLabel(false,402))
        assertEquals(16000,RtlSdrUsb.retuneFlushBytes(2_000_000))
        val cu8Spec=StateFormat.iqSpec("kat_433670000Hz_2000000sps_rx433920000Hz_1794365554491.cu8",RadioSettings())
        assertEquals(2_000_000,cu8Spec.rate);assertEquals(433_670_000,cu8Spec.center);assertEquals(433_920_000,cu8Spec.target);assertEquals(250_000.0,cu8Spec.offsetHz,0.0)
    }
}
