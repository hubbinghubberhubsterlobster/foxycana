# Foxycana 🦊

Lorcana-Sammler-App für Android: freihändig Karten scannen (Deutsch & Englisch), Sammlung mit
Cardmarket-Preisen, Sets, Decks und Fertigdecks.

## APK herunterladen

Jeder Push auf `main` baut automatisch eine neue APK (GitHub Actions → „APK bauen“) und
veröffentlicht sie unter **Releases**: `Releases → neueste Version → Foxycana.apk`.
Neue Versionen installieren sich als Update, die Daten bleiben erhalten.

## So funktioniert's

- **Scanner:** Handy in den Ständer legen, Scan-Knopf drücken, Karte drunter schieben. Die App
  liest Name, Untertitel und die Sammlernummer (z. B. `12/204 • DE • 3`), erkennt die Karte,
  piept und wartet auf die nächste. Kein Tippen nötig.
- **Preise:** täglicher Cardmarket-Preisguide (Trend, Ab, 30-Tage-Schnitt, Foil) in Euro,
  umrechenbar in andere Währungen (EZB-Kurse).
- **Fertigdecks:** „Decks → Fertigdeck gekauft“ fügt alle Karten auf einmal hinzu.
- **Import/Export:** CSV (Lorcery, Dreamborn …), Backup als JSON in `Download/Foxycana`.

## Aufbau

- `app/src/main/java/de/foxycana/app/MainActivity.java` – WebView-Hülle, Texterkennung (ML Kit),
  Netz-Proxy, Kamera, Dateien
- `app/src/main/assets/www/` – die App-Oberfläche (`app.js`), Datenlogik (`data.js`),
  Kartenerkennung (`engine.js`)

Datenquellen: [LorcanaJSON](https://lorcanajson.org), Cardmarket-Preisguide, EZB.
Inoffizielle Fan-App, nicht verbunden mit Disney oder Ravensburger.
