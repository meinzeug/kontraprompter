# Sprachfolge: synthetische Test-Fixture

Dieser isolierte WIP verbindet nur im Testquelltext den vorhandenen PCM-Tee,
Wortabgleich und Cursor mit künstlichen Erkennungsergebnissen und einer
simulierten Positionszustellung. Er aktiviert weder Mikrofon noch Recorder,
Netzwerk, Telefone oder einen echten Spracherkenner. Das Testskript lautet
`bash scripts/test-speech-pcm-fixture.sh`; es nutzt lokal vorhandene Kotlin-/
JUnit-Abhängigkeiten und nur temporäre JVM-Klassen.

Die Tests untersuchen finale und verspätete Ergebnisse, manuelles Neusetzen,
Pause, eine erkannte Lücke im Samplezähler, gesättigte Analysewarteschlangen
und falsche oder wiederholte Positionsidentitäten. Die Fixture enthält keine
Stimmerkennung oder Stilleklassifikation; fehlende Samples sind keine Prüfung
echter Stille. Der Testempfänger ist kein authentisierter Transport. Auch die
Recorder-Unabhängigkeit wird nur an unveränderten Testbytes und begrenztem
Tee-Verhalten untersucht, nicht am Android-Audio-/Codec-Lifecycle.

Vor Integration fehlen u. a. ein geprüfter Offline-Erkenner mit eigenem
PCM-Eingang, echte Sprach- und Latenztests, ein verbindlich synchronisiertes
Skript auf beiden Geräten, verschlüsselte Sitzungstransporte bzw. sichere
Positionsbindung, Geräte-/Recorder-Tests, Unterbrechungs- und Datenschutz-
Verhalten. Der bestehende manuelle Prompter bleibt unverändert.
