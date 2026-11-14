# Dauerhafte Geräteverbindung: Entwicklungsstand

Die bestehenden LAN-Sitzungen sind zeitlich begrenzt und benötigen bei einem
Neuaufbau derzeit eine erneute Kopplung. Der Pairing-Code in diesem Branch ist
noch nicht im Produkt aktiviert. Ein bestätigter Journal-Eintrag allein ist
keine Berechtigung zum Verbinden: Dazu müssen aktueller authentifizierter
Serverstatus, widerrufsfreie Gerätezuordnung, Peer-Pin und ein frischer
Ende-zu-Ende-Handshake zusammen geprüft werden.

Das Journal enthält ausschließlich öffentliche Gerätekennungen, Hashes und
Vertrauenszustände; keine privaten Schlüssel, Sitzungscodes oder Zugangsdaten.
Fehlende oder beschädigte Daten dürfen alte Beziehungen nicht automatisch
wiederherstellen. Ein lokaler Dateisystem-Rollback ist mit dem Journal allein
nicht erkennbar. Ein über den Server frisch überprüfter Widerruf bleibt daher
bei jeder Verbindung erforderlich. Die Wiederherstellung nach Stromverlust ist
noch auf echten Android-Geräten zu prüfen.

Am 14. November 2026 bestanden auf einem Xiaomi und einem Samsung getrennte,
lokale AndroidKeyStore-Tests mit neuen temporären Schlüsseln. Geprüft wurden
TLS 1.3 über Loopback, Zurückweisung falscher Pins, Nicht-Exportierbarkeit und
Löschen der Testschlüssel. Schlüssel, die ausschließlich SHA-256 erlaubten,
schlugen mit `INCOMPATIBLE_DIGEST` fehl; die neue Testvariante erlaubt
zusätzlich `DIGEST_NONE`. Diese Tests beweisen weder TLS zwischen den beiden
Geräten noch die erste Paarung, den Widerruf oder die automatische
Wiederverbindung. Bestehende Schlüssel dürfen bei einer Formatänderung nicht
unbemerkt ausgetauscht werden.
