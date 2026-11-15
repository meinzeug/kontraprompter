# Deaktivierter Android-Kameravertrag v4: Integrationsentwurf

Der isolierte WIP überträgt Android-Quellen und Tests aus `b959881` selektiv
auf `1bc1baf`; ein älterer Branch wurde nicht gemergt. Bestehende Upload- und
Aufnahmeänderungen des Hauptstands bleiben erhalten. `CAMERA_REMOTE_UI_ENABLED`
bleibt `false`. Der zugehörige Web-v4-WIP `d981d8b` ist nicht integriert;
dessen produktive Kamerarouten bleiben gesperrt.

Android meldet `remote-record-v4` und validiert die gelieferte Befehls-ID,
Aufnahme-/Projektbindung, Kamera-Ausgangsseite und Befehlsfrist. Pause und
Fortsetzen prüfen dieselbe Projektbindung nochmals innerhalb des Recorder-
Locks. Der wiederholbare STOP bleibt an die Aufnahme gebunden und braucht kein
Projekt, damit der Rettungsweg nicht durch eine fehlende Projektzuordnung
verloren geht. Nicht unterstützte oder fehlerhafte Serverantworten dürfen eine
laufende Aufnahme nicht allein durch Schließen des Controller-Owners beenden.

Bei START, Pause, Fortsetzen und Kamerawechsel wird vor dem Geräteaufruf
`admitting` dauerhaft journalisiert. Eine negative Rückgabe, Ausnahme oder ein
Neustart vor dem Abschluss bleibt `uncertain` und darf nicht still wiederholt
werden. Eine positive START-Rückgabe heißt `enqueued`: Sie belegt nur die
Einreihung beim Kamera-Executor, keine Recorderzulassung oder fertige Datei.
`accepted` bei anderen Aktionen bezeichnet die Rückgabe des Geräteaufrufs,
keinen Cloud-Upload. Ein sicherer manueller Auflösungsweg für ungeklärte
Aktionen, Anti-Downgrade-/Backup-Restore-Schutz und weitere STOP-/Geräte-
Lifecycle-Tests sind vor Freigabe erforderlich.

Der Android-WIP ergänzt die JSON-Testabhängigkeit für die Wireformat-Tests;
die vorhandene separate Upload-JSON-Testkonfiguration und die Android-Laufzeit
bleiben erhalten. Frühere CI-, synthetische HTTP- und Gerätetests beziehen sich
auf andere Quellkombinationen und belegen diesen selektiven Stand nicht.
Unabhängiger Review, vollständiger Build und getrennte Android-/Web-Tests mit
echtem Recorder, Prozessabbruch, Netzwerkverlust und gemischten Versionen
stehen noch aus. Webvorschau-Widerruf und sichere Netzwerkbindung sind eigene
Blocker und werden durch diesen Vertrag nicht gelöst.
