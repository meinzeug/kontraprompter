# Live-Upload: isolierte Testvorbereitung

Dieser Arbeitsbaum basiert auf Android-main `5164192`. Die hier abgelegten
Clientdateien stammen unverändert aus WIP `9fe98c0` und liegen bewusst
**außerhalb von `app/src`**. Sie werden nicht in der App ausgeführt. Die
referenzierte `upstream-crash-driver.py.txt` ist eine historische Vorlage,
kein ausführbarer oder freigegebener Testrunner.

Das Gegenstück liegt in `/home/dennis/.videomaker-live-main-fixture` auf
Videomaker-main `32f61b5`; seine Route bleibt unter `NODE_ENV=production`
unabhängig von Umgebungsvariablen gesperrt. Keine produktive Sitzung, keinen
Server, keine Telefone und keine Originalaufnahmen für diese Fixture nutzen.

`test_scenarios.py` enthält ausdrücklich übersprungene Platzhalter für vier
noch zu implementierende Tests. Ein grüner Lauf ohne ausgeführte Szenarien ist
kein Nachweis. Der zukünftige Runner muss einen eigenen synthetischen Server
mit eigener SQLite-Datenbank und eigenem Datenverzeichnis starten, dessen
Prozess und Loopback-Port selbst besitzen und vor jedem Schreibzugriff prüfen.
Er darf ausschließlich von ihm erzeugte synthetische Testdateien verändern
und seinen eigenen Clientprozess mit SIGKILL beenden. Er muss anschließend
seine Prozesse zuverlässig stoppen, ohne fremde Dateien zu löschen.

Der Fencing-Test benötigt zusätzlich die auf main angepasste `LiveChunkBindingStore`-
und `CloudUploadCoordinator`-Integration; sie ist hier noch nicht übernommen.
Insbesondere muss nach ungewissem Commit der bestätigte Receipt lesend geprüft
werden; ein Wechsel auf den Vollupload darf erst nach einem sicheren,
getesteten Übergang erfolgen. Die Android-`AtomicFile`-/Keystore-, Muxer-,
Audio-, Netzwerk-, Aufnahmelifecycle- und Zwei-Geräte-Tests bleiben offen.
