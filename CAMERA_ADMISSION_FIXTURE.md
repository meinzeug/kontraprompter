# Synthetisches Modell: Kamera-Befehlszulassung

Dieser Stand liegt ausschließlich in `app/src/test` und aktiviert weder die
Kamerafernsteuerung noch verändert er einen bestehenden Recorder. Das Modell
unterscheidet `PREPARED`, `DISPATCHING`, `ADMITTED`, `EXECUTED`, `REJECTED`
und `UNCERTAIN`. Vor dem simulierten Recorderaufruf wird `DISPATCHING`
gespeichert; erst ein erfolgreich zurückgekehrter simulierter Zulassungsaufruf
ermöglicht `ADMITTED` und eine positive Modell-ACK. `EXECUTED` erfordert eine
separat gelieferte, auf Befehl, Aufnahme und Projekt bezogene Beobachtung.

Nach simuliertem Prozessverlust werden offene bzw. nur zugelassene Vorgänge
`UNCERTAIN`. Sie werden nicht automatisch wiederholt und nicht positiv oder
negativ quittiert. Auch eine Exception während oder nach der Wirkung sowie ein
ungewisser Speicherausgang werden nicht als Ablehnung ausgegeben. Das Modell
blockiert beim simulierten Recorderaufruf nicht seinen eigenen Zustandslock.
Sechs JVM-Tests decken diese Fälle mit synthetischem Speicher ab. Die STOP-
Konkurrenz verwendet nur einen künstlichen STOP und beweist nichts über den
echten Recorder- oder Recoverypfad.

Die Kernfrage bleibt offen: Eine einzelne Transaktion über Android-Journal,
WebRTC-/Codec-Wirkung und Server existiert nicht. Dieses Modell beweist weder
eine atomare Übernahme noch einen Crash-sicheren Android-Dateisystem-Commit.
Ein späterer Adapter muss die tatsächliche Recorderzulassung unter ihrem
bestehenden Aufnahme-/Projekt-/Eigentümer-Lock prüfen, dauerhafte Entscheidungen
und Fehlermeldungen sauber koordinieren und ein geprüftes Wire-Format für
`UNCERTAIN` bereitstellen. Auch das Schließen eines laufenden oder blockierten
Codecs, mehrere Prozesse, Geräte- und Serverneustarts, Speicherfehler und eine
sichere Auflösung unklarer Entscheidungen sind nicht implementiert. Insbesondere
ist ein bereits vor Disconnect zugelassener Auftrag nicht rückwirkend widerrufen.
`recover` setzt voraus, dass ein früherer Prozess wirklich nicht mehr aktiv ist;
eine parallele zweite Instanz ist nicht geschützt. Kein produktiver Einsatz.
