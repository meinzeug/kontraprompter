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

Der erste vollständige Android-Testkandidat für die Gerätesuche wurde auf
beiden Telefonen installiert und erneut geprüft. Android meldete die Auflösung
des neuen Kameradienstes als erfolgreich; die App verwarf das Ergebnis jedoch
als unvollständig. Das Android-Systemprotokoll zeigte den Diensttyp mit
`.local`, während die App diesen Zusatz zunächst nicht akzeptierte. Eine
Korrektur für beide zulässigen Schreibweisen ist separat im Test; der erneute
Gerätetest steht aus. Die zuvor veröffentlichte Release-Version wurde auf
beiden Geräten wiederhergestellt und die manuelle Verbindung eingerichtet.

Eine anschließende Android-Diagnose zeigte den konkreten Ablehnungsgrund:
Samsung lieferte den Resolve-Diensttyp mit einem zusätzlichen führenden Punkt;
Dienstname, IPv4-Adresse und Port waren vorhanden. Mit einem dafür angepassten
Testkandidaten bestanden 192 CI-Tests. Auf den Telefonen wurden drei neue
Kamerasitzungen nacheinander jeweils über die Suche mit aktuellem Port gefunden,
gegenseitig bestätigt und als Videoverbindung hergestellt. Eine nachfolgende
Aufnahme wurde gespeichert und zum Server übertragen.

Dabei stieg die angezeigte Signalisierungs-Roundtripzeit zeitweise auf etwa
fünf Sekunden und ein Pausen-/Fortsetzungszustand erschien verzögert, während
ein gleichzeitiger ICMP-Ping etwa zwei bis drei Millisekunden benötigte. Der
Kandidat wurde wegen dieser Steuerungsverzögerung nicht veröffentlicht. Der
normale Release-Stand wurde wiederhergestellt; danach lag seine angezeigte
Signalisierungszeit bei etwa 50 ms. Die Ursache im neuen Socket-Pfad wird
getrennt untersucht.

Mit der wiederhergestellten Release-APK wurde eine weitere Kontrollaufnahme am
Samsung gestartet, pausiert, fortgesetzt und gestoppt. Das Xiaomi meldete die
lokale Speicherung. Nach erneuter Anmeldung der Kamera meldete die App den
erfolgreichen Upload zum Skript. Die zugehörige Serverdatei war 6.164.796 Bytes
groß, 25,56 Sekunden lang und enthielt H.264-Video mit 480 × 640 Pixeln sowie
AAC-Ton mit 48 kHz. Ihre SHA-256-Summe war
`267c309d2aa7364b2dd99f1331160dcc962714a0e28723e30ffb8c6feb0c2ec7`.
Für diese zusätzliche Aufnahme wurde kein erneuter Originalexport verglichen;
der bytegleiche Originalvergleich ist oben für die vorherige Aufnahme belegt.

## Statusanzeige nach Neukopplung

Die Release-APK mit der Korrektur des veralteten Trennungshinweises wurde auf
beiden Geräten als Update installiert. Ihre SHA-256-Summe ist
`21526ddcca71d65d777d0f3c576b93bbed1c2862fdb676dc73dfbe6581948d6b`.
Ein vollständiger CI-Lauf bestand mit 174 Tests. Nach Trennung und manueller
Neukopplung meldete das Samsung „Kamera bereit“ und eine hergestellte
Videoverbindung; der alte Trennungshinweis war nicht mehr sichtbar. Start,
Pause, Fortsetzen und Stopp einer weiteren Aufnahme wurden vom Samsung aus
geprüft. Die Aufnahme wurde lokal gespeichert und ihr Upload zum Skript vom
Server bestätigt. Beide Geräte waren anschließend bei Videomaker angemeldet;
die Web-Fernsteuerung des Teleprompters war aktiviert.

Diese APK wurde danach auf Videomaker veröffentlicht. Ein vollständiger
authentifizierter HTTPS-Download hatte dieselbe SHA-256-Summe wie die auf
beiden Geräten installierte Datei. Alle 21 Projekte und 24 Aufnahmen der
Serverprüfung waren vorhanden. Die Beschriftung der Weboberfläche kann bis zum
separaten Web-Update weiterhin „Debug“ anzeigen.

## Rohbild-Kandidat: 720p-Gerätetest

Ein weiterer, nicht veröffentlichter Kandidat versorgte den Recorder direkt
mit Kamerabildern vor der WebRTC-Anpassung. Sein vollständiger CI-Lauf bestand
mit 179 Tests. Auf Xiaomi und Samsung wurden Kopplung, Aufnahmestart, Pause,
Fortsetzen und Stopp geprüft. Die Verbindung blieb während der Aufnahme laut
Gerätestatus hergestellt; wegen der dunklen Kameraszene wurde Bewegung in der
Vorschau nicht zuverlässig beurteilt. Die Akkutemperatur des Xiaomi betrug
während der Stichproben 29 °C, der gemeldete Thermalstatus war 0.

Die fertiggestellte Datei enthält H.264 mit 720 × 1280 Pixeln und AAC mit
48 kHz. Video und Ton ließen sich vollständig dekodieren. Das über den
Android-Dateidialog exportierte Original
`/sdcard/Download/KontraPrompter-eaf1f03b.mp4` und die Serverdatei waren je
49.550.977 Bytes groß und hatten dieselbe SHA-256-Summe
`e5a4465b4fd4940009a65d1f7fb0d556f23d5e69a28a514b1d42d5d6c7a25e01`.
Die Datei war etwa 100,2 Sekunden lang; Ton- und Videospur hatten nahezu
identische Laufzeiten. Die Sprachverständlichkeit wurde nicht abgehört.

Die 1.419 Videobilder entsprechen etwa 14,2 Bildern pro Sekunde und liegen
unter der angefragten Rate von 20. Der Median und das 95. Perzentil der
Bildabstände lagen bei 70,6 ms; es gab keine Lücke über 150 ms. Der Raum war
sehr dunkel; ob Belichtung, Kamerakonfiguration oder Verarbeitung die Rate
begrenzt, ist ungeklärt. Ein aussagekräftiger Vergleich mit guter Beleuchtung
und getrennten Messungen von Kameraeingang und Encoderausgabe steht noch aus.
Der Kandidat wurde deshalb nicht veröffentlicht. Auf beiden Geräten wurde
anschließend wieder die oben dokumentierte Release-APK installiert.

Ein darauf folgender, ausschließlich für die Diagnose aktivierter Testbuild
zeichnete bei einer weiteren Aufnahme begrenzte Bildzähler auf: Am
Recorder-Callback kamen 621 Bilder mit etwa 14,17 fps an; 17 davon fielen in
die Vorbereitung, die übrigen 604 wurden angenommen und als 604 Videopuffer
vom Encoder ausgegeben. Die Erfassung am Recorder-Callback ist keine direkte
Sensormessung; die Encoderpuffer sind für sich genommen kein MP4-Nachweis.
Die Aufnahme wurde regulär lokal abgeschlossen. Direkt anschließend meldete
Android für den aktiven Kameraclient eine automatische Zielspanne von 5 bis
30 fps, etwa 70,0 ms Belichtungszeit und 70,46 ms Bilddauer bei ISO 6300.
Die Messung grenzt den Engpass vor der Encoderausgabe ein; ein Vergleich bei
besserer Beleuchtung und eine Prüfung einer festen Bildrate stehen aus.

## Kamerasuche und veröffentlichte Release-APK

Der korrigierte Suchpfad akzeptiert auch den von Samsung gelieferten
Diensttyp mit führendem Punkt. Jede Kamerasitzung erhält einen neuen
Dienstnamen; ein verspätetes Suchergebnis wird an den zugehörigen Versuch und
Socket gebunden. Ein regulärer Vollduplex-Socket ersetzte den zuvor getesteten
Adapter, bei dem die Signalisierung verzögert war. Der vollständige CI-Lauf
bestand mit 193 Tests.

Auf Xiaomi und Samsung wurden drei neue Kamerasitzungen nacheinander über die
Suche gefunden, jeweils Adresse und Port mit der Kameraseite verglichen und
nach gegenseitiger Bestätigung als Videoverbindung hergestellt. Stichproben
der angezeigten Signalisierungs-Roundtripzeit lagen bei 47 bis 51 ms. Bei
einer anschließenden Aufnahme wurden Start, Pause, Fortsetzen und Stopp vom
Samsung aus geprüft. Die Serverdatei war 3.271.772 Bytes groß, 13,65 Sekunden
lang und enthielt H.264-Video mit 480 × 640 Pixeln sowie AAC-Ton mit 48 kHz.
Ihre SHA-256-Summe war
`ef699330e98fa20833d0252fe8f307ef7c2f3d0517a733ed123df5c3d949ce72`.
Das Xiaomi meldete den erfolgreichen Upload; das lokale Original blieb
vorhanden. Für diese Aufnahme wurde kein erneuter Originalvergleich durchgeführt.

Die auf beiden Geräten getestete Release-APK wurde auf Videomaker
veröffentlicht. Der vollständige authentifizierte HTTPS-Download war 30.435.025
Bytes groß und hatte dieselbe SHA-256-Summe
`c35fb4fe7977e726280d4a7cf5cbb0db8c0d4d640209f0a3f2d9004baeb64bdb`.
Die Bestandsprüfung fand alle 21 Projekte und 28 Aufnahmen; keines der
ursprünglich erfassten Projekte hatte sich geändert. Der anonyme Download und
der Download nach Abmeldung waren gesperrt. Die Weboberfläche kann bis zum
separaten Web-Update weiterhin „Debug“ als Dateinamen anzeigen; die APK selbst
ist ein nicht debuggbarer Release-Build. Das ist kein Nachweis für eine bereits
implementierte automatische dauerhafte Wiederverbindung.

## Kanalgebundene Steuerung und Abbruchtest

Der Release-Kandidat für die kanalgebundene Steuerung bestand in der CI die
Debug- und die Release-Variante derselben Suite mit jeweils 210 Tests. Er wurde
mit dem bisherigen Geräteschlüssel auf beiden Telefonen als Update installiert;
die APK hatte auf beiden Geräten dieselbe SHA-256-Summe
`5fb741550a313504d8f9cfba552e681610efbd9a58863560111cc55e58470c11`.
Drei frische Kamerasitzungen wurden nacheinander über die Gerätesuche mit dem
jeweils aktuellen Port gefunden, bestätigt und als Videoverbindung aufgebaut.
Eine weitere Sitzung wurde nach versehentlichem Zurücknavigieren eingerichtet.

Start, Pause, Fortsetzen und Stopp einer Testaufnahme wurden vom Samsung aus
ausgelöst und vom Xiaomi bestätigt. Nach erneuter Videomaker-Anmeldung auf dem
Xiaomi wurde die Aufnahme hochgeladen. Die 4.258.719 Bytes große Serverdatei
war 17,44 Sekunden lang und enthielt H.264 mit 480 × 640 Pixeln und AAC mit
48 kHz. Ihre SHA-256-Summe war
`9c7c7f19d67509cb53c25dfe862fd2ac8b6594078c6ecefb59f3b07752d74989`.

Für den Abbruchtest wurde eine weitere Aufnahme vom Samsung aus gestartet und
dessen WLAN kurz ausgeschaltet. Das Xiaomi meldete, dass die Aufnahme ohne
Videoverbindung weiterläuft. Sie wurde auf dem Xiaomi regulär gestoppt und
lokal gespeichert; danach wurde das Samsung-WLAN wieder eingeschaltet. Die
Serverdatei war 3.888.012 Bytes groß und 16,34 Sekunden lang; sie enthielt
H.264 mit 480 × 640 Pixeln sowie AAC mit 48 kHz. Ihre SHA-256-Summe war
`c7ef0d9e0bf93fdf77ff48831a1c936c9fe3caad77beb10de21bd343e9cd8dfe`.
Für diese beiden Aufnahmen wurde kein Originalexport zum Bytevergleich erstellt.

Die getestete APK wurde auf Videomaker veröffentlicht. Der vollständige
HTTPS-Download nach Anmeldung war 30.439.121 Bytes groß und hatte dieselbe
SHA-256-Summe wie die installierte APK. Alle 21 Projekte und 30 Aufnahmen waren
bei der Bestandsprüfung vorhanden; kein ursprünglich erfasstes Projekt war
verändert. Anonymer Download und Download nach Abmeldung waren gesperrt. Die
vorherige APK wurde separat gesichert. Der Downloadname kann in der alten
Weboberfläche weiterhin „Debug“ enthalten, obwohl die APK nicht debuggbar ist.
Die Anmeldung und Kopplung werden dadurch noch nicht dauerhaft gespeichert.

## Kompakter Status im Teleprompter

Die überarbeitete Statusanzeige wurde mit der Release-APK auf beiden Geräten
geprüft. Im Hochformat waren Aufnahmezustand und Studioverbindung sichtbar;
der geöffnete Detaildialog zeigte Verbindung, Skriptzuordnung und Kameraauswahl.
Eine erneute Aufnahme wurde vom Samsung aus gestartet, pausiert, fortgesetzt
und auf dem Xiaomi gespeichert. Der Upload einer 4.229.987 Bytes großen
Testdatei wurde auf dem Server gefunden.

Im Querformat waren Aufnahme-, Stopp- und Promptertasten erreichbar. Bei
sichtbarer Bedienleiste blieb jedoch wenig Platz für den Skripttext; nach dem
Ausblenden der Leiste war mehr Text sichtbar. Die ursprünglichen
Drehungseinstellungen des Samsung wurden wiederhergestellt. Ein vollständiger
TalkBack-Test und eine Prüfung mit sehr großer Systemschrift stehen aus.

Die getestete APK wurde auf Videomaker veröffentlicht. Der authentifizierte
HTTPS-Download war 30.443.217 Bytes groß und hatte die SHA-256-Summe
`d4cf8d7e7fc84494af3cb27d7a2c36b2a03fe525a511f2efa005f3e56daa1d18`.
Bei der Bestandsprüfung waren alle 21 Projekte und 31 Aufnahmen vorhanden;
die vorherige APK wurde gesichert. Die ältere Weboberfläche kann weiterhin
„Debug“ im Downloadnamen anzeigen, obwohl die APK nicht debuggbar ist.

Beim anschließenden erneuten Koppeln nach dem WLAN-Abbruch war das Xiaomi trotz
WLAN-Adresse vorübergehend weder vom Samsung noch vom Laptop direkt erreichbar;
das Samsung meldete eine fehlgeschlagene Verbindung. Nach erneutem Aus- und
Einschalten des Xiaomi-WLANs waren direkte Pings wieder möglich und eine neue
Sitzung mit Livebild wurde eingerichtet. Später waren direkte Pings in beiden
Richtungen erfolgreich. Die genaue Ursache der zeitweisen fehlenden
Netzwerkerreichbarkeit ist nicht festgestellt; eine automatische dauerhafte
Wiederverbindung ist weiterhin nicht nachgewiesen.

## Platzsparende Querformat-Bedienung

Der Querformat-Kandidat wurde auf beiden Telefonen mit vorhandenen Appdaten
installiert. Auf dem Samsung standen im Querformat Aufnahme, Stopp,
Kamerawechsel, Editor, Anfang und Start in einer Zeile; der Text hatte dadurch
mehr Platz. Auch mit aktiver Studioverbindung waren beide Statusflächen und die
Bedienelemente sichtbar. Start, Pause, Fortsetzen und Stopp einer Testaufnahme
wurden im Querformat vom Samsung ausgelöst und vom Xiaomi bestätigt. Die
ursprünglichen Drehungseinstellungen wurden anschließend wiederhergestellt.
Ein erster Versuch konnte die Aufnahmebedienung wegen einer getrennten
Kameraverbindung nicht prüfen; nach frischer Kopplung gelang der beschriebene
Test. Ein vollständiger TalkBack-Test und Tests aller sehr kleinen
Fenstergrößen stehen aus.

Die 4.180.076 Bytes große Testaufnahme wurde nach erneuter Kamera-Anmeldung
hochgeladen. Die Serverdatei war 17,20 Sekunden lang und enthielt H.264 mit
480 × 640 Pixeln sowie AAC mit 48 kHz; ihre SHA-256-Summe war
`d877316fb6e7597c359a8041c40a6888f17ac372eb1cc69feb95c63ad397580f`.
Ein Originalexport zum Bytevergleich erfolgte nicht.

Die getestete Release-APK wurde auf Videomaker veröffentlicht. Der
vollständige authentifizierte HTTPS-Download war 30.447.313 Bytes groß und
hatte die SHA-256-Summe
`3234dfcd22f7a66968d66d46d6280c406f53df7c8374fd36f0933eb3770e585c`.
Alle 21 Projekte und 33 Aufnahmen waren bei der Bestandsprüfung vorhanden; die
vorherige APK wurde gesichert. Die Weboberfläche kann weiterhin „Debug“ im
Downloadnamen anzeigen, obwohl die APK nicht debuggbar ist.

Nach der Veröffentlichung wurden die Webbefehle für Promptertempo,
Schriftgröße, Start und Pause erneut an das Samsung gesendet und vom Gerät
bestätigt. Tempo und Schriftgröße wurden auf die ursprünglichen Werte
zurückgesetzt. Dies prüft die vorhandene Prompter-Fernsteuerung; direkte
Kamerabefehle aus dem Webdashboard sind damit nicht nachgewiesen.

## Release mit geschütztem Uploadjournal

Der Android-Stand `1b004d0` meldet eine vom Server bewusst gelöschte
Upload-ID ausdrücklich und behandelt verspätete Lösch- und Erfolgsantworten
sitzungsgebunden. Historische zufällige Upload-IDs bleiben lesbar. Der
vollständige CI-Lauf bestand jeweils 222 Debug- und Release-Unit-Tests.
Eine getrennte Android-Test-App prüfte auf beiden Geräten jeweils fünf Fälle
mit echtem `AtomicFile` und Android-JSON, darunter alte IDs, den Löschgrund,
einen simulierten Schreibfehler und einen veränderten Journalauftrag. Die
Testpakete wurden anschließend entfernt. Die Journal-Synchronisierung gilt
für kooperierende Zugriffe im selben App-Prozess; die bestehende Grenze für
generische Fehler bei Sitzungswechsel ist im Testplan beschrieben.

Die nicht-debuggbare Release-APK wurde mit dem bisherigen, gerätekompatiblen
Android-Debug-Signaturschlüssel signiert und auf beiden Telefonen als Update
installiert. Die ursprünglichen Appdaten blieben erhalten. Ihre SHA-256-Summe
ist `13df4c5db86da4755c522f599bf55b0adf617c0a30c70853fed52f16c3d5e855`.
Nach dem Update war das WLAN zwischen den Geräten zunächst erneut nicht direkt
erreichbar; nach Aus- und Einschalten des Xiaomi-WLANs gelang die frische
Kopplung mit Livebild. Der Grund des zeitweisen Netzwerkfehlers ist weiterhin
ungeklärt; dauerhafte automatische Wiederverbindung ist nicht implementiert.

Mit dem Samsung als Prompter wurden Aufnahme, Pause, Fortsetzen und Stopp auf
dem Xiaomi ausgelöst und vom Kameragerät bestätigt. Die lokal gespeicherte
Testaufnahme wurde automatisch nach Videomaker hochgeladen. Die Serverdatei
ist 17,21 Sekunden und 4.207.811 Bytes groß und enthält H.264 mit 480 × 640
Pixeln sowie AAC-Ton mit 48 kHz. Ihre SHA-256-Summe lautet
`efd3af630732d25fa2c1d554c834371b9df4d21ad93a6c7f480b13f7a227bfb0`.
Ein unabhängiger Originalexport und eine Sprachverständlichkeitsprüfung
wurden für diese Aufnahme nicht durchgeführt.

Die APK wurde mit unabhängigem Backup der vorherigen Datei auf Videomaker
veröffentlicht. Der vollständige authentifizierte öffentliche HTTPS-Download
war 30.455.505 Bytes groß und stimmte bytegleich mit den auf beiden Geräten
installierten APK-Dateien überein. Die Signierung und Installation wurden
geprüft; die echte ITFIT-Fernbedienung sowie eine neue Kameraaufnahme über das
Webdashboard wurden dabei nicht getestet.

## Produktivtest nach Schutz gelöschter Uploads

Am 15. November wurde der Serverstand `4dca746` nach isoliertem Test und
Datenbanksicherung veröffentlicht. Für eine eigene 20-sekündige Testaufnahme
stimmte der Export vom Xiaomi vorab mit der Serverdatei überein
(SHA-256 `0ec9b39904ed8b55cd768f4010f9e7fa4fb3f66efe49a611de291e7b5789a2ba`).
Nach der ausdrücklichen Löschung dieser Testaufnahme auf dem Server meldete die
App beim erneuten Upload, dass die Server-Aufnahme-ID gelöscht wurde und das
Original lokal bleibt. Die Serverprüfung zeigte weiterhin keine Aufnahmezeile
und keine Datei, aber eine Löschsperre. Der Export blieb auf dem Gerät erhalten.
Vor dem Serverfix hatte derselbe echte Fall noch „Connection reset“ gemeldet.
Bei sehr großen oder langsamen Uploads kann die Übertragung trotzdem wegen der
serverseitigen Fristen abbrechen; der gesamte Inhalt wird bei diesem Konflikt
zunächst übertragen und verworfen.

Nach dem Release waren beide Geräte zunächst im WLAN nicht direkt erreichbar.
Ein erneuter WLAN-Verbindungsaufbau am Xiaomi stellte die Erreichbarkeit wieder
her; die Ursache ist weiterhin ungeklärt. Über die erneute direkte Kopplung mit
Xiaomi als Kamera und Samsung als Prompter wurden Start, Pause, Fortsetzen und
Stopp erneut bestätigt. Die 17,47 Sekunden lange Testaufnahme wurde automatisch
zum Server hochgeladen. Ihre 4.154.550 Bytes enthalten H.264-Video mit 480 × 640
Pixeln und AAC-Ton mit 48 kHz; die Server-SHA-256 lautet
`4b57ec43b493bf26a243b2b9021101f880790edf32eca021625887dad928e413`.
Für diesen letzten Test wurden kein unabhängiger Export und keine Prüfung der
Sprachverständlichkeit durchgeführt. Die Xiaomi-USB-Verbindung war während
des Tests getrennt; die Bedienung erfolgte dort über WLAN-ADB.
