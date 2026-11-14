# Android: Anmeldung, Kopplung und Wiederverbindung

## Tatsächlicher Stand

- Die Studio-Anmeldung wird per HTTPS ausgeführt. `StudioClient` hält den
  Sitzungscookie ausschließlich im Arbeitsspeicher; nach Prozessende ist eine
  neue Anmeldung erforderlich. Das Adminpasswort wird nicht gespeichert.
- Die aktuelle Kamera-Prompter-Kopplung benutzt einen zufälligen LAN-Code mit
  fünf Minuten Gültigkeit und gegenseitiger Bestätigung. Der vorhandene
  Nachrichtenkanal schützt die Integrität per HMAC; er verschlüsselt den
  LAN-Inhalt nicht. Die Sitzung ist nicht dauerhaft: Eine Unterbrechung
  verlangt eine neue Kopplung. Lokale WLAN-Client-Isolation kann auch bei
  korrekter Kopplung das WebRTC-Bild verhindern.
- Beim `ON_STOP` schließt die App momentan ihre Kamera-/Prompter-Sitzung und
  deaktiviert die Web-Fernsteuerung. Das kann beim Verlassen oder bei einer
  Activity-Neuerstellung die Anzeige unterbrechen. Eine begonnene Aufnahme
  wird kontrolliert beendet; nach einem Neustart wird keine Aufnahme gestartet.
- `RelayDeviceIdentity`, das lokale Peerjournal und das gepinnte TLS sind
  vorbereitete Bausteine in diesem Entwicklungsbranch. Sie sind derzeit nicht
  als automatischer Wiederverbindungsweg an die App angeschlossen. Ein echter
  Cross-Device-TLS-Test über USB-ADB beweist die Produktivverbindung nicht.

## Zielablauf und Sicherheitsbedingungen vor Aktivierung

1. Einmalige Studio-Anmeldung über HTTPS. Auf beiden Geräten wird nach
   ausdrücklicher Zustimmung eine zweckgebundene Geräteidentität registriert;
   deren privater Schlüssel verbleibt nicht exportierbar im AndroidKeyStore.
   Das Adminpasswort und der kurzlebige QR-Code werden nicht als dauerhafte
   Gerätezugänge gespeichert.
2. Die erste Paarung bindet beide öffentlichen Geräteidentitäten und Rollen
   an denselben überprüften Paarungstranskript und eine ausdrückliche
   Bestätigung auf beiden Geräten. Auch bei Transport über den Server muss
   eine gepinnte Ende-zu-Ende-Verbindung die Schlüssel tatsächlich bestätigen.
3. Ein Server-Endpunkt für registrierte, widerrufbare Geräte authentisiert
   frische, zweck- und zeitgebundene Challenges mittels Signatur. Nur der
   berechtigte Geräteschlüssel erhält eine kurzlebige rollen- und
   sitzungsgebundene Berechtigung. Vor jedem Neuaufbau ist der aktuelle
   Serverstatus zu prüfen; lokale alte Journalstände allein genügen nicht.
   Ersatz, Schlüsselverlust, Löschung und Widerruf verlangen eine explizite
   erneute Einrichtung. Dieser Dienst und seine HTTP-Routen fehlen noch.
4. Bei App-Neustart, Netzwechsel oder Verbindungsfehler wird im sichtbaren
   Betrieb eine neue Signalisierungssitzung angefordert, die vorhandene
   Vertrauensbindung und der Widerruf erneut geprüft und ein neuer TLS-1.3-
   Handshake aufgebaut. Wiederholungen brauchen begrenztes Backoff und eine
   generationsgebundene Zustandsmaschine. Eine Wiederverbindung startet keine
   Aufnahme und darf bestehende Aufnahmen nicht unterbrechen oder umhängen.
5. Videomaker ist die gemeinsame Instanz für Anmeldung, widerrufbare
   Gerätefreigaben und den Aufbau jeder Sitzung. Bild und Ton sollen im selben
   WLAN für geringe Latenz nach Möglichkeit direkt über WebRTC zwischen den
   Telefonen laufen. Die Tonvorschau auf dem Prompter ist separat schaltbar;
   der Originalton wird weiterhin auf dem Kameragerät aufgenommen. Der aktuelle
   Livepfad überträgt nur Bild; eine Tonvorschau ist noch nicht implementiert.
   Bei isolierten WLAN-Clients müssen Signalisierung und WebRTC-Medien
   unabhängig über sichere Server-Relays funktionieren. TURN-Zugangsdaten werden auf jedem Gerät über HTTPS bezogen,
   niemals über den heutigen unverschlüsselten LAN-Kanal. Sie sind kurzlebig;
   ihre Erneuerung und erneute ICE-Aushandlung müssen vor Ablauf erfolgen.
   Server-Relay, TURN-Endpunkt, Netzgrenzen und deren Betrieb fehlen noch.
6. Die Oberfläche muss Anmelde-, Pairing-, Verbindungs-, Widerrufs- und
   Fehlerzustände getrennt und wahrheitsgemäß anzeigen. Erst nach echten Tests
   über beide Telefone, Neustarts, Netzabbruch, Widerruf und Wiederanmeldung
   darf der Weg als funktionierende dauerhafte Verbindung ausgewiesen werden.

## Tonvorschau ohne Aufnahmeverlust

Der aktuelle MP4-Recorder öffnet selbst ein `AudioRecord` mit
`AudioSource.CAMCORDER`, überwacht System-Stummschaltung und leitet
monotone Hardware-Zeitstempel an AAC weiter. Eine zweite unabhängige
Mikrofonaufnahme für WebRTC darf nicht vorausgesetzt werden: Android kann bei
konkurrierenden Aufnahmen eine Seite stummschalten. Die spätere Umsetzung muss
auf den betroffenen Geräten eine gemeinsam genutzte Erfassung oder einen
geeigneten PCM-Abzweig verwenden; die getrennte Vorschau darf den Recorder
weder blockieren noch seine Zeitbasis ändern. Vor Freigabe sind auf dem Samsung
mit Tonvorschau an und aus Originalton, AAC-/H.264-Dekodierung, Pausengrenzen,
A/V-Synchronität und eine tatsächlich hörbare Vorschau auf dem Xiaomi zu
prüfen. Solange diese Umsetzung und Prüfungen fehlen, wird kein Live-Ton
versprochen.

## Hardware-Praxistest am 14. November 2026

Mit Xiaomi als Kamera und Samsung als Teleprompter wurde die Verbindung über
lokalen USB-ADB-Testtunnel und einen temporären TURN-Relay hergestellt. Das
Samsung startete die Aufnahme, pausierte, setzte fort und stoppte sie. Das
Xiaomi speicherte eine 23,87 Sekunden lange Aufnahme mit H.264 (480×640) und
AAC (48 kHz, Mono); alle geprüften Audio- und Videopaket-DTS waren monoton.
Der Ton enthielt ein messbares Signal, aber es wurde keine verständliche
Sprachprobe aufgenommen. Der automatische Upload in das am Prompter gewählte
Studio-Skript wurde bestätigt. Datei und Cloud-Download waren bei 5.662.577
Bytes anhand SHA-256 identisch; das Original blieb auf dem Xiaomi.

Dieser Test belegt die aktuellen Aufnahme- und Uploadfunktionen mit diesen
Rollen. Er belegt weder Live-Ton noch eine Verbindung ohne ADB-Testtunnel,
einen sicheren produktiven Relay, automatische Wiederverbindung oder den
Betrieb nach App-Neustart.

## Sprachfolge

Die Weboberfläche besitzt bereits einen begrenzten Wortabgleich und Browser-
Spracherkennung. Dort kann der Browser je nach Unterstützung auf sein eigenes
Mikrofon zurückfallen; das beweist nicht die Verarbeitung der Kamerastimme.
Der Android-Branch bereitet einen kompatiblen Wortabgleich und eine
sessionsgebundene Positionslogik vor; eine tatsächliche Android-Erkennung und
Sprechersteuerung sind damit noch nicht verbunden.

Android 13 und neuer bietet mit `RecognizerIntent.EXTRA_AUDIO_SOURCE` eine
Möglichkeit, einen geöffneten PCM-Datenstrom bereitzustellen. Laut offizieller
[Android-Dokumentation](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)
kann ein Erkennungsdienst dies jedoch ignorieren und stattdessen selbst das
Mikrofon öffnen. Weil die bestehende Originalaufnahme bereits `CAMCORDER`
verwendet und [konkurrierende Erfassung stummgeschaltet werden kann](https://developer.android.com/media/platform/sharing-audio-input),
darf diese API nicht ohne nachgewiesene Provider-Unterstützung während der
Aufnahme aktiviert werden. Ein geeigneter Erkenner muss nachweislich den
geteilten PCM-Strom verarbeiten; bei fehlendem Nachweis bleibt der manuelle
Modus aktiv. Gesprochene Rohdaten oder Transkripte werden nicht ohne bewusste
Entscheidung an externe Dienste gesendet. Bei Stille und Sprechpause darf die
Position nicht selbständig weiterlaufen; verspätete Ergebnisse nach Pause,
manuellem Sprung oder Skriptwechsel sind zu verwerfen.

## Aufbau am Desview T3 und Bildqualität

Die App bietet bereits getrennte Spiegelung und Drehung für Text und
Kamerabild sowie die Tastenbedienung. Ob die konkrete Textspiegelung,
Lesbarkeit und Ausrichtung am angeschlossenen Desview T3 tatsächlich passen,
ist noch mit dem aufgebauten Gerät zu prüfen. Die Tastenlogik für Einzel-
und Doppeldruck wird gesondert überarbeitet und ist erst nach Gerätetest zu
beurteilen.

Die aktuelle Kamerapipeline fordert 640×480 bei 20 fps an; das gemessene
Xiaomi-Testvideo hatte 480×640 Pixel. Eine Auswahl echter unterstützter
Kombinationen aus Auflösung, Bildrate und Bitrate ist noch zu entwickeln und
auf den Geräten zu messen. Hochskalierung ist kein Nachweis höherer
Aufnahmequalität.

## Stand der vorbereiteten Kernbausteine

Im Entwicklungsbranch existieren eine gemeinsame Signalisierungsschnittstelle,
ein nach erfolgreicher gegenseitiger TLS-Prüfung verwendbarer begrenzter
Nachrichtenkanal, ein deaktivierter PCM-Abzweig am vorhandenen Recorder und
ein reiner Wort-/Positionsabgleich. Zusammen bestanden diese isolierten
Kernbausteine am 14. November 2026 dreizehn JVM-Tests. Die TLS-Hardwareprobe
übertrug vor der nachträglich ergänzten Frame-Gesamtfrist bereits
`capture_query`, `capture_status` und `sdp` über gegenseitig gepinntes TLS.
Die vollständige Android-App und die neu ergänzte Recorder-Einhängung wurden
seit diesen Änderungen noch nicht gebaut oder auf Geräten geprüft. Der
PCM-Abzweig hat standardmäßig keine Verbraucher und überträgt weder Audio
noch Transkripte. Er bietet nur während einer laufenden lokalen Aufnahme Daten;
eine Tonvorschau vor Aufnahmebeginn ist damit nicht umgesetzt.
