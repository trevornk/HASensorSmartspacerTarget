# HA Sensor Smartspacer Target

🇬🇧 **English version:** [README.en.md](README.en.md) · 🇩🇪 Deutsch (diese Datei)

Smartspacer-Plugin, das einen Home-Assistant-Sensorwert per REST-API abruft und als
Smartspacer-Target (z. B. auf dem Homescreen / Sperrbildschirm) anzeigt.

## Einrichtung

### 1. Werte in Home Assistant besorgen

Du brauchst drei Angaben:

| Feld | Woher? | Beispiel |
|---|---|---|
| **Home Assistant Base-URL** | Die Adresse, unter der du Home Assistant im Browser öffnest — ohne Pfad, ohne abschließenden `/` | `http://homeassistant.local:8123` oder `https://ha.example.de` |
| **Long-Lived Access Token** | In Home Assistant: unten links auf deinen **Benutzernamen** klicken → Tab **Sicherheit** → ganz unten **Langlebige Zugangstoken** → **Token erstellen**. Der Token wird nur **einmal** angezeigt — direkt kopieren! | `eyJhbGciOiJIUzI1...` (sehr lange Zeichenkette) |
| **Entity-ID** | In Home Assistant: **Entwicklerwerkzeuge → Zustände**, dort den Sensor suchen. Die Entity-ID steht in der ersten Spalte. | `sensor.aussentemperatur` |

> **Tipp:** Ob URL, Token und Entity-ID stimmen, kannst du vorab im Browser/Terminal testen:
> ```
> curl -H "Authorization: Bearer DEIN_TOKEN" http://homeassistant.local:8123/api/states/sensor.aussentemperatur
> ```
> Kommt JSON mit `"state": "..."` zurück, passt alles. Alternativ in der Einrichtung den
> Button **„Verbindung testen"** nutzen.

### 2. Target in Smartspacer hinzufügen und Werte eintragen

1. **Smartspacer-App** öffnen
2. Zu **Targets** wechseln → **+** (Target hinzufügen)
3. In der Liste **„Home Assistant Sensor"** auswählen
4. Es öffnet sich automatisch die **Einrichtungsseite dieses Plugins** — hier die drei Werte eintragen:
   - **Home Assistant Base-URL**
   - **Long-Lived Access Token**
   - **Entity-ID**
5. Optional **„Verbindung testen"** tippen (zeigt sofort den Sensorwert oder den Fehler), dann **Speichern**

Direkt nach dem Speichern wird der erste Wert im Hintergrund abgerufen; bis dahin zeigt das
Target kurz „Lädt …". Danach aktualisiert sich der Wert automatisch etwa alle **15 Minuten** —
auch wenn das Handy länger ruht (Doze) und nach einem Neustart.
Als Symbol wird das Icon des Sensors aus Home Assistant übernommen (aus `icon` bzw.
`device_class`), sonst ein Haus-Symbol.

### 3. Target antippen und Werte ändern

- **Auf das Target tippen** (Homescreen/Sperrbildschirm) öffnet den **Verlauf dieses Sensors in
  der Home-Assistant-App** (ist die App nicht installiert, wird die Weboberfläche im Browser
  geöffnet).
- Zum **Ändern** der Werte in der Smartspacer-App unter **Targets** das „Home Assistant Sensor"-
  Target öffnen und dessen **Einstellungen** aufrufen — die Felder sind mit den gespeicherten
  Werten vorausgefüllt.

### Nur bei bestimmten Zuständen anzeigen (optional)

Manche Sensoren haben **immer** einen Zustand — eine Müllabfuhr-Entität etwa wechselt zwischen
„Bioabfall Heute" und „Bioabfall in 7 tagen". Ohne Filter stünde das Target dauerhaft im
Smartspace, obwohl es nur am Abholtag interessiert.

Dafür gibt es auf der Einrichtungsseite das optionale Feld **„Nur anzeigen, wenn der Zustand
enthält"**:

- Mehrere Begriffe mit **Komma** trennen — es genügt, wenn **einer** davon im Zustand vorkommt.
- Groß-/Kleinschreibung ist egal, Teiltreffer reichen (`Heute` passt auf „Bioabfall Heute").
- **Leer lassen** = immer anzeigen (Verhalten wie bisher).

Beispiel: `Heute, Morgen` blendet das Abfall-Target nur am Abholtag und am Tag davor ein — sonst
verschwindet es komplett aus dem Smartspace. Solange noch kein Wert abgerufen wurde, bleibt das
Target bei gesetztem Filter ebenfalls ausgeblendet (kein „Lädt …").

### Mehrere Sensoren

Das Target kann **mehrfach hinzugefügt** werden — jede Instanz hat ihre eigene URL, ihren
eigenen Token und ihre eigene Entity-ID. Einfach in Smartspacer ein weiteres
„Home Assistant Sensor"-Target anlegen.

## Bedingung: nur anzeigen, wenn du zuhause bist (Anwesenheit)

Zusätzlich zum Sensor-Target bringt das Plugin eine **Bedingung** („Requirement") mit. Damit kannst
du in Smartspacer beliebige Targets/Complications davon abhängig machen, ob du **zuhause** bist.

### Einrichten

1. In Smartspacer beim gewünschten Target/Complication die **Bedingungen** („Requirements")
   öffnen und eine hinzufügen.
2. In der Liste **„Home Assistant: Zuhause"** auswählen.
3. Base-URL, Token und die **Anwesenheits-Entity** eintragen. Das ist die Entität, die in Home
   Assistant deine Anwesenheit abbildet — meist `person.…` oder `device_tracker.…`, mit Zustand
   `home` / `not_home`.
4. Optional **„Verbindung testen"** tippen, dann **Speichern**.

Die Bedingung ist **erfüllt, wenn der Zustand `home` ist**. In Smartspacer lässt sie sich
**invertieren**, um „nur wenn ich **unterwegs** bin" abzubilden.

> **Hinweis zur Aktualität:** Smartspacer wertet Bedingungen von sich aus nur aus, wenn die
> Smartspace sichtbar wird oder ein zugehöriges Target aktualisiert — nicht in festem Takt. Damit
> „zuhause/unterwegs" trotzdem von allein umschaltet, frischt das Plugin die Anwesenheit **selbst
> etwa alle 15 Minuten** auf und meldet Smartspacer jede Änderung aktiv. Das funktioniert auch,
> wenn das Handy längere Zeit ruht (Doze): Der Weckruf läuft über einen Alarm, der im Ruhezustand
> feuern darf, und der Abruf bekommt dabei ein kurzes Netzwerkfenster. Zusätzlich wird beim
> Draufschauen nachgeladen, wenn der Wert älter als **2 Minuten** ist.
>
> Ein Wechsel wird damit **innerhalb weniger Minuten** erkannt — nicht sekundengenau. Live-Tracking
> ginge nur mit einem dauerhaft laufenden Dienst samt fester Benachrichtigung; das ist hier
> bewusst nicht eingebaut, um Akku und Nerven zu schonen.

## Hinweise

- Das Target lässt sich **nicht wegwischen**; entfernen geht nur über die
  Smartspacer-Einstellungen (dabei werden die gespeicherten Werte inkl. Token gelöscht).
- Bei Netzwerkfehlern bleibt der **letzte bekannte Wert** stehen — es wird nichts geleert.
  Ein `unavailable`/`unknown`-Zustand wird als „Nicht verfügbar" angezeigt.
- Der Access Token wird in **EncryptedSharedPreferences** (androidx.security) verschlüsselt
  im Android-Keystore abgelegt.
- Lokale Adressen (`http://…`) werden unterstützt; für externen Zugriff (z. B. Nabu Casa)
  immer `https` verwenden.
- Der Token sollte in Home Assistant einem Benutzer mit möglichst wenig Rechten gehören,
  wenn du auf Nummer sicher gehen willst.
- Nach einem **Neustart** oder App-Update frischt das Plugin alle Werte von selbst auf; dafür
  braucht es die Berechtigung, beim Systemstart zu starten.
- **Fehlersuche:** Das Plugin protokolliert die letzten 300 Abrufe (Zeitpunkt, Auslöser, Ergebnis
  bzw. Fehler — ohne URL und Token) in einer Datei, abrufbar per
  `adb pull /sdcard/Android/data/com.noirdraco.hasensorsmartspacertarget/files/refresh_log.txt`.

## Build

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Voraussetzung: [Smartspacer](https://github.com/KieronQuinn/Smartspacer) ist auf dem Gerät
installiert.
