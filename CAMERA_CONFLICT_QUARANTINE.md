# Kamerabericht bei Spätkonflikt

Dieser Stand ist eine **nicht freigegebene Vorbereitung** für die deaktivierte
Kamera-Websteuerung. Er darf nicht als Nachweis eines funktionsfähigen
Produktionsbetriebs verstanden werden.

Ein Konflikt wird nur bei einer ausdrücklich typisierten Serverantwort auf einen
genau passenden, geräteauthentifizierten Poll behandelt. Geräte-ID, Poll-Sequenz,
Skript, Aufnahme, Phase, Größe und Prüfsumme müssen zum gesendeten Bericht passen.
Andere HTTP-409-Antworten behalten ihr bisheriges Fehlerverhalten. Die abgelehnte
Meldung wird nicht als bestätigt markiert; im selben gerätegebundenen Journal wird
stattdessen der exakt dazu passende, noch offene Bericht in Quarantäne markiert.
ACKs aus der abgelehnten Anfrage bleiben offen. Andere Berichte und Polls können
weiterlaufen; eine aktive Aufnahme hat weiterhin Vorrang, und Stoppbefehle können
verarbeitet werden. Ein neuer Start bleibt bei einem ungeklärten Bericht gesperrt.

Ein einmal als Konflikt gespeicherter Bericht bleibt mit seinen ursprünglichen
Aufnahme- und Projektdaten im Journal. Das Speichern einer bereits eingegangenen
Konfliktantwort wird vor der Rückkehr in einen gegebenenfalls abgemeldeten
Coroutine-Kontext abgeschlossen. Der Controller hält den Owner bis zum Ende des
Polls; ein Identitätswechsel ist währenddessen gesperrt. Quarantäne wird nur in
das zur Antwort passende Gerätejournal geschrieben. Journalversion 2 verhindert,
dass ältere App-Versionen die Markierung still übergehen. Passwörter und
Sitzungscookies werden nicht gespeichert.

Die Kameraoberfläche zeigt unabhängig vom Verbindungsstatus einen Hinweis für
die in dieser Runtime zuletzt geprüfte Geräteidentität. Er übersteht Ticketfehler,
Trennung und Abmeldung dieser Runtime. Nach erneutem Öffnen des zugehörigen
Journals wird der Hinweis aus diesem wiederhergestellt; vor erfolgreicher
Anmeldung und Journalöffnung nach einem Prozessneustart ist er noch nicht sichtbar.
Bei bestätigtem Identitätswechsel wird der Hinweis für die neue Identität
zurückgesetzt. **Ein Auflösungs- oder Wiederaufnahmeweg ist noch
nicht implementiert**: Der Konflikt bleibt bestehen und weitere Starts bleiben
gesperrt. Nach einem ausdrücklich bestätigten Geräte-/Schlüsselwechsel bleibt das
alte Journal erhalten, die neue Identität zeigt dessen Konflikt aber noch nicht
automatisch an. Eine fehlschlagende oder nicht eindeutig zuordenbare dauerhafte
Speicherung führt weiterhin zum vorhandenen Sicherheitsstopp; dann ist ein
unterbrechungsfreier Aufnahmebetrieb nicht garantiert.

Die JVM-Tests prüfen Entscheidungslogik, Quarantänebindung, nicht bestätigte ACKs,
Stoppentscheidung, Sequenz und eine simulierte Abmeldung während des Transports.
Sie belegen **nicht** die Android-`AtomicFile`-Implementierung, echten Stromausfall,
Prozessende, tatsächliche Codecaufnahme oder den vollständigen Lebenszyklus auf
Geräten. Bei einem Sitzungswechsel vor der Prüfung der Serverantwort wird kein
neues Konfliktereignis aus der alten Sitzung übernommen; der weiter unbestätigte
Bericht muss nach erneuter Anmeldung wieder versucht werden. Sichere Auflösung,
geräteübergreifende Sichtbarkeit, dauerhafte Wiederherstellung und unabhängige
Geräte-/HTTP-Rennprüfungen sind Freigabeblocker. Das Produktionsflag bleibt aus.

Die deaktivierte Kamera-Websteuerung zeigt zusätzlich Aufnahme-ID, Skript-ID und
Fehler- oder Teilstatus der im aktuellen Gerätejournal gespeicherten Konflikte.
Die ersten 20 Einträge sind sichtbar; weitere bleiben im Journal und werden
gezählt. Die Anzeige ändert keine Berichte und bewahrt die Startsperre. Nach
Neustart ist sie erst nach erfolgreicher Anmeldung und Öffnung des zugehörigen
Journals verfügbar; alte Identitäten werden nicht automatisch durchsucht.
Eine erneute Zustellung ist bewusst nicht angeboten: Ein generisches HTTP 200
belegt noch nicht, dass der Server gerade diesen Bericht dauerhaft zugeordnet
hat. Vor einer Wiederaufnahme muss eine geräte-, aufnahme-, projekt-, phasen-
und sequenzgebundene Serverbestätigung transaktional erzeugt und vom Gerät
geprüft werden. Fehlende oder widersprüchliche Bestätigung darf den Konflikt
nicht löschen und keinen Start ermöglichen.
