# Kamera-v4: Eigentümerschaft und Koexistenz

Die Fernsteuerung bleibt deaktiviert. Der unveränderte Recorder lässt während
bestehenden P2P-/lokalen Aufnahmen keinen Webbesitz zu. Ist Webbesitz aktiv,
werden LAN-Starts und LAN-Pause/Fortsetzen/Stopp gesperrt; am Kamerahandy bleibt
der lokale Rettungsstopp möglich. Dies ist gegenseitiger Ausschluss, keine
vollständige gemeinsame Fernbedienung. Neue Tests verwenden die tatsächlichen
`LiveSession`-Zulassungspfade mit synthetischem Kanal/Recorder und prüfen die
Nichtübernahme eines laufenden P2P-Takes sowie konkurrierenden LAN-Start und
Webbesitz. Sie belegen weder echte Kamera/Codec noch ein Android-/Netzwerkrace.

Ein möglicher sicherer Weg ist ein einziger unter `LiveSession.guard` vergebener,
aufnahme- und generationengebundener Take-Eigentümer: Das bloße Herstellen einer
Webverbindung darf einen laufenden LAN-Take nicht übernehmen. Ein Start muss
journalisiert und atomar nur einem Besitzer zugeteilt werden; Pause/Fortsetzen
und Wechsel müssen Identität, Projekt, tatsächliche Phase und aktiven jeweils
freigegebenen Kanal erneut prüfen. Ob ein explizit freigegebener zweiter
Kontrollkanal denselben Take bedienen darf, erfordert einen eigenen bindenden
Berechtigungs- und ACK-Vertrag; bei Zweifel ablehnen. Der lokale Notstopp muss
jederzeit bleiben. Journalentscheidungen, ausstehende Netzwerkaufrufe und
verspätete Callbacks dürfen niemals einen späteren Take steuern.

Nicht die Exklusivsperre einfach entfernen: `RemoteCameraController.close()`
und mehrere Journalfehler rufen derzeit `session.stopRecording()` ohne
Take-/Besitzerbindung auf und könnten dann eine fremde P2P-Aufnahme stoppen.
Vor echter Koexistenz sind diese Pfade, Lifecycle-/Logout-Verhalten,
Controller- und Recorder-Lockreihenfolge sowie Kanal-/Take-Fencing im echten
Aufnahmeweg nachzuweisen. Vollständiger Android-/Web-/Gerätetest mit laufendem
Recorder, Netzwerkverlust, Neustart und beiden Reihenfolgen bleibt offen.
