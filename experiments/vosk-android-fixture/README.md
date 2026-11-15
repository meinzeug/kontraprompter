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
hier ausdrücklich **noch nicht ausgeführt oder freigegeben**. Auch der Build
und eine vollständige Analyse der aufgelösten Abhängigkeiten stehen noch aus.

Auf dem Speech-WIP baut der Workflow `.github/workflows/vosk-fixture.yml`
ausschließlich diese isolierte App und ihre Test-APK, ohne das Modell zu
kopieren oder einen Android-Instrumentierungstest zu starten. Das Prüfschema
`check-artifacts.py` vergleicht dabei die beiden AAR-SHA-256-Werte und prüft
die vier nativen ABI-Paare sowie die abwesenden Mikrofon-/Internetpermissions in
den APK-Manifests. Die fertigen APK-Dateien sind nur kurzlebige CI-Artefakte,
keine KontraPrompter-Releases.
