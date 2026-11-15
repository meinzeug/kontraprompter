# Isolierter Vosk-Android-Instrumentierungskandidat

Dieses eigenständige Testprojekt wird weder in KontraPrompter eingebunden noch
auf einem Gerät installiert. Sein Manifest fordert weder Mikrofon noch
Internet an. Der einzige Instrumentierungstest überprüft die Permissions des
Test- und Zielpakets erneut und führt künstliche 48-kHz-Mono-PCM16-Stille sowie
synthetisch gesprochene deutsche Wörter direkt durch `Recognizer.acceptWaveForm`. Er prüft JSON-Ausgabe und
schließt `Recognizer` vor `Model`; kein `SpeechService`, `AudioRecord`, echte
Aufnahme, Netzwerkzugriff oder Transkript wird verwendet. Dies belegt auch bei
späterem Bestehen weder Sprachverständlichkeit noch Laufzeit im Recorder.

Die expliziten AAR-Abhängigkeiten folgen der [offiziellen Demo](https://github.com/alphacep/vosk-android-demo/blob/master/app/build.gradle):
`com.alphacephei:vosk-android:0.3.75@aar` und `net.java.dev.jna:jna:5.18.1@aar`.
Die per HTTPS bezogenen Maven-Zentralartefakte hatten am 15.11.2026 lokal:

| Artefakt | Bytes | SHA-256 (lokal beobachtet) |
|---|---:|---|
| Vosk Android 0.3.75 AAR | 13.472.638 | `ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad` |
| JNA 5.18.1 AAR | 522.677 | `7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0` |

Beide enthalten `arm64-v8a`, `armeabi-v7a`, `x86` und `x86_64`; JNA enthält
zusätzlich ältere ABIs. Die ELF-LOAD-Alignmentwerte von Vosk sind 16.384 Bytes
bei `arm64-v8a`/`x86_64` und 4.096 Bytes bei `armeabi-v7a`/`x86`; das gesamte
fertige APK und ein Gerät mit 16-KiB-Seiten sind damit nicht geprüft. JNAs
hier geprüfte LOAD-Segmente zeigen 16.384 Bytes. Die Hashes sind selbst
ermittelte Vergleichswerte, keine unabhängig signierte Herstellerbestätigung;
vor einem Release sind Dependency-Verifikation, Lizenz- und Lieferkettenprüfung
erforderlich.

Der Test erwartet optional ein lokal bereitgestelltes Testasset
`app/src/androidTest/assets/vosk-model-small-de-0.15.zip`. Die Datei ist per
`.gitignore` ausgeschlossen und muss dem bereits im Speech-WIP lokal
geprüften Modellarchiv entsprechen (46.499.967 Bytes, SHA-256
`b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43`).
Der Test prüft ZIP-Hash und Größe vor dem Entpacken, begrenzt Einträge,
Gesamtgröße und Pfade und schreibt ausschließlich in ein neu angelegtes
privates Cacheverzeichnis der Fixture-App. Das ausgepackte Modell benötigt
rund 95,6 MB; dieses Testverzeichnis bleibt zunächst erhalten. Es ist keine
Speicherbereinigung oder Deinstallation beauftragt.

Der erweiterte Instrumentierungstest verarbeitet außerdem die kleine,
eingecheckte Datei `synthetic-de-48khz-s16le.pcm`: künstliche deutsche Sprache,
48 kHz, Mono, PCM16 little-endian, 556.732 Bytes (5,80 Sekunden), SHA-256
`cfcfe20034a34e79326187276cd8d5225284655239d82e24f65b6d0b5d9cc311`.
Der fest vorgegebene Text lautet „Hallo zusammen heute sprechen wir über Medien
und Videos.“. Die Datei wurde ohne Mikrofon oder Netz mit eSpeak NG 1.52.0
(`-v de -s 140 --stdout`) und FFmpeg 7.1.1 auf 48 kHz erzeugt, mit 0,5 Sekunden
Stille vor und 1 Sekunde nach dem Text. `python3 reproduce-synthetic.py`
vergleicht die unter denselben Werkzeugversionen neu erzeugten Bytes mit dem
Asset, ohne es zu verändern. CI prüft die Größe und den SHA-256-Wert sowohl
vor dem Build als auch in der Test-APK. Auf dem Entwicklungsrechner lieferte
das vorhandene Linux-Vosk 0.3.45 mit dem Modell genau die vorgegebene Wortfolge;
anschließend wurde die Datei mit dem Android-AAR auf beiden Geräten geprüft,
wie unten dokumentiert. Der exakte Solltexttest misst eine einzige synthetische Stimme und
beweist keine allgemeine Sprachqualität, Aufnahmeanbindung oder Echtzeitfähigkeit.

Die Klasse `Recognizer` des geprüften AAR deklariert den nativen PCM-Rückgabewert
als `boolean`. Die [native Vosk-C-API](https://github.com/alphacep/vosk-api/blob/master/src/vosk_api.cc)
liefert bei einer Ausnahme dagegen `-1`. Wie dieser Wert durch das genaue
JNA-Binding auf Android ankommt, ist noch nicht auf dem Gerät geprüft; ein
verlässlicher Fehlerpfad und ein versionsgebundener negativer Fehlertest sind
vor einer Appintegration erforderlich. Der Stille-Smoke-Test beweist sie nicht.

Der nächste ausschließlich testseitige Stand verwendet in `JnaVoskPcmEngine`
`Function.invokeInt` für das C-Symbol `vosk_recognizer_accept_waveform` mit dem
Pointer des Vosk-`Recognizer`. `BoundedPcmDecoder` behandelt `0` als laufend,
`1` als finales Segment und `-1` sowie alle anderen Werte als Analysefehler.
Der Eingang ist auf 1–16 Blöcke à höchstens 8192 Bytes, 48-kHz-Mono-PCM16 und
fortlaufende Samplepositionen begrenzt; bei Queueverlust, Lücke, falschem
Format, nativer Ausnahme oder ungültigem Ergebnis stoppt nur der Decoder. Das
Angebot wartet nicht auf den Queue-Lock. Native Aufrufe und Abschluss gehören
dem separaten Analyseworker und öffnen kein Mikrofon. Nach einem verlorenen
Block darf die Anwendung bereits ausgegebene Ergebnisse nur über einen noch zu
implementierenden atomaren Epochen-/Kanal-Fence anwenden. Der Decoder selbst
ist nicht mit `PcmTee`, Recorder, Netzwerk oder UI verbunden.

`run-core-tests.py` prüft die Statuswerte und Queuefehler auf dem Host mit
einer künstlichen nativen Engine; es ruft kein echtes Vosk auf. Die
Instrumentierung wurde auf das integerbasierte JNA-Binding umgestellt und
ist für Stille und das synthetische Sprachasset vorbereitet. Ein erneuter
Android-CI-Build und ein späterer Gerätetest müssen gesondert belegt werden;
das sichere Hervorrufen eines echten nativen `-1` ist noch nicht geklärt. Die
Fake-Engine beweist daher nicht die tatsächliche Android-JNA-Fehlersemantik.
Modellinitialisierung und Speichermangel können darüber hinaus den Prozess
beeinträchtigen; deren Isolierung und Belastungstests bleiben offen.

`SyntheticSpeechPipeline` bereitet einen weiteren isolierten Android-
Instrumentierungstest vor: künstliches, hashgeprüftes PCM wird in einen echten
AAC-Encoder eingereiht und danach durch den echten Tee, den begrenzten
integerbasierten Vosk-Decoder, den Wortcursor und den lokalen Positionsfence
geführt. Der Test vergleicht die PCM-Hashes vor/nach Tee, prüft AAC-Ausgabe,
den bekannten synthetischen Satz und die lokale Endposition. Die testseitigen
Kopien von Tee/Fence stammen unverändert aus Recorder-WIP `4748a80`, der Cursor
aus Speech-WIP `ef891d5`; `check-pipeline-sources.py` prüft ihre SHA-256-Werte.
Sie sind nicht mit der KontraPrompter-App verbunden. Es wird weder ein
`AudioRecord` geöffnet noch ein Mikrofon simuliert: Android bietet hier ohne
besondere Systemrechte keinen nachgewiesenen Weg, `AudioRecord` künstliches
PCM einzuspeisen. Echte Recorder-Timestamps, paralleles Mikrofon-/Kamera-/
Codecverhalten, AAC-Dekodierung, Echtzeit, Drop-/Seek-/Netzwerkrennen und
Produktionsfreigabe sind damit nicht bewiesen. Der Test verschickt weder Audio
noch Transkripte und funktioniert nur mit separat bereitgestelltem Modellasset.

Der isolierte [CI-Run 43678295966](https://github.com/meinzeug/kontraprompter/actions/runs/43678295966)
zum Quellcommit `0a15363` hat diesen Testkandidaten erfolgreich kompiliert,
die kopierten Quell-/Modell-/PCM-Hashes, Permissions und ABIs geprüft sowie
sechs Fake-Native-Hosttests ausgeführt. Das kurzlebige
[Artefakt 14147117699](https://github.com/meinzeug/kontraprompter/actions/runs/43678295966/artifacts/14147117699)
enthält die App-APK (41.759.012 Bytes, SHA-256
`721ac04559a1382c57fff5af25a9c408de0aae75d9b719641fa87d226cfa9e4b`)
und die Test-APK (47.007.205 Bytes, SHA-256
`16161d745a60eaeb1b3694f0ce69e745130121a1eb9a639f3c7c8d5b9bf918b6`).
Die neu zusammengestellte AAC-/Sprachpipeline wurde nicht auf Android
ausgeführt; ihr Build beweist keine erfolgreiche Instrumentierung.

Der isolierte [CI-Run 43675525092](https://github.com/meinzeug/kontraprompter/actions/runs/43675525092)
zum Quellcommit `3b94ab9` baut die Fixture mit integerbasiertem JNA-Aufruf und
prüft Modell, synthetisches PCM, beide APK-Manifeste und native ABIs. Alle
sechs Fake-Native-Hosttests bestanden. Das kurzlebige
[Artefakt 14146042913](https://github.com/meinzeug/kontraprompter/actions/runs/43675525092/artifacts/14146042913)
enthält die App-APK (40.926.499 Bytes, SHA-256
`8b9cdcdaea3070adcd31f61c112b7929094842a9076300e86e4bf2163d2a9d39`)
und Test-APK (47.748.405 Bytes, SHA-256
`d692f6d1f62e2ab39c995d87f342d5de2c34764bf279c4cf7fab4801a24b9c60`).
Die anschließenden isolierten Gerätetests dieses Kandidaten stehen unten.

Der integerbasierte Kandidat wurde anschließend isoliert auf beiden Geräten
ausgeführt. Mit den oben angegebenen CI-APKs, ohne Mikrofon- oder
Internetberechtigung und ausschließlich mit künstlicher Stille und dem
synthetischen deutschen Testsatz bestanden echte Android-/JNA-Decoderläufe:

- Samsung `R5CR60Q07YN`: 1 Instrumentierungstest, 9,595 Sekunden, erfolgreich.
- Xiaomi `HEHU4TQSINOJEQG6`: 1 Instrumentierungstest, 18,934 Sekunden, erfolgreich.

Die nur für den Test installierten separaten Fixture-Pakete wurden anschließend
entfernt. Die Tests bestätigen die Verarbeitung dieser künstlichen Signale über
das integerbasierte JNA-Binding; sie belegen keinen echten nativen `-1`-Fehler,
keine Verbindung zum Recorder oder eine funktionierende Live-Sprachfolge.

Ein späterer Bau vom Hauptverzeichnis des Speech-WIP aus wäre beispielsweise
`./gradlew -p experiments/vosk-android-fixture :app:assembleDebug :app:assembleDebugAndroidTest`.
Ein solcher Build lädt gegebenenfalls Abhängigkeiten und muss bei knappen
Ressourcen abgestimmt werden. Der Instrumentierungslauf würde eine separate
Fixture-App und Test-APK auf einem geeigneten Testgerät installieren. Die unten
dokumentierten Tests wurden isoliert ausgeführt; eine Produktionsfreigabe und
vollständige Analyse der aufgelösten Abhängigkeiten stehen noch aus.

Auf dem Speech-WIP baut der Workflow `.github/workflows/vosk-fixture.yml`
ausschließlich diese isolierte App und ihre Test-APK, ohne das Modell zu
kopieren oder einen Android-Instrumentierungstest zu starten. Das Prüfschema
`check-artifacts.py` vergleicht dabei die beiden AAR-SHA-256-Werte und prüft
die vier nativen ABI-Paare sowie die abwesenden Mikrofon-/Internetpermissions in
den APK-Manifests. Die fertigen APK-Dateien sind nur kurzlebige CI-Artefakte,
keine KontraPrompter-Releases.

Der separate Workflow `.github/workflows/vosk-model-fixture.yml` lädt das
Modell ausschließlich auf den isolierten CI-Runner, prüft vor dem Build Größe
und SHA-256 und prüft nach dem Build die exakten Modellbytes in der Test-APK.
Er baut dieselbe separate Fixture-App und eine Test-APK inklusive Modell; ein
Smartphone wird auch dabei nicht installiert oder gesteuert. Die CI-Prüfung
vergleicht zusätzlich die Paketnamen `de.kontrakurs.voskfixture` und
`de.kontrakurs.voskfixture.test` sowie den Runner
`androidx.test.runner.AndroidJUnitRunner`. KontraPrompter selbst ist ein anderes
Paket und wird von diesem Build nicht verändert.

Der CI-Run [43673921419](https://github.com/meinzeug/kontraprompter/actions/runs/43673921419)
zum Commit `4da020e` hat diese Prüfungen bestanden und das kurzlebige
[Artefakt 14145024674](https://github.com/meinzeug/kontraprompter/actions/runs/43673921419/artifacts/14145024674)
erzeugt. Die Fixture-App hat 40.926.499 Bytes und SHA-256
`b79e96bfa02a349c8bdfa28fb908dd6dacc2fac8730ee9bd5286451685a32a7e`;
die Test-APK **mit Modell** hat 47.446.756 Bytes und SHA-256
`71cdee7def8e2aedac2251d1e691d772528ddc7c7faed88db0adfd597723026e`.
Die exakten Modellbytes wurden vor dem Build und im Test-APK verglichen. Kein
Instrumentierungstest wurde ausgeführt und keine App auf einem Telefon
installiert; der bestehende KontraPrompter blieb unverändert.

Der erweiterte CI-Run [43674814191](https://github.com/meinzeug/kontraprompter/actions/runs/43674814191)
zum Quellcommit `eb82703` erzeugte das separate
[Artefakt 14146216148](https://github.com/meinzeug/kontraprompter/actions/runs/43674814191/artifacts/14146216148).
App-APK: 40.926.499 Bytes, SHA-256
`d927a0bc1f4835d0011f28867bdca413914c076a92b22de0fba59ff5be569aa6`.
Test-APK einschließlich geprüftem Modell und synthetischem Sprachasset:
47.742.005 Bytes, SHA-256
`b6d2cc61b5566cd7f263c06182430483355a7762a7cb558bfcbfdb4b78caffef`.
Beide Manifeste bleiben ohne Mikrofon- und Internetberechtigung. Die späteren
isolierten Gerätetests dieses erweiterten Fixtures stehen unten.

Falls die beiden separaten Fixture-APKs später auf einem geeigneten Testgerät
installiert werden, lautet der gezielte Aufruf (Geräteseriennummer einsetzen):

```sh
adb -s SERIAL shell am instrument -w -r -e class de.kontrakurs.voskfixture.OfflineVoskInstrumentedTest de.kontrakurs.voskfixture.test/androidx.test.runner.AndroidJUnitRunner
```

Dieser Aufruf setzt die gesonderte Installation voraus. Das Modell wird dabei in ein neu angelegtes privates Cacheverzeichnis
der Fixture-App entpackt; der Test entfernt es nicht eigenmächtig.

## Isolierter Gerätetest vom 15. November 2026

Mit den oben genannten CI-APKs (`b79e96bfa02a349c8bdfa28fb908dd6dacc2fac8730ee9bd5286451685a32a7e`
und `71cdee7def8e2aedac2251d1e691d772528ddc7c7faed88db0adfd597723026e`)
und dem darin anhand Größe und SHA-256 geprüften deutschen Modell lief die
Instrumentierung mit künstlicher Stille erfolgreich:

- Samsung `R5CR60Q07YN`: 1 Test, 7,548 Sekunden, erfolgreich.
- Xiaomi `HEHU4TQSINOJEQG6`: 1 Test, 6,546 Sekunden, erfolgreich.

Auf dem Xiaomi benötigte die Installation die ausdrückliche ADB-Bestätigung
des Systemdialogs; die zuvor bereits aktivierte Systemeinstellung blieb
unverändert. Die vor dem Test auf beiden Telefonen nicht vorhandenen separaten
Fixture-Pakete wurden anschließend samt ausschließlich ihrem eigenen Cache
entfernt. KontraPrompter und vorhandene Aufnahmen wurden nicht verändert; die
Wiederherstellung der Sitzung war danach noch im Gang.

Die geprüften APK-Manifeste fordern weder `RECORD_AUDIO` noch `INTERNET` an;
der Test speist ausschließlich künstliche Stille ein. Das Ergebnis belegt die
Modellinitialisierung und Verarbeitung dieses Testsignals auf beiden Geräten.
Es belegt kein Sprachverständnis, keine Recorder- oder Prompteranbindung, keine
Latenz unter Echtzeitlast und keinen sicheren Produktionsbetrieb.

## Isolierter Gerätetest mit synthetischem Deutsch

Die oben dokumentierten erweiterten CI-APKs mit den SHA-256-Werten
`d927a0bc1f4835d0011f28867bdca413914c076a92b22de0fba59ff5be569aa6` und
`b6d2cc61b5566cd7f263c06182430483355a7762a7cb558bfcbfdb4b78caffef`
wurden anschließend getrennt auf beiden Geräten getestet. Der eine jeweilige
Instrumentierungstest umfasst künstliche Stille und die exakte Erkennung des
fest vorgegebenen synthetischen deutschen Satzes:

- Samsung `R5CR60Q07YN`: erfolgreich, 1 Test in 9,568 Sekunden.
- Xiaomi `HEHU4TQSINOJEQG6`: erfolgreich, 1 Test in 14,782 Sekunden.

Die Xiaomi-Systemdialoge für beide Fixture-Pakete wurden gezielt bestätigt;
eine dauerhafte Systemeinstellung wurde nicht geändert. Anschließend wurden
beide separaten Fixture-Pakete samt ausschließlich eigenem Cache entfernt.
KontraPrompter läuft weiter; Kamera-Verbindung und Studio wurden bestätigt.
Dies ist kein Test einer echten Stimme, eines Live-Mikrofons, von
Decoderfehlern oder einer Echtzeit-Recorderintegration.

Der isolierte CI-Run 43673659245 zum Commit `e76fa04` war erfolgreich. Die
Fixture-App hat 40.926.499 Bytes, SHA-256
`fb6e0f0a2efca4d92a41f1873f2a3e91cee35f90d8f1bab0a6612088b7e1ef28`;
die Test-APK ohne Modell hat 1.657.331 Bytes, SHA-256
`31d8b6713b0129f06d7416c7d4558de8d77185b0c04620fbe72ad52b9fce387b`.
Beide enthalten laut Manifestprüfung weder `RECORD_AUDIO` noch `INTERNET`;
alle vier ABI-Paare sind in der Fixture-App vorhanden. Der Build ist kein
Gerätetest: Die Test-APK enthält absichtlich kein Modell und kann den
Instrumentierungstest so noch nicht erfolgreich ausführen. Für einen späteren
Test muss das geprüfte Modell vor einem erneuten Fixture-Build bereitgestellt
werden; die daraus entstehende APK hat einen anderen Hash.
