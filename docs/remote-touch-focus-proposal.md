# Vorschlag: erster externer Auslöser nach Touchbedienung

Dieser Zweig enthält einen isolierten Prototyp und ist keine freigegebene Lösung.
Nach einem Touch auf Samsung hatte der erste korrekt erzeugte ENTER-Druck keine
Wirkung, der zweite dagegen schon. Der AOSP-Inputpfad kann einen ersten
Navigations-DOWN beim Verlassen des Touchmodus für die Fokuswahl verbrauchen.
Die getrennte Diagnose auf dem Samsung zeigte den Wechsel und ein einzelnes UP
ohne ein an der Activity angekommenes DOWN. Ein isoliertes UP darf nie als
gültiger Auslöser gelten.

## Prüfbarer Ansatz

Ein für Touchmodus geeigneter Fokusanker könnte den ursprünglichen DOWN
erhalten, indem er bereits vor dem Tastendruck vorhanden und fokussiert ist.
Dieser Anker dürfte nur während einer geöffneten, verbundenen Prompteransicht
mit entsprechend konfiguriertem externen Eingabegerät bestehen; er darf weder
Klicks noch eigene Tastenaktionen auslösen. Nach Verlassen der Ansicht müsste
er vollständig entfernt werden. Bestehender Fokus, Texteingabe und insbesondere
Barrierefreiheitsfunktionen dürfen dabei nicht still verdrängt werden.

Der Prototyp verdrängt keinen bereits fokussierten Android-View, deaktiviert sich
bei aktiven Accessibility-Diensten oder aktivem IME und gibt bei jeder nicht
als Auslöser erkannten Hardwaretasteneingabe auf. Nach einer erneuten expliziten
Touchaktion wird die Bedingung nochmals geprüft. Kamera-/Medientasten anderer
externer Geräte werden nur nach expliziter Auswahl als Auslöser behandelt;
die bekannten Lautstärketasten des P007 bleiben automatisch unterstützt.
Bei aktivierter Accessibility ist das ursprüngliche Problem weiterhin offen.
Er ist noch nicht auf Geräten geprüft. Es ist zu klären, ob sich die Bedingungen
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
