# Testplan

## Automatisiert

`./gradlew assembleDebug testDebugUnitTest`: überprüft Build, Scroll-/Transformationsmodell und lokalen authentifizierten Kanalaustausch einschließlich ungültigem Sitzungscode und wiederholter Nachricht. Kein Ersatz für Darstellung, Storage und echte Hardware.

## Später auf zwei Android-Geräten

1. Beide Geräte installieren, Rollen wählen, gleiches WLAN ohne Internet verwenden. Discovery/QR/manuelle Kopplung und Bestätigung an beiden Geräten prüfen.
2. Echtes Kamerabild und unabhängig darüber scrollenden Text prüfen. Horizontale/vertikale Spiegelung und Drehung beider Ebenen einzeln am echten Glas prüfen.
3. Aufnahme von B aus starten; Status auf B erst nach Bestätigung durch A. MP4 auf A auf Bild, Ton, Orientierung und Speicherort prüfen.
4. Mindestens 20 Minuten parallel aufnehmen und streamen; WLAN kurz unterbrechen. Aufnahme muss weiterlaufen; Wiederverbindung ohne doppelten Start prüfen.
5. LocalOnlyHotspot auf unterstützten Geräten sowie Verbindungsfehler, Wechsel der IP und manuellen Reconnect prüfen.
6. Encoder-/Use-Case-Grenzen und Qualitätsreduktion bzw. ehrliche Betriebsmodi prüfen.
7. Verweigerte Berechtigungen, Rotation, Standby, Hintergrundbetrieb, knappes Speicherangebot, Mikrofon-/Kamerabelegung, Akku und Temperatur prüfen.

Alle diese Tests auf realen Geräten sind derzeit **offen**. Vorschau und lokale Kopplung sind experimentell implementiert; Aufnahme, automatischer Reconnect und Hotspot fehlen noch.
