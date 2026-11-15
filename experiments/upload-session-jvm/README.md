# Synthetische Upload-/Sitzungs-Race-Fixture

`python3 experiments/upload-session-jvm/run.py [git-revision]` liest die Original-Quellen unverändert mit `git show` aus diesem Git-Repository und kompiliert sie mit lokal gecachten Kotlin-/Gson-/Coroutines-JARs (maximal 256 MiB Compiler-Heap). Standardrevision ist `2e69798`. Der Java-HTTPS-Handler ist vollständig im Prozess simuliert; es gibt keine Netzwerkverbindung, Geräte oder produktiven Konten. Builddateien liegen in einem eigenen temporären Verzeichnis.

Die Fixture nutzt den echten `StudioClient`, `CloudUploadCoordinator`, `UploadJournal`, `SessionCookie` und Upload-Regeln. Android-`Context`, `AtomicFile`, `Os`, Uhr, Modell und JSON-API sind Host-Stubs; Gson simuliert das benötigte JSON-Verhalten. Insbesondere beweist sie weder Androids tatsächliches `AtomicFile`-Crash-/fsync-Verhalten noch echte HTTP-/TLS-Auslieferung oder Mehrprozesssicherheit. Das künstliche Original wird vor und nach jedem Lauf geprüft und nur im eigenen temporären Fixtureverzeichnis gespeichert.

Für die Journal-Race wartet `AtomicFile.finishWrite` vor dem künstlichen Rename, während eine zweite Anmeldung startet. `session_at_confirmed_commit=original` bedeutet, dass die ursprüngliche Session beim simulierten Abschluss noch aktiv war; `changed` zeigt den kritischen Fall. Eine korrekte Sperre darf die zweite Anmeldung bis nach dem Abschluss warten lassen, ohne dass dies allein ein Fehler ist.

Basisstand `2e69798` (2026-11-15):

```text
control_409 outcome=recording_deleted journal=conflict reason=recording_deleted session_at_confirmed_commit=none original=preserved
session_during_409_body outcome=recording_deleted journal=conflict reason=recording_deleted session_at_confirmed_commit=none original=preserved
control_200 outcome=confirmed journal=confirmed reason=none session_at_confirmed_commit=original original=preserved
session_during_200_body outcome=confirmed journal=confirmed reason=none session_at_confirmed_commit=changed original=preserved
session_during_confirmed_journal outcome=confirmed journal=confirmed reason=none session_at_confirmed_commit=changed original=preserved
```

Die Kontrollfälle bestätigen den Testaufbau. Die drei Sitzungswechsel zeigen die alte Schwäche; eine neue Revision muss mit demselben unveränderten Test erneut geprüft werden. Die Fixture gibt ausschließlich synthetische Statusdaten aus, keine Cookies oder Inhalte.

Gegenlauf auf `66f45c3` mit unveränderter Fixture:

```text
control_409 outcome=recording_deleted journal=conflict reason=recording_deleted session_at_confirmed_commit=none original=preserved
session_during_409_body outcome=auth journal=uncertain reason=none session_at_confirmed_commit=none original=preserved
control_200 outcome=confirmed journal=confirmed reason=none session_at_confirmed_commit=original original=preserved
session_during_200_body outcome=auth journal=uncertain reason=none session_at_confirmed_commit=none original=preserved
session_during_confirmed_journal outcome=confirmed journal=confirmed reason=none session_at_confirmed_commit=original original=preserved
```

Damit belegen die Host-Mocks die oben genannten Wege unter einem Prozess; sie ersetzen keine instrumentierten Android- oder Gerätesystemtests.

Ein zusätzlicher Fall `journal_changed_before_receipt` verändert während des pausierten 200-Receipts ausschließlich den synthetischen Journalwert `uploadId`. Auf `66f45c3` wurde die Änderung zwar erkannt (`outcome=uncertain`), aber anschließend durch die alte Journalversion überschrieben (`journal_mutation overwritten=true`). Die lokale Videodatei bleibt erhalten. Dieses Testinterleaving simuliert einen anderen Schreiber bzw. eine Beschädigung und belegt keine solche konkurrierende Änderung auf einem Gerät.

Der nachträglich ergänzte Fall `session_during_conflict_journal` pausiert zusätzlich das Speichern eines Löschkonflikts. Auf `66f45c3` zeigte der Test `session_at_conflict_commit=original`: Der parallel gestartete Login wurde bis nach dem Abschluss serialisiert. Die zusätzliche Ausgabespalte `session_at_conflict_commit` fehlte in den oben protokollierten früheren Läufen.

Auf `bdd6cb5` blieb der geänderte Journalwert im selben synthetischen Interleaving erhalten (`journal_mutation overwritten=false`); Sessionwechsel während der 409-/200-Bodys ergaben `auth`/`uncertain`, und beide terminalen Journal-Commits waren gegenüber einem Login serialisiert. Die Implementierung vergleicht und schreibt für Aufrufe über `UploadJournal` unter einem Monitor **innerhalb desselben App-Prozesses**. Der Hosttest simuliert eine vor dem Vergleich ausgeführte externe Dateiveränderung; er belegt keine atomare Sperre gegen einen zweiten Prozess oder direkte Dateizugriffe während des Vergleichs und Schreibens.
