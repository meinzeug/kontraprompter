package de.kontrakurs.kontraprompter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Help describes only the currently available setup; open sections do not interrupt an active session. */
@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    AppPage(
        title = "Hilfe & Aufbau",
        eyebrow = "DESVIEW T3",
        description = "Xiaomi als Kamera, Samsung als Teleprompter – vom Aufbau bis zur fertigen Aufnahme.",
        onBack = onBack,
    ) {
        AppPanel {
            Text("Schnellstart", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            HelpParagraph("1. Beide Handys mit demselben WLAN verbinden und KontraPrompter öffnen.")
            HelpParagraph("2. Auf dem Xiaomi „Kamera“, auf dem Samsung „Teleprompter“ wählen. Den QR-Code der Kamera mit dem Samsung scannen und auf beiden Geräten bestätigen.")
            HelpParagraph("3. Handys am T3 befestigen, Skript öffnen und Spiegelung am Glas prüfen. Eine kurze Probe mit Bild und Ton aufnehmen.")
        }
        HelpSection("Desview T3 aufbauen") {
            HelpParagraph("Xiaomi als Kamera hinter dem Spiegelglas befestigen. Das ausgewählte Objektiv soll mittig durch das Glas filmen und nicht von der Halterung verdeckt werden.")
            HelpParagraph("Samsung mit dem Bildschirm zum Spiegelglas in die untere Halterung setzen. Beide Geräte sicher befestigen. Mit einer kurzen Probe prüfen, ob kein Rahmen und keine Spiegelung im Bild zu sehen ist.")
            HelpParagraph("Auf dem Samsung im Skript „Spiegelung kalibrieren“ öffnen. Bei Bedarf Text horizontal oder vertikal spiegeln oder drehen, bis er im Glas richtig lesbar ist. Die Video-Spiegelung ist getrennt einstellbar.")
        }
        HelpSection("Kamera verbinden") {
            HelpParagraph("Auf dem Xiaomi „Kamera“ öffnen und die Kameraerlaubnis erteilen. Der QR-Code enthält einen kurzlebigen Sitzungscode: Behandle ihn wie ein Passwort.")
            HelpParagraph("Auf dem Samsung unter „Teleprompter“ → „Kamera verbinden“ den QR-Scanner starten und den Code auf dem Xiaomi scannen. Die angezeigte Gegenstelle auf beiden Geräten bestätigen. Danach „Zu Skripten und Prompter“ wählen.")
            HelpParagraph("Falls der Scan nicht klappt, lassen sich Kameraadresse, Port und Sitzungscode auch von Hand eingeben. Beide Geräte müssen sich im lokalen Netz direkt erreichen können; ein gemeinsames WLAN allein reicht nicht immer aus.")
        }
        HelpSection("Skript und Studio") {
            HelpParagraph("Auf dem Samsung unter „Skripte aus Videomaker öffnen“ anmelden und ein Skript auswählen. Eine Kopie wird auf dem Gerät geöffnet; lokale Änderungen werden nicht ins Studio zurückgeschrieben.")
            HelpParagraph("Tempo, Schriftgröße und Spiegelung findest du in den Einstellungen. Mit „Start“ und „Pause“ steuerst du den Text; „Anfang“ setzt ihn zurück. Das Studio-Dashboard kann nach ausdrücklicher Aktivierung den Teleprompter steuern.")
            HelpParagraph("Die Studio-Anmeldung wird nicht dauerhaft gespeichert. Nach einem App-Neustart oder Sitzungsablauf kann eine erneute Anmeldung nötig sein.")
        }
        HelpSection("Aufnahme bedienen") {
            HelpParagraph("Im verbundenen Prompter startet „Aufnahme starten“ die Kameraaufnahme. Die Mikrofonfreigabe muss auf dem Xiaomi erteilt sein. Mit derselben Taste pausierst du und setzt fort; „Stopp“ beendet und speichert das Video.")
            HelpParagraph("Auf dem Xiaomi zeigt der Aufnahmestatus, ob Video und Ton laufen, pausieren oder gespeichert werden. Bei Verbindungsverlust läuft eine bereits gestartete Aufnahme weiter. Die App muss für die Kamerasitzung im Vordergrund bleiben.")
            HelpParagraph("Optional kannst du in den Einstellungen „Aufnahme und Teleprompter synchron“ aktivieren. Der Text startet oder pausiert dann mit der bestätigten Aufnahme; beim Stopp bleibt er stehen.")
        }
        HelpSection("Bluetooth-Auslöser") {
            HelpParagraph("Den Auslöser zuerst in den Android-Bluetooth-Einstellungen mit dem Samsung koppeln. Der ITFIT Selfie P007 wird unterstützt; andere externe Auslöser können in den App-Einstellungen ausgewählt werden.")
            HelpParagraph("Im verbundenen Prompter löst eine unterstützte Taste Start, Pause oder Fortsetzen der Aufnahme aus. Zum endgültigen Beenden „Stopp“ in der App verwenden. Eingebaute Lautstärketasten bleiben unverändert.")
        }
        HelpSection("Speichern und Grenzen") {
            HelpParagraph("Die fertige Aufnahme liegt zunächst auf dem Xiaomi. Unter „Aufnahmen“ kannst du sie exportieren oder nach Anmeldung einem Studio-Skript zuordnen und hochladen. Das lokale Original bleibt erhalten.")
            HelpParagraph("Für geeignete Fernaufnahmen prüft die App bei aktiver Studio-Anmeldung und geöffneter App den automatischen Upload. Beachte den angezeigten Status und gib eine Aufnahme bei Bedarf erneut frei. Während der Aufnahme findet kein Live-Upload statt.")
            HelpParagraph("Ton ist in der fertigen Aufnahme, wird aber noch nicht live zum Prompter übertragen. Sprachgesteuertes Folgen, dauerhafte automatische Kopplung und ein Server-Fallback für getrennte WLAN-Geräte sind noch nicht verfügbar. Kamera-Aufnahmebefehle über das Webdashboard sind noch nicht freigegeben.")
        }
    }
}

@Composable
private fun HelpSection(title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AppPanel {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "Aufgeklappt" else "Zugeklappt"
            },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun HelpParagraph(text: String) {
    Text(text, color = SoftText, style = MaterialTheme.typography.bodyLarge)
}
