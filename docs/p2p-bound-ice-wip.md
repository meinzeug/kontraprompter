# Experiment: P2P-Kandidaten an den Signalisierungskanal binden

Dieser WIP ist kein Produktionsnachweis. Nur ein bereits authentisierter und
beidseitig bestätigter Kanal kann einen Snapshot seiner Socket-Endpunkte liefern.
Die lokale IPv4-Adresse muss auf einem aktiven Interface mit dem beobachteten
Namen `p2p0` oder `p2p-*` liegen; der Kanal wird nach der Interfaceabfrage erneut
geprüft. Der bisherige WLAN-Pfad gilt nur, wenn Android das aktive Defaultnetz
als WLAN mit Internetfähigkeit meldet und dessen Interface und Adresse zum
Socket passen. Unklarer Zustand und nicht einordenbare P2P-Interfaces melden
einen Fehler. Offline-/VPN-/Ethernet-Sonderfälle sind deshalb noch nicht
kompatibel nachgewiesen und bleiben ein Freigabeblocker.
Die Namen sind eine Herstellerheuristik, kein Beleg einer Android-P2P-Gruppe.

Nur in diesem Pfad wird `disableNetworkMonitor` gesetzt und die Upstream-
Loopback-Ausschlussmaske explizit erhalten. DTLS/SRTP wird nicht abgeschaltet.
Aus- und eingehende ICE-Hostkandidaten müssen die lokale bzw. entfernte IPv4 des
authentisierten Sockets enthalten; mDNS, IPv6 und Relay-Kandidaten werden nicht
übernommen. SDP mit eingebetteten Kandidaten, unzulässigen Adressen oder ohne
Video-/DTLS-Fingerprint wird abgelehnt. Signalisierung wird an die bestätigte
Kanalidentität gebunden. Eine Webvorschau wird in diesem P2P-Modus nicht angeboten;
auf dem normalen WLAN-Pfad ist die bestehende Factory-Konfiguration unverändert.

Kandidatenfilter beweisen **nicht**, dass native WebRTC-Sockets ausschließlich
auf P2P gebunden sind. Auch P2P-Namensgleichheit, ein einzelner Interfacecheck und
Polling beweisen keine dauerhafte Gruppenidentität oder atomare Reaktion auf
Netzwechsel. Die vorhandene HMAC-Signalisierung verschlüsselt ihre Inhalte nicht.
Vor einer Freigabe sind unter anderem unabhängiger SDP-/ICE-/Kanal-Racereview,
Netzwechsel-/VPN-/Interface- und native Sockettests, Android-Build und zwei
echte Gerätetests nötig. Während aktiver Aufnahme darf die Fehlerbehandlung weder
Recorder noch lokales Original beenden. Keine Tests dürfen Credentials oder
Adresswerte in Logs ausgeben.

Bei erkannter Trennung invalidiert der P2P-Pfad die Callbackgeneration und
reiht ein `PeerConnection.close()` auf dem Videoexecutor ein. Er ruft dabei weder
`dispose()` noch einen Recorder- oder Kamerastopp auf. Hängt der Executor oder
der native Aufruf, ist das Ende der Medienübertragung nicht bestätigt; ein
Interfaceverlust ohne passenden Callback ist ebenfalls noch nicht aktiv
überwacht. Ein sofortiger oder garantierter Medienabbruch wird nicht behauptet.
Die Unabhängigkeit des nativen Peer-Close vom aktiven Recorder muss auf echter
Hardware nachgewiesen werden.

Die Policy-Tests prüfen bekannte/unbekannte und inaktive Interfaces, IPv4-Host-
Kandidaten, verschiedene unerlaubte Kandidatenformen und SDP-Escapehatches. Ein
isolierter LanSession-Test prüft, dass ein zuvor gefasster Socket-Snapshot nach
Kanalwechsel keine aktuelle Kanalidentität mehr besitzt. Sie ersetzen weder
echte WebRTC- noch Geräte-/Netzwechseltests.
