# Testplan

## Automatisiert

`./gradlew assembleDebug testDebugUnitTest`: überprüft Build, Scroll-/Transformationsmodell und lokalen authentifizierten Kanalaustausch einschließlich ungültigem Sitzungscode und wiederholter Nachricht. Kein Ersatz für Darstellung, Storage und echte Hardware.

## Stand des Tests auf zwei Android-Geräten (13.11.2026)

- Samsung Galaxy A52 5G (SM-A526B, Android 14) als Kamera und Xiaomi 2409BRN2CY (Android 14) als Anzeige, beide im selben WLAN: Discovery, gegenseitige Bestätigung und Aufbau der Videoverbindung erfolgreich.
- Ein Testskript wurde auf dem Xiaomi über dem empfangenen Kamerabild angezeigt und nach Countdown automatisch gescrollt. Bei bevorzugtem H.264 war das Empfangsbild stark blockartig gestört, während die lokale Kamera-Vorschau klar war. Nach Umstellung der Codec-Priorität auf VP8 war das Bild im kurzen Vergleich klar. Die Ursache der H.264-Störung ist noch ungeklärt; die Statusmeldung zur Verbindung allein belegt keine ausreichende Bildqualität.
- Die Signalisierungs-RTT lag bei dieser Stichprobe ungefähr zwischen 50 und 130 ms; daraus lässt sich die Bildlatenz nicht ableiten. Ein längerer Stabilitätstest und die Ursachenanalyse der Bildstörung stehen aus.

## Weitere Tests auf zwei Android-Geräten

1. Beide Geräte installieren, Rollen wählen, gleiches WLAN ohne Internet verwenden. Discovery/QR/manuelle Kopplung und Bestätigung an beiden Geräten prüfen.
2. Echtes Kamerabild und unabhängig darüber scrollenden Text prüfen. Horizontale/vertikale Spiegelung und Drehung beider Ebenen einzeln am echten Glas prüfen.
3. Aufnahme von B aus starten; Status auf B erst nach Bestätigung durch A. MP4 auf A auf Bild, Ton, Orientierung und Speicherort prüfen.
4. Mindestens 20 Minuten parallel aufnehmen und streamen; WLAN kurz unterbrechen. Aufnahme muss weiterlaufen; Wiederverbindung ohne doppelten Start prüfen.
5. LocalOnlyHotspot auf unterstützten Geräten sowie Verbindungsfehler, Wechsel der IP und manuellen Reconnect prüfen.
6. Encoder-/Use-Case-Grenzen und Qualitätsreduktion bzw. ehrliche Betriebsmodi prüfen.
7. Verweigerte Berechtigungen, Rotation, Standby, Hintergrundbetrieb, knappes Speicherangebot, Mikrofon-/Kamerabelegung, Akku und Temperatur prüfen.

Die oben nicht ausdrücklich als durchgeführt genannten Tests sind weiterhin **offen**. Vorschau und lokale Kopplung sind experimentell implementiert; Aufnahme, automatischer Reconnect und Hotspot fehlen noch.
