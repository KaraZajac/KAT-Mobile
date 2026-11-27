package app.katmobile

import org.junit.Assert.*
import org.junit.Test

/** The Flipper Zero's USB console on the app's side: the serial settings it is opened with, and the bytes its reader thread hands KAT's core. */
class FlipperTest {
    @Test fun theConsoleIsOpenedAt115200EightNOne(){
        assertArrayEquals(byteArrayOf(0x00,0xC2.toByte(),0x01,0x00,0,0,8),FlipperUsb.lineCoding(115_200))
    }
    @Test fun theConsolesBytesArriveInOrderInAnyAmounts(){
        val q=ByteQueue()
        val first="Listening at".toByteArray();val second=" 433920000\r\n+350 -700 ".toByteArray()
        q.put(first,first.size);q.put(second,second.size)
        val out=StringBuilder();val buf=ByteArray(5)
        while(true){val n=q.read(buf,10);if(n<=0)break;out.append(String(buf,0,n))}
        assertEquals("Listening at 433920000\r\n+350 -700 ",out.toString())
        assertEquals("Nothing more yet: a timeout, not the end",0,q.read(buf,5))
    }
    @Test fun aReadWaitsForTheFirstByte(){
        val q=ByteQueue()
        val late=Thread{Thread.sleep(50);q.put(">: ".toByteArray(),3)}.apply{start()}
        val buf=ByteArray(16)
        assertEquals(3,q.read(buf,2000))
        assertEquals(">: ",String(buf,0,3))
        late.join()
    }
    @Test fun bytesQueuedBeforeTheConnectionWentAreStillRead(){
        val q=ByteQueue()
        q.put("abc".toByteArray(),3);q.close()
        val buf=ByteArray(8)
        assertEquals(3,q.read(buf,10))
        assertEquals("Then the end",-1,q.read(buf,10))
        assertEquals(-1,q.read(buf,10))
        q.put("late".toByteArray(),0)
        assertEquals(-1,q.read(buf,10))
    }
}
