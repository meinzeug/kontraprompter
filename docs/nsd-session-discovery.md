# NSD-Sitzungen und veraltete Endpunkte

Ein Praxistest fand bei erreichbarem WLAN einen früheren Port in der Suche. Die
Ursache ist nicht abschließend belegt. Die App vergab bisher für jede Sitzung
denselben DNS-SD-Namen, obwohl der Listener einen neuen Port erhielt. Android
weist außerdem darauf hin, dass ein einzelnes `resolveService`-Ergebnis jederzeit
veralten kann: <https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager>.

Ein weiterer isolierter Gerätetest zeigte einen zweiten, konkreten Fehler: Der
DNS-SD-Cache enthielt den erwarteten Dienst, der Resolve-Callback auf dem Samsung
lieferte jedoch den Typ `._kontraprompter._tcp` mit führendem Punkt. Ein
temporäres `app_process`-Diagnoseprogramm bestätigte gleichen Dienstnamen, gültigen
Port und IPv4-Adresse; nur die Typprüfung schlug fehl. Das Programm wurde danach
entfernt. Der korrigierte Vergleich akzeptiert genau einen optionalen führenden
Punkt, den erwarteten TCP-Dienst mit oder ohne `.local` und einen optionalen
abschließenden Punkt; andere Typen, mehrfache Punkte und zusätzliche
Domainbestandteile bleiben ungültig.

Der isolierte Arbeitsstand vergibt jeder Kamerasitzung einen neuen zufälligen,
nicht geheimen DNS-SD-Namen. Suchergebnisse und verspätete Auflösungen sind an
die aktuelle Auswahl gebunden; manuelle Bearbeitung widerruft die Auswahl.
Für aufgelöste Endpunkte wird ein regulärer `Socket` unter kurzer Auswahlprüfung
gebunden. Das blockierende `connect` läuft außerhalb des Auswahl-Locks; Verlust
oder Neuauswahl schließt den ausstehenden Socket. Vor dem Handshake und bei der
Kanalinstallation wird die Auswahl erneut geprüft, letztere atomar. Ein vor
Widerruf zugelassener Versuch kann im kleinen Zeitfenster vor dem wirksamen
Schließen noch anlaufen; bereits gestartete Pakete lassen sich nicht zurücknehmen.
Nach der Installation gehört der Socket der Verbindung; ein späterer
Discovery-Verlust schließt ihn nicht. Verbindungsfehler
alter Versuche dürfen neuere Versuche nicht überschreiben. Manuelle Eingabe
bleibt möglich und authentisiert sich unverändert mit Sitzungscode.

Ein direkter Kotlin-/Compose-Compile und 19 JVM-Tests bestanden. Drei Tests
verwenden echten TCP-Loopback und authentisierte Kanäle: manueller Aufbau,
Widerruf während eines unvollständigen Handshakes und Fortbestand nach späterem
Verlust des Suchergebnisses. Im manuellen Aufbau wird parallel eine andere
Discovery-Auswahl widerrufen und ein weiterer Such-Verbindungsversuch ausgelöst;
die manuelle Verbindung darf dadurch nicht abgebrochen werden. Weitere Tests
verwenden synthetische DNS-Einträge und Callback-Reihenfolgen. Insbesondere
beenden asynchroner Resolve-Fehler und Verlust einer ausgewählten Dienstinstanz
den Ladezustand; überholte Fehler dürfen einen neueren Vorgang nicht ändern.
Ein echter authentisierter Loopback-Ping/Pong läuft über den regulären Socket
auch bei wartendem Reader zeitnah durch. Ein separates JVM-Modell mit dem in
[Androids SocketAdaptor](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/sun/nio/ch/SocketAdaptor.java)
und [Channels](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/java/nio/channels/Channels.java)
sichtbaren gemeinsamen Blocking-Lock zeigt, dass ein blockierter Reader das
Schreiben bis zu einem nächsten Paket verzögern kann. Dies ist ein plausibler
Erklärungsansatz für die auf Android beobachtete Latenz, kein Nachweis der
konkreten Ursache auf dem Samsung.
Ohne Antwort endet die Auflösung nach zehn Sekunden. Ein verspätetes Ergebnis
nach dem Timeout wird verworfen. Adresse und Port werden auf dem UI-Thread nur
unter Prüfung derselben Auswahl übernommen; erst danach endet der Ladezustand.
Die Timeout-Tests nutzen eine verkürzte Frist und keine Android-Geräte.
Dies ist kein Android-Gradle-Build und kein
Gerätetest; Androids konkretes DNS-SD-Cacheverhalten ist damit nicht belegt.
Alte Einträge können sichtbar bleiben. Ein verspätetes Lost-Ereignis für einen
identischen Dienstnamen kann insbesondere bei alten App-Versionen ein neues
Suchergebnis entfernen; das ist ein Verfügbarkeitsfehler und erfordert erneute
Auswahl. Gegenseitige Bestätigung, Kanalwechsel und andere offene LAN-Rennen
werden hierdurch nicht umfassend gelöst. Der Stand benötigt unabhängige Prüfung,
Android-Build und einen Praxistest, bevor er übernommen wird.
