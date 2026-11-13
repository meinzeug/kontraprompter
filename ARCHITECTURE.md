# Architektur und aktueller Stand

Die App besteht aktuell aus einem Android-Modul. `MainActivity.kt` und `ConnectionScreen.kt` enthalten Compose-Oberfläche und Navigation, `PrompterModel.kt` das Scroll-/Transformationsmodell sowie lokale Skriptspeicherung. Einstellungen und Skripte liegen in privaten SharedPreferences. Die Aufteilung in UI, Netzwerk und Video ist ein Zwischenstand, noch keine vollständige Clean Architecture.

`AuthenticatedChannel` ist ein längenbegrenztes TCP-Protokoll. Zwei unabhängige Zufalls-Challenges binden die gegenseitigen HMAC-Beweise und anschließend jede richtungsgebundene, fortlaufend nummerierte Nachricht an die Verbindung. Der 128-Bit-Sitzungscode wird nicht gesendet. Nur nach Zustimmung auf beiden Seiten werden SDP und ICE verarbeitet. Nachrichten sind authentifiziert, nicht verschlüsselt; die SDP-Fingerprints binden WebRTC-DTLS-SRTP an die authentifizierte Signalisierung. Signaling-IP-Adressen können im LAN sichtbar sein.

`LanSession` verwaltet eine kurzlebige Sitzung, NSD, manuelle numerische IPs, Heartbeat und Verbindungsverlust. Die RTT ist nur die Zeit für Ping/Pong auf dem TCP-Signalisierungskanal. Es gibt derzeit keinen automatischen Reconnect und keinen Hintergrundbetrieb. Ein neuer Sitzungsaufbau erfordert einen neuen Code und erneute Zustimmung. `RtcVideo` verwendet genau eine durch WebRTC geöffnete Kamera und eine WebRTC-PeerConnection ohne externe ICE-Server. Es nimmt nicht auf und verspricht keine parallele CameraX-Nutzung. Capture-/Encoderfehler werden angezeigt. Die gewählte Qualität und das Verhalten von Camera2/Camera1 sowie H.264 sind hardwareabhängig.

Das Scrolltempo ist in dichteunabhängigen Pixeln pro Sekunde definiert. Die Fortschreibung verwendet verstrichene Framezeit; Steuerung bleibt außerhalb der transformierten Text- und Videoebenen. Die Anzeige des Texts erfolgt lokal.

Vor M3 müssen Kamera- und Encoder-Limits für gleichzeitige lokale MP4-Aufnahme und Streaming auf echten Geräten geprüft und ein transparenter Betriebsmodus mit sauberem Fallback implementiert werden. Aufnahmezustandsautomat, idempotente Fernbefehle, MediaStore und Foreground Service existieren noch nicht.
