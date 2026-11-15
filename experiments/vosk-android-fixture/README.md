# Isolierter Vosk-Android-Instrumentierungskandidat

Dieses eigenständige Testprojekt wird weder in KontraPrompter eingebunden noch
auf einem Gerät installiert. Sein Manifest fordert weder Mikrofon noch
Internet an. Der einzige Instrumentierungstest überprüft die Permissions des
Test- und Zielpakets erneut und führt eine Sekunde künstliche 48-kHz-Mono-PCM16-
Stille direkt durch `Recognizer.acceptWaveForm`. Er prüft JSON-Ausgabe und
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

Die Klasse `Recognizer` des geprüften AAR deklariert den nativen PCM-Rückgabewert
als `boolean`. Die [native Vosk-C-API](https://github.com/alphacep/vosk-api/blob/master/src/vosk_api.cc)
liefert bei einer Ausnahme dagegen `-1`. Wie dieser Wert durch das genaue
JNA-Binding auf Android ankommt, ist noch nicht auf dem Gerät geprüft; ein
verlässlicher Fehlerpfad und ein versionsgebundener negativer Fehlertest sind
vor einer Appintegration erforderlich. Der Stille-Smoke-Test beweist sie nicht.

Ein späterer Bau vom Hauptverzeichnis des Speech-WIP aus wäre beispielsweise
`./gradlew -p experiments/vosk-android-fixture :app:assembleDebug :app:assembleDebugAndroidTest`.
Ein solcher Build lädt gegebenenfalls Abhängigkeiten und muss bei knappen
Ressourcen abgestimmt werden. Der Instrumentierungslauf würde eine separate
Fixture-App und Test-APK auf einem geeigneten Testgerät installieren und ist
hier ausdrücklich **noch nicht ausgeführt oder freigegeben**. Eine vollständige
Analyse der aufgelösten Abhängigkeiten steht noch aus.

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

Falls die beiden separaten Fixture-APKs später auf einem geeigneten Testgerät
installiert werden, lautet der gezielte Aufruf (Geräteseriennummer einsetzen):

```sh
adb -s SERIAL shell am instrument -w -r -e class de.kontrakurs.voskfixture.OfflineVoskInstrumentedTest de.kontrakurs.voskfixture.test/androidx.test.runner.AndroidJUnitRunner
```

Dieser Aufruf setzt die gesonderte Installation voraus und wurde noch nicht
ausgeführt. Das Modell wird dabei in ein neu angelegtes privates Cacheverzeichnis
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
