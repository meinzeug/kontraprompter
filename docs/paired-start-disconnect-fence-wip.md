# Fernstart und Sitzungsende: isolierter WIP

Ein positives Start-ACK bestätigt die Annahme, keinen abgeschlossenen Kamerastatus.
Der ausstehende Befehl bleibt daher gesperrt, bis eine nach dem ACK angeforderte,
requestgebundene Statusantwort mit neuer Revision verarbeitet wurde. Eine falsche
Query-ID oder alte Revision löst die Sperre nicht. Ein negatives ACK beendet den
ausstehenden Auftrag wie bisher.

Bleibt das ACK aus, bleibt der Start auch nach acht Sekunden gesperrt. Ein
ungekorrelierter Status nach dem Timeout beweist nicht, dass der asynchron
eingereihte Start nicht noch ankommt. Die UI verweist auf Prüfung und Stopp direkt
an der Kamera sowie auf den bewussten Weg über die getrennte Sitzung; die App
startet den Auftrag nicht erneut. Die normale Disconnect-Behandlung setzt den
Status auf unbekannt und die bestehende Wiederherstellungsbestätigung bleibt nötig.

`PairedCapturePolicyTest` und `DrawerNavigationTest` prüfen die Regeln. Zusätzlich
kompiliert `fixtures/paired-disconnect/run.py --json-jar /pfad/zu/json.jar` die
echte `PairedCapture`-Implementierung mit synthetischem Transport und Recorder;
die Fixture prüft ACK vor Status, den echten Timeout, falsche Query-ID und alte
Revision. Sie benötigt die angegebenen Kotlin-/Coroutines-Artefakte im lokalen
Gradle-Cache und führt keine Geräte- oder Netzwerkoperationen aus.

Dies ist kein vollständiges geräteübergreifendes Sperrprotokoll: Eine Kamera
kann unabhängig direkt am Gerät eine Aufnahme starten, und zwischen einem
empfangenen Status und dem Schließen besteht ein nicht atomarer Zeitabstand.
Fehlendes ACK kann Fernsteuerung bis zur bewussten Wiederherstellung blockieren.
Android-Build, dynamischer Gerätetest und unabhängige Prüfung stehen aus.
