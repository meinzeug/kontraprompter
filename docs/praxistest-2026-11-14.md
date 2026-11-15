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

## Nicht-debuggbare Release-APK

Anschließend wurde eine nicht-debuggbare, mit dem bisherigen Geräteschlüssel
signierte Release-APK auf beiden Telefonen als Update installiert. Ihre
SHA-256-Summe ist
`ab7c923a968fdc744964f997c544db0691e1946b71b62bc3eb8ab92cc6652903`;
die auf beiden Geräten installierten APK-Dateien stimmen bytegleich überein.
Vorhandene Aufnahmen blieben in der App sichtbar. Vor dem Update wurden die
privaten Appdateien und Einstellungen beider Geräte lokal gesichert; eine solche
Dateisicherung ersetzt keine Sicherung von Android-Keystore-Schlüsseln.

Auf dem Release-Stand wurden die manuelle gegenseitige Kopplung, ein
Studio-Skript, das direkte Livebild, Kamerawechsel und die synchronisierten
Aufnahme-Schaltflächen erneut geprüft. Eine 12,22 Sekunden lange Aufnahme wurde
automatisch hochgeladen und auf dem Xiaomi über den Android-Dateidialog
exportiert. Originalexport und Serverdatei waren mit 2.925.256 Bytes und
SHA-256 `f0200f8945942a12d568e1c34e5f3920d7437586d93fed2ee4e7dbd638bd8023`
bytegleich; H.264-Bild und AAC-Ton ließen sich dekodieren. Die Webbefehle für
Textstart, Pause, Tempo und Schriftgröße wurden vom Samsung quittiert; die
Einstellwerte wurden wiederhergestellt.

Ein simulierter externer P007-kompatibler Lautstärke-Auslöser und ein separat
ausgewählter Enter-Auslöser steuerten Aufnahme und Text auf der Release-APK.
Der Enter-Auslöser erforderte die sichtbar beschriftete Bereitschaftstaste;
anschließend startete der erste Druck die Aufnahme, Einzel- und Doppelklicks
verhielten sich wie vorgesehen. Die echten Bluetooth-Fernbedienungen waren
für den Test nicht verbunden. Ein kurzzeitig aktivierter Samsung-Screenreader
startete zunächst seine eigene Einrichtung und verdrängte die App; ein
vollständiger Screenreader-Test des Schalters bleibt offen. Die vorherigen
Bedienungshilfen-Einstellungen wurden wiederhergestellt.

## Veröffentlichung am 15. November

Die oben beschriebene Release-APK wurde auf Videomaker veröffentlicht. Ein
vollständiger authentifizierter HTTPS-Download hatte die gleiche SHA-256-Summe
`ab7c923a968fdc744964f997c544db0691e1946b71b62bc3eb8ab92cc6652903`
wie die auf beiden Smartphones getestete Datei. Beim anschließenden Vergleich
waren alle 21 Projekte und 22 Aufnahmen weiterhin vorhanden; die Projektinhalte
waren gegenüber der Prüfung vor dem Austausch unverändert. Die vorherige APK
liegt als eigenständige Sicherung auf dem Server. Die Web-Beschriftung und der
Download-Dateiname können bis zum separaten Web-Update noch „Debug“ anzeigen.

## Höhere Kameraauflösung: Versuch am 15. November

Ein isolierter Kandidat forderte 1280 × 720 bei 20 Bildern pro Sekunde an und
erhöhte die Zielbitrate der lokalen H.264-Aufnahme. Das Livebild wurde nach
der Kopplung angezeigt, der Aufnahmestart auf dem Xiaomi scheiterte aber mit
„Kein passender H.264-Oberflächenencoder für diese Kameraauflösung“.
Android meldete unabhängig davon Hardware-Encoderunterstützung für 1280 × 720
und 720 × 1280 bei 20 Bildern pro Sekunde. Welche Framegröße die App im
fehlgeschlagenen Versuch tatsächlich erhalten hat, wurde nicht erfasst; die
Ursache ist daher noch offen. Der Kandidat wurde nicht veröffentlicht.

Ein zweiter Diagnosebuild ergänzte die tatsächlich abgelehnten Maße in der
Fehlermeldung: Der Kamera-Stream hatte laut Android 1280 × 720 Pixel, am
Recorder kamen 540 × 960 Pixel bei 20 Bildern pro Sekunde an. Für diese Größe
fand der Recorder keinen passenden H.264-Oberflächenencoder. Die Vorschau und
die Aufnahme nutzen derzeit dieselbe WebRTC-Videospur; die höhere angefragte
Kameraauflösung garantiert daher keine entsprechend große Aufnahme. Eine
Trennung von Aufnahme und adaptiver Vorschau ist noch zu entwickeln und auf
beiden Geräten zu testen.

Danach wurde auf dem Xiaomi die oben dokumentierte Release-APK wieder als
Update installiert und ihre Prüfsumme erneut kontrolliert. Nach einem erneuten
WLAN-Verbindungsaufbau wurden Xiaomi und Samsung wieder gekoppelt; Samsung
zeigte das Livebild und das Skript an. Der Test demonstriert keine erfolgreiche
HD-Aufnahme. Die aktuelle veröffentlichte Version nahm zuvor im Geräteversuch
mit 480 × 640 Pixeln auf.

Bei einer späteren Neukopplung löste die Gerätesuche einen alten Port für den
gleichen Dienstnamen auf, obwohl der Kamera-Listener bereits einen anderen
Port nutzte. Mit den aktuellen, manuell übernommenen Angaben ließ sich die
Verbindung herstellen. Die genaue Rolle von Androids NSD-Cache und verzögerten
Callbacks ist noch nicht geklärt; ein Fix wird separat erprobt.

Mit der wiederhergestellten Release-APK wurde eine weitere Kontrollaufnahme am
Samsung gestartet, pausiert, fortgesetzt und gestoppt. Das Xiaomi meldete die
lokale Speicherung. Nach erneuter Anmeldung der Kamera meldete die App den
erfolgreichen Upload zum Skript. Die zugehörige Serverdatei war 6.164.796 Bytes
groß, 25,56 Sekunden lang und enthielt H.264-Video mit 480 × 640 Pixeln sowie
AAC-Ton mit 48 kHz. Ihre SHA-256-Summe war
`267c309d2aa7364b2dd99f1331160dcc962714a0e28723e30ffb8c6feb0c2ec7`.
Für diese zusätzliche Aufnahme wurde kein erneuter Originalexport verglichen;
der bytegleiche Originalvergleich ist oben für die vorherige Aufnahme belegt.
