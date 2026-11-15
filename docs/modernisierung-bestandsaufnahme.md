# KontraPrompter: Bestandsaufnahme und Designrichtung

Stand der Bestandsaufnahme: Android-main `2016094`. Die aktuell auf den beiden
Testgeräten installierte Menü-Testversion stammt aus einem separaten WIP; sie
ist nicht Grundlage dieser Bestandsaufnahme des Hauptbranches.

## Seiten und Funktionen

- **Start und Rollenwahl:** Teleprompter bzw. Kamera öffnen, Frontkamera wählen,
  Aufnahmen und Hilfe erreichen; Kameraerlaubnis und belegte Kamera anzeigen.
- **Verbindung/Kamera:** Geräte im lokalen Netz suchen oder Adresse, Port und
  Sitzungscode eingeben; Kopplung beidseitig bestätigen, Vorschau und Status
  anzeigen, Kamera wechseln sowie Video mit Ton aufnehmen und speichern.
- **Skripte:** lokale Skripte auswählen oder neu erstellen; Studio und
  Kameraverbindung öffnen.
- **Videomaker:** Passwortanmeldung, Studio-Skripte laden und lokale Kopie
  auswählen, Verbindung und Freigabe der Web-Promptersteuerung anzeigen.
- **Editor:** Titel und Text bearbeiten, Textdatei importieren, Hinweise zum
  Skript anzeigen, Teleprompter, Einstellungen und Kalibrierung öffnen.
- **Teleprompter:** gespiegelten Text und Livebild anzeigen, Textlauf und
  Aufnahme steuern, Kamera wechseln, Status und Hinweise öffnen.
- **Aufnahmen:** geprüfte, teilweise und ungeprüfte Dateien anzeigen und
  exportieren; geeignete Aufnahmen zum manuellen Upload auswählen.
- **Upload:** Studio-Anmeldung und Ziel wählen, Fortschritt, Serverbeleg,
  Abbruch sowie Fehler- und Konfliktzustände anzeigen.
- **Einstellungen:** Text, Tempo, Kamerabild, Synchronisierung, Bluetooth,
  Spiegelung, Helligkeit, Leselinie und Countdown.
- **Spiegelung:** Orientierung und Spiegelung am Teleprompterglas prüfen.
- **Hilfe:** Aufbau, Kopplung, Studio, Aufnahme, Auslöser und aktuelle Grenzen.

## Ausgangslage und Grenzen

Die Gestaltung verwendet feste Dunkelfarben und zahlreiche einzeln definierte
Farben, Abstände und gerundete Flächen. Große Teile der Navigation, des
Zustands und der Darstellung liegen in `MainActivity.kt`; die Einstellungen
bilden eine lange Seite. Texte stehen überwiegend direkt im Kotlin-Code.
Listen werden oft vollständig in einer Scrollspalte aufgebaut. Previews,
Edge-to-Edge und zustandsbewusste Flow-Collection sind noch nicht durchgehend
vorhanden. TalkBack, 200 % Schrift und größere Fenster benötigen gezielte
Prüfung. Einige Bedienelemente haben bereits Semantik und 48-dp-Touchziele.

Die Studio-Anmeldung ist nicht dauerhaft; Sprachfolge, Live-Ton zum Prompter,
Live-Originalupload und Kameraaufnahme über das Webdashboard sind nicht
produktiv freigegeben. Die aktuelle Verbindung nutzt Gerätesuche und manuelle
Codeeingabe. Lokale Aufnahmen und Skript-/Uploaddaten müssen erhalten bleiben.
Ein laufender manueller Upload kann beim Verlassen seiner Seite abbrechen.

## Designrichtung

- **Nutzung:** Medienproduktion am Stativ und vor dem Teleprompter, oft auf
  zwei Smartphones, mit gut erreichbaren Aktionen und klaren Zuständen.
- **Tonalität:** ruhig, präzise und kontrastreich; redaktionell statt verspielt.
- **Blickfang:** eindeutiger Aufnahmezustand mit Hauptaktion und eine gut
  lesbare Prompterfläche; übrige Informationen bleiben zurückhaltend.
- **Farben:** das bestehende Korallrot als Markenakzent auf neutralen Flächen;
  helle und dunkle Schemen, dynamische Systemfarben nur mit gutem Kontrast.
- **Typografie:** prägnante Überschriften und eine robuste, bei großer
  Systemschrift lesbare Fließ- und Bedienungsschrift.

## Technik und Reihenfolge

Es gibt aktuell keinen Gradle-Version-Catalog. `app/build.gradle.kts` verwendet
Compose-BOM `2025.08.00`; deren lokales BOM löst Material 3 `1.3.2` auf.
Expressive-APIs werden nur verwendet, wenn diese tatsächlich vorhanden sind;
andernfalls gelten die verfügbaren Standard-Material-3-Komponenten. Gradle,
AGP und Kotlin bleiben unverändert, solange eine benötigte API dies nicht
zwingend erfordert.

Zuerst folgen Theme und zentrale Gestaltungstokens, dann Navigation und Header,
danach werden die Seiten einzeln überarbeitet. Ein Menüwechsel darf keine
Aufnahme oder keinen Upload still abbrechen. Pro Schritt: relevante Previews,
Build, Lint und vorhandene Tests; zusätzlich Sichtprüfung auf verfügbaren
Geräten. Einschränkungen und nicht verifizierte Zustände werden dokumentiert.
