# KontraPrompter

Native Android-App für einen Teleprompter mit zwei Geräten. **Entwicklungsstand: experimentelle Live-Vorschau, noch nicht auf zwei realen Geräten geprüft.** Videoaufnahme, Ton, Aufnahmefernsteuerung und das gleichzeitige Aufnehmen/Streamen sind nicht implementiert. Die Definition of Done ist nicht erfüllt; diese APK nicht für wichtige Aufnahmen verwenden.

## Implementiert

- Deutsche Compose-Oberfläche und Offline-Skripte; mehrere Skripte werden im privaten App-Speicher gehalten. TXT/MD-Import über den Android-Dateiauswahldialog, Bearbeitung einschließlich Einfügen über die Systemtastatur.
- Prompter mit zeitbasiertem Scrollen, manuellem Verschieben, Start/Pause, Anfang und Countdown. Tempo in dp/s. Schriftgröße, Zeilenabstand, Breite, Hintergrund, Displayhelligkeit und Leselinie einstellbar. Text horizontal/vertikal spiegelbar und in 90°-Schritten drehbar; Kalibrierbild mit Richtungsangaben.
- Experimenteller lokaler Verbindungsweg: Kameragerät bietet einen temporären Dienst an; Android NSD sucht diesen, alternativ werden numerische IP und Port manuell eingegeben. Ein zufälliger 128-Bit-Code ist höchstens fünf Minuten gültig und wird bei erfolgreicher Authentifizierung verbraucht. Beide Geräte müssen die Verbindung bestätigen. Es werden keine Accounts oder externen Signaling-Server verwendet.
- Authentifiziertes TCP-Signaling mit beidseitiger Challenge-Response, HMAC-SHA-256 und Sequenznummern. WebRTC mit Host-ICE-Kandidaten, ohne STUN/TURN, für die Videovorschau; WebRTC schützt Videodaten mittels DTLS-SRTP. Das Signaling selbst ist authentifiziert, aber nicht verschlüsselt: Netzwerkteilnehmer können beispielsweise IP-Adressen und SDP-Inhalt beobachten. Der Sitzungscode wird nicht übertragen oder protokolliert.
- Kamera-Vorschau nur während der App im Vordergrund ist. Camera2 wird bevorzugt, Camera1 dient als Fallback; der gewählte Stream startet mit 640×480 bei 20 fps als Anfrage an den Capture-Backend. Die tatsächliche Geräteunterstützung, Codec-Auswahl und Bildrate sind ungeprüft. H.264 wird bevorzugt, sofern WebRTC es anbietet; WebRTC darf auch einen anderen Codec aushandeln.
- Empfangenes Video liegt unter dem lokalen Text. Text und Video sind unabhängig horizontal/vertikal spiegelbar und drehbar; Fit/Fill, Deckkraft und Abdunklung der Videoebene sind einstellbar. Die angezeigte RTT misst den Signalisierungskanal, **nicht** die Bildlatenz.

## Verbindung testen, sobald Geräte verfügbar sind

1. Gleiche APK auf beiden Geräten installieren; beide Geräte im selben WLAN ohne Client-Isolation verbinden. Internet ist für die App-Nutzung nicht erforderlich.
2. Auf A **Kamera / Vorschau** wählen und die Kameraerlaubnis erteilen. IP, Port und Sitzungscode stehen auf dem Bildschirm.
3. Auf B **Teleprompter / Anzeige → Kamera verbinden** wählen. Gefundenes Gerät auswählen oder IP und Port von A manuell eingeben. Sitzungscode eingeben und verbinden.
4. Den angezeigten Gerätenamen prüfen und die Kopplung **auf beiden Geräten bestätigen**. Auf B **Skripte und Prompter** öffnen und einen Text starten. Auf A sollte die Vorschau und auf B das Videobild hinter dem Text erscheinen.
5. In den Prompter-Einstellungen Text und Video einzeln spiegeln. Fit zeigt das gesamte Bild, Fill beschneidet es gegebenenfalls. Die sinnvolle Spiegelrichtung ist am eigenen Glas zu kalibrieren.

Diese Schritte sind implementiert, aber derzeit **nicht auf zwei realen Geräten validiert**. Bei Abbruch die Sitzung auf A neu starten und auf B erneut mit dem neuen Code koppeln. Die App beendet die Live-Sitzung beim Verlassen des Vordergrunds; ein automatisches Wiederverbinden, Hintergrundbetrieb und ein Foreground Service fehlen. Die Navigation zu einem externen Dateiauswahldialog kann die Sitzung ebenfalls beenden.

## Noch offen

M3/M4 einschließlich MP4/Audio, Fernsteuerung und ACKs, gleichzeitiger Aufnahme und Stream oder geräteabhängiger Fallbacks, Aufnahme-Foreground-Service, Hotspot, automatischem Reconnect und vollständigem Hardware-/Thermomanagement. Außerdem fehlen QR-Code, Video-Kalibrierbild, Presets, weitere Textoptionen, Skriptexport, End-to-End-Bildlatenzmessung, Screenshots und viele der gewünschten automatisierten Tests. Die App wurde noch nicht auf echter Android-Hardware validiert. Es werden keine Frames oder Skripte auf einen Server hochgeladen; beide Geräte kommunizieren unmittelbar im gewählten Netz.

## Bauen unter Ubuntu/Linux

Voraussetzungen: JDK 17, Android SDK Platform 36, Build Tools 35.0.0 (vom verwendeten Plugin gewählt), Internetzugriff beim ersten Abruf der Gradle-Abhängigkeiten. `ANDROID_HOME` auf das SDK setzen oder `sdk.dir` in einer nicht eingecheckten `local.properties` angeben.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug testDebugUnitTest
adb devices -l
adb -s SERIENNUMMER install -r app/build/outputs/apk/debug/app-debug.apk
```

Für beide Geräte den Installationsbefehl mit der jeweiligen Seriennummer wiederholen. USB-Debugging und ADB sind nur zur Entwicklungsinstallation erforderlich; die APK kann alternativ auf üblichem Weg installiert werden. Ein Debug-Build ist kein signiertes Release. Skripte und Einstellungen befinden sich im privaten App-Speicher und werden bei Deinstallation gelöscht.

## Versionen und Recherche (13.11.2026)

JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.1, Kotlin 2.2.20, Compose BOM 2025.08.00, [webrtc-sdk/android 150.7871.04](https://github.com/webrtc-sdk/android), minSdk 26, compileSdk/targetSdk 36. Diese festgelegten Versionen sind reproduzierbar, aber nicht alle die neuesten verfügbaren Versionen. Laut [Android Gradle Plugin 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes) ist inzwischen 9.4 verfügbar; ein Upgrade muss gesondert geprüft werden. Für targetSdk 37 erfordert direkter LAN-Zugriff auf Android 17 die Laufzeitberechtigung `ACCESS_LOCAL_NETWORK`; System-Picker-Ausnahmen sind zu prüfen ([Android-Dokumentation](https://developer.android.com/privacy-and-security/local-network-permission)). Die App zielt auf 36 und fordert sie daher nicht an. Kamera wird nur für die Kamera-Rolle zur Laufzeit abgefragt; `INTERNET` und `ACCESS_NETWORK_STATE` sind normale Netzwerkberechtigungen. Es ist keine Google-Play-Dienste-Abhängigkeit eingebunden.

Siehe [Architektur](ARCHITECTURE.md), [Hardware](HARDWARE.md) und [Testplan](TESTPLAN.md).
