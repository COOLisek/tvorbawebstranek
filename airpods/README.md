# AirPods Baterie

Android aplikace, která přes Bluetooth Low Energy zjistí stav baterie AirPods –
zvlášť levé sluchátko, pravé sluchátko a pouzdro, včetně toho, jestli se něco
z toho zrovna nabíjí a jestli má uživatel sluchátka v uších.

Stránka ke stažení: [`index.html`](index.html) (odkazuje se na ni tlačítko na vizitce).

## Jak to funguje

AirPods **nemají žádnou veřejnou GATT službu**, ze které by se dala baterie
přečíst – Apple ji nikdy nezveřejnil, takže klasické „připoj se a přečti
charakteristiku“ nefunguje.

Sluchátka ale neustále vysílají do okolí BLE inzerát (tzv. *proximity pairing*
zprávu), jehož první polovina není šifrovaná a stav baterie je v ní schovaný.
Aplikace se proto k ničemu nepřipojuje – jen pasivně poslouchá broadcast a
dekóduje ho.

Manufacturer data firmy Apple (company ID `0x004C`), 27 bajtů:

| bajt   | význam                                                                  |
|--------|-------------------------------------------------------------------------|
| 0      | `0x07` = typ zprávy *proximity pairing*                                  |
| 1      | `0x19` = délka zbytku (25 bajtů)                                         |
| 2      | stavový prefix                                                           |
| 3      | ID modelu (`0x0E` = AirPods Pro, `0x0A` = AirPods Max, …)                |
| 4      | `0x20`                                                                   |
| 5      | horní půlbajt = příznaky, dolní půlbajt = detekce v uchu                 |
| 6      | horní půlbajt = baterie prvního sluchátka, dolní = druhého               |
| 7      | horní půlbajt = příznaky nabíjení, dolní půlbajt = baterie pouzdra       |
| 8–26   | šifrovaná část, tu nepoužíváme                                           |

Baterie je půlbajt `0`–`10`, tedy násobky 10 %. Hodnota `15` (`0xF`) znamená
„neznámo“ – typicky sluchátko v zavřeném pouzdře nebo vypnuté.

Bit `0x02` v horním půlbajtu bajtu 5 říká, které sluchátko paket odeslalo.
Když je nulový, jsou hodnoty levého a pravého **prohozené** – proto ta logika
s `flipped` v [`AirPodsParser.kt`](app/src/main/java/cz/tvorbawebstranek/airpods/AirPodsParser.kt).

## Struktura projektu

```
airpods/
├── index.html                     stránka ke stažení APK
└── app/src/
    ├── main/java/…/airpods/
    │   ├── AirPodsParser.kt        dekodér BLE paketu (čistá logika, bez Androidu)
    │   ├── AirPodsStatus.kt        datové třídy
    │   ├── AirPodsScanner.kt       BLE skenování, oprávnění, filtrování
    │   └── MainActivity.kt         obrazovka s bateriemi
    ├── main/res/                   layouty, barvy, texty, ikona
    └── test/java/…/airpods/
        └── AirPodsParserTest.kt    testy dekodéru na ručně sestavených paketech
```

## Build

Otevři složku `airpods` v **Android Studiu** (Ladybug nebo novější) a dej Run.
Studio si samo dotáhne Gradle i Android SDK.

Z příkazové řádky (potřebuješ nainstalované Android SDK a proměnnou
`ANDROID_HOME`):

```bash
cd airpods
gradle wrapper            # jen poprvé, vyrobí ./gradlew
./gradlew test            # testy dekodéru, běží i bez telefonu
./gradlew assembleDebug   # APK v app/build/outputs/apk/debug/
```

APK pro web pak stačí zkopírovat sem do složky:

```bash
cp app/build/outputs/apk/debug/app-debug.apk AirPodsBaterie.apk
```

## Oprávnění

| Android          | oprávnění                                          |
|------------------|-----------------------------------------------------|
| 12 (API 31) a víc | `BLUETOOTH_SCAN` (s `neverForLocation`), `BLUETOOTH_CONNECT` |
| 11 (API 30) a míň | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` |

Do Androidu 11 vyžadoval systém k BLE skenování polohové oprávnění, protože se
z okolních zařízení dá odvodit poloha. Od Androidu 12 už stačí `BLUETOOTH_SCAN`
s příznakem `neverForLocation`, takže aplikace o polohu vůbec nežádá.

## Omezení

- **Sluchátka musí zrovna vysílat.** AirPods inzerát posílají jen chvíli po
  otevření víka pouzdra nebo když jsou v uších. Zavřené pouzdro v kapse mlčí.
- Apple používá **náhodné MAC adresy**, které se pravidelně mění, takže se
  konkrétní kus sluchátek nedá spolehlivě sledovat mezi jednotlivými pakety.
  Aplikace proto vždy ukáže ta nejbližší sluchátka podle síly signálu.
- Inzeráty slabší než −80 dBm se ignorují, ať se neukazují cizí sluchátka
  o dvě místnosti dál. Konstanta `MIN_RSSI` v `AirPodsScanner.kt`.
- Skenování běží jen když je aplikace na obrazovce – na pozadí by zbytečně
  ubíralo baterii. Kdyby bylo potřeba trvalé sledování (widget, notifikace),
  chtělo by to foreground service.
- Rozpoznávání modelu vychází z veřejně zdokumentovaných ID; u modelu, který
  v tabulce chybí, aplikace zobrazí jeho ID a baterie i tak dekóduje správně.
