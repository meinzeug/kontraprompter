# Wiederverbindung von Kamera und Teleprompter

Dies ist ein Entwurf, keine implementierte automatische Wiederverbindung. Der aktuelle Hardwaretest zeigte: Das Samsung-WLAN verband sich nach drei Sekunden Unterbrechung wieder mit dem Netz; die Telefonkopplung und das Web-Livebild kehrten nicht automatisch zurück. Die Aufnahme lief weiter und wurde anschließend über das Dashboard gestoppt und geprüft.

## Getrennte Lebenszyklen

1. **WLAN:** Android verwaltet die physische Verbindung. Netzverfügbarkeit allein beweist weder, dass der frühere Peer erreichbar ist, noch dass es dasselbe vertrauenswürdige Netz ist.
2. **Telefonkopplung:** Der Host beendet heute nach der ersten Authentisierung seinen Listener und verwirft den Sitzungscode. Eine Wiederanbindung braucht einen neuen, explizit geprüften Protokollzustand; bloßes Öffnen des alten Ports oder Vertrauen in eine IP-Adresse reicht nicht.
3. **Kamera und Aufnahme:** Eine aktive Aufnahme behält ihren bisherigen Kamerabesitz, Track, Encoder und ihre Aufnahme-ID. Ein fehlgeschlagener oder verzögerter Verbindungsversuch darf diese Ressourcen nicht schließen, neu starten oder ein zweites Mal anfordern. Ein überholter Pairing-Callback darf keinen neuen Kamerabesitzer erzeugen; der aktuelle Code verwirft solche Callbacks, wenn die Verbindung bereits getrennt ist oder ein Videoprozess existiert.
4. **Telefon-Livebild:** Der verbindungsbezogene WebRTC-Peer muss getrennt vom Capture-Lebenszyklus erneuert werden können. Heute ist er in `RtcVideo` mit der Aufnahmequelle verknüpft; bei vorhandenem Videoprozess baut `LiveSession.onReady` keinen Ersatzpeer. Eine Wiederanbindung darf daher nicht durch `LiveSession.close()` und Neuerzeugen der ganzen Sitzung simuliert werden: `close()` stoppt bei laufender Aufnahme bewusst den Recorder.
5. **Webvorschau:** Eine abgebrochene Vorschau bleibt geschlossen. Weder die Wiederkehr des WLANs noch die der Telefonkopplung erteilt eine neue Kamerafreigabe. Eine neue Vorschau benötigt eine frische ausdrückliche Freigabe am Telefon, eine neue Session und die noch offenen Datenschutz- und Netzwerkprüfungen.

## Vorgeschlagener Ablauf

- Eine laufende Kamera erhält eine eigene stabile Capture-Generation, deren Lease erst nach tatsächlichem Freigeben der Kameraressource endet. Einzelne Signalisierungs- und Peer-Generationen sind davon unabhängig. Verspätete Nachrichten, ICE-Kandidaten, Abschluss- und Fehlercallbacks einer alten Generation dürfen keine neuere Generation ändern.
- Ein vorübergehender Verbindungsverlust beendet ausschließlich den betroffenen Peer und lässt einen aktiven Recorder einschließlich STOP und Finalisierung unangetastet. Bereits in Vorbereitung oder Finalisierung befindliche Aufnahmen bleiben geschützt. Beim späteren Beenden muss derselbe Recorder weiterhin seine lokalen Daten und seinen Uploadnachweis abschließen können.
- Eine Wiederanbindung ist nur an die zuvor tatsächlich bestätigte Gegenstelle gebunden. Sie benötigt frische beidseitige Challenges, sichere Schlüsselbindung und Replay-Schutz; ein kurzlebiger QR-Code, Gerätename oder wiederverwendeter Netzwerkendpunkt allein ist keine dauerhafte Identität. Ablehnung, Schlüsselverlust, Sitzungsschluss oder Vertrauensentzug löschen die Wiederaufnahmebefugnis. Ein neuer Partner erfordert eine neue Bestätigung.
- Erst nach erfolgreicher Authentisierung werden ein neuer Telefon-Peer und eine neue SDP-/ICE-Aushandlung angelegt. Währenddessen bleibt die bisherige Kameraquelle Eigentümer der Aufnahme. Die Oberfläche zeigt getrennt Netz, Kopplung, Kamerabild und Aufnahmezustand an; eine offene oder fehlgeschlagene Aushandlung wird nicht als Livebild gemeldet.
- Die Webvorschau wird erst nach neuer Gerätefreigabe unabhängig erstellt. Ihre Freigabe muss auch bei blockiertem Executor nachweisbar widerrufen werden können; bis dahin bleibt diese Funktion produktiv gesperrt.

## Erforderliche Prüfungen vor Freigabe

- Wiederholte und gleichzeitige Verbindungsversuche, verspätete Events aus früheren Generationen, gefälschte oder wiederholte Nachrichten, Zeitablauf, Widerruf und Prozessneustart mit gezielten Tests abdecken.
- Echte Geräteversuche bei WLAN-Ausfall während `preparing`, Aufnahme, STOP und `finalizing`: jeweils dieselbe Aufnahme-ID, ununterbrochenen Kamerabesitz und lokal vollständig prüfbare Datei nachweisen. Auch Kamerafehler, fehlende Berechtigung, blockierte Worker und vollen Speicher einbeziehen.
- Nach erfolgreich authentisierter Telefon-Wiederanbindung tatsächlich wechselnde Frames auf dem Xiaomi nachweisen. Getrennt belegen, dass ohne neue Freigabe kein Browserbild erscheint und mit neuer Freigabe nur die neue Vorschauverbindung aufgebaut wird. Wiedereintritt oder Fehler dürfen nie eine Aufnahme doppelt starten.
- Das Sicherheitsmodell für eine langlebigere Bindung, die Begrenzung der Wiederholungsversuche sowie die Freigabe für reale Netzwerke unabhängig prüfen. Der vorhandene Test mit aktiviertem WLAN genügt hierfür nicht.
