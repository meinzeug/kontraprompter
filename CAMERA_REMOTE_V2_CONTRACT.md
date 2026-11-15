# Kamera-Webprotokoll v1/v2: isolierter Vertragsstand

Der Android-Code sendet im vorhandenen, weiterhin deaktivierten Kamerapfad
`remote-record-v2`. Der dazugehörige Videomaker-WIP liegt unter
`wip/camera-contract-v2-web-20261115`. Beide Repositories enthalten dieselbe
synthetische Vertragsdatei `camera-v1-v2-contract.json` mit SHA-256
`a157d167b02f64b10dbc544f6c79d851e54a048588cb2e310f4282249daa6ba5`.
Android liest die enthaltenen konkreten Serverantworten durch denselben
JSON-Parser, den auch der bestehende Poll-Client aufruft. Dieser Parser prüft
Typen, kanonische UUIDs, Projekt-/Aufnahmebindung, Kamera-Ausgangsseite und
Fristen, bevor ein Befehl das Entscheidungsjournal erreicht.

`remote-record-v1` ist ein bestehender älterer Serververtrag; diese Android-App
fällt nicht automatisch von v2 auf v1 zurück. Ein ACK bestätigt lediglich die
journalisierte Entscheidung, nicht die tatsächliche Aufnahme, den Objektivwechsel
oder einen Cloud-Upload. Ein Widerruf nach bereits angenommenem Befehl wird von
der Parserprüfung allein nicht rückwirkend durchgesetzt.

Beide Feature-Gates bleiben unverändert: `CAMERA_REMOTE_UI_ENABLED=false` und
Videomaker-Kamerarouten in Produktion 404. JVM-Parser- und Servertests ersetzen
keine Android-Provider-, vollständigen APK-, Geräte-, Browser-, Medien- oder
Ende-zu-Ende-Tests. Insbesondere Identitäts-/Sessionbindung, erneute Zustellung,
unklare Ergebnisse, konkurrierende lokale Bedienung, App-Neustart, laufende
Aufnahme und Widerruf brauchen weiterhin unabhängige Prüfung vor Freigabe.
