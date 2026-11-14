# KontraPrompter

Native Android-App für einen Teleprompter mit zwei Geräten. **Entwicklungsstand: experimentelle Live-Vorschau, lokale Videoaufnahme mit Mikrofon und manuell gestarteter Cloud-Upload**, auf zwei realen Geräten und einem isolierten Testserver erprobt. Aufnahmefernsteuerung ist noch nicht implementiert. Langzeitstabilität, Fehlerfälle und Lippensynchronität sind nicht ausreichend nachgewiesen; wichtige Aufnahmen sollten unabhängig gesichert werden.

## Implementiert

- Deutsche Compose-Oberfläche und Offline-Skripte; mehrere Skripte werden im privaten App-Speicher gehalten. TXT/MD-Import über den Android-Dateiauswahldialog, Bearbeitung einschließlich Einfügen über die Systemtastatur.
- Im Bereich „Studio-Skripte öffnen“ können nach Anmeldung Skripte über HTTPS von videomaker.dorfspy.de geladen und als lokale Kopien geöffnet werden. Lokale Änderungen werden nicht zum Studio zurückgeschrieben. Passwort und Sitzungscookie bleiben nur im Arbeitsspeicher.
- Nach ausdrücklicher Aktivierung kann das angemeldete Anzeigegerät im Studio-Dashboard Skript, Start, Pause und Anfang empfangen. Die Fernsteuerung endet, sobald die App den Vordergrund verlässt; bei erneuter Aktivierung wird eine neue Gerätesitzung angelegt. Aufnahme und Kamerasteuerung über das Dashboard sind noch nicht implementiert.
- Prompter mit zeitbasiertem Scrollen, manuellem Verschieben, Start/Pause, Anfang und Countdown. Tempo in dp/s. Schriftgröße, Zeilenabstand, Breite, Hintergrund, Displayhelligkeit und Leselinie einstellbar. Text horizontal/vertikal spiegelbar und in 90°-Schritten drehbar; Kalibrierbild mit Richtungsangaben.
- Experimenteller lokaler Verbindungsweg: Kameragerät bietet einen temporären Dienst an; Das Anzeigegerät kann einen lokal erzeugten QR-Code scannen; alternativ sucht Android NSD den Dienst oder numerische IP, Port und Code werden manuell eingegeben. Ein zufälliger 128-Bit-Code ist höchstens fünf Minuten gültig und wird bei erfolgreicher Authentifizierung verbraucht. Beide Geräte müssen die Verbindung bestätigen. Es werden keine Accounts oder externen Signaling-Server verwendet.
- Authentifiziertes TCP-Signaling mit beidseitiger Challenge-Response, HMAC-SHA-256 und Sequenznummern. WebRTC mit Host-ICE-Kandidaten, ohne STUN/TURN, für die Videovorschau; WebRTC schützt Videodaten mittels DTLS-SRTP. Das Signaling selbst ist authentifiziert, aber nicht verschlüsselt: Netzwerkteilnehmer können beispielsweise IP-Adressen und SDP-Inhalt beobachten. Der Sitzungscode wird beim Signaling nicht übertragen oder protokolliert. Der QR-Code enthält die kurzlebigen Zugangsdaten und sollte wie ein Passwort behandelt werden; der Kopplungsbildschirm ist gegen Screenshots geschützt.
- Kamera-Vorschau nur während der App im Vordergrund ist. Camera2 wird bevorzugt, Camera1 dient als Fallback; der gewählte Stream startet mit 640×480 bei 20 fps als Anfrage an den Capture-Backend. VP8 wird bevorzugt, sofern WebRTC es anbietet; WebRTC darf auch einen anderen Codec aushandeln. Die tatsächliche Geräteunterstützung und Bildrate hängen von der Hardware ab.
- Auf dem gekoppelten Kameragerät kann eine lokale Aufnahme mit H.264-Video und AAC-Mikrofonton gestartet und gestoppt werden. Die Mikrofonberechtigung wird beim Start angefragt. Aufnahme und Livebild nutzen denselben Kamerastream; Ton wird nicht live übertragen. Bei Verbindungsverlust läuft eine bereits gestartete Aufnahme weiter. Ein Stopp wegen Platzmangel, Größen- oder Zeitlimit wird angezeigt. Nach Fehlern können technisch geprüfte Teilvideos ausdrücklich als unvollständig erscheinen.
- Abgeschlossene Videos liegen zunächst ausschließlich im privaten App-Speicher. Unter **Aufnahmen** lassen sie sich über die Android-Dateiauswahl exportieren; der Export prüft vorher den SHA-256 der lokalen Datei. Die App lädt Aufnahmen nicht automatisch hoch. Bei Deinstallation werden auch nicht exportierte Aufnahmen gelöscht.
- Eine abgeschlossene Aufnahme kann unter **Aufnahmen → In Videomaker hochladen** nach Anmeldung und ausdrücklicher Skriptauswahl manuell übertragen werden. Das lokale Original bleibt erhalten. Die App prüft vor und während der Übertragung den SHA-256 und zeigt Erfolg erst nach einem passenden Serverbeleg für Aufnahme-ID, Skript, Größe und Hash. Ein erneuter Versuch verwendet dieselbe Upload-ID. Teilvideos sind ausschließlich lokal exportierbar; für sie fehlt eine serverseitige Kennzeichnung. Das Serverlimit beträgt 1 GiB pro Aufnahme.
- Beim Verlassen des Uploadbildschirms oder bei einer Neuerstellung der Activity wird eine laufende Übertragung abgebrochen; ein nicht bestätigtes Ergebnis kann mit demselben Ziel erneut versucht werden. Die Anzeige unterscheidet Übertragung und Serverbestätigung. Eine einfache Drehung erstellt die Activity bei dieser App nicht neu. Ein dauerhaft blockierter Netzwerkworker kann weitere Uploads bis zur Freigabe oder zum Prozessende sperren. Die App bietet keinen Hintergrund-Upload.
- Beim Verlassen des Vordergrunds wird eine laufende Aufnahme beendet und ihre Finalisierung angestoßen. Ein Foreground Service existiert nicht: Bei einem erzwungenen Prozessende oder wenn Android den Prozess vor dem Abschluss beendet, ist eine vollständige Datei nicht garantiert. Unfertige Dateien werden nicht als abgeschlossene Aufnahmen ausgewiesen.
- Empfangenes Video liegt unter dem lokalen Text. Text und Video sind unabhängig horizontal/vertikal spiegelbar und drehbar; Fit/Fill, Deckkraft und Abdunklung der Videoebene sind einstellbar. Die angezeigte RTT misst den Signalisierungskanal, **nicht** die Bildlatenz.

## Verbindung testen, sobald Geräte verfügbar sind

1. Gleiche APK auf beiden Geräten installieren; beide Geräte im selben WLAN ohne Client-Isolation verbinden. Internet ist für die App-Nutzung nicht erforderlich.
2. Auf A **Kamera / Vorschau** wählen und die Kameraerlaubnis erteilen. Der QR-Code sowie IP, Port und Sitzungscode stehen auf dem Bildschirm.
3. Auf B **Teleprompter / Anzeige → Kamera verbinden → QR-Code scannen** wählen, die Kameraerlaubnis erteilen und die Rückkamera auf den Code von A richten. Alternativ ein gefundenes Gerät wählen oder IP, Port und Sitzungscode manuell eingeben.
4. Den angezeigten Gerätenamen prüfen und die Kopplung **auf beiden Geräten bestätigen**. Auf B **Skripte und Prompter** öffnen und einen Text starten. Auf A sollte die Vorschau und auf B das Videobild hinter dem Text erscheinen.
5. In den Prompter-Einstellungen Text und Video einzeln spiegeln. Fit zeigt das gesamte Bild, Fill beschneidet es gegebenenfalls. Die sinnvolle Spiegelrichtung ist am eigenen Glas zu kalibrieren.

Diese Schritte wurden auf einem Samsung Galaxy A52 5G und einem Xiaomi 2409BRN2CY kurz erprobt; Kopplung, Video und Scrollen funktionierten mit VP8. Mit H.264 war das Bild in diesem Versuch stark gestört. Einzelheiten stehen im Testplan. Bei Abbruch die Sitzung auf A neu starten und auf B erneut mit dem neuen Code koppeln. Die Geräte waren im Test wiederholt trotz desselben WLANs nicht gegenseitig erreichbar; zeitweises Aus- und Einschalten des Xiaomi-WLANs stellte die Erreichbarkeit in einzelnen Versuchen wieder her, ist aber keine garantierte Lösung. Automatisches Wiederverbinden und Hintergrundbetrieb fehlen. Die Navigation zu einem externen Dateiauswahldialog kann die Sitzung ebenfalls beenden.

## Noch offen

Aufnahme-Start/Stopp über das Webdashboard und dauerhafte Befehls-ACKs, Aufnahme-Foreground-Service, Hotspot, automatischer Reconnect und vollständiges Hardware-/Thermomanagement fehlen. Außerdem offen sind ein belastbarer Langzeit- und Lippensynchronitätstest, Kamerastörung und Encoder-Timeout auf Hardware, Video-Kalibrierbild, Presets, weitere Textoptionen, Skriptexport und End-to-End-Bildlatenzmessung. Die H.264-Bildstörung der Live-Vorschau und weitere Hardwarefälle müssen noch untersucht werden. Die Live-Vorschau kommuniziert unmittelbar im gewählten Netz; die Studio-Anbindung lädt Skripte von videomaker.dorfspy.de.

## Bauen unter Ubuntu/Linux

Voraussetzungen: JDK 17, Android SDK Platform 36, Build Tools 35.0.0 (vom verwendeten Plugin gewählt), Internetzugriff beim ersten Abruf der Gradle-Abhängigkeiten. `ANDROID_HOME` auf das SDK setzen oder `sdk.dir` in einer nicht eingecheckten `local.properties` angeben.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug testDebugUnitTest
adb devices -l
adb -s SERIENNUMMER install -r app/build/outputs/apk/debug/app-debug.apk
```

Für beide Geräte den Installationsbefehl mit der jeweiligen Seriennummer wiederholen. USB-Debugging und ADB sind nur zur Entwicklungsinstallation erforderlich; die APK kann alternativ auf üblichem Weg installiert werden. Ein Debug-Build ist kein signiertes Release. Skripte, Einstellungen und nicht exportierte Aufnahmen befinden sich im privaten App-Speicher und werden bei Deinstallation gelöscht.

## Versionen und Recherche (13.11.2026)

JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.1, Kotlin 2.2.20, Compose BOM 2025.08.00, [webrtc-sdk/android 150.7871.04](https://github.com/webrtc-sdk/android), minSdk 26, compileSdk/targetSdk 36. Diese festgelegten Versionen sind reproduzierbar, aber nicht alle die neuesten verfügbaren Versionen. Laut [Android Gradle Plugin 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes) ist inzwischen 9.4 verfügbar; ein Upgrade muss gesondert geprüft werden. Für targetSdk 37 erfordert direkter LAN-Zugriff auf Android 17 die Laufzeitberechtigung `ACCESS_LOCAL_NETWORK`; System-Picker-Ausnahmen sind zu prüfen ([Android-Dokumentation](https://developer.android.com/privacy-and-security/local-network-permission)). Die App zielt auf 36 und fordert sie daher nicht an. Kamerazugriff wird für die Kamera-Rolle und den QR-Scanner, Mikrofonzugriff beim Aufnahmestart zur Laufzeit abgefragt; `INTERNET` und `ACCESS_NETWORK_STATE` sind normale Netzwerkberechtigungen. Es ist keine Google-Play-Dienste-Abhängigkeit eingebunden.

Siehe [Architektur](ARCHITECTURE.md), [Hardware](HARDWARE.md) und [Testplan](TESTPLAN.md).
