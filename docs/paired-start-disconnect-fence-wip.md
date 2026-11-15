# Fernstart und Sitzungsende: isolierter WIP

Ein Start-ACK bestätigt allein keinen abgeschlossenen Kamerastatus, auch eine
Ablehnung schließt eine lokal gestartete Aufnahme nicht aus. Der ausstehende
Befehl bleibt daher nach positivem wie negativem ACK gesperrt, bis eine danach angeforderte,
requestgebundene Statusantwort mit neuer Revision verarbeitet wurde. Eine falsche
Query-ID oder alte Revision löst die Sperre nicht. Überholt ein anderer Status
eine korrelierte Antwort, wird diese bis zu einer Gesamtgrenze von drei Abfragen
erneut angefordert. Danach bleibt die Sperre bei unklarem Status bestehen.
ACKs ohne echtes JSON-Boolean werden ignoriert.
Bleibt nach einem gültigen ACK die korrelierte Antwort aus, fordert der Timeout
sie einmal erneut innerhalb derselben Gesamtgrenze an. Ohne ACK erfolgt keine
korrelierte Wiederholung, da eine Statusabfrage den Start überholen könnte.

Bleibt das ACK aus, bleibt der Start auch nach acht Sekunden gesperrt. Ein
ungekorrelierter Status nach dem Timeout beweist nicht, dass der asynchron
eingereihte Start nicht noch ankommt. Die UI verweist auf Prüfung und Stopp direkt
an der Kamera sowie auf den bewussten Weg über die getrennte Sitzung; die App
startet den Auftrag nicht erneut. Die normale Disconnect-Behandlung setzt den
Status auf unbekannt und die bestehende Wiederherstellungsbestätigung bleibt nötig.

`PairedCapturePolicyTest` und `DrawerNavigationTest` prüfen die Regeln. Zusätzlich
kompiliert `fixtures/paired-disconnect/run.py --json-jar /pfad/zu/json.jar` die
echte `PairedCapture`-Implementierung mit synthetischem Transport und Recorder;
die Fixture prüft ACK vor Status, fehlerhafte ACKs, den echten Timeout vor und
nach ACK, falsche Query-ID, überholte Revisionen und begrenzte Wiederholung. Sie benötigt die angegebenen Kotlin-/Coroutines-Artefakte im lokalen
Gradle-Cache und führt keine Geräte- oder Netzwerkoperationen aus.

Dies ist kein vollständiges geräteübergreifendes Sperrprotokoll: Eine Kamera
kann unabhängig direkt am Gerät eine Aufnahme starten, und zwischen einem
empfangenen Status und dem Schließen besteht ein nicht atomarer Zeitabstand.
Fehlendes ACK kann Fernsteuerung bis zur bewussten Wiederherstellung blockieren.
Android-Build, dynamischer Gerätetest und unabhängige Prüfung stehen aus.
