package app.katmobile

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Hub {
    val settings=MutableStateFlow(RadioSettings())
    val captures=MutableStateFlow<List<Capture>>(emptyList())
    // Session-only monitoring: never substitute a duplicate for the archived waveform.
    val latestReception=MutableStateFlow<Capture?>(null)
    val status=MutableStateFlow(RadioStatus())
    val logs=MutableStateFlow<List<String>>(emptyList())
    val notice=MutableStateFlow("")
    val offlineBusy=MutableStateFlow(false)
    /** A transmission for the radio service: the capture, what it sends, and KAT's plan of it. */
    data class TxRequest(val captureId:String,val what:String,val carrier:Long,val plan:TxPlan)
    val pendingTx=java.util.concurrent.atomic.AtomicReference<TxRequest?>(null)
    /** The last transmission's result, for the signal details. */
    val txMessage=MutableStateFlow("")
    private var vault:Vault?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var pending:Job?=null
    private val writer=Mutex()
    private var removed:List<Capture> = emptyList()
    private var readable=true
    private var analysis:Job?=null
    private fun bounded(items:List<Capture>):List<Capture>{
        var pulseCount=0;var metadataSize=0
        return items.asReversed().take(settings.value.maxCaptures).takeWhile{c->
            pulseCount+=c.pulses.size;metadataSize+=c.metadata.length
            pulseCount<=200_000&&metadataSize<=4*1024*1024
        }.asReversed()
    }
    @Synchronized fun initialize(context:Context){if(vault!=null)return;vault=Vault(context.applicationContext)
        runCatching{vault!!.load()}.onSuccess{settings.value=it.settings;captures.value=it.captures.takeLast(it.settings.maxCaptures)}.onFailure{readable=false;notice.value="The saved session could not be read. The original is preserved; export it before resetting."}
    }
    @Synchronized fun log(text:String){logs.value=(logs.value+"${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(java.util.Date())} $text").takeLast(200)}
    @Synchronized fun configure(s:RadioSettings){check(!status.value.running&&!offlineBusy.value){"Stop reception or analysis before changing settings"};s.validate();settings.value=s;persist()}
    @Synchronized fun add(c:Capture){
        latestReception.value=c
        val identity=c.identity
        val same=captures.value.lastOrNull{it.frequency==c.frequency&&it.modulation==c.modulation&&it.identity==identity}
        // A repeat counts on the first reception, whose pulses the entry keeps: its measurement (SNR, offset) stays with them.
        if(same!=null&&(settings.value.ignoreDuplicates||c.time-same.time<500))captures.value=captures.value.map{if(it.id==same.id)it.copy(repeats=it.repeats+1,
            protocol=if(c.protocol!="Unknown")c.protocol else it.protocol,recognition=it.recognition.ifBlank{c.recognition},offsetHz=it.offsetHz?:c.offsetHz)
            .let{merged->if(merged.decode.isBlank())merged.withDecode(c.decode)else merged} else it}
        else captures.value=bounded(captures.value+c)
        if(settings.value.autosave)persist()
    }
    /** A transmission went out. A rolling code's capture keeps the frame sent (desktop KAT's `store_sent_frame`), so the next press sends the code after. */
    @Synchronized fun transmitted(r:TxRequest){
        val sent=r.plan.sent
        if(sent!=null)captures.value=captures.value.map{if(it.id==r.captureId)it.withDecode(sent.toString())else it}
        val counter=sent?.get("counter")?.takeIf{it.isJsonPrimitive}?.asLong?.let{" · counter %04X".format(java.util.Locale.US,it)}.orEmpty()
        txMessage.value="Sent ${r.what}$counter · ${r.plan.modulation} · ${"%.3f".format(java.util.Locale.US,r.carrier/1e6)} MHz"
        log(txMessage.value)
        persist()
    }
    @Synchronized fun delete(ids:Set<String>){removed=captures.value.filter{it.id in ids};captures.value=captures.value.filterNot{it.id in ids};persist()}
    @Synchronized fun undo(){captures.value=bounded((captures.value+removed).distinctBy{it.id}.sortedBy{it.time});removed=emptyList();persist()}
    @Synchronized fun persist(){
        if(!readable)return
        pending?.cancel();val snapshot=Saved(settings.value,captures.value.toList())
        pending=scope.launch{delay(250);writer.withLock{runCatching{vault?.save(snapshot)}.onFailure{notice.value="Could not save: ${it.message}"}}}
    }
    suspend fun flush(){pending?.join();writer.withLock{if(readable)vault?.save(Saved(settings.value,captures.value))}}
    fun importCapture(c:Capture){
        // Imported metadata is kept as supplied; raw pulses never replace its saved counter.
        captures.value=bounded(captures.value+c);persist()
    }
    fun restore(state:Saved){check(!status.value.running&&!offlineBusy.value);state.settings.validate();settings.value=state.settings;captures.value=bounded(state.captures);latestReception.value=null;persist()}
    fun cancelAnalysis(){analysis?.cancel()}
    fun analyze(context:Context,uri:android.net.Uri){
        check(!status.value.running&&!offlineBusy.value){"Reception or analysis is already running"}
        offlineBusy.value=true
        analysis=scope.launch{
            val s=settings.value;var receiver:ReceivePipeline?=null
            try{
                val name=context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else""}.orEmpty().ifBlank{uri.lastPathSegment.orEmpty()}
                require(!name.endsWith(".cf32",true)){"This version analyzes CS8 and CU8 IQ files"}
                val unsigned=name.endsWith(".cu8",true)
                val iqFormat=if(unsigned)IqFormat.CU8 else IqFormat.CS8
                val spec=StateFormat.iqSpec(name,s)
                val engine=ReceivePipeline(s.copy(sampleRate=spec.rate,frequency=spec.target),spec.offsetHz,iqFormat,"IQ file",::add);receiver=engine
                status.value=RadioStatus(device="IQ file",activeSampleRate=spec.rate,frequency=engine.frequency,message="Analyzing: $name")
                context.contentResolver.openInputStream(uri)!!.use{input->
                    val buffer=ByteArray(32768);var carry=0;var read=0L;var refreshed=0L
                    while(true){ensureActive();val n=input.read(buffer,carry,buffer.size-carry);if(n<0){require(carry==0){"The IQ file contains an incomplete sample pair"};break}
                        val all=n+carry;val length=all and -2;val odd=if(all%2==1)buffer[all-1]else 0.toByte()
                        engine.process(buffer,length);read+=length
                        if(System.currentTimeMillis()-refreshed>200){status.value=status.value.copy(bytes=read,powerDb=engine.powerDb,spectrum=Spectrum.fft(buffer,length,unsigned));refreshed=System.currentTimeMillis()}
                        carry=all%2;if(carry==1)buffer[0]=odd
                    }
                };engine.flush();log("IQ analysis complete");status.value=status.value.copy(message="IQ analysis is complete")
            }catch(_:CancellationException){log("IQ analysis canceled")}
            catch(e:Exception){notice.value=e.message?:"Could not read the IQ file"}
            finally{receiver?.close();offlineBusy.value=false;persist()}
        }
    }
}
