# Crisis Mesh / Radio Officer

Eksperymentalna aplikacja Android/Wear OS do krótkich raportów kryzysowych: formularz → pakiet CM1 → przekazywanie między sąsiadami TCP lub BLE → deterministyczny opis. Repo zawiera też adapter lokalnego GoLLeM i benchmark ekstrakcji. **BLE działa telefon ↔ zegarek na fizycznym sprzęcie; trzywęzłowe przekazywanie sprawdzono dotąd na TCP, nie na radiu BLE.** To nie jest niezawodny system ratunkowy. Nie wysyła SOS do służb.

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

Moduł Kotlin + Compose, minSdk 26, target 35. Formularz, lokalna historia,
import/eksport CM1 i przekazywanie przez TCP lub BLE. `INTERNET` obsługuje TCP
i **nie ogranicza połączeń do LAN** — adres wpisany przez użytkownika może
wskazywać publiczny host. Brak wbudowanego endpointu chmurowego.
BLE prosi o uprawnienia dopiero przy skanowaniu, nasłuchu lub wysyłce:
„Urządzenia w pobliżu” na Androidzie 12+ (`SCAN`, `CONNECT`, `ADVERTISE`),
a na Androidzie 8–11 o lokalizację potrzebną systemowi do skanowania BLE.
Aplikacja nie pobiera pozycji GPS; na Androidzie 12+ skan ma `neverForLocation`.

Historia jest zapisywana w prywatnym `filesDir/reports.json`; przychodzące
poprawne raporty zapisują się automatycznie ze źródłem `mesh`. Ta etykieta
nie uwierzytelnia nadawcy. Notatka z formularza pozostaje lokalna.
Udostępnianie otwiera systemowy share sheet z samym tekstem CM1.
Zapis historii nie jest transakcyjny; sprawdzono przetrwanie zamknięcia
aplikacji po zapisie, nie awarii podczas zapisu.

### Co zweryfikowano (emulator `Wear5_LargeRound`, API 34, 2026-10-16)

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

### Multi-hop: `MeshNode` + `TcpMeshTransport`

W karcie mesh wpisz do 8 adresów `host:port`, oddzielonych przecinkiem,
i naciśnij **Zastosuj sąsiadów**. Pusta lista oznacza węzeł odbierający bez
przekazywania. Uruchom **Nasłuchuj :8765** na każdym odbiorcy/przekaźniku.
Przycisk **Wyślij** przy raporcie publikuje go do zastosowanej listy.
Edycja pola bez „Zastosuj” nie zmienia aktywnej konfiguracji.

Każda świadoma wysyłka tworzy nowy UUID. Odbiorca najpierw sprawdza kopertę
i kanoniczny CM1, potem blokuje powtórzone UUID, zapisuje raport i przekazuje
go wszystkim swoim sąsiadom, jeśli pozwala budżet przeskoków. Domyślny limit
to 4 połączenia od źródła; API dopuszcza 1–8. Odbiorca z budżetem 0 nadal
otrzymuje raport, ale już go nie przekazuje. Źródło pamięta UUID przed wysłaniem,
więc powrót przez cykl nie dopisuje mu własnego raportu.

**Protokół sieciowy — czyste przejście, bez zgodności ze starymi ramkami:**

| Element | Kodowanie |
|---|---|
| Długość ramki | 4 B, big-endian, limit transportu 1024 B |
| Wersja koperty | 4 B ASCII `CMF1` |
| Identyfikator wiadomości | 16 B UUID, most/least significant bits big-endian |
| Pozostałe przekazania | 1 B, 0–7; nadawca początkowy wpisuje `maxHops - 1` |
| Treść | kanoniczny CM1, bez zmian w kodeku |
| Odpowiedź na pełną ramkę | 1 B `0x06`, następnie nadawca zamyka połączenie |

Koperta dodaje 21 B do CM1. Przykład `CM1|12|2|flood|water`: 20 B treści,
41 B koperty, 45 B żądania z długością oraz 1 B odpowiedzi — bez narzutu TCP/IP.
Jedno połączenie przenosi jedną ramkę. Timeouty: 3 s łączenie, 10 s odczyt.
Odbiorca czeka na zamknięcie przez nadawcę po odpowiedzi. Natychmiastowe
zamykanie po wysłaniu dawało w tunelu ADB EOF i brak raportu albo brak
potwierdzenia; po zmianie kolejności zamykania opisany niżej scenariusz przeszedł.
Nie ustalono wewnętrznej przyczyny zachowania ADB.

`0x06` potwierdza **odbiór pełnej ramki**, również duplikatu lub koperty
odrzuconej przez wyższą warstwę. Nie potwierdza poprawności raportu, zapisu
na dysku ani dostarczenia przez kolejne węzły. UI mówi to wprost.

**Granice prototypu:**
- Deduplikacja obejmuje ostatnie 4096 UUID w pamięci procesu, nie trwałą
  historię. Restart/odtworzenie ekranu lub wyparcie UUID pozwala na ponowny odbiór.
- Ponowne naciśnięcie „Wyślij” tworzy nową wiadomość, nawet dla identycznej treści.
- Lista sąsiadów i nasłuch wymagają ponownego ustawienia po odtworzeniu aplikacji.
- TCP nie ma discovery. BLE wykrywa wyłącznie reklamujące usługę Crisis Mesh
  urządzenia; wybór i zastosowanie sąsiadów pozostają ręczne.
- Brak kolejki offline, automatycznego ponawiania, gwarancji dostarczenia,
  szyfrowania, uwierzytelniania i usługi działającej niezawodnie w tle.
- Nasłuch TCP jest na wszystkich interfejsach, a BLE przyjmuje nieuwierzytelnione
  zapisy GATT. Dostęp do łącza pozwala podawać fałszywe raporty, zapełniać
  historię lub blokować odbiór. Tylko zaufane urządzenia i środowisko testowe.
- BLE sprawdzono na dwóch urządzeniach. Nie sprawdzono trzech węzłów BLE,
  zasięgu, zużycia baterii, odporności na zakłócenia ani WiFi Aware.

### Dowody multi-hop

`testDebugUnitTest`: 22 testy bez błędów (7 `AppCodecTest`, 4
`TcpMeshTransportTest`, 6 `MeshNodeTest`, 5 `MeshFramesTest`). Testy routingu używają rzeczywistych
gniazd loopback: łańcuch bez bezpośredniego A–C, cykl, limit jednego przeskoku,
tożsamość UUID niezależna od treści, błędny payload bez zatrucia UUID,
nieznana wersja i niepoprawny budżet.

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

### BLE GATT — obsługa i dowody

Na obu urządzeniach wybierz **BLE** w karcie sieci. Przy pierwszym użyciu
zaakceptuj systemowy dostęp do urządzeń w pobliżu.

1. Na odbiorcy naciśnij **Nasłuchuj BLE**. Poczekaj na status reklamowania.
2. Na nadawcy naciśnij **Skanuj urządzenia** (skan trwa 10 sekund).
3. Wybierz wykryte urządzenie i naciśnij **Zastosuj sąsiadów**.
4. Przy raporcie w historii naciśnij **Wyślij**.
5. Dla komunikacji w obie strony powtórz konfigurację w odwrotnym kierunku.

Przełączenie TCP/BLE zamyka poprzedni transport i czyści pole sąsiadów.
Po odtworzeniu aplikacji trzeba ponownie wybrać transport i uruchomić nasłuch.
Adresy MAC w reklamach BLE mogą się zmieniać — po restarcie reklamowania
zeskanuj sąsiadów ponownie. Wykrycie usługi nie potwierdza tożsamości urządzenia.

`BleMeshTransport` przenosi tę samą kopertę CMF1 i 4-bajtową długość co TCP.
Serwer GATT reklamuje usługę `d9e0b811-484c-4a01-9ada-b8700e4182a1`;
charakterystyka zapisu to `d9e0b812-484c-4a01-9ada-b8700e4182a1`.
Nadawca wysyła kolejne fragmenty do 20 B, mieszczące się w obowiązkowym MTU 23,
czekając na odpowiedź ATT po każdym fragmencie. Brak negocjacji MTU.
Ostatnia odpowiedź potwierdza pełną ramkę, **nie zapis ani dostarczenie dalej**.
Ramki mają limit 1024 B; bufor odbiorcy jest ograniczony, zapisy prepare-write
i niezerowe offsety są odrzucane. Przekazywanie i deduplikacja pozostają w `MeshNode`.

**Końcowy smoke na Galaxy A22 5G i Galaxy Watch 4 (2026-10-19):**

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

Nie ma automatycznej diagnozy, triażu medycznego, wnioskowania o utracie przytomności ani samoczynnego alarmu z tętna. Oryginalne zgłoszenie trzeba zachować poza skrótem czterech pól. BLE telefon ↔ zegarek jest sprawdzone, ale radiowy multi-hop na trzech urządzeniach, praca w tle i LoRa nie są zweryfikowanymi funkcjami.
