# Testplan

## Automatisiert

`./gradlew assembleDebug testDebugUnitTest`: überprüft Build, Scroll-/Transformationsmodell und lokalen authentifizierten Kanalaustausch einschließlich ungültigem Sitzungscode und wiederholter Nachricht. Kein Ersatz für Darstellung, Storage und echte Hardware.

## Stand des Tests auf zwei Android-Geräten (13.11.2026)

- Samsung Galaxy A52 5G (SM-A526B, Android 14) als Kamera und Xiaomi 2409BRN2CY (Android 14) als Anzeige, beide im selben WLAN: Discovery, gegenseitige Bestätigung und Aufbau der Videoverbindung erfolgreich.
- Ein Testskript wurde auf dem Xiaomi über dem empfangenen Kamerabild angezeigt und nach Countdown automatisch gescrollt. Bei bevorzugtem H.264 war das Empfangsbild stark blockartig gestört, während die lokale Kamera-Vorschau klar war. Nach Umstellung der Codec-Priorität auf VP8 war das Bild im kurzen Vergleich klar. Die Ursache der H.264-Störung ist noch ungeklärt; die Statusmeldung zur Verbindung allein belegt keine ausreichende Bildqualität.
- Die Signalisierungs-RTT lag bei dieser Stichprobe ungefähr zwischen 50 und 130 ms; daraus lässt sich die Bildlatenz nicht ableiten. Ein längerer Stabilitätstest und die Ursachenanalyse der Bildstörung stehen aus.
- Studio-Anmeldung und Skriptliste über HTTPS auf dem Xiaomi getestet. Bei aktivierter Web-Fernsteuerung wurde ein Studio-Skript über die Live-Schnittstelle auf das Gerät geladen; Start und Pause wurden am Gerät beobachtet. Der Status meldete anschließend „Pausiert“. Kamera-Fernsteuerung, Aufnahme und Cloud-Upload bleiben offen.
- Nach der Gestaltungsüberarbeitung wurden beide Startansichten auf den realen Geräten geprüft und die Verbindung erneut manuell gekoppelt. Während des laufenden Videobilds funktionierten Studio-Skriptladen, Start, Pause und Anfang; die Hinweise für Studio und Kamera sowie die Android-Zurück-Navigation wurden geprüft. In einer kurzen Stichprobe meldeten die Renderer etwa 16–17 Bilder pro Sekunde. Ein längerer Stabilitäts- oder Latenztest ist dies nicht.

## Weitere Tests auf zwei Android-Geräten

1. Beide Geräte installieren, Rollen wählen, gleiches WLAN ohne Internet verwenden. Discovery/QR/manuelle Kopplung und Bestätigung an beiden Geräten prüfen.
2. Echtes Kamerabild und unabhängig darüber scrollenden Text prüfen. Horizontale/vertikale Spiegelung und Drehung beider Ebenen einzeln am echten Glas prüfen.
3. Aufnahme von B aus starten; Status auf B erst nach Bestätigung durch A. MP4 auf A auf Bild, Ton, Orientierung und Speicherort prüfen.
4. Mindestens 20 Minuten parallel aufnehmen und streamen; WLAN kurz unterbrechen. Aufnahme muss weiterlaufen; Wiederverbindung ohne doppelten Start prüfen.
5. LocalOnlyHotspot auf unterstützten Geräten sowie Verbindungsfehler, Wechsel der IP und manuellen Reconnect prüfen.
6. Encoder-/Use-Case-Grenzen und Qualitätsreduktion bzw. ehrliche Betriebsmodi prüfen.
7. Verweigerte Berechtigungen, Rotation, Standby, Hintergrundbetrieb, knappes Speicherangebot, Mikrofon-/Kamerabelegung, Akku und Temperatur prüfen.

Die oben nicht ausdrücklich als durchgeführt genannten Tests sind weiterhin **offen**. Vorschau und lokale Kopplung sind experimentell implementiert; Aufnahme, automatischer Reconnect und Hotspot fehlen noch.
