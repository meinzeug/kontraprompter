# Sprachfolge: synthetische Test-Fixture

Dieser isolierte WIP verbindet nur im Testquelltext den vorhandenen PCM-Tee,
Wortabgleich und Cursor mit künstlichen Erkennungsergebnissen und einer
simulierten Positionszustellung. Er aktiviert weder Mikrofon noch Recorder,
Netzwerk, Telefone oder einen echten Spracherkenner. Das Testskript lautet
`bash scripts/test-speech-pcm-fixture.sh`; es nutzt lokal vorhandene Kotlin-/
JUnit-Abhängigkeiten und nur temporäre JVM-Klassen.

Die Tests untersuchen finale und verspätete Ergebnisse, manuelles Neusetzen,
Pause, eine erkannte Lücke im Samplezähler, gesättigte Analysewarteschlangen
und falsche oder wiederholte Positionsidentitäten. Die Fixture enthält keine
Stimmerkennung oder Stilleklassifikation; fehlende Samples sind keine Prüfung
echter Stille. Der Testempfänger ist kein authentisierter Transport. Auch die
Recorder-Unabhängigkeit wird nur an unveränderten Testbytes und begrenztem
Tee-Verhalten untersucht, nicht am Android-Audio-/Codec-Lifecycle.

Vor Integration fehlen u. a. ein geprüfter Offline-Erkenner mit eigenem
PCM-Eingang, echte Sprach- und Latenztests, ein verbindlich synchronisiertes
Skript auf beiden Geräten, verschlüsselte Sitzungstransporte bzw. sichere
Positionsbindung, Geräte-/Recorder-Tests, Unterbrechungs- und Datenschutz-
Verhalten. Der bestehende manuelle Prompter bleibt unverändert.

## Reproduzierbarer Offline-Erkenner-Probe

`bash scripts/test-vosk-offline-fixture.sh` verwendet ausschließlich einen
von `espeak-ng` neu synthetisierten deutschen Satz. `ffmpeg` erzeugt daraus
48-kHz-Mono-PCM; ein eigenständiger JVM-Prozess reicht die Bytes durch den
echten `PcmTee`. Der Vosk-C-Erkenner liest die resultierenden Bytes aus stdin;
SHA-256 und Bytezahl werden mit dem ursprünglichen PCM verglichen. Das
synthetische Transkript wird an den echten `SpokenScriptCursor` übergeben.
Die Fixture öffnet kein Mikrofon und baut selbst keine Netzwerkverbindung auf;
es werden keine Benutzerdaten verwendet. Eine Betriebssystem-Netzwerksperre
oder unabhängige Prüfung sämtlicher nativer Bibliotheksaufrufe ist noch nicht
erfolgt. Das synthetische Transkript erscheint in der Konsole.

Der einmalige getrennte Download umfasst Vosk 0.3.45 (Linux-Wheel, 7,2 MB,
Apache-2.0) und `vosk-model-small-de-0.15` (46.499.967 Bytes komprimiert,
95.621.761 Bytes entpackt, Apache-2.0). Quelle und Lizenz laut
[Vosk-Modellliste](https://alphacephei.com/vosk/models) und
[PyPI](https://pypi.org/project/vosk/0.3.45/). Das Wheel wird anhand des
PyPI-SHA-256 `25e025093c4399d7278f543568ed8cc5460ac3a4bf48c23673ace1e25d26619f`
geprüft. Der Modell-Download aus der offiziellen HTTPS-Quelle ergab für diese
Fixture SHA-256 `b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43`;
dies ist ein lokal fixierter Reproduzierbarkeitswert, kein gesondert
verifiziertes signiertes Hersteller-Manifest. Die Artefakte bleiben unter dem
ignorierten `.gradle/speech-fixture/` und werden nicht committed oder in die
APK übernommen. Vosk nennt für kleine Modelle typischerweise ca. 300 MB RAM.

Einmalige Vorbereitung auf Linux x86-64 im Testworktree (Internetzugriff nur
für diese Downloads):

```sh
mkdir -p .gradle/speech-fixture/wheels
curl --fail --location --output .gradle/speech-fixture/vosk-model-small-de-0.15.zip https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip
python3 -m pip download --no-deps --only-binary=:all: --dest .gradle/speech-fixture/wheels vosk==0.3.45
bash scripts/test-vosk-offline-fixture.sh
```

Für den letzten Befehl werden lokal `espeak-ng`, `ffmpeg`, ein JDK und die im
separaten JVM-Fixture-Skript genannten Kotlin-Jars benötigt. Archivpfade und
Größe werden vor Entpacken begrenzt; eine vorhandene unmarkierte Modellkopie
wird nicht ersetzt.

Am 15. November 2026 wurden 206.366 synthetische Samples ohne Tee-Verlust und
mit identischem PCM-Hash erkannt: „hallo zusammen heute sprechen wir über
medien und videos“. Der Cursor erreichte Wortposition 9. Dieser Einzeltest
belegt weder echte Stimmen, Erkennungsqualität, Echtzeitlatenz unter Last noch
Android-ABI, Audioaufnahme, Plattformintegration oder Zwei-Geräte-Steuerung.

## Probe mit vorhandener Sprachaufnahme

Am 15. November wurde zusätzlich die zuvor bei einem Sprechtest erstellte,
83,33 Sekunden lange Serveraufnahme über SSH gelesen und ihr Ton zur lokalen
Offline-Probe in 48-kHz-Mono-PCM umgewandelt. Die Aufnahme blieb unverändert;
das Testtranskript wurde nur im Speicher verarbeitet und nicht ausgegeben oder
gespeichert. Die Pegelprüfung der Aufnahme ergab -32,3 dB mittleren und
-2,9 dB maximalen Pegel. Vosk meldete für 83,3 Sekunden PCM 69 erkannte
Wörter. Ohne Abgleich mit einem Referenztranskript sagt die Anzahl nichts über
die Erkennungsgenauigkeit oder die Sprachverständlichkeit aus.

Dies ist ein Offline-Test auf dem Entwicklungsrechner mit bereits
abgeschlossenem Audiomaterial. Er belegt weder die Echtzeitlatenz noch eine
Integration in Android, den Aufnahmeprozess, die sichere Übertragung der
Position oder eine funktionierende automatische Sprachfolge.

In einer weiteren rein lokalen Auswertung wurde das Erkennungsergebnis
anschließend mit dem zum Aufnahmeprojekt aktuell gespeicherten Skript
verglichen. Von 69 erkannten Wörtern stimmten 45 in Reihenfolge exakt mit dem
Skript überein; die längste exakte Folge hatte neun Wörter. Auch dabei wurde das
Transkript weder ausgegeben noch gespeichert. Da weder die Skriptfassung zum
Aufnahmezeitpunkt noch der tatsächlich gesprochene Wortlaut unabhängig
verifiziert wurden, ist dies kein Genauigkeitsmaß und kein Beleg für eine
zuverlässige Positionssteuerung.
