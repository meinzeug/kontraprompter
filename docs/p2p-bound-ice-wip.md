# Experiment: P2P-Kandidaten an den Signalisierungskanal binden

Dieser WIP ist kein Produktionsnachweis. Nur ein bereits authentisierter und
beidseitig bestätigter Kanal kann einen Snapshot seiner Socket-Endpunkte liefern.
Die lokale IPv4-Adresse muss auf einem aktiven Interface mit dem beobachteten
Namen `p2p0` oder `p2p-*` liegen; der Kanal wird nach der Interfaceabfrage erneut
geprüft. Unbekannte Namen, IPv6 und Fehler nutzen den bisherigen WLAN-Pfad.
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

Die Policy-Tests prüfen bekannte/unbekannte und inaktive Interfaces, IPv4-Host-
Kandidaten, verschiedene unerlaubte Kandidatenformen und SDP-Escapehatches. Ein
isolierter LanSession-Test prüft, dass ein zuvor gefasster Socket-Snapshot nach
Kanalwechsel keine aktuelle Kanalidentität mehr besitzt. Sie ersetzen weder
echte WebRTC- noch Geräte-/Netzwechseltests.
