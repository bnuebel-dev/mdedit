# MdEdit

Android-App zum Öffnen, Anzeigen und Bearbeiten von Markdown-Dateien. Quellen: lokale Ordner (Storage Access Framework) und pCloud (direkt per API). Kotlin, Jetpack Compose, ohne Fremdserver.

## Installation (wie bei Fit)

1. Repo auf GitHub anlegen und den Inhalt auf `main` pushen. **Öffentlich** anlegen, wenn du die APK direkt auf dem Handy herunterladen willst: Releases privater Repos lassen sich nur eingeloggt laden. Im Code stehen keine persönlichen Daten.
2. Der Workflow **Android-App** (`.github/workflows/build.yml`) baut bei jedem Push auf `main` die APK und veröffentlicht sie als Release **android-latest**. Manuell startest du ihn unter *Actions → Android-App → Run workflow*.
3. Auf dem Handy `https://github.com/<benutzer>/<repo>/releases/tag/android-latest` öffnen, `mdedit.apk` laden und installieren („Installation aus dieser Quelle zulassen“ bestätigen).
4. Spätere Builds installierst du einfach drüber. Die Versionsnummer zählt automatisch hoch (`1.0.<Build>`), die Signatur bleibt gleich, deine Einstellungen bleiben erhalten.

Auf anderen Branches entsteht nur ein Build-Artefakt (*Actions → Lauf → Artifacts*), ohne Release.

## Datenschutz

- Keine Server Dritter, keine Analytics, keine Werbung. Die einzige Netzwerkverbindung geht per HTTPS an `eapi.pcloud.com` (EU) bzw. `api.pcloud.com` (US).
- Das pCloud-Passwort wird nur für die Anmeldung gesendet und nicht gespeichert. Abgelegt wird ein Auth-Token, verschlüsselt im Android Keystore. Ist der sichere Speicher auf einem Gerät nicht verfügbar, verweigert die App die Anmeldung, statt das Token im Klartext abzulegen.
- „Abmelden“ macht das Token auch bei pCloud ungültig.
- `allowBackup=false`: Token und Einstellungen landen nicht in Android-Backups.
- GitHub sieht nur den Quellcode, nie deine Dateien.

## Benutzung

- **pCloud**: Startseite → „Bei pCloud anmelden“. Region wählen (Schweiz/Europa = EU). Bei aktivem 2FA den Code eintragen.
- **Lokaler Ordner**: „Lokalen Ordner hinzufügen“ und den Ordner mit deinen .md-Dateien auswählen. Der Zugriff bleibt dauerhaft erhalten. Android erlaubt keine Auswahl der Speicher-Wurzel oder des Download-Ordners selbst, ein Unterordner geht.
- **Einzelne Datei**: „Einzelne Datei öffnen“, oder .md-Datei im Dateimanager „Öffnen mit → MdEdit“.
- **Editor**: Oben „Vorschau“ / „Bearbeiten“ umschalten, „Speichern“ schreibt zurück (lokal oder nach pCloud, die Datei wird überschrieben). Beim Verlassen mit ungespeicherten Änderungen fragt die App nach.
- „Neu“ in der Ordneransicht legt eine neue .md-Datei an.

## Hinweise

- **Signatur:** Die APK ist mit dem mitgelieferten Schlüssel `app/debug.keystore` signiert, damit Updates über die bestehende Installation möglich sind. Das ist für privates Sideloading gedacht. In einem öffentlichen Repo kann jeder mit demselben Schlüssel signieren, das ist nur relevant, wenn du fremde APKs installierst. Für eine Veröffentlichung im Play Store brauchst du einen eigenen Schlüssel.
- **pCloud-Token:** Nach längerer Nichtnutzung kann pCloud das Token ablaufen lassen. Dann einmal abmelden und neu anmelden.
- **Parallele Bearbeitung:** Es gibt keinen Abgleich. Wird dieselbe Datei am PC und im Handy bearbeitet, gewinnt, wer zuletzt speichert.
- Angezeigt werden Dateien mit der Endung .md, .markdown und .txt.

## Entwicklung

Toolchain wie im Fit-Projekt: Gradle 8.14.3 (Wrapper), Android Gradle Plugin 8.13.0, compileSdk 36, JDK 21. Lokal: `./gradlew assembleRelease`, die APK liegt dann unter `app/build/outputs/apk/release/`.
