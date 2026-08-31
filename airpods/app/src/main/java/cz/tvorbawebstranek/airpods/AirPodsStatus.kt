package cz.tvorbawebstranek.airpods

/**
 * Stav jednoho sluchátka nebo pouzdra.
 *
 * @param percent nabití v procentech (0-100), nebo null pokud sluchátko
 *        neposlalo platnou hodnotu (je vypnuté, v pouzdře, mimo dosah...)
 * @param charging true pokud se právě nabíjí
 * @param inEar true pokud je sluchátko v uchu (pouzdro má vždy false)
 */
data class PodBattery(
    val percent: Int?,
    val charging: Boolean,
    val inEar: Boolean = false
) {
    val isKnown: Boolean get() = percent != null
}

/**
 * Kompletní stav AirPods vyčtený z jednoho BLE inzerátu.
 *
 * @param model rozpoznaný model (např. "AirPods Pro")
 * @param singleBattery true pro sluchátka s jedinou baterií (AirPods Max, Beats náhlavní),
 *        kde má smysl zobrazit jen jednu hodnotu
 * @param rssi síla signálu v dBm (čím blíž k nule, tím blíž zařízení je)
 * @param timestampMs čas přijetí paketu (SystemClock.elapsedRealtime nebo System.currentTimeMillis)
 */
data class AirPodsStatus(
    val model: String,
    val left: PodBattery,
    val right: PodBattery,
    val case: PodBattery,
    val singleBattery: Boolean,
    val rssi: Int,
    val timestampMs: Long
)
