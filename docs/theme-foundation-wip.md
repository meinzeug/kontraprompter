# Theme-Fundament: isolierter Zwischenstand

`KontraAdaptiveTheme` stellt das neue Theme als ausdrücklichen Opt-in bereit.
Es folgt standardmäßig der Hell-/Dunkel-Einstellung des Systems und verwendet
ab Android 12 (API 31) die Material-3-Systemfarben. Auf älteren Geräten oder
bei deaktivierten dynamischen Farben gelten geprüfte eigene Hell-/Dunkel-
Paletten. Es verwendet `MaterialExpressiveTheme` und `MotionScheme.expressive()`
aus Material 3 1.5.0-rc01. Diese Version ist ein Release Candidate; die dafür
nötigen Änderungen an Compose-BOM und Buildwerkzeugen sind gesondert im
Dokument `material3-expressive-deps-wip.md` festgehalten.

Typografie, Formen und `LocalKontraSpacing` bieten ein gemeinsames Fundament
für die spätere Seitenmigration. Drei Compose-Vorschauen zeigen Hell, Dunkel
und größere Schrift. `KontraVideoOverlay` stellt weiß beschriftbare, dunkle
Flächen für beliebige Videobilder bereit; der vorhandene Livebildpfad wird
dadurch nicht verändert.

Die App verwendet weiterhin bewusst ihr bisheriges dunkles `KontraTheme`.
Bestehende Seiten enthalten feste helle Vordergrundfarben und dunkle Flächen;
ein globales Umschalten auf ein helles dynamisches Schema ohne Migration würde
Kontraste und die Lesbarkeit über Video gefährden. Erst nach schrittweiser
Umstellung jeder Seite und Geräte-/Barrierefreiheitstests darf der App-Einstieg
das adaptive Theme verwenden. Die neue Funktion aktiviert keine Navigation,
Aufnahme oder Einstellung.

`ThemeContrastTest` prüft die ausdrücklich definierten Text-/Hintergrundpaare
der Fallback-Paletten auf mindestens 4,5:1 sowie die weiße Video-Overlay-
Beschriftung über vollständig weißem Videoinhalt. Dynamische Systempaletten,
andere Farbkombinationen, echte Kamerabilder, erhöhte Schriftgröße und
TalkBack müssen zusätzlich auf Geräten geprüft werden; die Vorschauen allein
sind dafür kein Nachweis.
