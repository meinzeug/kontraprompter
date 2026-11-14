# KontraPrompter mit dem Desview T3

Diese Anleitung beschreibt den vorgesehenen Aufbau mit **Xiaomi als Kamera** und **Samsung als Teleprompter**. Die mechanische Montage und Spiegelrichtung müssen am eigenen Gerät geprüft werden; der hier beschriebene physische T3-Aufbau wurde noch nicht vor Ort getestet. Funktionen, die noch in Entwicklung sind, stehen am Ende.

## Vorbereitung

- Beide Smartphones laden und dieselbe aktuelle KontraPrompter-Version installieren. Das Studio-Dashboard stellt die App zum Download bereit.
- Beide Geräte im selben lokalen WLAN anmelden. Sie müssen sich dort direkt erreichen können. Manche Gäste-WLANs und Router mit Client-Isolation verhindern das, obwohl beide Geräte Internetzugang haben.
- Auf dem Xiaomi Kamera- und Mikrofonfreigabe für KontraPrompter prüfen. Auf dem Samsung die Kamera für den QR-Scanner freigeben. USB-Debugging ist für den normalen Betrieb nicht nötig.
- Für Studio-Skripte und Cloud-Upload ist Internet sowie eine Videomaker-Anmeldung nötig. Die lokale Kameraverbindung nutzt zurzeit keinen Server-Fallback.
- T3 und Stativ entsprechend der beiliegenden Anleitung aufbauen. Die Herstellerseite nennt die mitgelieferte Smartphone-Halterung und die Möglichkeit für Hoch- und Querformat; je nach Ausführung können Zubehör und Klemmen abweichen. [Desview T3](https://desview.com/collections/teleprompter/products/desview-t3-teleprompter-for-smartphone-tablet-dslr-camera-with-remote-control-lens-adapter)

## Beide Telefone verbinden

Es ist einfacher, den QR-Code vor dem endgültigen Einspannen zu scannen.

1. Auf dem **Xiaomi** KontraPrompter öffnen. Wenn die Rückkamera verwendet werden soll, „Frontkamera verwenden“ vor dem Start ausschalten und „Kamera“ wählen. Die Kameraerlaubnis erteilen.
2. Auf dem **Samsung** „Teleprompter“ und anschließend „Kamera verbinden“ wählen. Den QR-Scanner öffnen und auf den angezeigten Code des Xiaomi richten. Für den Scanner die Kameraerlaubnis auf dem Samsung erteilen.
3. Die angezeigte Gegenstelle auf beiden Geräten prüfen und bestätigen. Auf dem Samsung „Zu Skripten und Prompter“ wählen. Ist der Code abgelaufen, die Verbindung neu beginnen.
4. Wenn der QR-Scan nicht funktioniert, können auf dem Samsung die auf dem Xiaomi angezeigte lokale IP-Adresse, der Port und der Sitzungscode von Hand eingegeben werden.

Der QR-Code enthält einen kurzlebigen Zugangscode. Er ist für die Verbindung bestimmt und sollte nicht veröffentlicht werden. Der derzeitige lokale Steuerkanal ist authentifiziert, aber nicht verschlüsselt; verwende dafür ein vertrauenswürdiges lokales Netzwerk.

## Desview T3 einrichten

1. Das **Xiaomi hinter dem Spiegelglas** mit der vorgesehenen Halterung befestigen. Das ausgewählte Kameraobjektiv soll durch das Glas filmen; Klemme und Rand dürfen das Objektiv nicht verdecken.
2. Das **Samsung in der unteren Displayhalterung** befestigen, sodass sein Bildschirm zum Spiegelglas zeigt. Die Befestigung passend zur tatsächlichen Geräte- und Halterungsgröße einstellen, ohne Tasten oder Kabel einzuklemmen.
3. Den Aufbau gegen Verrutschen sichern. Kamerabild prüfen: Gesicht und Bildausschnitt passend ausrichten. Wenn ein Rahmen sichtbar ist, Position und Bildausschnitt verändern.
4. Auf dem Samsung ein Skript öffnen und im Editor **Spiegelung kalibrieren** wählen. Den Text **im eingebauten Glas** ansehen: Bei Bedarf Text horizontal oder vertikal spiegeln oder in 90°-Schritten drehen, bis er lesbar und richtig herum ist. Schriftgröße, Breite, Helligkeit und Tempo anschließend anpassen.
5. Textspiegelung und Videospiegelung sind **getrennte Einstellungen**. Eine Änderung am Text verändert das Kamerabild nicht automatisch. Mit der Vorschau und einer kurzen Probe prüfen, ob das Ergebnis für dich passt.

Das Glas spiegelt den Text zum Sprecher und die Kamera filmt durch das Glas. Desview empfiehlt, das Objektiv passend auszurichten und eine Probeaufnahme zu prüfen. [Desview FAQ](https://desview.com/pages/faq)

## Skript öffnen und lesen

Auf dem Samsung unter **Teleprompter → Skripte aus Videomaker öffnen** anmelden und ein vorhandenes Skript auswählen. Die App öffnet eine lokale Kopie; Bearbeitungen werden nicht automatisch ins Studio zurückgeschrieben. Alternativ lässt sich ein lokales Skript erstellen oder eine TXT-/Markdown-Datei importieren.

Im Editor **Teleprompter öffnen** wählen. **Start/Pause** steuert den Textlauf, **Anfang** setzt die Position zurück. Unter **Einstellungen** sind unter anderem Schriftgröße, Tempo, Textbreite und Spiegelung einstellbar. Die Studio-Anmeldung wird nicht dauerhaft gespeichert und kann nach App-Neustart oder Ablauf erneut nötig sein.

Das Videomaker-Dashboard kann den Prompter nach Anmeldung und ausdrücklicher Aktivierung fernsteuern. Die Aufnahme- und Kamerasteuerung über das Dashboard ist derzeit noch nicht produktiv freigegeben.

## Video mit Ton aufnehmen

1. Kontrollieren, dass die beiden Geräte verbunden sind, das Xiaomi-Kamerabild verfügbar ist und die Mikrofonfreigabe auf dem Xiaomi erteilt wurde.
2. Auf dem Samsung im geöffneten Prompter **Aufnahme starten** betätigen. Der Status am Xiaomi zeigt an, ob die Aufnahme läuft.
3. Mit der Aufnahmetaste pausieren oder fortsetzen. **Stopp** beendet die Aufnahme; warten, bis das Xiaomi das lokale Speichern bestätigt.
4. Die fertige Probe auf Bild, Ton, Ausrichtung und Lesbarkeit im Glas prüfen. Die Kamera kann während einer laufenden Aufnahme nicht umgeschaltet werden.

Die App muss für die Kamerasitzung im Vordergrund bleiben. Verliert die Kamera während einer bereits laufenden Aufnahme die Verbindung zum Prompter, wird die Aufnahme zunächst lokal fortgesetzt. Die spätere Bedienung und der Speicherstatus müssen am Xiaomi geprüft werden.

Optional startet und pausiert die Einstellung **Aufnahme und Teleprompter synchron** den Text mit der bestätigten Aufnahme. Sie bedeutet keine automatische Spracherkennung.

## ITFIT und andere Auslöser

Den Bluetooth-Auslöser in Android mit dem **Samsung** koppeln. Der ITFIT Selfie P007 wird erkannt. Andere externe Auslöser mit Lautstärketasten können unter **Einstellungen → Bluetooth-Auslöser** ausdrücklich ausgewählt werden; die Liste lässt sich dort aktualisieren. Externe Auslöser werden ab Android 10 unterstützt.

Im verbundenen Prompter kann eine unterstützte Auslösertaste die Aufnahme starten, pausieren oder fortsetzen. Für das endgültige Beenden **Stopp** in der App verwenden. Eingebaute Lautstärketasten des Smartphones werden nicht als Auslöser umgedeutet. Da Fernbedienungen verschiedene Tasten senden können, den eigenen Auslöser vor einer wichtigen Aufnahme ausprobieren.

## Speichern und Upload

Die Aufnahme liegt nach dem Stoppen zunächst im privaten App-Speicher des **Xiaomi**. Unter **Aufnahmen** kann ein geprüftes Video in einen selbst gewählten Android-Ordner exportiert werden. Dort kann es auch nach Studio-Anmeldung und ausdrücklicher Skriptauswahl manuell zu Videomaker hochgeladen werden. Das lokale Original bleibt erhalten.

Für geeignete Fernaufnahmen prüft die App bei aktiver Studio-Anmeldung und geöffneter App den automatischen Upload. Der Status auf dem Kameragerät zeigt Fortschritt, einen bestätigten Upload oder eine nötige erneute Freigabe. Bei lokalem Skript kann ein neutrales Aufnahmeprojekt verwendet werden. Nicht geprüfte oder unvollständige Dateien können separat exportiert werden und sind nicht automatisch als fertiger Upload ausgewiesen.

Bei Deinstallation der App können nicht exportierte lokale Aufnahmen verloren gehen. Einen bestätigten Cloud-Upload oder Export prüfen, bevor die App entfernt wird.

## Wenn etwas nicht funktioniert

- **QR-Code lässt sich nicht scannen:** Samsung-Kameraerlaubnis prüfen, Code vollständig und scharf ins Bild halten. Beide Geräte müssen denselben lokalen Netzwerkpfad nutzen; alternativ Adresse, Port und Code manuell eingeben.
- **Beide im WLAN, aber kein Livebild:** Router-/Gastnetz-Isolation kann die direkte Geräteverbindung sperren. Ein gemeinsamer Internetzugang allein genügt nicht. Ein Server-Fallback wird noch entwickelt.
- **Text im Glas falsch herum:** Auf dem Samsung **Spiegelung kalibrieren** öffnen und horizontal/vertikal getrennt testen. Maßgeblich ist die Lesbarkeit im eingebauten Glas.
- **Aufnahme startet nicht:** Kamera- und insbesondere Mikrofonfreigabe auf dem Xiaomi, Kamerastatus und freien Speicher prüfen. Die App muss für die Kamerasitzung geöffnet bleiben.
- **Kein Live-Ton am Samsung:** Der Ton wird in der Datei auf dem Xiaomi aufgenommen, aber noch nicht live zum Prompter übertragen. Nach dem Speichern die Tonspur im Video prüfen.
- **Auslöser reagiert nicht:** Bluetooth-Kopplung auf dem Samsung, Batteriestand und Auswahl unter **Bluetooth-Auslöser** prüfen. Die Aufnahmebedienung ist nur im verbundenen Prompter aktiv.
- **Upload wartet:** Internet und Studio-Anmeldung am Xiaomi prüfen und den angezeigten Uploadstatus beachten. Bei Bedarf die einzelne Aufnahme erneut freigeben oder manuell exportieren. Das Original bleibt lokal.

## Noch nicht verfügbar

Dauerhafte automatische Gerätekopplung, Live-Tonvorschau, sprachgesteuertes Folgen des Textes, Upload während laufender Aufnahme und ein produktiver TURN-/Server-Fallback sind in Entwicklung. Dieser Text beschreibt sie nicht als bereits nutzbare Funktionen.
