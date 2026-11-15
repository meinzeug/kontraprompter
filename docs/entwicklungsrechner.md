# Entwicklungsrechner: Android und Speicher

Auf dem Entwicklungsrechner liegt das Android-SDK dauerhaft unter
`/home/dennis/Android/Sdk`. Der lokale Android-Build wird mit begrenztem
Gradle-Heap und einem Worker ausgeführt; größere vollständige Prüfungen können
über einen getrennten CI-Zweig laufen.

Am 15. November 2026 war die vorhandene 4-GiB-Auslagerungsdatei nahezu voll,
obwohl noch verfügbarer Arbeitsspeicher gemeldet wurde. Ergänzend wurde das
Ubuntu-Paket `systemd-zram-generator` installiert. Dessen mitgelieferte
Konfiguration richtet beim Systemstart `/dev/zram0` als komprimierten
Auslagerungsbereich ein. Auf diesem Rechner meldet es 2,6 GiB logische Größe
und die Priorität 100; `/swap.img` bleibt mit 4 GiB und niedrigerer Priorität
aktiv. Zram belegt physischen RAM abhängig vom tatsächlich komprimierten Inhalt,
nicht unmittelbar seine gesamte logische Größe.

Nach der Einrichtung waren beide Swap-Bereiche aktiv und ungefähr 2,6 GiB
Auslagerungsplatz frei. Die bereits in der Datei ausgelagerten Seiten wurden
nicht zwangsweise zurückgeladen; ein Neustart wurde für diese Prüfung nicht
ausgeführt. Die tatsächlich verfügbare Reserve hängt von Speicherlast und
Komprimierbarkeit ab. `swapon --show`, `zramctl` und `free -h` zeigen den
jeweiligen aktuellen Zustand.
