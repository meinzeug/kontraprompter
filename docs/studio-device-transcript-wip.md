# Geräteanmeldung: Transkript-Fixture

Isolierter, nicht aufgerufener Kotlin-Baustein für die Zeichenfolge des Server-WIP `e45bb8d`. Er validiert Geräte- und Challenge-IDs, strikt kodierte Challenges, bekannte Fähigkeiten, Grenzen und erzeugt die erwarteten UTF-8-Bytes für Enrollment und Login. Es gibt keine Android-/HTTP-/Produktiv-Anbindung und keine neue lokale Authentifizierung.

Der Baustein prüft derzeit nicht selbst, ob die übergebene SPKI tatsächlich zum Android-Keystore-Schlüssel gehört oder P-256 ist. Der künftige Signieradapter muss den eigenen unveränderlichen öffentlichen Schlüssel aus dem zweckgetrennten Keystore verwenden, Besitz und Transkript prüfen und niemals bloß serverseitig gelieferte Bytes blind signieren. Der Enrollment-Ownerhash ist erst zusammen mit einem vertrauenswürdigen, tatsächlich authentisierten Serveradapter und ausdrücklicher Benutzerbestätigung sinnvoll. Die Token-Zeit ist nur syntaktisch geprüft; Ablauf, Replay, Widerruf und Rechte werden serverseitig entschieden.

Keine Aussage zur Nutzbarkeit der Gesamtanmeldung: explizite Gerätefreischaltung, Keystore-Identität/Verlust, geschützte Serverrouten, Kurzzeittickets, Logout-/Upload-Commit-Fence, Lastgrenzen, Schlüsselrotation und Wiederherstellung sowie echte Cross-Repo-/Gerätetests fehlen.

Unter `experiments/studio-device/` liegen separate Signatur-Fixtures für einen kurzlebigen JVM-Softwareschlüssel und einen eigenen, zufällig benannten Android-Keystore-Testschlüssel im Shell-Kontext. Letzterer ist nicht ausgeführt oder hardwaregeprüft; er darf ausschließlich nach koordinierter Gerätefreigabe laufen, räumt nur seinen frisch erzeugten Testalias auf und ist keine App-Identität. Android-Keystore-Schlüssel und Gerätestatus bestehender Apps dürfen nicht verändert werden.
