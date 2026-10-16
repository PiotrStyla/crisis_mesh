# Crisis Mesh / Radio Officer

Eksperyment **codec-first** dla kryzysowej komunikacji offline: polskie zgłoszenie → propozycja pól lokalnego GoLLeM → przegląd przez człowieka → mały pakiet → deterministyczny opis. Repo zawiera działający kodek, adapter lokalnej inferencji i benchmark. **Nie jest to jeszcze aplikacja Android/Wear OS ani sieć mesh.** Nie wysyła SOS ani nie kontaktuje się ze służbami.

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

Moduł Kotlin + Compose, uprawnienie **wyłącznie `INTERNET`** (gniazda do
lokalnego transportu `MeshTransport`; żaden serwer zewnętrzny nie jest
wołany — brak lokalizacji, Bluetooth, reklam i chmury): ekran formularza,
lokalna historia, import/eksport pakietów CM1 oraz karta sieci lokalnej.
Działa na
telefonie i zegarku (minSdk 26, target 35). Dane trafiają wyłącznie do
prywatnego pliku aplikacji (`filesDir/reports.json`). Notatka z formularza jest
lokalna i **nie wchodzi w pakiet CM1**.

Udostępnianie otwiera wyłącznie systemowy wybór aplikacji (share sheet) z
tekstem pakietu. Jedyny aktywny kanał sieciowy to transport `MeshTransport`
(gniazda TCP w sieci lokalnej) opisany niżej — żaden adres zewnętrzny nie jest
wołany. Własnej warstwy mesh (multi-hop, odkrywanie węzłów) jeszcze nie ma.

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

### Transport: `MeshTransport` (TCP, sieć lokalna)

Szczelina (seam) między pakietami CM1 a warstwą bitową: interfejs
`listen/send/close`, adapter `TcpMeshTransport` — jedno połączenie TCP na
pakiet, ramka `4 B` długość (big-endian) + payload (limit 1024 B), timeouty
3 s (łączenie) / 10 s (odbiór), bez odkrywania węzłów i bez stanu między
pakietami. Odbiorca waliduje bajty przez ten sam `Codec.unpack` co import:
pakiet niekanoniczny nie trafia do historii.

Testy JVM (`TcpMeshTransportTest` + `test_codec`, razem 11 testów w
`./gradlew testDebugUnitTest`): dokładny roundtrip bajtów po pętli, błędny
adres, nieosiągalny rówieśnik, restart nasłuchu po `close()`.

Test dwu-urządzeniowy (emulatory, ten sam APK na obu):

```powershell
adb -s <wear> forward tcp:9001 tcp:8765    # host:9001 -> zegarek:8765
# zegarek: przycisk "Nasłuchuj (port 8765)"
# telefon: pole rozmówcy 10.0.2.2:9001, przy raporcie "Wyślij"
```

Ograniczenia, wprost: TCP to pierwszy adapter, **nie mesh** — brak multi-hop,
redundancji i odkrywania; `10.0.2.2` to pętla emulatora, nie adres w terenie.
Dla kryzysu docelowym łączem są BLE / WiFi Aware pod ten sam interfejs
(wymagają sprzętu — emulatory nie mają radiowy hardware). Prawdziwy mesh
multi-hop to osobny, przyszły etap.

Wyniki testu dwu-urządzeniowego (wear `Wear5_LargeRound` ↔ telefon `LLM_API`,
oba emulatory, 2026-10-17):

| Kierunek | Dowód |
|---|---|
| telefon → zegarek | telefon: `Wysłano 23 B → 10.0.2.2:9001`; zegarek: `Odebrano CM1\|9\|4\|collapse\|rescue`, `Historia (1)` → `(2)`, wpis źródła `mesh` |
| zegarek → telefon | zegarek: `Wysłano 23 B → 10.0.2.2:9002`; telefon: `Odebrano CM1\|9\|4\|collapse\|rescue`, `Historia (3)` → `(4)` |
| przechwycenie bajtów zegarka (host:9701) | dokładnie 27 B: `00000017` + `434d317c…726573637565` = `CM1\|9\|4\|collapse\|rescue` |
| ramka niepoprawna | walidacja po stronie odbiorcy przez `Codec.unpack` (bez appendu do historii) |

Obserwacja nie w pełni wyjaśniona: przy **pierwszym** połączeniu przez świeżo
utworzony `adb forward` odbiorca raz zanotował `Błąd odbioru: null` (EOF przed
danymi); kolejne wysyłki przez ten sam tunel i każde połączenie z hosta
docierały poprawnie — traktujemy to jak artefakt zestawiania tunelu emulatora,
nie jako błąd protokołu aplikacji `[INFERENCE]`. Brak deduplikacji: powtórzony
pakiet dopisuje kolejny wpis (zaobserwowane przy `(3)` → `(4)`).


Wymagania: JDK 17, Android SDK (platforma 35, build-tools), Gradle 8.10.2
(wrapper w repo; jeśli `gradlew` zgłosi brak `gradle-wrapper.jar`, uruchom
`gradle wrapper --gradle-version 8.10.2` albo otwórz projekt w Android Studio):

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-17.0.20.1+1"
.\gradlew.bat testDebugUnitTest assembleDebug
.\adb install -r app\build\outputs\apk\debug\app-debug.apk
```


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

Kompaktowy formularz i deterministyczny kodek są wykonalne. Warstwa modelowa wymaga dalszej pracy i oddzielnej walidacji: np. dostrojenia GoLLeM do ekstrakcji na osobnym zbiorze, z testami na danych niewidzianych. Sama gramatyka JSON może wymusić składnię, ale nie gwarantuje zgodnych faktów. Tego treningu ani testów radiowych nie wykonano w tym eksperymencie.

Nie ma automatycznej diagnozy, triażu medycznego, wnioskowania o utracie przytomności ani samoczynnego alarmu z tętna. Oryginalne zgłoszenie trzeba zachować poza skrótem czterech pól. Watch-first i transport BLE/LoRa pozostają kierunkiem produktu, nie deklarowaną funkcją tego repo.
