package app.katmobile

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** Supported USB radios: two SDR families, and the Flipper Zero, whose command line gives pulses (no IQ). */
enum class RadioKind(val label:String,val supportsTx:Boolean) {
    HACKRF("HackRF",true),
    RTLSDR("RTL-SDR",false),
    FLIPPER("Flipper Zero",true)
}

/** Raw interleaved 8-bit IQ sample format delivered by a USB SDR. */
enum class IqFormat(val extension:String,val label:String,val unsigned:Boolean) {
    CS8("cs8","CS8",false),
    CU8("cu8","CU8",true)
}

/** Common receive interface for USB OTG SDRs (HackRF and RTL-SDR) over Android's USB Host API. */
interface SdrUsb:AutoCloseable {
    val kind:RadioKind
    val iqFormat:IqFormat
    val description:String
    val supportsTx:Boolean get()=kind.supportsTx
    fun sampleRate(s:RadioSettings):Int
    fun configure(s:RadioSettings,center:Long=s.frequency)
    fun tune(hz:Long)
    fun isCurrentGeneration(generation:Long):Boolean
    fun read(hopping:Boolean=false,consume:(ByteArray,Int,Long)->Unit)
    fun stop()
}

/** Discovery and classification of the supported USB radios (HackRF, RTL2832U-based RTL-SDRs, Flipper Zero). */
object SdrDevices {
    /** Known RTL2832U VID/PID pairs (matching native/src/rtlsdr/device/constants.rs and usb_devices.xml). */
    val RTL_SDR_IDS:Set<Pair<Int,Int>> = setOf(
        0x0bda to 0x2832, 0x0bda to 0x2838,
        0x0413 to 0x6680, 0x0413 to 0x6f0f,
        0x0458 to 0x707f,
        0x0ccd to 0x00a9, 0x0ccd to 0x00b3, 0x0ccd to 0x00b4, 0x0ccd to 0x00b5,
        0x0ccd to 0x00b7, 0x0ccd to 0x00b8, 0x0ccd to 0x00b9, 0x0ccd to 0x00c0,
        0x0ccd to 0x00c6, 0x0ccd to 0x00d3, 0x0ccd to 0x00d7, 0x0ccd to 0x00e0,
        0x1554 to 0x5020,
        0x15f4 to 0x0131, 0x15f4 to 0x0133,
        0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680,
        0x1b80 to 0xd393, 0x1b80 to 0xd394, 0x1b80 to 0xd395, 0x1b80 to 0xd397,
        0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4, 0x1b80 to 0xd3a8,
        0x1b80 to 0xd3af, 0x1b80 to 0xd3b0,
        0x1d19 to 0x1101, 0x1d19 to 0x1102, 0x1d19 to 0x1103, 0x1d19 to 0x1104,
        0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803, 0x1f4d to 0xd286,
        0x1f4d to 0xd803
    )

    val HACKRF_IDS:Set<Pair<Int,Int>> = setOf(0x1d50 to 0x6089, 0x1d50 to 0x604b, 0x1d50 to 0xcc15)

    /** The Flipper Zero's console (ST's virtual COM port ID, which desktop KAT also goes by). */
    val FLIPPER_IDS:Set<Pair<Int,Int>> = setOf(FlipperUsb.VENDOR_ID to FlipperUsb.PRODUCT_ID)

    fun kindOf(vendorId:Int,productId:Int):RadioKind? = when {
        (vendorId to productId) in HACKRF_IDS -> RadioKind.HACKRF
        (vendorId to productId) in RTL_SDR_IDS -> RadioKind.RTLSDR
        (vendorId to productId) in FLIPPER_IDS -> RadioKind.FLIPPER
        else -> null
    }

    fun kindOf(d:UsbDevice):RadioKind? = kindOf(d.vendorId,d.productId)
    fun isHackRf(d:UsbDevice):Boolean = kindOf(d)==RadioKind.HACKRF
    fun isRtlSdr(d:UsbDevice):Boolean = kindOf(d)==RadioKind.RTLSDR
    fun isFlipper(d:UsbDevice):Boolean = kindOf(d)==RadioKind.FLIPPER
    fun supported(d:UsbDevice):Boolean = kindOf(d)!=null

    fun devices(manager:UsbManager):List<UsbDevice> =
        manager.deviceList.values.filter(::supported).sortedBy{it.deviceName}

    fun displayName(d:UsbDevice):String {
        val kind=kindOf(d)?.label?:"SDR"
        val product=runCatching{d.productName}.getOrNull()?.trim().orEmpty()
        return when {
            product.isBlank() -> kind
            // A Flipper names itself "Flipper <its name>".
            kindOf(d)==RadioKind.FLIPPER && product.startsWith("Flipper") -> product
            product.contains(kind,ignoreCase=true) -> product
            else -> "$kind · $product"
        }
    }

    fun open(manager:UsbManager,device:UsbDevice):SdrUsb = when(kindOf(device)) {
        RadioKind.HACKRF -> HackRfUsb(manager,device)
        RadioKind.RTLSDR -> RtlSdrUsb(manager,device)
        RadioKind.FLIPPER -> error("A Flipper Zero gives pulses, not IQ (RadioService receives with it through FlipperUsb)")
        null -> error("Unsupported USB SDR device")
    }
}
