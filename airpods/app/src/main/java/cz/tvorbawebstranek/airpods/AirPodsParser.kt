package cz.tvorbawebstranek.airpods

/**
 * Dekodér Apple "proximity pairing" BLE inzerátu, kterým AirPods vysílají stav baterie.
 *
 * AirPods se nedají na Androidu vyčíst přes GATT službu – Apple žádnou veřejnou nemá.
 * Místo toho sluchátka (a otevřené pouzdro) vysílají nešifrovanou část
 * BLE advertisementu s manufacturer daty firmy Apple (company ID 0x004C).
 * Ta stačí jen pasivně odposlechnout, k ničemu se není potřeba připojovat.
 *
 * Formát manufacturer dat (27 bajtů):
 *
 *   bajt 0     : 0x07  = typ zprávy "proximity pairing"
 *   bajt 1     : 0x19  = délka zbytku (25 bajtů)
 *   bajt 2     : stavový prefix
 *   bajt 3     : ID modelu (0x0E = AirPods Pro, 0x0A = AirPods Max, ...)
 *   bajt 4     : 0x20
 *   bajt 5     : horní půlbajt = příznaky (mj. které sluchátko je "primární"),
 *                dolní půlbajt = detekce v uchu
 *   bajt 6     : horní půlbajt = baterie prvního sluchátka,
 *                dolní půlbajt = baterie druhého sluchátka
 *   bajt 7     : horní půlbajt = příznaky nabíjení,
 *                dolní půlbajt = baterie pouzdra
 *   bajty 8-26 : šifrovaná část (nepoužíváme)
 *
 * Hodnota baterie je půlbajt 0-10 (tj. násobky 10 %), hodnota 15 (0xF) znamená
 * "neznámo" – typicky sluchátko v pouzdře nebo vypnuté.
 *
 * Které sluchátko je levé a které pravé se přehazuje podle bitu 0x20 v bajtu 5
 * (AirPods hlásí baterii z pohledu právě aktivního sluchátka).
 */
object AirPodsParser {

    /** Company ID firmy Apple v BLE manufacturer datech. */
    const val APPLE_COMPANY_ID = 0x004C

    /** Typ zprávy "proximity pairing" – první bajt manufacturer dat. */
    const val PROXIMITY_PAIRING_TYPE = 0x07.toByte()

    /** Očekávaná délka manufacturer dat u proximity pairing zprávy. */
    const val EXPECTED_LENGTH = 27

    /** Půlbajt, kterým sluchátko říká "nevím / nejsem k dispozici". */
    private const val UNKNOWN_NIBBLE = 15

    /**
     * Rozpozná model podle bajtu 3. Seznam vychází z veřejně zdokumentovaných
     * ID, která Apple používá v inzerátu; neznámé ID vrací obecný popis.
     */
    private val MODELS = mapOf(
        0x02 to "AirPods (1. generace)",
        0x03 to "Powerbeats 3",
        0x05 to "BeatsX",
        0x06 to "Beats Solo 3",
        0x07 to "Beats Studio 3",
        0x09 to "Beats Solo Pro",
        0x0A to "AirPods Max",
        0x0B to "Powerbeats Pro",
        0x0C to "Beats Solo Pro",
        0x0D to "Powerbeats 4",
        0x0E to "AirPods Pro",
        0x0F to "AirPods (2. generace)",
        0x10 to "Beats Flex",
        0x11 to "Beats Studio Buds",
        0x13 to "AirPods (3. generace)",
        0x14 to "AirPods Pro (2. generace)",
        0x17 to "Beats Fit Pro",
        0x19 to "AirPods (4. generace)",
        0x1A to "AirPods Pro (2. generace, USB-C)",
        0x1B to "Beats Studio Buds+",
        0x1E to "AirPods (4. generace, ANC)",
        0x1F to "AirPods Max (USB-C)",
        0x20 to "Beats Solo 4",
        0x24 to "AirPods Pro (2. generace, USB-C)"
    )

    /** Modely s jedinou baterií – nemá smysl u nich zobrazovat levé/pravé/pouzdro. */
    private val SINGLE_BATTERY_MODELS = setOf(0x0A, 0x1F, 0x06, 0x07, 0x09, 0x0C, 0x20)

    /**
     * Zkontroluje, jestli manufacturer data vypadají jako proximity pairing zpráva.
     */
    fun isAirPodsAdvertisement(data: ByteArray?): Boolean =
        data != null && data.size == EXPECTED_LENGTH && data[0] == PROXIMITY_PAIRING_TYPE

    /**
     * Vyčte stav baterie z manufacturer dat Apple (company ID 0x004C).
     *
     * @param data manufacturer data bez dvoubajtového company ID
     * @param rssi síla signálu z [android.bluetooth.le.ScanResult]
     * @param timestampMs čas přijetí
     * @return dekódovaný stav, nebo null pokud paket není proximity pairing zpráva
     */
    fun parse(data: ByteArray?, rssi: Int, timestampMs: Long): AirPodsStatus? {
        if (!isAirPodsAdvertisement(data)) return null
        requireNotNull(data)

        val modelId = data[3].toInt() and 0xFF
        val flags = highNibble(data[5])
        val inEarBits = lowNibble(data[5])
        val chargingBits = highNibble(data[7])

        // Bit 0x02 v horním půlbajtu bajtu 5 říká, které sluchátko paket odeslalo.
        // Když je nulový, jsou hodnoty levého a pravého prohozené.
        val flipped = (flags and 0x02) == 0

        val firstNibble = highNibble(data[6])
        val secondNibble = lowNibble(data[6])

        val leftNibble = if (flipped) firstNibble else secondNibble
        val rightNibble = if (flipped) secondNibble else firstNibble

        val leftCharging = if (flipped) (chargingBits and 0b0010) != 0 else (chargingBits and 0b0001) != 0
        val rightCharging = if (flipped) (chargingBits and 0b0001) != 0 else (chargingBits and 0b0010) != 0
        val caseCharging = (chargingBits and 0b0100) != 0

        val leftInEar = if (flipped) (inEarBits and 0b1000) != 0 else (inEarBits and 0b0010) != 0
        val rightInEar = if (flipped) (inEarBits and 0b0010) != 0 else (inEarBits and 0b1000) != 0

        return AirPodsStatus(
            model = MODELS[modelId] ?: "Neznámé Apple sluchátko (0x%02X)".format(modelId),
            left = PodBattery(nibbleToPercent(leftNibble), leftCharging, leftInEar),
            right = PodBattery(nibbleToPercent(rightNibble), rightCharging, rightInEar),
            case = PodBattery(nibbleToPercent(lowNibble(data[7])), caseCharging),
            singleBattery = modelId in SINGLE_BATTERY_MODELS,
            rssi = rssi,
            timestampMs = timestampMs
        )
    }

    /** Půlbajt 0-10 na procenta; 15 (a jiné neplatné hodnoty) na null. */
    private fun nibbleToPercent(nibble: Int): Int? =
        if (nibble in 0..10) nibble * 10 else null

    private fun highNibble(b: Byte): Int = (b.toInt() shr 4) and 0x0F

    private fun lowNibble(b: Byte): Int = b.toInt() and 0x0F
}
