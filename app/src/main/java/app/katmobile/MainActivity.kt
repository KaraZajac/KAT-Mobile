package app.katmobile

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

private val Green=Color(0xFF60EBA9)
private val Cyan=Color(0xFF65CFFF)
private val Bg=Color(0xFF070D10)
private val Panel=Color(0xFF101C23)
private val Dim=Color(0xFF8FA5AF)
private val colors=darkColorScheme(primary=Green,onPrimary=Bg,secondary=Cyan,background=Bg,surface=Panel,onSurface=Color(0xFFE5F0F2),onBackground=Color(0xFFE5F0F2))
private fun frequency(hz:Long)="%.3f MHz".format(Locale.US,hz/1e6)
private fun time(ms:Long)=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(ms))
private fun dateTime(ms:Long)=SimpleDateFormat("dd.MM.yyyy. · HH:mm:ss",Locale.getDefault()).format(Date(ms))
private fun shortDateTime(ms:Long)=SimpleDateFormat("dd.MM. · HH:mm",Locale.getDefault()).format(Date(ms))
private fun size(bytes:Long)=when{bytes>=1024*1024->"%.1f MB".format(bytes/1048576.0);bytes>=1024->"%.0f KB".format(bytes/1024.0);else->"$bytes B"}

class MainActivity:ComponentActivity(){
    private val permissionAction="app.katmobile.USB_PERMISSION"
    private val txPermissionAction="app.katmobile.USB_PERMISSION_TX"
    private val deviceChoices=kotlinx.coroutines.flow.MutableStateFlow<List<android.hardware.usb.UsbDevice>>(emptyList())
    private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
        if(intent.action==permissionAction||intent.action==txPermissionAction){
            @Suppress("DEPRECATION")val device=intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
            if(device==null||!getSystemService(UsbManager::class.java).hasPermission(device)){Hub.pendingTx.set(null);Hub.notice.value="USB permission was not granted"}
            // Asked for a transmission: it goes out now (Hub.pendingTx); else reception starts.
            else RadioService.action(this@MainActivity,if(intent.action==txPermissionAction)"TX" else "START",device.deviceName)
        }
    }}
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);Hub.initialize(this)
        if(!NativeDecoder.available)Hub.notice.value="The native KAT core could not be loaded. Only basic decoders are available; check the APK architecture."
        val filter=IntentFilter().apply{addAction(permissionAction);addAction(txPermissionAction)}
        androidx.core.content.ContextCompat.registerReceiver(this,receiver,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent{MaterialTheme(colorScheme=colors){App(::startRadio,::startDevice,deviceChoices,{deviceChoices.value=emptyList()},::startTransmit)}}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun startRadio(){
        try{
            check(!Hub.offlineBusy.value){"Stop IQ file analysis first"}
            val manager=getSystemService(UsbManager::class.java);val devices=SdrDevices.devices(manager)
            if(devices.isEmpty()){Hub.notice.value="No HackRF or RTL-SDR device found. Connect a USB OTG cable and, if needed, a powered hub.";return}
            if(devices.size==1)startDevice(devices.first()) else deviceChoices.value=devices
        }catch(e:Exception){Hub.notice.value=e.message?:"USB is unavailable"}
    }
    private fun startDevice(device:android.hardware.usb.UsbDevice){
        deviceChoices.value=emptyList()
        try{
            check(!Hub.offlineBusy.value){"Stop IQ file analysis first"}
            val manager=getSystemService(UsbManager::class.java)
            if(manager.hasPermission(device))RadioService.action(this,"START",device.deviceName)
            else manager.requestPermission(device,PendingIntent.getBroadcast(this,4,Intent(permissionAction).setPackage(packageName).putExtra(UsbManager.EXTRA_DEVICE,device),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }catch(e:Exception){Hub.notice.value=e.message?:"USB is unavailable"}
    }
    /** Hand a transmission to the radio service; without USB access yet, Android asks, and it goes out once granted. */
    private fun startTransmit(request:Hub.TxRequest){
        try{
            check(!Hub.offlineBusy.value){"Stop IQ file analysis first"}
            check(!Hub.status.value.transmitting){"A transmission is already on air"}
            val manager=getSystemService(UsbManager::class.java)
            val device=HackRfUsb.devices(manager).firstOrNull()
            if(device==null){
                val hasRtl=SdrDevices.devices(manager).any(SdrDevices::isRtlSdr)
                Hub.notice.value=if(hasRtl)"RTL-SDR is receive-only. Connect a HackRF to transmit." else "No HackRF device. Connect a USB OTG cable and, if needed, a powered hub."
                return
            }
            Hub.pendingTx.set(request)
            if(manager.hasPermission(device))RadioService.action(this,"TX",device.deviceName)
            else manager.requestPermission(device,PendingIntent.getBroadcast(this,5,Intent(txPermissionAction).setPackage(packageName).putExtra(UsbManager.EXTRA_DEVICE,device),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }catch(e:Exception){Hub.pendingTx.set(null);Hub.notice.value=e.message?:"USB is unavailable"}
    }
    override fun onDestroy(){unregisterReceiver(receiver);super.onDestroy()}
    override fun onStop(){super.onStop();Hub.persist()}
}

@Composable private fun Frame(title:String,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Surface(color=Panel,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Color(0xFF243B45)),modifier=modifier){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,color=Cyan,fontSize=12.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold);content()}}
}

@Composable private fun App(
    start:()->Unit,
    pickDevice:(android.hardware.usb.UsbDevice)->Unit,
    deviceChoicesFlow:kotlinx.coroutines.flow.StateFlow<List<android.hardware.usb.UsbDevice>>,
    dismissDevices:()->Unit,
    transmit:(Hub.TxRequest)->Unit
){
    val s by Hub.settings.collectAsStateWithLifecycle();val status by Hub.status.collectAsStateWithLifecycle()
    val captures by Hub.captures.collectAsStateWithLifecycle();val notice by Hub.notice.collectAsStateWithLifecycle()
    val latestReception by Hub.latestReception.collectAsStateWithLifecycle()
    val offline by Hub.offlineBusy.collectAsStateWithLifecycle()
    val deviceChoices by deviceChoicesFlow.collectAsStateWithLifecycle()
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    var tab by remember{mutableIntStateOf(0)}
    val focusManager=LocalFocusManager.current
    var protocolFilter by remember{mutableStateOf<String?>(null)}
    var protocolSearch by remember{mutableStateOf("")}
    val grouped=remember(captures){captureGroups(captures)}
    fun openProtocol(name:String){focusManager.clearFocus();protocolFilter=name;tab=1}
    var selected by remember{mutableStateOf<Capture?>(null)}
    BackHandler(enabled=tab==1&&protocolFilter!=null&&selected==null){protocolFilter=null}
    var clear by remember{mutableStateOf(false)}
    var exportData by remember{mutableStateOf<ByteArray?>(null)}
    var importBusy by remember{mutableStateOf(false)}
    var sessionImport by remember{mutableStateOf<Saved?>(null)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val bytes=exportData;if(uri!=null&&bytes!=null)scope.launch{runCatching{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")!!.use{it.write(bytes)}}}.onSuccess{Hub.notice.value="File exported"}.onFailure{Hub.notice.value="Export failed"};exportData=null}
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{
        importBusy=true
        try{
            val text=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use{input->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=4*1024*1024){"File is too large"};out.write(buffer,0,n)}
                out.toString("UTF-8")
            }}
            when{
                text.trimStart().startsWith("{")->if(com.google.gson.JsonParser.parseString(text).asJsonObject.has("captures"))sessionImport=StateFormat.decode(text)else Hub.importCapture(StateFormat.importFob(text))
                text.contains("Filetype: Flipper")->Hub.importCapture(StateFormat.importRawSub(text))
                else->Hub.configure(StateFormat.ini(text,s))
            };if(sessionImport==null)Hub.notice.value="Import complete"
        }catch(e:Exception){Hub.notice.value=e.message?:"Import failed"}finally{importBusy=false}
    }}
    fun exportCapture(c:Capture,format:String){try{exportData=when(format){"sub"->StateFormat.rawSub(c);"fob"->StateFormat.fob(c);else->StateFormat.captureJson(c,s.includeRaw)}.toByteArray();export.launch("KAT_${c.heardFrequency}_${c.id.take(8)}.$format")}catch(e:Exception){Hub.notice.value=e.message?:"Export failed"}}
    // KAT's core plans the transmission (Replay, or its encoder with the copies KAT sends); the radio service sends it.
    fun send(c:Capture,action:String,button:TxButton?){scope.launch{
        try{
            val plan=withContext(Dispatchers.Default){NativeTx.plan(c,action,button?.code)}
            val what=when(action){"replay"->"a replay of ${c.pulses.size} pulses";"next"->"the next code"+plan.button_label?.let{" ($it)"}.orEmpty();else->plan.button_label?:button?.label?:"a button"}
            // Where the remote was heard (desktop KAT's `heard_frequency`), not where the radio was tuned.
            transmit(Hub.TxRequest(c.id,what,c.heardFrequency,plan))
        }catch(e:Exception){Hub.notice.value=e.message?:"This capture cannot be sent"}
    }}
    DisposableEffect(s.keepScreen){val window=(context as ComponentActivity).window;if(s.keepScreen)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);onDispose{window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
    if(notice.isNotBlank())AlertDialog(onDismissRequest={Hub.notice.value=""},title={Text("KAT Mobile")},text={Text(notice)},confirmButton={TextButton(onClick={Hub.notice.value=""}){Text("OK")}})
    if(deviceChoices.isNotEmpty())AlertDialog(onDismissRequest=dismissDevices,title={Text("Select USB SDR")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        deviceChoices.forEach{d->val k=SdrDevices.kindOf(d);OutlinedButton(onClick={pickDevice(d)},modifier=Modifier.fillMaxWidth()){
            Column(Modifier.weight(1f)){Text(SdrDevices.displayName(d),color=Green);Text(if(k==RadioKind.HACKRF)"RX + TX · CS8" else "RX only · CU8",color=Dim,fontSize=11.sp)}
        }}
    }},confirmButton={TextButton(onClick=dismissDevices){Text("Cancel")}})
    if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("Clear signal history?")},text={Text("Clears captured signal history. Settings and IQ files are retained.")},confirmButton={TextButton(onClick={Hub.delete(captures.map{it.id}.toSet());selected=null;clear=false}){Text("Delete")}},dismissButton={TextButton(onClick={clear=false}){Text("Cancel")}})
    if(sessionImport!=null)AlertDialog(onDismissRequest={sessionImport=null},title={Text("Import a saved session?")},text={Text("Replaces the current signal history and settings with the contents of the file.")},confirmButton={TextButton(onClick={Hub.restore(sessionImport!!);sessionImport=null}){Text("Import")}},dismissButton={TextButton(onClick={sessionImport=null}){Text("Cancel")}})
    if(selected!=null){val c=captures.firstOrNull{it.id==selected?.id}?:selected!!;AlertDialog(onDismissRequest={selected=null},title={Text("Signal details",fontFamily=FontFamily.Monospace)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("${frequency(c.frequency)} · ${c.modulation}\n${c.protocol} · ${c.source}",color=Cyan)
        Text("Received: ${dateTime(c.time)}",color=Dim,fontSize=12.sp)
        Text("${c.pulses.size} pulses · ${c.durationUs/1000.0} ms · ×${c.repeats}",fontFamily=FontFamily.Monospace,fontSize=12.sp)
        if(c.recognition.isNotBlank())Text(c.recognition,color=Green,fontSize=12.sp)
        if(c.heardFrequency!=c.frequency)Text("Heard at ${frequency(c.heardFrequency)}, where it is sent",color=Dim,fontSize=12.sp)
        if(c.serial.isNotBlank())Text("Serial: ${c.serial}\nButton: ${c.button}\nCounter: ${c.counter}",fontFamily=FontFamily.Monospace,fontSize=12.sp)
        TransmitControls(c,!status.transmitting&&!offline,status.running&&status.radioKind==RadioKind.RTLSDR,::send)
        PulsePlot(c,Modifier.fillMaxWidth().height(110.dp))
        val groups=c.pulses.groupingBy{(it.duration_us/25)*25}.eachCount().entries.sortedByDescending{it.value}.take(8)
        Text("PULSE DURATION",color=Dim,fontSize=11.sp)
        Text(groups.joinToString("\n"){"${it.key} µs  × ${it.value}"},fontFamily=FontFamily.Monospace,fontSize=12.sp)
        Text("KAT FOB and RAW SUB preserve all saved pulses. The pulse count describes this capture; repeats are never added artificially.",color=Dim,fontSize=11.sp)
        Row{TextButton(onClick={exportCapture(c,"fob")},enabled=c.pulses.isNotEmpty()){Text("KAT FOB")};TextButton(onClick={exportCapture(c,"sub")},enabled=c.modulation=="AM"&&c.pulses.isNotEmpty()){Text("RAW SUB")};TextButton(onClick={exportCapture(c,"json")}){Text("JSON")}}
        TextButton(onClick={Hub.delete(setOf(c.id));selected=null}){Text("Delete signal",color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={selected=null}){Text("Close")}})}
    Scaffold(containerColor=Bg,bottomBar={NavigationBar(containerColor=Bg){
        listOf("Receive" to Icons.Outlined.GraphicEq,"Signals" to Icons.Outlined.ListAlt,"Archive" to Icons.Outlined.FolderOpen,"Settings" to Icons.Outlined.Tune).forEachIndexed{i,p->NavigationBarItem(selected=tab==i,onClick={tab=i;if(i==1)protocolFilter=null},icon={Icon(p.second,p.first)},label={Text(p.first,fontSize=11.sp)},colors=NavigationBarItemDefaults.colors(indicatorColor=Green.copy(alpha=.15f),selectedIconColor=Green))}
    }}){insets->Column(Modifier.fillMaxSize().padding(insets).padding(horizontal=18.dp)){
        Row(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(Icons.Outlined.GraphicEq,null,tint=Green,modifier=Modifier.size(28.dp));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text("KAT",fontWeight=FontWeight.Bold,fontSize=25.sp,fontFamily=FontFamily.Monospace);Text("KAT — coded by CooKie",color=Dim,fontSize=10.sp,letterSpacing=.5.sp)}
            Surface(color=(if(status.running||status.transmitting)Green else Dim).copy(alpha=.12f),shape=RoundedCornerShape(10.dp)){Text(if(status.transmitting)"● TX"else if(status.running)"● LIVE RX"else if(offline)"● IQ FILE"else"○ IDLE",color=if(status.running||status.transmitting)Green else if(offline)Cyan else Dim,fontFamily=FontFamily.Monospace,fontSize=11.sp,modifier=Modifier.padding(10.dp))}
        }
        val wide=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp>=700
        Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(16.dp)){
        Box(Modifier.weight(1f).fillMaxHeight()) { when(tab){
            0->LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=20.dp)){
                item{
                    val radioTitle=when(status.radioKind){RadioKind.HACKRF->"RADIO / HACKRF";RadioKind.RTLSDR->"RADIO / RTL-SDR";null->"RADIO / SDR"}
                    val radioSummary=if(status.radioKind==RadioKind.RTLSDR){
                        val rtlRate=if(status.running)status.activeSampleRate else s.rtlSampleRate
                        "${"%.2f".format(Locale.US,rtlRate/1e6)} MS/s   GAIN ${RtlSdrUsb.gainLabel(s.rtlAutoGain,s.rtlGainTenthsDb)}   PPM ${s.rtlPpm}${if(s.rtlBiasTee)"   BIAS-T"else""}"
                    }else{
                        "${s.sampleRate/1_000_000} MS/s   LNA ${s.lna}   VGA ${s.vga}   AMP ${if(s.amp)"ON"else"OFF"}"
                    }
                    Frame(radioTitle){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Bottom){Text(frequency(if(status.running)status.frequency else s.frequency),fontSize=30.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold);Text(s.modulation,color=Cyan)}
                    Text(radioSummary,color=Dim,fontFamily=FontFamily.Monospace,fontSize=11.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        Button(onClick={if(status.running)RadioService.action(context,"STOP")else start()},enabled=!offline&&!status.transmitting){Icon(if(status.running)Icons.Outlined.Stop else Icons.Outlined.PlayArrow,null);Text(if(status.running)"Stop RX"else"Start RX")}
                        OutlinedButton(onClick={RadioService.action(context,"RECORD")},enabled=status.running){Icon(if(status.recording.isNotBlank())Icons.Outlined.StopCircle else Icons.Outlined.FiberManualRecord,null);Text(if(status.recording.isNotBlank())"Stop IQ"else"Record IQ")}
                    }
                    if(s.hopping)Text(if(status.running)"HOP ${status.hopIndex}/${status.hopTotal} · ${s.hopDwellMs} ms · ${status.hopSwitches} switches"else"HOP ready · ${s.hopDwellMs} ms per frequency",color=Green,fontFamily=FontFamily.Monospace,fontSize=12.sp)
                    Text(status.message,color=Dim,fontSize=12.sp)
                    if(status.recording.isNotBlank())Text("● IQ ${size(status.recordingBytes)}",color=Color(0xFFFF8686),fontFamily=FontFamily.Monospace)
                }}
                item{LatestReception(latestReception,status.running,offline){selected=it}}
                item{Frame("SIGNAL / SPECTRUM"){SpectrumPlot(status,if(status.running||offline)status.activeSampleRate else s.sampleRate,Modifier.fillMaxWidth().height(150.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${"%.1f".format(status.powerDb)} dBFS",color=Green,fontFamily=FontFamily.Monospace);Text("${size(status.bytesPerSecond)}/s",color=Dim,fontFamily=FontFamily.Monospace,fontSize=12.sp)}}}
                item{Frame("USB / SESSION"){Text("${status.device}${if(status.firmware.isNotBlank())" · ${status.firmware}"else""}",color=Cyan,fontSize=12.sp);Text("${size(status.bytes)} received  ·  ${captures.size} signals",color=Dim,fontFamily=FontFamily.Monospace,fontSize=12.sp)}}
                item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("RECENT PROTOCOLS",color=Cyan,fontFamily=FontFamily.Monospace,fontSize=12.sp);TextButton(onClick={protocolFilter=null;tab=1}){Text("View all")}}}
                if(captures.isEmpty())item{EmptyState("Waiting for a signal","Start RX or import a RAW/FOB file. Live reception requires real USB samples.")}
                if(grouped.isNotEmpty())item{ProtocolShelf(grouped.take(4),::openProtocol)}
            }
            1->Column(Modifier.fillMaxSize()){
                if(status.running||offline)Text(if(status.running)"● RX active · history updates automatically"else"● IQ analysis · history updates automatically",color=Green,fontSize=12.sp,modifier=Modifier.padding(bottom=8.dp))
                if(protocolFilter!=null)TextButton(onClick={protocolFilter=null}){Icon(Icons.Outlined.ArrowBack,null);Spacer(Modifier.width(6.dp));Text("All protocols")}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(protocolFilter?:"Signals (${captures.size})",fontSize=23.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f));IconButton(onClick={Hub.undo()}){Icon(Icons.Outlined.Undo,"Undo deletion")};IconButton(onClick={clear=true},enabled=captures.isNotEmpty()){Icon(Icons.Outlined.DeleteSweep,"Clear history")}}
                Row{TextButton(onClick={import.launch(arrayOf("*/*"))},enabled=!importBusy&&!status.running){Text("Import RAW / FOB")};TextButton(onClick={exportData=StateFormat.gson.toJson(Saved(s,captures)).toByteArray();export.launch("KAT-session.json")}){Text("Export session")}}
                if(protocolFilter==null){
                    OutlinedTextField(protocolSearch,{protocolSearch=it},label={Text("Search protocols")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(protocolSearch.isNotEmpty())IconButton(onClick={protocolSearch=""}){Icon(Icons.Outlined.Close,"Clear search")}},singleLine=true,modifier=Modifier.fillMaxWidth().padding(bottom=12.dp))
                    val shown=grouped.filter{it.protocol.contains(protocolSearch.trim(),true)}
                    if(captures.isEmpty())EmptyState("No captured signals","A new session is ready.")
                    else if(shown.isEmpty())EmptyState("No results","Try another protocol name.")
                    LazyVerticalGrid(columns=GridCells.Adaptive(148.dp),verticalArrangement=Arrangement.spacedBy(10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
                        items(shown,key={it.protocol}){g->ProtocolTile(g,Modifier.fillMaxWidth()){openProtocol(g.protocol)}}
                    }
                }else{
                    val records=grouped.firstOrNull{it.protocol==protocolFilter}?.records.orEmpty()
                    Text("${recordCount(records.size)} · newest first",color=Dim,fontSize=12.sp,modifier=Modifier.padding(bottom=12.dp))
                    if(records.isEmpty())EmptyState("No captures in this group","Return to all protocols or undo the deletion.")
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)){items(records,key={it.id}){c->CaptureRow(c,s.fontSize){selected=c}}}
                }
            }
            2->Archive(status)
            3->Settings(s,status.running||offline,{import.launch(arrayOf("*/*"))})
        } }
        if(wide&&tab==0)Column(Modifier.width(310.dp).fillMaxHeight()){
            Text("LIVE CAPTURES",color=Cyan,fontFamily=FontFamily.Monospace,fontSize=12.sp,modifier=Modifier.padding(bottom=12.dp))
            if(captures.isEmpty())EmptyState("Waiting for a signal","The list updates during reception.")
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(grouped,key={it.protocol}){g->ProtocolTile(g,Modifier.fillMaxWidth()){openProtocol(g.protocol)}}}
        }
        }
    }}
}

/** Desktop KAT's transmit actions: Replay for any capture; a car remote's Lock, Unlock, Trunk and Panic; a gate remote's next code and its own buttons. */
@Composable private fun TransmitControls(c:Capture,enabled:Boolean,rtlRxOnly:Boolean,send:(Capture,String,TxButton?)->Unit){
    val tx=remember(c.decode){NativeTx.actions(c.decode)}
    val message by Hub.txMessage.collectAsStateWithLifecycle()
    Text("TRANSMIT",color=Dim,fontSize=11.sp)
    if(rtlRxOnly)Text("RTL-SDR is receive-only; transmitting requires a HackRF.",color=Dim,fontSize=11.sp)
    Row{
        TextButton(onClick={send(c,"replay",null)},enabled=enabled&&c.pulses.isNotEmpty()){Text("Replay")}
        if(tx.encodes&&tx.gate)TextButton(onClick={send(c,"next",null)},enabled=enabled){Text("Send next code")}
    }
    if(tx.encodes&&!tx.gate)tx.buttons.chunked(2).forEach{row->Row{row.forEach{b->TextButton(onClick={send(c,"button",b)},enabled=enabled){Text("TX ${b.label}")}}}}
    if(tx.encodes&&tx.gate&&tx.buttons.isNotEmpty()){
        var open by remember{mutableStateOf(false)}
        Box{
            OutlinedButton(onClick={open=true},enabled=enabled){Text("Send button…")}
            DropdownMenu(expanded=open,onDismissRequest={open=false}){tx.buttons.forEach{b->DropdownMenuItem(text={Text(b.label)},onClick={open=false;send(c,"button",b)})}}
        }
    }
    if(!tx.encodes&&c.decode.isNotBlank())Text("KAT cannot build this remote's codes (its key is not known): Replay only.",color=Dim,fontSize=11.sp)
    if(message.isNotBlank())Text(message,color=Green,fontSize=11.sp)
}

@Composable private fun LatestReception(c:Capture?,running:Boolean,offline:Boolean,open:(Capture)->Unit){
    Frame("LATEST RECEIVED SIGNAL",Modifier.fillMaxWidth().testTag("latest-reception")){
        if(c==null){
            Text(if(running||offline)"Waiting for a new signal…"else"Start reception for live monitoring",color=Dim,fontSize=12.sp)
        }else{
            Text(c.protocol,color=Green,fontSize=21.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("${frequency(c.frequency)} · ${c.modulation} · ${time(c.time)}",color=Cyan,fontFamily=FontFamily.Monospace,fontSize=12.sp)
            Text("${c.pulses.size} pulses · ${c.source}",color=Dim,fontSize=12.sp)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Every reception, including duplicates",color=Dim,fontSize=10.sp,modifier=Modifier.weight(1f))
                TextButton(onClick={open(c)}){Text("Details")}
            }
        }
    }
}
@Composable private fun EmptyState(title:String,description:String){Column(Modifier.fillMaxWidth().padding(vertical=30.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.Sensors,null,tint=Green,modifier=Modifier.size(42.dp));Text(title,fontSize=18.sp,modifier=Modifier.padding(top=12.dp));Text(description,color=Dim,fontSize=12.sp,modifier=Modifier.padding(12.dp))}}
@Composable private fun ProtocolShelf(groups:List<CaptureGroup>,open:(String)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        groups.chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            row.forEach{g->ProtocolTile(g,Modifier.weight(1f)){open(g.protocol)}}
            if(row.size==1)Spacer(Modifier.weight(1f))
        }}
    }
}
@Composable private fun ProtocolTile(group:CaptureGroup,modifier:Modifier=Modifier,click:()->Unit){
    Surface(onClick=click,color=Panel,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Color(0xFF243B45)),modifier=modifier.testTag("protocol-group-${group.protocol}")){
        Column(Modifier.padding(14.dp).heightIn(min=94.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Icon(Icons.Outlined.Sensors,null,tint=Cyan,modifier=Modifier.size(18.dp));Spacer(Modifier.weight(1f))
                Surface(color=Green.copy(alpha=.12f),shape=RoundedCornerShape(7.dp)){Text(group.records.size.toString(),color=Green,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold,fontSize=12.sp,modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp))}
            }
            Text(group.protocol,color=Green,fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("${group.modulation} · ${shortDateTime(group.latest)}",color=Dim,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun CaptureRow(c:Capture,font:Int,click:()->Unit){
    Surface(onClick=click,color=Panel,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,Color(0xFF1C303A)),modifier=Modifier.testTag("capture-row")){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(dateTime(c.time),fontWeight=FontWeight.Medium,fontSize=(font-1).coerceIn(11,16).sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight,null,tint=Dim,modifier=Modifier.size(18.dp))
            }
            Text("${frequency(c.frequency)} · ${c.modulation} · ${c.pulses.size} pulses",color=Cyan,fontFamily=FontFamily.Monospace,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${c.source} · ${"%.1f".format(c.durationUs/1000.0)} ms · ×${c.repeats}",color=Dim,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun PulsePlot(c:Capture,modifier:Modifier){Canvas(modifier.background(Bg,RoundedCornerShape(8.dp))){
    val pulses=c.pulses.take(160);val total=pulses.sumOf{it.duration_us.toLong()}.coerceAtLeast(1);var x=0f;val path=Path()
    pulses.forEachIndexed{index,p->val y=if(p.level)size.height*.2f else size.height*.8f;if(index==0)path.moveTo(x,y)else path.lineTo(x,y);x+=p.duration_us.toFloat()/total*size.width;path.lineTo(x,y)}
    drawPath(path,Green,style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
}}
@Composable private fun SpectrumPlot(status:RadioStatus,rate:Int,modifier:Modifier){
    Canvas(modifier.background(Bg,RoundedCornerShape(10.dp))){
        for(i in 1..4)drawLine(Color(0xFF20323C),Offset(0f,size.height*i/5),Offset(size.width,size.height*i/5))
        for(i in 1..5)drawLine(Color(0xFF20323C),Offset(size.width*i/6,0f),Offset(size.width*i/6,size.height))
        val data=status.spectrum
        if(data.isNotEmpty()){val p=Path();data.forEachIndexed{i,v->val x=i.toFloat()/(data.size-1)*size.width;val y=(1-(v+100)/106)*size.height;if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};drawPath(p,Green,style=androidx.compose.ui.graphics.drawscope.Stroke(2f))}
        drawLine(Cyan.copy(alpha=.5f),Offset(size.width/2,0f),Offset(size.width/2,size.height),1f)
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("−${rate/2_000_000.0} MHz",color=Dim,fontSize=10.sp);Text("CENTER",color=Dim,fontSize=10.sp);Text("+${rate/2_000_000.0} MHz",color=Dim,fontSize=10.sp)}
    var history by remember{mutableStateOf<List<List<Float>>>(emptyList())}
    LaunchedEffect(status.spectrum,status.frequency){if(status.running&&status.spectrum.isNotEmpty())history=(listOf(status.spectrum)+history).take(32)}
    Canvas(Modifier.fillMaxWidth().height(64.dp).background(Bg)){
        history.forEachIndexed{row,bins->bins.forEachIndexed{col,v->val t=((v+85)/75).coerceIn(0f,1f)
            val color=if(t<.5f)Color(0f,t*.9f,t*1.7f)else Color((t-.5f)*2,1f,1f-t)
            drawRect(color,Offset(col.toFloat()/bins.size*size.width,row.toFloat()/32*size.height),androidx.compose.ui.geometry.Size(size.width/bins.size+1,size.height/32+1))
        }}
    }
}

@Composable private fun Settings(s:RadioSettings,running:Boolean,import:()->Unit){
    var edit by remember(s){mutableStateOf(s)}
    var frequency by remember(s){mutableStateOf((s.frequency/1e6).toString())}
    var catalog by remember{mutableStateOf(false)}
    var whatsNew by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    fun save(){try{val next=edit.copy(frequency=RadioSettings.mhz(frequency));Hub.configure(next);Hub.notice.value="Settings saved"}catch(e:Exception){Hub.notice.value=e.message?:"Check settings"}}
    if(catalog)ProtocolCatalog{catalog=false}
    if(whatsNew)WhatsNew{whatsNew=false}
    LazyColumn(Modifier.testTag("settings-list"),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=30.dp)){
        item{Text("Settings",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("KAT controls · USB reception",color=Dim,fontSize=12.sp)}
        item{Frame("PROTOCOLS / KAT RX"){
            Text(if(NativeDecoder.available)"${NativeDecoder.protocols.size} native KAT receive decoders"else"Native core unavailable",color=Green,fontSize=15.sp)
            Text("AUTO checks both AM and FM paths. The registry is KAT's; not every physical model has been verified. Transmit from a signal's details.",color=Dim,fontSize=12.sp)
            OutlinedButton(onClick={catalog=true}){Text("View supported protocols")}
        }}
        item{Frame("RADIO"){OutlinedTextField(frequency,{frequency=it},label={Text("Frequency (MHz)")},enabled=!running,singleLine=true,modifier=Modifier.fillMaxWidth());Choice("Sample rate",edit.sampleRate.toString(),listOf(2_000_000,4_000_000,8_000_000,10_000_000,16_000_000,20_000_000).map{it.toString()},!running){value->val rate=value.toInt();edit=edit.copy(sampleRate=rate,bandwidth=edit.bandwidth.coerceAtMost(Wire.bandwidths.last{it<=rate}))};Choice("LNA gain (dB)",edit.lna.toString(),(0..40 step 8).map{it.toString()},!running){edit=edit.copy(lna=it.toInt())};Choice("VGA gain (dB)",edit.vga.toString(),(0..62 step 2).map{it.toString()},!running){edit=edit.copy(vga=it.toInt())};Toggle("RF amplifier (AMP)",edit.amp,!running){edit=edit.copy(amp=it)};Choice("Filter bandwidth",edit.bandwidth.toString(),Wire.bandwidths.filter{it<=edit.sampleRate}.map{it.toString()},!running){edit=edit.copy(bandwidth=it.toInt())};Choice("Demodulation",edit.modulation,listOf("AUTO","AM","FM"),!running){edit=edit.copy(modulation=it)}}}
        item{Frame("RTL-SDR (RX ONLY)",Modifier.testTag("rtlsdr-settings")){
            Choice("RTL-SDR sample rate",edit.rtlSampleRate.toString(),RtlSdrUsb.SAMPLE_RATES.map{it.toString()},!running){edit=edit.copy(rtlSampleRate=it.toInt())}
            Toggle("Auto tuner gain (AGC)",edit.rtlAutoGain,!running){edit=edit.copy(rtlAutoGain=it)}
            Choice("Manual tuner gain",RtlSdrUsb.gainLabel(false,edit.rtlGainTenthsDb),RtlSdrUsb.R82XX_GAINS_TENTHS_DB.map{RtlSdrUsb.gainLabel(false,it)},!running&&!edit.rtlAutoGain){label->
                RtlSdrUsb.R82XX_GAINS_TENTHS_DB.firstOrNull{RtlSdrUsb.gainLabel(false,it)==label}?.let{edit=edit.copy(rtlGainTenthsDb=it)}
            }
            NumberField("Frequency correction (PPM)",edit.rtlPpm,!running){edit=edit.copy(rtlPpm=it)}
            Toggle("Bias-Tee (~4.5 V DC)",edit.rtlBiasTee,!running){edit=edit.copy(rtlBiasTee=it)}
            Text("Direct USB OTG for RTL2832U dongles with R820T / R820T2 / R828D tuners (including RTL-SDR Blog V3/V4). Keep Bias-Tee OFF unless powering an active LNA/antenna; DC-shorted antennas may damage the dongle.",color=Dim,fontSize=11.sp)
        }}
        item{Frame("TRANSMIT"){Choice("TX power",txPowerLabel(edit.txVga,edit.txAmp),TX_POWER_STEPS.map{(g,a)->txPowerLabel(g,a)},!running){label->TX_POWER_STEPS.firstOrNull{(g,a)->txPowerLabel(g,a)==label}?.let{(g,a)->edit=edit.copy(txVga=g,txAmp=a)}};Text("Replay, Send next code and the car and gate buttons (in a signal's details) transmit through the HackRF with this TX gain and RF amplifier, as desktop KAT does. Receiving pauses for a transmission. Transmit only to systems you own or may test.",color=Dim,fontSize=11.sp)}}
        item{Frame("FREQUENCY HOPPING"){Toggle("Hop",edit.hopping,!running){edit=edit.copy(hopping=it)};OutlinedTextField(edit.hopFrequencies,{edit=edit.copy(hopFrequencies=it)},label={Text("Frequencies in MHz, separated by commas")},enabled=!running,modifier=Modifier.fillMaxWidth());NumberField("Time per frequency (ms)",edit.hopDwellMs,!running){edit=edit.copy(hopDwellMs=it)};Text("Hop continuously cycles through the list at the set interval, without waiting for a signal to end. A transmission crossing a hop may be incomplete. IQ recording uses one fixed frequency.",color=Dim,fontSize=11.sp)}}
        item{Frame("SIGNAL / DETECTION"){Text("Original KAT DSP: carrier detection, channel filtering, adaptive AM/FM thresholds and pulse extraction. Bursts close after 80 ms of silence. Legacy manual detector settings do not control this pipeline.",color=Dim,fontSize=12.sp);Toggle("Show unknown signals",edit.showUnknown,!running){edit=edit.copy(showUnknown=it)};Toggle("Merge duplicates",edit.ignoreDuplicates,!running){edit=edit.copy(ignoreDuplicates=it)}}}
        item{Frame("SESSION / ARCHIVE"){NumberField("Maximum captures",edit.maxCaptures,!running){edit=edit.copy(maxCaptures=it)};NumberField("IQ quota (MB)",edit.recordingQuotaMb,!running){edit=edit.copy(recordingQuotaMb=it)};Toggle("Save session automatically",edit.autosave,!running){edit=edit.copy(autosave=it)};Toggle("Include RAW pulses in JSON export",edit.includeRaw,!running){edit=edit.copy(includeRaw=it)};Toggle("Keep screen on",edit.keepScreen,!running){edit=edit.copy(keepScreen=it)};NumberField("Signal text size",edit.fontSize,!running){edit=edit.copy(fontSize=it)}}}
        item{Button(onClick=::save,enabled=!running,modifier=Modifier.fillMaxWidth()){Text("Save settings")};OutlinedButton(onClick=import,enabled=!running,modifier=Modifier.fillMaxWidth()){Text("Import KAT config.ini / RAW / FOB")};if(running)Text("Stop RX to change settings.",color=Cyan)}
        item{Frame("THANK YOU",Modifier.testTag("kara-thanks")){Text("Thank you, Kara, for supporting me in developing the Android app.",color=Green,fontSize=15.sp);Text("— CooKie",color=Dim,fontSize=12.sp)}}
        item{Frame("ABOUT"){OutlinedButton(onClick={whatsNew=true}){Text("What's new")};Text("KAT Mobile RX ${BuildConfig.VERSION_NAME}\nReceive core: KAT ${BuildConfig.KAT_CORE}\nRadios: HackRF (RX + TX) · RTL-SDR R820T/R820T2/R828D (RX only)\nConnection: Android USB Host, no root or external driver required.",fontSize=12.sp,color=Dim);Text("${NativeDecoder.protocols.size} native KAT receive decoders, plus Nexus/TFA and Rubicson/Solight sensors. A format match does not confirm a manufacturer; not every physical model has been verified.",fontSize=12.sp,color=Dim);Text("KAT's receive core identifies each signal, and KAT's encoders send Replay, Send next code and the car and gate buttons through the HackRF (a signal's details, Transmit). Key recovery is not part of the app. Imported FOB metadata is preserved.",fontSize=12.sp,color=Dim);Text("Data stays on this device. Sessions use Android Keystore encryption; IQ recordings are stored in app-private storage. No network permission.",fontSize=12.sp,color=Dim);Text("KAT source: BSD-3-Clause, © 2026 .kara. Embedded RTL-SDR control driver adapted from rtl-sdr-rs (MPL-2.0). Original license notices are included in the project and APK.",fontSize=11.sp,color=Dim)}}
    }
}

@Composable private fun WhatsNew(close:()->Unit){
    AlertDialog(onDismissRequest=close,title={Text("What's new · ${BuildConfig.VERSION_NAME}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("RTL-SDR over USB OTG",color=Green,fontWeight=FontWeight.Bold)
        Text("Direct receive-only support for RTL2832U dongles with R820T, R820T2 and R828D tuners (including RTL-SDR Blog V3/V4) over Android's USB Host API — no root and no external driver app required. Includes live CU8 spectrum/waterfall, frequency hopping, .cu8 IQ recording, auto/manual tuner gain, PPM correction and optional bias-tee.")
        Text("Transmit",color=Green,fontWeight=FontWeight.Bold)
        Text("KAT Mobile transmits through the HackRF, as KAT does. Replay sends a capture's pulses; a car remote's Lock, Unlock, Trunk and Panic and a gate remote's Send next code and buttons come from KAT's encoders, and a rolling code's counter advances with every press. Receiving pauses for a transmission; TX power is in Settings.")
        Text("KAT ${BuildConfig.KAT_CORE} receive core",color=Green,fontWeight=FontWeight.Bold)
        Text("KAT Mobile's version now follows KAT's: this release's receive core is KAT ${BuildConfig.KAT_CORE}'s, with the same ${NativeDecoder.protocols.size} decoders, its encoders, and KAT's embedded keys, which it now loads at start as KAT does: the Kia V3/V4, V5 and V6, VAG, PSA, Ford V1, BMW V3 and Audi V3 decoders decrypt with them.")
        Text("KAT's official Android app",color=Green,fontWeight=FontWeight.Bold)
        Text("KAT Mobile is now built and tested from github.com/KaraZajac/KAT-Mobile on every change. Its application ID is io.karazajac.katmobile, so it installs beside the earlier test builds; the notification's Stop button is now in English.")
        Text("0.8.0",color=Dim,fontWeight=FontWeight.Bold)
        Text("English edition",color=Green,fontWeight=FontWeight.Bold)
        Text("All screens, settings, notifications and app messages are now in English.")
        Text("KAT 1.29.0 receive core",color=Green,fontWeight=FontWeight.Bold)
        Text("${NativeDecoder.protocols.size} native receive decoders (previously 150), with decoder fixes and improved recognition of BMW, Fiat/Maserati, Ford, Honda, PSA and other formats. The supported-protocol catalog lists the formats included in this build.")
        Text("New format recognition",color=Green,fontWeight=FontWeight.Bold)
        Text("New upstream receive formats include BMW FZV, Subaru V1, Lexus UX, Volvo S40 and PSA 2-Button. Recognition does not guarantee compatibility with every physical remote.")
        Text("Monitoring and capture compatibility",color=Green,fontWeight=FontWeight.Bold)
        Text("Grouped history, the latest-reception panel, all saved RAW pulses and FOB/SUB/JSON export are retained. Fixed-dwell hopping runs independently of signal detection; a hop can interrupt a transmission.")
        Text("Android scope",color=Green,fontWeight=FontWeight.Bold)
        Text("This is a receive-only Android adaptation. Desktop TX/replay, key recovery, per-remote Nissan tagging, additional radio drivers and terminal tools are not Android features. Existing FOB metadata is preserved.")
        Text("Thanks, Kara",color=Green,fontWeight=FontWeight.Bold)
        Text("Thank you, Kara, for supporting me in developing the Android app. — CooKie")
    }},confirmButton={TextButton(onClick=close){Text("Close")}})
}

@Composable private fun ProtocolCatalog(close:()->Unit){
    var search by remember{mutableStateOf("")}
    val entries=remember(search){NativeDecoder.protocols.filter{it.name.contains(search,true)||it.format.contains(search,true)}}
    AlertDialog(onDismissRequest=close,title={Text("KAT RX · ${NativeDecoder.protocols.size}")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=450.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            OutlinedTextField(search,{search=it},label={Text("Search protocols")},singleLine=true,modifier=Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){
                items(entries,key={it.name}){p->Column{Text(p.name,color=Green,fontFamily=FontFamily.Monospace,fontSize=13.sp);Text("${p.rf} · ${p.coding}\n${p.format}",fontSize=11.sp,color=Dim)}}
            }
        }
    },confirmButton={TextButton(onClick=close){Text("Close")}})
}
@Composable private fun Choice(label:String,value:String,values:List<String>,enabled:Boolean,change:(String)->Unit){var open by remember{mutableStateOf(false)};Box{OutlinedButton(onClick={open=true},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text("$label: $value",modifier=Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)};DropdownMenu(expanded=open,onDismissRequest={open=false}){values.forEach{v->DropdownMenuItem(text={Text(v)},onClick={change(v);open=false})}}}}
@Composable private fun Toggle(label:String,value:Boolean,enabled:Boolean,change:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,modifier=Modifier.weight(1f),fontSize=13.sp);Switch(checked=value,onCheckedChange=change,enabled=enabled)}}
@Composable private fun NumberField(label:String,value:Int,enabled:Boolean,change:(Int)->Unit){var text by remember(value){mutableStateOf(value.toString())};OutlinedTextField(text,{text=it;it.toIntOrNull()?.let(change)},label={Text(label)},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())}

@Composable private fun Archive(status:RadioStatus){
    val context=androidx.compose.ui.platform.LocalContext.current;val scope=rememberCoroutineScope()
    var refresh by remember{mutableIntStateOf(0)}
    val files=remember(refresh,status.recording){File(context.getExternalFilesDir(null),"recordings").listFiles().orEmpty().filter{it.extension in listOf("cs8","cu8")}.sortedByDescending{it.lastModified()}}
    var selected by remember{mutableStateOf<File?>(null)};var busy by remember{mutableStateOf(false)}
    val offline by Hub.offlineBusy.collectAsStateWithLifecycle()
    val iqImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)runCatching{Hub.analyze(context,uri)}.onFailure{Hub.notice.value=it.message.orEmpty()}}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->val file=selected;if(uri!=null&&file!=null)scope.launch{busy=true;try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")!!.use{out->file.inputStream().use{it.copyTo(out)}}};Hub.notice.value="IQ recording exported"}catch(e:Exception){Hub.notice.value=e.message?:"Export failed"}finally{busy=false}}}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=20.dp)){
        item{Text("IQ archive",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("CS8 / CU8 · raw complex samples",color=Dim,fontSize=12.sp);Row{TextButton(onClick={refresh++}){Text("Refresh")};TextButton(onClick={if(offline)Hub.cancelAnalysis()else iqImport.launch(arrayOf("*/*"))},enabled=!status.running){Text(if(offline)"Cancel analysis"else"Analyze IQ file")}}}
        if(files.isEmpty())item{EmptyState("No IQ recordings","Enable recording during USB reception.")}
        items(files,key={it.path}){file->Frame(file.name){Text(size(file.length()),color=Dim);Row{TextButton(onClick={runCatching{Hub.analyze(context,android.net.Uri.fromFile(file))}.onFailure{Hub.notice.value=it.message.orEmpty()}},enabled=!status.running&&!offline&&file.name!=status.recording){Text("Analyze")};TextButton(onClick={selected=file;export.launch(file.name)},enabled=!busy&&file.name!=status.recording){Text("Export")};TextButton(onClick={if(file.name!=status.recording){file.delete();File(file.path+".json").delete();refresh++}},enabled=file.name!=status.recording){Text("Delete",color=MaterialTheme.colorScheme.error)}}}}
        item{Frame("DIAGNOSTICS"){val logs by Hub.logs.collectAsStateWithLifecycle();Text(logs.takeLast(12).joinToString("\n").ifBlank{"No entries"},fontFamily=FontFamily.Monospace,fontSize=11.sp,color=Dim)}}
    }
}
