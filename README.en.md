# HA Sensor Smartspacer Target

🇬🇧 English (this file) · 🇩🇪 **Deutsche Version:** [README.md](README.md)

Smartspacer plugin that fetches a Home Assistant sensor value via the REST API and shows it as
a Smartspacer target (e.g. on the home screen / lock screen).

## Setup

### 1. Get the values from Home Assistant

You need three things:

| Field | Where to find it | Example |
|---|---|---|
| **Home Assistant base URL** | The address you use to open Home Assistant in your browser — no path, no trailing `/` | `http://homeassistant.local:8123` or `https://ha.example.com` |
| **Long-lived access token** | In Home Assistant: click your **username** at the bottom left → **Security** tab → at the very bottom **Long-lived access tokens** → **Create token**. The token is shown **only once** — copy it right away! | `eyJhbGciOiJIUzI1...` (very long string) |
| **Entity ID** | In Home Assistant: **Developer tools → States**, find your sensor there. The entity ID is in the first column. | `sensor.outdoor_temperature` |

> **Tip:** You can check the URL, token and entity ID up front from a browser/terminal:
> ```
> curl -H "Authorization: Bearer YOUR_TOKEN" http://homeassistant.local:8123/api/states/sensor.outdoor_temperature
> ```
> If JSON with `"state": "..."` comes back, everything is correct. Alternatively use the
> **"Test connection"** button in the setup screen.

### 2. Add the target in Smartspacer and enter the values

1. Open the **Smartspacer app**
2. Go to **Targets** → **+** (add target)
3. Pick **"Home Assistant Sensor"** from the list
4. This plugin's **setup screen** opens automatically — enter the three values here:
   - **Home Assistant base URL**
   - **Long-lived access token**
   - **Entity ID**
5. Optionally tap **"Test connection"** (shows the sensor value right away, or the error), then **Save**

Right after saving, the first value is fetched in the background; until then the target briefly
shows "Loading …". After that it refreshes automatically roughly every **15 minutes** — also
while the phone rests for a long time (Doze) and after a reboot. The icon
is taken from the sensor in Home Assistant (from `icon` or `device_class`), otherwise a house icon.

### 3. Tapping the target and changing the values

- **Tapping the target** (home screen/lock screen) opens the **history of that sensor in the
  Home Assistant app** (if the app is not installed, the web UI opens in the browser instead).
- To **change** the values, open the "Home Assistant Sensor" target in the Smartspacer app under
  **Targets** and open its **settings** — the fields are pre-filled with the saved values.

### Only show for certain states (optional)

Some sensors **always** have a state — a waste collection entity, for example, alternates between
"Bioabfall Heute" and "Bioabfall in 7 tagen". Without a filter the target would sit in the
Smartspace permanently, even though it only matters on collection day.

That's what the optional **"Only show when the state contains"** field on the setup screen is for:

- Separate multiple terms with **commas** — **one** of them appearing in the state is enough.
- Case-insensitive, and partial matches count (`today` matches "Bioabfall Today").
- **Leave empty** to always show (the previous behaviour).

Example: `today, tomorrow` shows the waste target only on collection day and the day before —
otherwise it disappears from the Smartspace entirely. While no value has been fetched yet, a
target with a filter set stays hidden as well (no "Loading …").

### Multiple sensors

The target can be **added more than once** — each instance has its own URL, its own token and
its own entity ID. Just create another "Home Assistant Sensor" target in Smartspacer.

## Condition: only show when you're home (presence)

In addition to the sensor target, the plugin ships a **condition** ("requirement"). With it you can
make any Smartspacer target/complication depend on whether you are **home**.

### Setup

1. In Smartspacer, open the **requirements** of the target/complication you want and add one.
2. Pick **"Home Assistant: At home"** from the list.
3. Enter the base URL, token and the **presence entity** — the entity that represents your presence
   in Home Assistant, usually `person.…` or `device_tracker.…`, with state `home` / `not_home`.
4. Optionally tap **"Test connection"**, then **Save**.

The condition is **met when the state is `home`**. In Smartspacer it can be **inverted** to express
"only when I'm **away**".

> **Note on freshness:** On its own, Smartspacer only evaluates requirements when the Smartspace
> becomes visible or a related target refreshes — not on a fixed schedule. So that "home/away" still
> switches by itself, the plugin refreshes presence **about every 15 minutes** on its own and
> actively notifies Smartspacer of every change. This keeps working while the phone has been idle
> for a long time (Doze): the wake-up runs on an alarm that is allowed to fire in that state, and
> the fetch gets a short networking window with it. On top of that, a value older than **2 minutes**
> is refreshed when you look at it.
>
> A change is therefore picked up **within a few minutes** — not to the second. Live tracking would
> require a permanently running service with a persistent notification; that is deliberately not
> included here, to go easy on the battery and on your patience.

## Notes

- The target **cannot be swiped away**; removing it is only possible via the Smartspacer settings
  (which also deletes the saved values including the token).
- On network errors the **last known value** stays in place — nothing is cleared. An
  `unavailable`/`unknown` state is shown as "Unavailable".
- The access token is stored encrypted in **EncryptedSharedPreferences** (androidx.security),
  backed by the Android keystore.
- Local addresses (`http://…`) are supported; for external access (e.g. Nabu Casa) always use
  `https`.
- For extra safety, the token should belong to a Home Assistant user with as few permissions as
  possible.
- After a **reboot** or app update, the plugin refreshes all values on its own; this requires the
  permission to start at boot.
- **Troubleshooting:** the plugin logs the last 300 fetches (time, trigger, result or error —
  without URL and token) to a file you can pull with
  `adb pull /sdcard/Android/data/com.noirdraco.hasensorsmartspacertarget/files/refresh_log.txt`.

## Build

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirement: [Smartspacer](https://github.com/KieronQuinn/Smartspacer) must be installed on the
device.
