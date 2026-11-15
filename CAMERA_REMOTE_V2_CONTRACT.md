# Kamera-Webprotokoll v1/v2: isolierter Vertragsstand

Der Android-Code sendet im vorhandenen, weiterhin deaktivierten Kamerapfad
`remote-record-v2`. Der dazugehörige Videomaker-WIP liegt unter
`wip/camera-contract-v2-web-20261115`. Beide Repositories enthalten dieselbe
synthetische Vertragsdatei `camera-v1-v2-contract.json` mit SHA-256
`ebb2f0915ca91b88e3ffd17fa4824fc49de75ced3ccf055347f5a9768cde2fb0`.
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

## Projektbindung und verbleibende Aktionsgrenze

Pause und Fortsetzen werden beim Anlegen an das zum Aufnahme-Claim gehörende
Projekt gebunden. Vor jeder Auslieferung prüft der Server im selben
SQLite-Schreiblock Aufnahme, Projekt und weiterhin offenen Claim; nie
ausgelieferte ungebundene Altbefehle werden abgelehnt. Bereits ausgelieferte
Aufträge werden bei einem späteren Zustandswechsel nicht fälschlich als
abgelehnt bezeichnet. Android prüft dasselbe Projekt im Entscheidungsjournal
und innerhalb des Recorder-Locks gegen das bei Aufnahmezulassung gespeicherte
Projekt. Der separate Not-/Recovery-Stopp erhält keine neue Projektpflicht.

Die Android-Journalentscheidung `accepted` wird weiterhin vor dem
Recorderaufruf gespeichert. Verweigert der Recorder-Lock die Aktion wegen
eines zwischenzeitlich veränderten Zustands, wird dessen `false` derzeit nicht
crashsicher in diese Entscheidung zurückgeschrieben. Eine solche Quittung
belegt deshalb keine tatsächlich zugelassene Aktion. Dieser Race und das
Verhalten nach Prozessabbruch bleiben vor Aktivierung ein Freigabeblocker.
Ältere Journalentscheidungen ohne Projekt bleiben lesbar, können aber keinen
neuen Pause-/Fortsetzen-Auftrag ohne Projekt autorisieren.
