# Crisis Mesh / Radio Officer

Eksperymentalna aplikacja Android/Wear OS do krótkich raportów kryzysowych. **1.2.0:** automatyczne wykrywanie i przekazywanie BLE, lokalna tablica w przeglądarce oraz jawne przekazanie do skonfigurowanego kontaktu przez edytor SMS. Ręczną trasę trzech fizycznych węzłów BLE potwierdzono w 1.1.1; pełną automatyczną sieć 1.2 sprawdzono na emulatorach. Fizyczny A22 ma potwierdzoną migrację, odbiór duplikatu BLE z PC i tablicę HTTP przez LAN; próba wielu fizycznych węzłów 1.2 nadal pozostaje do wykonania. **To nie jest niezawodny system ratunkowy ani integracja ze służbami.** Repo zawiera też oddzielny eksperyment lokalnego GoLLeM, bez integracji z APK.

**[Tablica architektury](ARCHITECTURE.md)** — przepływ raportu, transport TCP/BLE,
moduły aplikacji oraz planowana rola lokalnego GoLLeM. Diagram renderuje się w GitHubie.

## Wynik eksperymentu

Uruchomiono rzeczywisty **GoLLeM v6 250M Instruct v3**, plik `gollem-v6-250m-instruct-v3-Q4_K_M-q8out.gguf` wskazany w konfiguracji istniejącego projektu zegarkowego `GoLLem_on_watch`. Nazwy organizacji i licencji tego konkretnego modelu nie wywodzimy z nazwy innych modeli SlayerLab. Wagi nie są dołączone.

| Pomiar | Wynik |
|---|---:|
| Ręcznie opisane polskie przypadki | 10 |
| Wyniki modelu zgodne ze schematem JSON | 0/10 |
| Dokładne dopasowanie wszystkich pól | 0/10 |
| Poprawne pola, błędne formaty liczone jako zero | 0/40 |
| Rozmiar wejściowych tekstów UTF-8 łącznie | 777 B |
| Pakiety z ręcznych etykiet, nie z modelu, łącznie | 213 B |

`benchmark-results.json` zawiera pełne surowe odpowiedzi, błędy, czasy obejmujące ładowanie, ustawienia oraz SHA-256 modelu, binarki i przypadków. Model odpowiadał prozą zamiast JSON. Wynik dotyczy tego promptu i tej konfiguracji, **nie dowodzi, że model nigdy nie nauczy się ekstrakcji**. Nie nadaje się obecnie do automatycznego tworzenia raportów kryzysowych.

213 B to rozmiar **stratnej projekcji czterech pól z ręcznych etykiet**, a nie bezstratna kompresja 777 B tekstu ani osiągnięcie modelu. Nie zachowuje miejsca, czasu, opisu obrażeń, negacji innych niż reprezentowane jako brak wiedzy ani innych szczegółów. Nie potwierdzono wcześniejszej hipotezy o 10-krotnej kompresji lub poprawie zasięgu/baterii.

Sprawdzone: 7 testów `unittest` (w tym podprzypadki graniczne), rzeczywisty CLI pack/unpack oraz odrzucenie niepoprawnego wyniku GoLLeM. Testowano na CPU Windows, **nie na fizycznym zegarku ani radiu**.

## Uruchomienie bez modelu

Python 3.11+, wyłącznie biblioteka standardowa:

```powershell
python -m unittest -v
python main.py pack --report example-report.json --output example.packet
python main.py unpack example.packet
```

Pakiet przykładu: `CM1|3|1|unknown|water` — 21 bajtów. Odbiorca odtwarza: 3 osoby łącznie, w tym 1 dziecko; zagrożenie nieustalone; potrzeba wody. Odbiorca **nie używa LLM**, więc nie może dopisać złamania, ciężkości stanu ani dodatkowych osób.

## Lokalna inferencja GoLLeM

Wymagany jest **patchowany llama.cpp z istniejącego projektu GoLLem_on_watch**, obsługujący ten GGUF. Zwykły upstream nie jest tu zweryfikowanym zamiennikiem. Budowa używa celu `llama-completion`, nie `llama-app`: scalony cel linkował nieistniejące biblioteki CLI/server przy wyłączonym serwerze.

Przykład PowerShell dla środowiska, w którym wykonano pomiar (dostosuj ścieżki na innym komputerze):

```powershell
$watch = "$HOME/Projects/GoLLem_on_watch"
$env:PATH = "C:/mingw64/bin;" + $env:PATH
$cmake = "$env:LOCALAPPDATA/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
$ninja = "$env:LOCALAPPDATA/Android/Sdk/cmake/3.22.1/bin/ninja.exe"

python build_host.py --source "$watch/vendor/llama.cpp" --build .build `
  --cmake $cmake --ninja $ninja `
  --cc C:/mingw64/bin/gcc.exe --cxx C:/mingw64/bin/g++.exe

$binary = "$PWD/.build/bin/llama-completion.exe"
$model = "$watch/models/bundled/gollem-v6-250m-instruct-v3-Q4_K_M-q8out.gguf"
python main.py benchmark --binary $binary --model $model --output benchmark-results.json
python main.py extract --binary $binary --model $model --text-file message.txt
```

`message.txt` musi być plikiem UTF-8 z własnym zgłoszeniem. `extract` wypisuje wyłącznie niezatwierdzoną propozycję JSON na stdout, ostrzeżenie/opis na stderr. Przy błędnym JSON kończy się kodem 1 i pokazuje odrzucony wynik. Nie istnieje awaryjne zgadywanie pól. Benchmark kończy się sukcesem wykonania także wtedy, gdy model ma 0 trafień — ocenę należy czytać w raporcie, nie z kodu wyjścia.

Build nie usuwa katalogów; można wskazać istniejący build. Podczas weryfikacji użyto istniejącego `GoLLem_on_watch/vendor/llama.cpp/build-host`; do nowej kompilacji powyższe polecenie używa lokalnego `.build`. MinGW musi być na PATH również podczas inferencji, ze względu na biblioteki runtime. Nie używaj `--log-disable`: w tej wersji llama-completion wyłącza również wypisywanie wygenerowanego tekstu.

## Aplikacja Android / Wear OS (`app/`)

Moduł Kotlin + Compose, minSdk 26, target 35. Domyślny transport to BLE;
TCP pozostaje diagnostyką z ręcznymi adresami. `INTERNET` obsługuje TCP i serwer
HTTP, nie ogranicza połączeń do LAN. Brak endpointu chmurowego.
BLE wymaga urządzeń w pobliżu na Androidzie 12+ albo systemowego dostępu do
lokalizacji na starszych wersjach. Hotspot wymaga `NEARBY_WIFI_DEVICES` na 13+,
a na 8–12 lokalizacji i odpowiednich ustawień systemu. Aplikacja nie pobiera GPS
i nie żąda `SEND_SMS`.

Historia, UUID i kolejka dostarczania są zapisane transakcyjnie w prywatnym
`databases/crisis.db`. Pierwsze uruchomienie wersji **1.1 lub nowszej / CMF2** importuje
`files/reports.json` dokładnie raz, zachowując identyfikatory, daty, pola i notatki.
Plik JSON pozostaje nietkniętą kopią migracyjną — nie jest już aktywną historią.
Błąd migracji wycofuje import i jest pokazany w UI; nie usuwa starego pliku.
Przychodzące raporty dostają źródło `mesh`, które **nie uwierzytelnia nadawcy**.
Notatka pozostaje lokalna, a share sheet dostaje tylko tekst CM1.

### Obsługa 1.2.0

1. Wybierz BLE i **Dołącz do sieci** na każdym uczestniczącym urządzeniu.
   Zatwierdź uprawnienia. Nasłuch i wykrywanie ruszają razem, bez wpisywania adresów.
2. Wypełnij formularz i naciśnij **Nadaj zgłoszenie SOS**. Raport, UUID i kolejka
   zapisują się atomowo, także bez sąsiada lub uprawnień; następnie można dołączyć.
   LLM nie uczestniczy w wysyłce. „Wyślij” w historii to nowa publikacja z nowym UUID.
3. **Odłącz od sieci** zatrzymuje radio, nie kasuje historii. Dołączenie jest
   pamiętane i przywracane w działającym procesie z uprawnieniami. Przełączenie
   na TCP zamyka BLE; aplikacja nie jest mostem między transportami.

**Bez instalacji:** uruchom tablicę w istniejącym LAN albo lokalny hotspot z tablicą.
Druga osoba łączy się z wyświetloną siecią, pozostaje w niej mimo braku internetu
i otwiera osiągalny adres `http://…:8080/`. Lista pochodzi z interfejsów urządzenia;
nie każdy adres należy do hotspotu. Brak stałego adresu bramy, captive portalu
i automatycznego powiadamiania cudzych telefonów. Przeglądarka nie przekazuje BLE.
Publiczna, nieszyfrowana tablica pokazuje do 100 ostatnich **opublikowanych lub
odebranych** raportów, bez szkiców, notatek i kontaktów. Wpisy są niezweryfikowane,
odświeżanie ręczne, POST odrzucany kodem 405. Wyłączenie zamyka HTTP i hotspot.

**Kontakty:** zapisz do 5 numerów i wybierz „Przekaż kontaktowi” przy raporcie.
Wybór odbiorcy otwiera edytor SMS z opisem, CM1 i identyfikatorem raportu, bez
prywatnej notatki. Człowiek osobno zatwierdza wysyłkę; może ona kosztować kilka
części SMS. Brak handlera jest błędem, nie sukcesem. To nie automatyczna bramka
ani integracja z 112. ACK węzła oznacza zapis u sąsiada, nie przeczytanie przez
człowieka ani wysłanie służb. Otwarcie edytora nie potwierdza wysłania SMS.
Brak samoczynnych syren, publicznych alarmów i dostępu do Alertu RCB.

Dowody: [wyniki 1.2](evidence/auto-mesh-smoke.json),
[tablica](evidence/auto-mesh-board.png),
[systemowy podgląd SMS](evidence/auto-mesh-sms-preview.png),
[Wear](evidence/auto-mesh-watch.png).

### Fizyczny Galaxy A22 — 1.2.0

Aktualizacja z 1.1.1 na Androidzie 13 zachowała dokładnie wszystkie 11 raportów
(identyfikatory, daty, źródła, pakiety i notatki) oraz 4 rekordy UUID i ich skróty.
Na telefonie przeszło 18 testów Android, na oddzielnych bazach testowych.
Komputer odebrał rzeczywistą reklamę BLE z identyfikatorem tego węzła i odtworzył
dawny testowy UUID `8ed1670e-0fd1-43de-9951-12ef716a5a18`: trzy potwierdzone
zapisy ATT, bez nowego SOS i bez zmiany historii.

Rzeczywista przeglądarka komputera odczytała dwa opublikowane raporty bezpośrednio
przez Wi-Fi LAN, bez przekierowania ADB. Zapis HTTP został odrzucony kodem 405.
Fizyczny hotspot został uruchomiony: `swlan0`, stan systemowy `LocalHotspotState`,
brak błędu i brak upstreamu internetowego. Serwer odpowiedział HTTP 200 na adresie
tego interfejsu; była to próba własna telefonu, nie połączenie zewnętrznego klienta.
W tej próbie `wlan0` przeszedł w stan DOWN, więc poprzedni adres LAN przestał być
właściwym adresem do testu hotspotu. Aktualny adres odczytano z systemu, nie założono
końcówki `.1`. Hasło pozostaje na ekranie telefonu i nie trafia do dowodów.
Komputer zachował własne połączenie Wi-Fi.

Na tym etapie nie testowano jeszcze automatycznego przekazywania między fizycznymi
Androidami ani zewnętrznego klienta hotspotu.
[Dowody tego etapu na A22](evidence/physical-a22-smoke.json).

### Dwa fizyczne telefony — automatyczne BLE 1.2.0

A51 został zaktualizowany z 1.1.1 do 1.2.0. Migracja bazy 1 → 2 zachowała
jedyny raport, wszystkie jego pola oraz istniejący UUID, skrót i powiązanie z raportem.
Telefon automatycznie wykrył A22, bez wpisywania adresu sąsiada.

Jedno kontrolowane nadanie istniejącego testowego pakietu `CM1|7|2|unknown|water`
otrzymało nowy UUID `4e57025f-d71a-4144-ad9d-cc3593d79c84`.
Kolejka A51 zapisała potwierdzenie od `node:abe7c9e372604a2c` przy pierwszej
próbie, bez błędu; historia A51 nadal zawiera jeden, niezmieniony raport.
To rzeczywista transmisja Android → Android, nie odtworzenie pakietu przez PC.
Dowodem jest potwierdzenie zapisu sąsiada według kontraktu CMF2; baza odbiorcy
nie została jeszcze ponownie odczytana bezpośrednio po tej transmisji.
Ta próba nie potwierdza trasy przez trzy fizyczne urządzenia ani późnego dołączenia.

**Hotspot: klient połączony, odczyt HTTP nadal nie działa.** A51 dołączył do
hotspotu A22, zaakceptował brak internetu i dostał adres `10.98.64.15/24`
z bramą `10.98.64.202`. Próba `/health` na porcie 8080 zatrzymała się w stanie
TCP `SYN-SENT` i zakończyła timeoutem; wyświetlenia tablicy w przeglądarce
zewnętrznego klienta nie potwierdzono. Przyczyna wymaga diagnozy po stronie A22,
który w tej chwili nie jest podłączony przez USB. Wcześniejszy własny HTTP 200
na A22 nie zastępuje tej próby.

Przywrócono oryginalny czas wygaszania A51 i usunięto tymczasowy zrzut UI.
Hasła hotspotu i prywatne notatki nie trafiły do repozytorium.
[Dowody dwóch fizycznych telefonów](evidence/physical-two-phone-smoke.json).



### Historyczna weryfikacja formularza (JSON/CMF1, emulator, 2026-10-16)

| Ścieżka | Wynik |
|---|---|
| Formularz: 3 osoby / 1 dziecko / nieznane zagrożenie / woda → zapis | baner `Zapisano · CM1\|3\|1\|unknown\|water · 21 B` — bajt w bajt z pakietem Python |
| Trwałość: `force-stop` + ponowne uruchomienie | historia nadal zawiera raport |
| Import poprawnego `CM1\|5\|2\|flood\|rescue` | podgląd: `Osoby łącznie: 5; w tym dzieci: 2… powódź… ratunek`, zapis → `Historia (2)` |
| Import niekanonicznego `CM1\|01\|0\|fire\|rescue` | `Pakiet odrzucony: Noncanonical packet` (bez zapisu) |
| Usuwanie z potwierdzeniem | `Anuluj` zostawia `Historia (2)`, `Usuń` → `Historia (1)` |
| Udostępnianie | intent `ACTION_SEND` otwiera handler systemu (na tym emulatorze Bluetooth OPP) |
| Testy JVM | `./gradlew testDebugUnitTest` — 7 testów kodu zgodnych z `test_codec.py` |

Zawartość udostępnianego `EXTRA_TEXT` jest odczytana z kodu, nie zaobserwowana
w drugiej aplikacji. Automatyzacja UI używa `contentDescription` (`save`,
`people-plus`, `choice-water`, `import-commit`, `delete`…).

### TCP — tryb diagnostyczny

Wybierz **TCP · test**, wpisz do 8 adresów `host:port` i naciśnij
**Zastosuj adresy TCP**. Odbiorca uruchamia **Nasłuchuj TCP :8765**.
Publikacja trafia do trwałej kolejki także bez sąsiada. Zastosowanie listy
przypisuje wiadomości bez celu; nie zmienia celów już zaadresowanych
oczekujących dostarczeń. Ta ścieżka nie wykrywa urządzeń automatycznie.

Świadoma wysyłka tworzy nowy UUID; automatyczne ponowienia zachowują ten sam
UUID i bajty. Odbiorca waliduje CMF2/CM1 i w jednej transakcji zapisuje raport,
trwałą deduplikację i ewentualną kolejkę dalszego przekazania. Dopiero po COMMIT
odpowiada potwierdzeniem. Duplikat tej samej treści jest potwierdzany bez nowego
wpisu, także po restarcie i po usunięciu raportu z aktywnej historii.
Ten sam UUID z inną treścią jest odrzucany. Domyślny limit to 4 połączenia od
źródła (1–8); przy `hopsLeft = 0` odbiorca zapisuje, ale nie przekazuje dalej.

**Protokół CMF2 wymaga co najmniej wersji 1.1 na obu urządzeniach. Nie współpracuje z CMF1.**

| Element | Kodowanie |
|---|---|
| Długość ramki | 4 B, big-endian, limit transportu 1024 B |
| Wersja koperty | 4 B ASCII `CMF2` |
| Identyfikator wiadomości | 16 B UUID, most/least significant bits big-endian |
| Pozostałe przekazania | 1 B, 0–7; nadawca początkowy wpisuje `maxHops - 1` |
| Treść | kanoniczny CM1, bez zmian w kodeku |
| Potwierdzenie TCP | 1 B `0x07` po COMMIT / rozpoznaniu trwałego duplikatu |

Koperta dodaje 21 B do CM1. Przykład `CM1|12|2|flood|water`: 20 B treści,
41 B koperty, 45 B żądania z długością oraz 1 B odpowiedzi — bez narzutu TCP/IP.
Jedno połączenie przenosi jedną ramkę. Timeouty: 3 s łączenie, 10 s odczyt.
Odbiorca czeka na zamknięcie przez nadawcę po odpowiedzi. Natychmiastowe
zamykanie po wysłaniu dawało w tunelu ADB EOF i brak raportu albo brak
potwierdzenia; po zmianie kolejności zamykania opisany niżej scenariusz przeszedł.
Nie ustalono wewnętrznej przyczyny zachowania ADB.

`0x06` było starym potwierdzeniem samej ramki i **nie zwalnia nowej kolejki**.
`0x07` oznacza przyjęcie przez konkretnego sąsiada, a nie dostarczenie do
wszystkich dalszych węzłów. Nie uwierzytelnia odbiorcy i nie potwierdza faktów.

**Granice prototypu:**
- UUID i skróty SHA-256 treści są trwałe w SQLite, bez limitu ostatnich 4096 wpisów.
  To stan deduplikacji, nie podpis ani mechanizm bezpieczeństwa.
- Ręczne „Wyślij” nadal tworzy nową wiadomość. Ponowienie kolejki nie zmienia UUID.
- Wybór TCP/BLE, listy sąsiadów i żądanie nasłuchu są zapamiętywane. Nasłuch
  wznawia się po uruchomieniu aplikacji, jeśli są wymagane uprawnienia.
- TCP nie ma discovery. BLE wykrywa wyłącznie reklamujące usługę Crisis Mesh
  urządzenia; wybór i zastosowanie sąsiadów pozostają ręczne.
- Ponowienia działają tylko w żywym procesie aplikacji, co około 20 sekund dla
  kolejnej oczekującej próby; po sukcesie kolejka przechodzi dalej bez tej zwłoki.
  Nie ma gwarantowanej pracy w tle, szyfrowania, uwierzytelniania ani gwarancji dostarczenia.
- Nasłuch TCP jest na wszystkich interfejsach, a BLE przyjmuje nieuwierzytelnione
  zapisy GATT. Dostęp do łącza pozwala podawać fałszywe raporty, zapełniać
  historię lub blokować odbiór. Tylko zaufane urządzenia i środowisko testowe.
- BLE sprawdzono na trzech fizycznych urządzeniach: zegarek → A22 → A51,
  a powrót tego samego UUID z A51 do A22 nie dodał duplikatu.
  Nie sprawdzono zasięgu, zużycia baterii, odporności na zakłócenia ani WiFi Aware.

### Testy bieżącej wersji

17 testów JVM: 7 `AppCodecTest`, 5 `TcpMeshTransportTest`, 5 `MeshFramesTest`.
12 testów instrumentacyjnych na fizycznym telefonie: 6 `ReportStoreTest` i 6
`MeshNodeTest`. Testy routingu używają prawdziwych gniazd loopback i SQLite.
Obejmują migrację, rollback, trwałą kolejkę i UUID, odrzucenie konfliktu treści,
trzywęzłowe przekazywanie, pętle oraz brak potwierdzenia przy błędzie zapisu.

### Historyczne dowody multi-hop (CMF1)

Smoke na APK: host A → emulator Wear B → emulator telefonu C, przez `adb forward`.
Host wysyła wyłącznie na port B; B ma sąsiada C. Nie jest to test radia.

| Scenariusz | Zaobserwowany wynik |
|---|---|
| `CM1\|12\|2\|flood\|water` przez A → B → C | historia zegarka 6→7, telefonu 5→6; zgodne cztery pola w obu prywatnych plikach |
| Ponowne przesłanie tego samego UUID | oba liczniki bez zmiany |
| Nowy UUID z budżetem 0 | zegarek 7→8, telefon pozostaje 6 |
| Niekanoniczny `CM1\|01\|0\|fire\|rescue` | odrzucony, bez zapisu i przekazania |
| Wysyłka przyciskiem na telefonie, ze zwrotnym połączeniem zegarek → telefon | zegarek 8→9, telefon pozostaje 6; oba pokazują potwierdzenie ramki |

Konfiguracja użyta w próbie (obydwa urządzenia z nowym APK):

```powershell
adb -s emulator-5554 forward tcp:9001 tcp:8765 # host -> zegarek
adb -s emulator-5556 forward tcp:9002 tcp:8765 # host -> telefon
# Zegarek: sąsiad 10.0.2.2:9002, Zastosuj, Nasłuchuj.
# Telefon: początkowo brak sąsiadów, Nasłuchuj.
# Próba cyklu: telefon dostaje sąsiada 10.0.2.2:9001, Zastosuj, Wyślij.
```

`10.0.2.2` jest aliasem hosta w emulatorze, nie adresem do użycia w terenie.
Wspólna sieć fizyczna wymaga osiągalnych adresów urządzeń i dostępnego portu.

### Weryfikacja na fizycznych urządzeniach (2026-10-19)

Te same dwa APK co w testach emulatorowych, na sprzęcie:

| Rola | Urządzenie | System | Adres w LAN |
|---|---|---|---|
| Telefon | Samsung Galaxy A22 5G (`SM-A226B`) | Android | `192.168.0.125` |
| Zegarek | Samsung Galaxy Watch 4 (`SM-R865F`), ekran 396×396 | Android 14; numer Wear OS nieodczytany | `192.168.0.156` |
| Komputer | Windows (sterowanie i odczyt stanu) | — | `192.168.0.179` |

**Telefon — trzy łączniki, ten sam protokół.** W każdym przypadku sprawdzono
obie strony, blokowanie duplikatu tego samego UUID oraz zgodność czterech pól
z `Codec.unpack`:

| Łącznik | Komputer → telefon | Telefon → komputer | Dowód |
|---|---|---|---|
| USB (`adb forward` / `adb reverse`) | historia 0→1 | przechwycona ramka zgodna | trwałość po `force-stop` + ponownym uruchomieniu |
| Wi-Fi LAN (router) | historia 1→2 | źródło `192.168.0.118` → PC | bez tuneli ADB w ścieżce danych |
| Hotspot telefonu (bez domowego routera) | historia 2→3 | źródło `10.175.88.226` → PC | SSID `SmartSwitch-133`, PC dostał `10.175.88.36` |

**Zegarek i mesh telefon ↔ zegarek** (oba urządzenia w jednej sieci Wi-Fi,
dane szły wyłącznie po IP — ADB służyło tylko do sterowania ekranem i odczytu
`files/reports.json`):

| Scenariusz | Zaobserwowany wynik |
|---|---|
| Instalacja APK na zegarku przez Wi-Fi (`adb pair` + `adb connect`) | `Success`, aplikacja uruchomiona na `SM-R865F` |
| Komputer → zegarek, `CM1\|5\|1\|unknown\|water` | historia zegarka 0→1, cztery pola zgodne, źródło `mesh` |
| Ponowne przesłanie tego samego UUID na zegarek | historia bez zmian |
| **Zegarek → telefon**, przyciskiem „Wyślij” | historia telefonu 4→5 |
| **Telefon → zegarek**, przyciskiem „Wyślij” | historia zegarka 1→2 |

W obu kierunkach telefon ↔ zegarek zapisany wpis to `CM1|5|1|unknown|water`
(5 osób, 1 dziecko, zagrożenie nieustalone, potrzeba woda), źródło `mesh`.

Zrzuty ekranu potwierdzające odbiór:
[`evidence/phone-received-from-watch.png`](evidence/phone-received-from-watch.png),
[`evidence/watch-received-from-phone.png`](evidence/watch-received-from-phone.png).

**Problemy napotkane przy teście — istotne dla wdrożenia:**

1. Zegarek nie mógł połączyć się z nasłuchem na komputerze (`failed to connect`),
   choć lokalne połączenie na komputerze działało. Windows raportował profil
   sieci „Public” i włączoną zaporę. **Zapora była hipotezą**, nie potwierdzoną
   przyczyną — nie wykonano porównania z regułą zezwalającą. Próba z telefonem
   jako odbiorcą przeszła; nie dowodzi to, że telefon nie ma filtrowania sieci.
2. W trakcie automatyzacji zegarek wracał do tarczy, a połączenia ADB zrywały
   się. Tymczasowe wydłużenie czasu ekranu i pozostawienie go na ładowarce
   pozwoliło zakończyć próbę. **Nie ustalono, że każde wygaszenie wyłącza Wi-Fi
   lub unieważnia kod parowania.** Ustawienia ekranu przywrócono po teście.
3. Zegarek wskazywał marzec 2025. Aplikacja używa czasu urządzenia do opisu
   wpisów, więc brak synchronizacji daje nieprawidłowe daty; historia wyświetla
   wpisy w kolejności dodania, nie sortuje po tych datach.

### BLE GATT — automatyczna sieć 1.2.0

**Dołącz do sieci** uruchamia skan na 10 s, z kolejnymi początkami co 40 s;
reklamowanie pozostaje aktywne. Zapisany lokalnie losowy identyfikator 64-bitowy
jest ogłaszany w danych usługi w odpowiedzi skanowania. Kolejka adresuje
`node:<identyfikator>`, a skaner zachowuje aktualny obiekt `BluetoothDevice`.
Zmiana MAC tego samego węzła nie tworzy nowego odbiorcy ani UUID wiadomości.
To identyfikator, **nie kryptograficznie uwierzytelniona tożsamość**.

Pamięć endpointów i lista sąsiadów mają limit 64. Endpoint jest uznawany za
osiągalny przez 120 s od wykrycia; nieosiągalne cele nie blokują wyboru osiągalnego.
Nie obiecujemy dotarcia do dowolnej liczby urządzeń ani wszystkich telefonów w okolicy.

SQLite v2 zachowuje ramkę także po ACK, aby nowo spotkany węzeł mógł dostać ten sam
UUID i budżet bez nowej publikacji. Retencja to 24 h od pierwszego lokalnego zapisu,
zależne od zegara urządzenia — **nie globalny TTL koperty CMF2**.
Wygaśnięcie usuwa oczekujące dostarczenia i ramkę, nie historię ani deduplikację.
Usunięcie raportu także anuluje jego przyszłe przekazywanie na tym urządzeniu.
Migracja v1 odzyskuje tylko oczekujące ramki, z retencją od migracji; nie odtwarza
dawnych zakończonych publikacji z historii.

Wszystkie węzły automatycznej sieci wymagają 1.2.0. CMF2 GATT pozostaje zgodne
bajtowo, ale reklamy 1.1.x nie zawierają nowego identyfikatora i nie uczestniczą
w automatycznym wykrywaniu. Starsze dowody poniżej dotyczą ręcznej konfiguracji.

`BleMeshTransport` przenosi CMF2 i tę samą długość co TCP.
Usługa GATT to `d9e0b821-484c-4a01-9ada-b8700e4182a1`,
a charakterystyka zapisu to `d9e0b822-484c-4a01-9ada-b8700e4182a1`.
Nadawca wysyła kolejne fragmenty do 20 B, mieszczące się w obowiązkowym MTU 23,
czekając na odpowiedź ATT po każdym fragmencie. Brak negocjacji MTU.
Ostatnia odpowiedź ATT następuje po COMMIT odbiorcy; nie potwierdza dostarczenia dalej.
Ramki mają limit 1024 B; bufor odbiorcy jest ograniczony, zapisy prepare-write
i niezerowe offsety są odrzucane. Przekazywanie i deduplikacja pozostają w `MeshNode`.

**Historyczny smoke CMF1 / commit `3392278`, przed trwałą kolejką (2026-10-19):**

| Scenariusz | Telefon | Zegarek |
|---|---|---|
| Telefon → zegarek, przy wyłączonym Wi-Fi telefonu | historia 7→7 | historia 4→5 |
| Zegarek → telefon, nadal bez Wi-Fi telefonu | historia 7→8 | historia 5→5 |
| Force-stop i ponowne uruchomienie po zapisach | 8 wpisów zachowanych | 5 wpisów zachowanych |

Obie strony miały skonfigurowanego sąsiada, więc powrót przez pętlę nie dopisał
nadawcy własnej wiadomości. Każdy świadomy przycisk „Wyślij” tworzył nowy UUID.
Odczytano rzeczywiste `files/reports.json`: w obu nowych wpisach źródło `mesh`
i pola `5 / 1 / unknown / water`. Nie wnioskowano o zapisie z samego ACK.

Telefon był sterowany przez USB, zegarek przez ADB po Wi-Fi. Wi-Fi telefonu
było wyłączone, jego `wlan0` raportował `state DOWN`; danych komórkowych nie
wyłączano. Raporty szły przez GATT, bez tunelu ADB i bez TCP. To potwierdza
niezależność tej wymiany od Wi-Fi/IP, nie pracę urządzeń w trybie samolotowym
ani ciągłą pracę w tle. Po próbie przywrócono Wi-Fi telefonu i ustawienia
ekranu, a nasłuchy BLE zamknięto przez ponowne uruchomienie aplikacji.

Dowody: [telefon](evidence/ble-phone.png),
[status zegarka](evidence/ble-watch.png),
[pakiet na zegarku](evidence/ble-watch-report.png),
[wyniki i SHA-256 APK](evidence/ble-smoke.json).

### Historyczny test trwałej kolejki — 1.1 / CMF2

Migracja na sprzęcie zachowała wszystkie pola i kolejność **8 raportów telefonu
i 5 zegarka**. Stare pliki JSON są nadal niezmienione. Następnie dodano jeden
raport testowy: `CM1|7|2|unknown|water`.

| Scenariusz | Wynik |
|---|---|
| TCP: odbiorca wyłączony → kolejka → restart nadawcy → powrót odbiorcy | Ten sam UUID; zegarek 5→6 raportów, potwierdzone dostarczenie u nadawcy. |
| Ten sam UUID po restarcie odbiorcy | ACK `0x07`, historia nadal 6. |
| UUID z inną treścią / stara koperta CMF1 | Brak potwierdzenia zapisu i brak nowego raportu. |
| BLE: odbiorca wyłączony → błąd GATT → restart nadawcy | Oczekująca ramka, UUID, wybór BLE i sąsiad zachowane. |
| BLE: powrót odbiorcy z innym MAC → jawne ponowienie do nowego adresu | Ten sam UUID; zegarek 6→7 raportów, telefon nadal 9. Wi-Fi telefonu było wyłączone. |
| Powrót wiadomości przez skonfigurowanego sąsiada | Trwała deduplikacja; bez ponownego wpisu u źródła. |

Sprawdzono raporty i stany kolejki przez odczyt rzeczywistej bazy SQLite,
nie wywnioskowano ich z samego statusu wysyłki.
Dowody: [wyniki](evidence/durable-smoke.json),
[kolejka telefonu](evidence/durable-phone-queue.png),
[kolejka zegarka](evidence/durable-watch-queue.png).

**Ograniczenia i obsługa:**
- Po force-stop, wyłączeniu lub ubiciu procesu nic nie ponawia wysyłek do czasu
  ponownego uruchomienia aplikacji. To nie jest jeszcze usługa pracy w tle.
- Licznik kolejki liczy dostarczenia do sąsiadów, nie unikalne zgłoszenia.
  Wpis „bez sąsiadów” czeka na zastosowanie listy.
- Usunięcie raportu anuluje powiązane oczekujące dostarczenia. Nie cofa już
  dostarczonych kopii i nie usuwa zachowanej kopii migracyjnej JSON.
  Już rozpoczętego połączenia nie da się w ten sposób odwołać.
- Znaczniki deduplikacji pozostają po usunięciu raportu, aby duplikat nie
  odtworzył go w historii. Nie ma automatycznego czyszczenia tych znaczników.
- Testy restartów i błędów transakcji nie są testem nagłego zaniku zasilania
  podczas fizycznego zapisu do pamięci urządzenia.

### Trzy fizyczne węzły BLE — 1.1.1

Jedna publikacja `CM1|7|2|unknown|water`, UUID
`8ed1670e-0fd1-43de-9951-12ef716a5a18`, przeszła trasę
**Galaxy Watch4 → Galaxy A22 5G → Galaxy A51**. Zegarek miał tylko A22
jako sąsiada — nie miał bezpośredniej trasy do A51. Potwierdzenia obu połączeń
oraz raport i ten sam UUID odczytano z rzeczywistych baz SQLite.

| Urządzenie | Rola | Raporty przed | Raporty po | Oczekujące po | Budżet wychodzącej koperty |
|---|---|---:|---:|---:|---:|
| Galaxy Watch4 | źródło, ponowne wysłanie istniejącego raportu | 7 | 7 | 0 | 3 |
| Galaxy A22 5G | przekaźnik | 9 | 10 | 0 | 2 |
| Galaxy A51 | odbiorca, następnie przekazanie z powrotem do A22 | 0 | 1 | 0 | 1 |

UUID i dokładne bajty oczekującej ramki przetrwały aktualizację zegarka.
Pierwsze przejście odbyło się z wersją 1.1.1 na źródle i przekaźniku oraz 1.1
na A51. Aktualizacja A51 do 1.1.1 zachowała odebrany raport i ramkę dalszego
przekazania. Po lokalnym skanie na A51 i zastosowaniu A22 wiadomość wróciła
z tym samym UUID: potwierdzona kolejka A51, nadal 10 raportów na A22 i 1 na A51.
Końcowo ta sama wersja 1.1.1 została zainstalowana na wszystkich trzech urządzeniach.

Próba po aktualizacji obejmowała także restart odbiorcy i ponowne lokalne
wyszukanie sąsiadów; nie odizolowano jednego czynnika wcześniejszych timeoutów.
Wersja 1.1.1 zachowuje urządzenia ze skanu, ale nie rozwiązuje stałej tożsamości
ani rotacji adresów. Wi-Fi pozostało włączone do sterowania przez ADB:
to test transmisji **BLE**, nie test z wyłączonymi wszystkimi innymi radiami.
Komputer nie wysyłał charakterystyk GATT z komunikatami mesh.

Dowody: [ramki, UUID, potwierdzenia i wyniki](evidence/three-node-ble-smoke.json),
[A22 po przekazaniu](evidence/three-node-a22-relay.png),
[A51 po powrocie tego samego UUID](evidence/three-node-a51-final.png).




### Budowa

Wymagania: JDK 17, Android SDK (platforma 35, build-tools), Gradle 8.10.2
(wrapper w repo; jeśli `gradlew` zgłosi brak `gradle-wrapper.jar`, uruchom
`gradle wrapper --gradle-version 8.10.2` albo otwórz projekt w Android Studio):

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
# Jeśli PATH zawiera osadzone cudzysłowy, usuń je tylko w tej sesji:
# $env:PATH = $env:PATH.Replace('"', '')
.\gradlew.bat testDebugUnitTest assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
```
Testy SQLite i routingu na wybranym fizycznym telefonie (nie czyszczą danych użytkownika):

```powershell
.\gradlew.bat assembleDebugAndroidTest
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s <serial> install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s <serial> shell am instrument -w dev.crisismesh.test/androidx.test.runner.AndroidJUnitRunner
```



## Format CM1

ASCII, bez końcowego znaku nowej linii:

```text
CM1|people|children|hazard|need
```

- `people`: **łączna** liczba osób, 0–254 lub `?`.
- `children`: dzieci wliczone w `people`, 0–254 lub `?`; nie może przewyższać znanej liczby osób.
- `hazard`: `unknown`, `fire`, `flood`, `collapse`.
- `need`: `unknown`, `rescue`, `medical`, `water`.
- `?` nie oznacza zera. `unknown` nie oznacza, że jest bezpiecznie. Zaprzeczone, niepewne i niereprezentowalne kategorie pozostają nieustalone.
- Maksimum tego schematu to 28 B; dekoder odrzuca wejścia powyżej 80 B, obce wersje, dodatkowe pola, niekanoniczne liczby, niespójne wartości i nie-ASCII.
- JSON wejściowy ma dokładnie cztery pola. Duplikaty kluczy, booleany jako liczby i proza dołączona do JSON są odrzucane.

To **payload aplikacji**, nie pełny pakiet radiowy. Nie zawiera nagłówków transportu, identyfikatora, czasu, lokalizacji, podpisu, szyfrowania, ACK ani TTL. Jego poprawny format nie oznacza poprawności faktów ani potwierdzonego źródła. Przed użyciem operacyjnym potrzebne są zatwierdzenie człowieka i system dostarczania ze sprawdzoną integralnością oraz uwierzytelnianiem.

## Co wynika z tego dla projektu

Kompaktowy formularz i deterministyczny kodek działają. Warstwa modelowa wymaga dalszej pracy i oddzielnej walidacji: np. dostrojenia GoLLeM do ekstrakcji na osobnym zbiorze, z testami na danych niewidzianych. Sama gramatyka JSON może wymusić składnię, ale nie gwarantuje zgodnych faktów. Model nie jest dołączony ani uruchamiany w aplikacji Android.

Nie ma automatycznej diagnozy, triażu medycznego, wnioskowania o utracie przytomności ani samoczynnego alarmu z tętna. CM1 nie zachowuje lokalizacji, czasu zdarzenia ani pełnej treści zgłoszenia. Ręczny multi-hop BLE 1.1.1 potwierdzono na fizycznym sprzęcie; automatyczna sieć 1.2.0 ma osobne dowody emulowane. Praca w tle, zasięg, bateria, fizyczny dostęp do nowego hotspotu i LoRa nie są zweryfikowanymi gwarancjami ratunkowymi.
