# Deaktivierter Recorder-PCM-Abzweig

Dieser WIP basiert auf Android-main `9baf658`. Die bestehenden App-Aufrufer
übergeben keinen PCM-Tee; der optionale Recorderparameter ist standardmäßig
`null`. Damit entstehen durch den Abzweig weder PCM-Kopie noch zusätzliches
AudioRecord, kein neuer Thread und keine Übertragung. Die Klasse ist noch mit
keinem Decoder, Sprachfolge- oder UI-Code verbunden.

Für eine spätere isolierte Testaktivierung werden beim Abonnieren begrenzte
Slots vorab allokiert. Nach erfolgreichem Einreihen des Originaltons in den
AAC-Encoder kann der vorhandene Recorderthread den Ton per `tryLock` und
begrenztem `System.arraycopy` an diese Slots anbieten. Volle oder belegte
Warteschlangen verwerfen ausschließlich Analyseblöcke und sperren den
betroffenen Abonnenten; die Originaldaten und deren Encoderpfad bleiben
unverändert. Kopien für einen auswertenden Verbraucher entstehen erst beim
Abrufen auf dessen eigenem Thread. Nach Start, Pause, Fortsetzen und Stop
werden alte Audio-Generationen ungültig. Ein unerwarteter Analysefehler
deaktiviert den Abzweig; Aufnahmedaten werden nicht absichtlich verworfen.

`scripts/test-pcm-tap.sh` führt isolierte JVM-Tests mit synthetischen Bytes
durch, einschließlich unveränderter Eingabe, voller und blockierter
Analysewarteschlange, Generationswechsel und fehlerhafter Daten. Eine
Bytecodeprüfung findet im direkten `publish`-/`offer`-Pfad keine Allokation
oder blockierende Lock-Anforderung. Das ist weder ein Beweis für ausbleibende
JVM-/OS-Verzögerungen noch ein Test eines echten Encoders. Vor Aktivierung
fehlen unabhängiger Review, Android-Gesamtbuild, Recorder-/Audio-Last- und
Dateivergleiche auf Geräten sowie ein tatsächlich angebundener atomarer
Audio-/Skript-/Kanal-/Seek-Zustellfence. Insbesondere darf der gesondert
entwickelte Decoder erst nach Verlust-/Generations- und Sitzungskontrolle
Ergebnisse an eine Oberfläche weitergeben.

Ein Block kann bereits entnommen sein, während ein konkurrierender Producer
wegen des gehaltenen Analyse-Locks einen Verlust registriert. Ein gezielter
Race-Test reproduziert diese Reihenfolge; `isCurrent` erkennt den danach
registrierten Verlust. Diese Abfrage allein ist kein atomarer UI-Zustellfence:
Ein weiterer Verlust oder Seek kann direkt nach der Prüfung eintreten. Auf dem
lokalen Empfänger müssen Positionsübernahme und bereits bekannte Invalidierung
in derselben atomaren Zustandsquelle entschieden werden. Ein auf dem anderen
Gerät eingetretener, noch nicht zugestellter Verlust kann dort nicht
rückwirkend bekannt sein. Transkripte dürfen nicht über den bestehenden
unverschlüsselten LAN-Kanal gesendet werden. Der Abzweig bleibt deaktiviert.
