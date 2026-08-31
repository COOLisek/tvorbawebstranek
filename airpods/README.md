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
    │   ├── AirPodsService.kt       foreground service s oznámením
    │   ├── BluetoothReceiver.kt    reakce na připojení a odpojení sluchátek
    │   ├── BluetoothAudio.kt       dotazy na klasické Bluetooth spojení
    │   ├── Prefs.kt                nastavení oznámení
    │   └── MainActivity.kt         obrazovka s bateriemi
    ├── main/res/                   layouty, barvy, texty, ikona
    └── test/java/…/airpods/
        └── AirPodsParserTest.kt    testy dekodéru na ručně sestavených paketech
```

## Build

APK se sestavuje **automaticky na GitHub Actions** – workflow
[`.github/workflows/airpods-apk.yml`](../.github/workflows/airpods-apk.yml)
při každé změně ve složce `airpods/` na větvi `main` pustí testy, sestaví APK
a nahraje ho do release s pevným tagem `airpods-latest`. Odkaz na stránce se
stažením proto míří pořád na stejnou adresu a ukazuje vždy aktuální build.
Workflow jde spustit i ručně (záložka Actions → Run workflow).

Lokálně stačí otevřít složku `airpods` v **Android Studiu** (Ladybug nebo
novější) a dát Run – Studio si samo dotáhne Gradle i Android SDK.

Z příkazové řádky (potřebuješ nainstalované Android SDK a proměnnou
`ANDROID_HOME`):

```bash
cd airpods
gradle wrapper            # jen poprvé, vyrobí ./gradlew
./gradlew test            # testy dekodéru, běží i bez telefonu
./gradlew assembleDebug   # APK v app/build/outputs/apk/debug/
```

APK je podepsané debug klíčem, takže se dá normálně nainstalovat do telefonu.
Release varianta by byla nepodepsaná a nešla by nainstalovat.

## Oznámení, dokud jsou sluchátka připojená

Aby se aplikace chovala jako AndroPods nebo AirBattery – tedy aby stav baterie
byl vidět pořád, ne jen když je aplikace otevřená – funguje to takhle:

1. `BluetoothReceiver` je zapsaný v manifestu a poslouchá broadcasty
   `ACL_CONNECTED` a `ACL_DISCONNECTED`. To jsou jedny z mála implicitních
   broadcastů, které smí přijímat i receiver z manifestu, takže aplikace nemusí
   kvůli tomu nic držet běžící.
2. Při připojení sluchátek (rozhodujeme podle třídy zařízení, ne podle jména –
   AirPods si jde přejmenovat) se spustí `AirPodsService`.
3. Ta běží jako foreground service typu `connectedDevice`, skenuje v režimu
   `SCAN_MODE_LOW_POWER` a stav baterie píše do trvalého oznámení.
   Bez foreground service by Android BLE skenování na pozadí utnul.
4. Při odpojení se služba zastaví a oznámení zmizí.

Dvě věci, které to umožňují:

- Bluetooth broadcast vyžadující `BLUETOOTH_CONNECT` je **výjimka z omezení
  Androidu 12+** na start foreground service z pozadí. Bez téhle výjimky by
  krok 2 skončil chybou.
- Oznámení drží **poslední známé hodnoty** i když sluchátka zmlknou. Kdyby se
  mazala, byla by po zavření pouzdra prázdná – místo toho je u nich napsané,
  jak jsou stará.

Pokud jsou sluchátka připojená už při otevření aplikace, broadcast dávno
proběhl a nikdo ho nezachytil – `MainActivity` proto stav spojení zjistí sama
přes profil A2DP a službu nastartuje.

Přepínačem na obrazovce jde oznámení vypnout (ukládá se do SharedPreferences).

## Oprávnění

| Android          | oprávnění                                          |
|------------------|-----------------------------------------------------|
| 12 (API 31) a víc | `BLUETOOTH_SCAN` (s `neverForLocation`), `BLUETOOTH_CONNECT` |
| 11 (API 30) a míň | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` |
| 13 (API 33) a víc | `POST_NOTIFICATIONS` – bez něj oznámení nejde zobrazit |
| 14 (API 34) a víc | `FOREGROUND_SERVICE_CONNECTED_DEVICE` |

Do Androidu 11 vyžadoval systém k BLE skenování polohové oprávnění, protože se
z okolních zařízení dá odvodit poloha. Od Androidu 12 už stačí `BLUETOOTH_SCAN`
s příznakem `neverForLocation`, takže aplikace o polohu vůbec nežádá.

## Omezení

- **Sluchátka musí zrovna vysílat.** AirPods inzerát posílají, když jsou
  připojená a v uších, nebo chvíli po otevření víka pouzdra. Zavřené pouzdro
  v kapse mlčí – oznámení pak drží poslední známé hodnoty a napíše, jak jsou
  staré.
- Apple používá **náhodné MAC adresy**, které se pravidelně mění, takže se
  konkrétní kus sluchátek nedá spolehlivě sledovat mezi jednotlivými pakety.
  Aplikace proto vždy ukáže ta nejbližší sluchátka podle síly signálu.
- Inzeráty slabší než −80 dBm se ignorují, ať se neukazují cizí sluchátka
  o dvě místnosti dál. Konstanta `MIN_RSSI` v `AirPodsScanner.kt`.
- Když je aplikace otevřená a zároveň běží služba, skenuje se dvakrát
  (obrazovka na plný výkon, služba v úsporném režimu). Androidu to nevadí,
  limit je pět skenů na aplikaci, ale je to trochu plýtvání.
- Rozpoznávání modelu vychází z veřejně zdokumentovaných ID; u modelu, který
  v tabulce chybí, aplikace zobrazí jeho ID a baterie i tak dekóduje správně.
