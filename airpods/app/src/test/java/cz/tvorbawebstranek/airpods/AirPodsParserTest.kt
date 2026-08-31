package cz.tvorbawebstranek.airpods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy dekodéru na ručně sestavených paketech.
 * Paket má vždy 27 bajtů, zbytek za bajtem 7 je šifrovaný a nezajímá nás,
 * takže ho doplňujeme nulami.
 */
class AirPodsParserTest {

    private fun packet(header: String): ByteArray {
        val hex = header + "00".repeat(AirPodsParser.EXPECTED_LENGTH - header.length / 2)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun `AirPods Pro v usich`() {
        // bajt 5 = 0x2B: příznak 0x2 (neprohozeno), oba půlbajty detekce v uchu
        // bajt 6 = 0x43: 40 % a 30 %
        // bajt 7 = 0x8F: nic se nenabíjí, baterie pouzdra neznámá (0xF)
        val status = AirPodsParser.parse(packet("0719010e202b438f"), -55, 1000L)!!

        assertEquals("AirPods Pro", status.model)
        assertEquals(30, status.left.percent)
        assertEquals(40, status.right.percent)
        assertNull(status.case.percent)
        assertTrue(status.left.inEar)
        assertTrue(status.right.inEar)
        assertFalse(status.left.charging)
        assertFalse(status.case.charging)
        assertFalse(status.singleBattery)
        assertEquals(-55, status.rssi)
    }

    @Test
    fun `AirPods 2 v nabijecim pouzdre s prohozenymi hodnotami`() {
        // bajt 5 = 0x00: příznak bez bitu 0x02, takže levé a pravé je prohozené
        // bajt 7 = 0x75: nabíjí se sluchátka i pouzdro, pouzdro na 50 %
        val status = AirPodsParser.parse(packet("0719010f20005475"), -70, 2000L)!!

        assertEquals("AirPods (2. generace)", status.model)
        assertEquals(50, status.left.percent)
        assertEquals(40, status.right.percent)
        assertEquals(50, status.case.percent)
        assertTrue(status.left.charging)
        assertTrue(status.right.charging)
        assertTrue(status.case.charging)
        assertFalse(status.left.inEar)
    }

    @Test
    fun `AirPods Max maji jednu baterii`() {
        val status = AirPodsParser.parse(packet("0719010a20208f0f"), -60, 3000L)!!

        assertEquals("AirPods Max", status.model)
        assertTrue(status.singleBattery)
        // Hodnota přišla jen v jednom půlbajtu, druhý je 0xF = neznámo.
        assertNull(status.left.percent)
        assertEquals(80, status.right.percent)
    }

    @Test
    fun `neznamy model se popise ID`() {
        val status = AirPodsParser.parse(packet("071901aa20204400"), -60, 0L)!!
        assertEquals("Neznámé Apple sluchátko (0xAA)", status.model)
    }

    @Test
    fun `pulbajt 0xF znamena neznamo`() {
        val status = AirPodsParser.parse(packet("0719010e2020ff0f"), -60, 0L)!!
        assertNull(status.left.percent)
        assertNull(status.right.percent)
        assertNull(status.case.percent)
    }

    @Test
    fun `nulove nabiti je platna hodnota`() {
        val status = AirPodsParser.parse(packet("0719010e2020000f"), -60, 0L)!!
        assertEquals(0, status.left.percent)
        assertEquals(0, status.right.percent)
        assertNull(status.case.percent)
    }

    @Test
    fun `jina zprava nez proximity pairing se ignoruje`() {
        // 0x10 = Apple "nearby info", nemá s bateriemi nic společného
        assertNull(AirPodsParser.parse(packet("1005012000"), -60, 0L))
        assertFalse(AirPodsParser.isAirPodsAdvertisement(packet("1005012000")))
    }

    @Test
    fun `kratky paket se ignoruje`() {
        assertNull(AirPodsParser.parse(byteArrayOf(0x07, 0x19, 0x01), -60, 0L))
        assertNull(AirPodsParser.parse(null, -60, 0L))
    }
}
