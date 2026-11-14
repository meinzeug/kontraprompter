# Praxistest: Kamera und Teleprompter im WLAN

Am 14. November 2026 wurde der Arbeitsstand `45a65d4` auf zwei vorhandenen
Geräten getestet: Xiaomi 2409BRN2CY als Kamera, Samsung SM-A526B als
Teleprompter. Die Installation erfolgte mit Erhalt der Appdaten. Während des
Tests wurden beide Geräte bei Videomaker angemeldet und neu miteinander
gekoppelt.

Zunächst waren beide Geräte im WLAN, konnten einander aber nicht direkt
erreichen. Nach einem erneuten WLAN-Verbindungsaufbau auf dem Xiaomi war die
Verbindung in beide Richtungen wieder möglich. Die Gerätesuche auf dem Samsung
fand das Xiaomi; die Verbindung wurde mit dem manuellen Sitzungscode und
beidseitiger Bestätigung aufgebaut. ADB-Portweiterleitungen und Testrelays
waren dabei nicht aktiv. Die Ursache des vorangegangenen Netzwerkfehlers ist
nicht abschließend geklärt.

Das Samsung zeigte das Livebild an. Das WebRTC-Protokoll meldete nach dem
Kamerawechsel zeitweise 120 dargestellte Bilder in vier Sekunden. Start,
Pause, Fortsetzen und Stopp einer Aufnahme auf dem Xiaomi funktionierten über
die Schaltflächen des Samsung. Der Wechsel zwischen Front- und Rückkamera
funktionierte ebenfalls; am Ende war wieder die Rückkamera ausgewählt.

Die 27,23 Sekunden lange Testaufnahme enthält H.264-Video mit 480 × 640 Pixeln
und eine AAC-Tonspur mit 48 kHz mono. Das 6.484.549 Bytes große Original blieb
auf dem Xiaomi. Der automatische Upload nach Videomaker wurde bestätigt; die
Datei auf dem Server hatte dieselbe Größe und SHA-256-Summe
`d2257722fd7628be115e3fc5e8b210c1a7dd372eaf173d6ac5f2ea5569cb1385`.
Der hörbare Inhalt der Tonspur wurde bei diesem Test nicht beurteilt.

Nicht nachgewiesen wurden der optische QR-Scan und die vollständige
Ersteinrichtung nach einer Neuinstallation ohne vorhandene Appdaten. Die
ITFIT-Fernbedienung war am Samsung gekoppelt, wurde von Android aber als
getrennt gemeldet; echte Einzel- und Doppelklicks wurden daher nicht geprüft.
Der getestete App-Arbeitsstand ist keine Aussage darüber, welche APK aktuell
auf dem Videomaker-Server zum Download bereitsteht.

## Sprechtest und Aufnahme-Synchronisierung

Anschließend wurde auf Wunsch des Nutzers ein vorhandenes Skript am Samsung
gestartet und testweise eingesprochen. Die Aufnahme wurde auf dem Xiaomi
regulär beendet und automatisch hochgeladen. Original und Serverkopie hatten
übereinstimmende SHA-256-Summen und waren 19.665.405 Bytes groß. Die Datei ist
83,33 Sekunden lang und enthält H.264-Video sowie AAC-Ton mit 48 kHz mono. Die
Pegelprüfung zeigte ein Audiosignal; eine Prüfung der Sprachverständlichkeit
fand nicht statt.

In einem weiteren kurzen Test wurde am Samsung „Aufnahme und Teleprompter
synchron“ aktiviert. Der Text startete mit der bestätigten Aufnahme,
pausierte mit ihr, lief nach dem Fortsetzen wieder an und blieb beim Stopp
stehen. Dieser Schalter wurde danach aktiviert gelassen. Er synchronisiert die
Bedienzustände; eine sprachgesteuerte Anpassung der Textposition ist noch nicht
implementiert.

Anschließend wurden über die angemeldete Videomaker-Webschnittstelle die
Gerätebefehle für Tempo, Schriftgröße, Start und Pause an das Samsung
gesendet. Das Gerät bestätigte jeden Befehl. Tempo und Schriftgröße wurden
danach auf ihre vorherigen Werte zurückgestellt; die Aufnahme blieb gestoppt.

## Manueller Kopplungs- und Fernbedienungstest

Der separat gebaute APK-Kandidat `c83a959` wurde auf beiden Geräten mit
erhaltenen Appdaten installiert. Ein falscher Sitzungscode wurde abgelehnt;
anschließend funktionierten Gerätesuche, manueller Code und die Bestätigung auf
beiden Telefonen. Nach erneutem Ausfall der direkten WLAN-Erreichbarkeit war
zuvor ein erneuter WLAN-Verbindungsaufbau auf dem Xiaomi nötig. Das Livebild,
Kamerawechsel, Aufnahmebedienung und Web-Fernsteuerung wurden mit dem
Kandidaten erneut geprüft. Eine 11,59 Sekunden lange Testaufnahme mit Bild und
Ton wurde erfolgreich dekodiert; die Serverdatei war bytegleich zum Original.

Ein künstliches externes Eingabegerät mit den Gerätekennungen des ITFIT Selfie
P007 und einer Lautstärketaste wurde ohne Geräteauswahl erkannt. Der erste
Einzelklick startete eine Testaufnahme. Einzelklicks während der Aufnahme
pausierten beziehungsweise starteten den Text; Doppelklicks pausierten und
setzten die Aufnahme fort. Auch dieses Video wurde regulär gespeichert und
bytegleich hochgeladen. Die echte ITFIT war am Samsung gekoppelt, aber nicht
verbunden; eine Simulation ersetzt keinen Hardwaretest.

Bei einem weiteren simulierten, ausdrücklich ausgewählten externen Auslöser
mit Enter-Taste ging nach Touchbedienung der erste Tastendruck verloren; der
nächste funktionierte. Eine getrennte Diagnose ergab, dass der erste DOWN vor
der Activity beim Wechsel aus dem Touchmodus verloren ging. Ein Fix für diese
Tastenart wird separat geprüft und war nicht Teil dieses Kandidaten.
