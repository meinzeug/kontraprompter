# Vorschlag: erster externer Auslöser nach Touchbedienung

Dieser Zweig enthält einen isolierten Prototyp und ist keine freigegebene Lösung.
Nach einem Touch auf Samsung hatte der erste korrekt erzeugte ENTER-Druck keine
Wirkung, der zweite dagegen schon. Der AOSP-Inputpfad kann einen ersten
Navigations-DOWN beim Verlassen des Touchmodus für die Fokuswahl verbrauchen.
Die getrennte Diagnose auf dem Samsung zeigte den Wechsel und ein einzelnes UP
ohne ein an der Activity angekommenes DOWN. Ein isoliertes UP darf nie als
gültiger Auslöser gelten.

## Prüfbarer Ansatz

Der erste Prototyp mit unsichtbarem Fokusanker schlug auf dem Samsung fehl:
Der Compose-Root war bereits fokussiert und wurde daher nicht verdrängt. Der
hier vorbereitete neue Prototyp fordert keinen neuen Fokus an. Er prüft, ob
genau der bereits fokussierte AndroidComposeView seine Kinder bevorzugt, und
ändert nur dann vorübergehend dessen Fokuspriorität. Die ursprüngliche Priorität
wird beim Verlassen, beim Verlust der Bedingungen oder vor fremder Tastatur-
bedienung wiederhergestellt. Bestehender Fokus, Texteingabe und insbesondere
Barrierefreiheitsfunktionen dürfen dabei nicht still verdrängt werden.

Der Prototyp verdrängt keinen bereits fokussierten Android-View, deaktiviert sich
bei aktiven Accessibility-Diensten oder aktivem IME und gibt bei jedem nicht
als Auslöser erkannten Hardware-DOWN auf. Nach einer erneuten expliziten
Touchaktion wird die Bedingung nochmals geprüft. Kamera-/Medientasten anderer
externer Geräte werden nur nach expliziter Auswahl als Auslöser behandelt;
die bekannten Lautstärketasten des P007 bleiben automatisch unterstützt.
Für die ohne Auswahl erkannten P007-Lautstärketasten wird kein Fokusanker
angelegt: Sie zählen im geprüften AOSP-Code nicht zu den Navigationstasten;
der reale P007-Test auf Samsung steht noch aus.
Bei aktivierter Accessibility ist das ursprüngliche Problem weiterhin offen.
Die Änderung der Compose-Root-Fokuspriorität ist noch nicht auf Geräten geprüft.
Es ist zu klären, ob sich die Bedingungen
mit der Compose- und Android-Fokushierarchie auf dem Samsung erfüllen lassen:
Ein künstlicher Fokusanker kann sonst die Tastaturnavigation verschlechtern.
Falls sich eine aktive Benutzer- oder Accessibility-Fokuswahl nicht eindeutig
erkennen und erhalten lässt, bleibt der Ansatz gesperrt. Keine globale Änderung
der Fokusregeln oder des Touchmodus anwenden.

## Freigabetests

* Beleg des ursprünglichen DOWN-Verlusts durch den separaten Diagnosebuild.
* Nach jedem erneuten Touch genau ein korrekter kurzer externer Druck: genau
  eine erwartete Aktion; zwei schnelle Drücke: nur die erwartete Doppelaktion.
* Halten, Wiederholungen, einzelne UP-Events, Gerätewechsel und Trennung
  dürfen keine unerwartete Aufnahme starten oder fortsetzen.
* Touchschaltflächen, Editor, Bildschirmtastatur, Hardwaretastatur und
  TalkBack/Accessibility-Fokus vor und nach Öffnen/Schließen der Ansicht prüfen.
* Eingebaute Lautstärketasten und fremde, nicht gewählte Geräte bleiben normal.
* Kameraaufnahme und Ton bleiben bei unterbrochener Verbindung erhalten.
