# Rohbildaufnahme: nicht freigegebener Teststand

Dieser Branch fordert als Testkandidat 1280 × 720 bei 20 fps an. Der Recorder erhält Frames direkt aus dem Kamera-Callback vor der adaptiven WebRTC-Quelle; die Vorschau bleibt am bisherigen Track. Die tatsächliche Capturegröße wird vom Gerät gewählt, und die H.264-Größe folgt der tatsächlichen Frame-Rotation. Weder 720p noch eine bestimmte Bildrate sind damit garantiert.

Der Kamera-Callback besitzt den Frame nur für die synchrone Zustellung. Der bestehende Recorder gibt einen zusätzlich referenzierten Frame an `EglRenderer`; dieser verwaltet seinen ausstehenden Frame selbst. Der Verteiler kopiert oder behält keine Frame-Referenz. Beim Abhängen wartet er auf bereits laufende Recorder-Callbacks, ohne seinen Monitor während der Callback-Ausführung zu halten. Neue Recorder können währenddessen nicht angehängt werden. Der WebRTC-Observer erhält den Frame auch dann, wenn der Recorder eine Exception meldet.

Grenzen vor Freigabe:

- Der Recorder-Callback läuft auf dem Kamera-Thread. Ein blockierter Recorder-/EGL-Callback kann daher auch die Vorschau verzögern; `detach` wartet in diesem Fall absichtlich und hält das Ressourcen-Gate geschlossen. Ein begrenzter Timeout mit sicherer verzögerter Ressourcenfreigabe ist noch nicht implementiert.
- `EglRenderer` kann wartende Frames ersetzen; Aufnahmevollständigkeit, tatsächlich kodierte fps und A/V-Synchronität müssen gemessen werden. Textur-Frames können die nächste Kameraausgabe bis zur Freigabe zurückhalten.
- Xiaomi als Kamera und Samsung als Prompter: tatsächliche Roh-/Encodergrößen, Vorder-/Rückkamera, 90°/270°-Rotation, Spiegelung, Kanten und Ton im MP4 untersuchen. Gleichzeitig Vorschau und Aufnahme, Pause/Fortsetzen, Peer-Abbruch während laufender Aufnahme sowie Hintergrund/Stopp und erneutes Koppeln prüfen.
- Längere Tests müssen Droprate, Wärme, Speichergrenze, 30-Minuten-Grenze, die konkurrierenden Hardwareencoder und Cloudbeleg/Original-SHA abdecken. Aufnahme- und Uploadpfad dürfen bei Qualitätsfehlern kein Original löschen.
- Nur isolierter WIP, keine Freigabe, keine Produktions-APK und keine Behauptung eines erfolgreichen Gerätetests.
