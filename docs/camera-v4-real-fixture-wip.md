# Gesperrter Entwurf: echte Kameraaufnahme mit Web-v4-ACK-Verlust

Dieser eigenständige Testbranch basiert auf dem synthetischen Fixture `563df32`.
Das Testpaket heißt ausschließlich `de.kontrakurs.kontraprompter.journalv4real`
und verwendet nur den lokalen HTTPS-Testserver und einen eigenen Keystore,
Dateibereich und Projekt. Es enthält Testcode und eine Test-CA, die nicht in
Produktiv-Branches übernommen werden dürfen. Die Produktiv-Kamerasteuerung
bleibt abgeschaltet.

**Nicht ausführbar:** Die Instrumentierung prüft vor jeder Geräte- oder
Netzwerkaktion `realDeviceFixtureEnabled=false`; der Hostrunner besitzt eine
zweite unabhängige Sperre. Vor Aufhebung müssen Aufnahme-, OS-Lifecycle-,
Wiederherstellungs- und Datenschutzfragen unabhängig geprüft werden.

Der Entwurf öffnet den echten vorhandenen Camera-/AudioRecord-/MP4-Pfad mit
Gerätemikrofon und Kamera und startet ihn über einen tatsächlichen Web-v4-
Befehl. Die LAN-Readiness wird testseitig gesetzt; eine echte Smartphone-
Paarung wird nicht geprüft. Eine nach Serverannahme absichtlich verworfene
Poll-Antwort simuliert ACK-Verlust zwischen Client und Controller, keinen
TCP-Abbruch. Anschließend soll idempotenter Retry, wiederholungsfreier START,
aufnahmegebundener STOP und die lokale Datei mit Video-/Audiospur geprüft
werden. Es entsteht echtes Bild und Ton der Umgebung; die Datei bleibt im
separaten Testpaket und wird nicht produktiv hochgeladen.

Nach 50 Sekunden versucht ein eigener Watchdog wiederholt lokalen STOP für die
exakt gleiche Aufnahme-ID. Ein später angekommener START wird so beim nächsten
Tick mit erfasst. Die Test-Session beendet sich nur nach belegtem Recorder-
und Controllerende und gemeldeter Freigabe der eigenen Ressourcen; anderenfalls
bleibt der Teststatus ungeklärt. Der Hostrunner behält Testpakete und Dateien
immer und führt keinen automatischen Uninstall/Force-Stop oder Foreground-
Wechsel durch: ein Release-Marker allein belegt keine OS-Freigabe. Ein killter,
suspendierter oder blockierter Testprozess kann auch den Watchdog verhindern.
Ein unabhängig geprüfter Android-/OEM-Statusnachweis und ein konkreter manueller
Wiederherstellungsweg für diese Fälle fehlen noch.

Kleine synthetische JVM-Tests (`FixtureEmergencyStopTest`) bestanden 3/3:
verzögerter START nach erster Stoppanfrage, wiederholbarer Stopp nach Fehler
und unveränderliche kanonische Aufnahme-ID. Dies belegt weder die Android-
Zeitplanung noch die tatsächliche Freigabe von Mikrofon, Kamera oder Datei.
Kein Android-Build, keine Geräteaktion und kein produktiver Zugriff wurden für
diesen Entwurf ausgeführt.
