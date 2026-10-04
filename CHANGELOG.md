# Changelog

All user-facing release notes are maintained here. The guarded release workflow publishes only the exact contents of the current Unreleased section.

## [Unreleased]

- Patch: voice announcements no longer repeat. Every announcement now goes through one central queue and the same words are never spoken twice within 20 seconds, whatever caused them. The cause of the repeats was that, with a Bluetooth device connected, every announcement was deliberately spoken again on Bluetooth right after the phone speaker. That replay is removed: each announcement is spoken once. A late announcement is possible, a repeat is not.
- Patch: the charger advice now speaks ONLY when charging is slow AND the battery temperature is rising. It stays quiet when the temperature is normal or falling, when the charger has shown it can charge fast, when the battery is at 95% or more (the phone slows charging on purpose near full), and when the level or power is not reported. "Slow charging." is no longer announced at 95% or more. The fixed 40 and 45 degree warnings are unchanged.
- Patch: the "Slow / Normal / Fast charging" change announcement now waits at least 60 seconds after the previous one, even if the charging speed changed again meanwhile, so it cannot keep repeating. The new speed is announced once the 60 seconds are over if it still differs.
- Patch: if slow charging and a rising temperature happen while the phone is plugged into a USB port, the advice now says a file transfer or a low power port may be the cause and that it may not be the charger. This is a guess from the plug type only: Android does not tell an app whether files are being copied, so the app cannot confirm a transfer. No new permission or library.

## [1.1.18]

- Journey mode for long trips: a manual switch in the Saver card that lasts up to 12 hours. While it is on and the phone is not charging, the Saver actions (lower brightness, shortest screen timeout, close background apps, clear notifications, each with its own switch) run right away instead of waiting for the battery level limit, and everything is put back when you plug in or it ends. It never starts by itself and adds no background work or new permission. Honest limit: Android only lets an app end background processes of other apps, so the saving can be small.

## [1.1.17]

- Charging temperature control, early warnings: while charging, the app now watches how fast the battery temperature is rising and speaks BEFORE the 40 and 45 degree warnings, with steps to take (close background apps, take off the case, unplug the charger). If charging is slow for 5 minutes while the phone heats up it also says the charger or cable may be faulty. Honest limit: an Android app cannot cool the phone or fix a charger, it can only warn and suggest. Nothing is said when the phone does not report temperature or power. Uses the existing thermal warning switch. No new permission or library.


## [1.1.16]

- Patch: after an in-app update installs, the downloaded installer file is now deleted automatically when the app starts, so nothing is left in storage.

## [1.1.15]

- New "Saver" (Settings, off by default). When the battery temperature reaches your limit (default 30 °C) or the level falls to your limit while not charging (default 35%), it sets brightness to 10% and the shortest screen timeout, asks Android to close background apps and clears notifications. When the battery is normal again it puts brightness and timeout back. Both limits and each action have their own switch. Music, navigation, calls, messaging, keyboard, launcher and Netra apps are never closed, and it runs at most once per 30 minutes. It shows the real free RAM before and after. Android only allows ending background processes (not Force stop), so the saving can be small. Needs "Modify system settings" for brightness/timeout and "Notification access" for clearing notifications; no new library.

## [1.1.14]

- Patch: the project moved to the Prayagi Store and Services GitHub organization. Update links, the backup update source and the privacy page link now point to the new address.

## [1.1.13]

- Patch: crash reports are now sent automatically. If the app crashed, the next time it opens it sends the report by itself (phone model, Android version, app version and code locations only). The "Send last crash report" button is gone.

## [1.1.12]

- Settings now really shows the "Check for updates" button (status line plus button). It was described in 1.1.10 but the Settings entry was missing from that build.
- New Settings switch "Share anonymous usage count" (on by default): once a day the app adds 1 to a public counter so the Netra Eco site can show approximate active users. Nothing else is sent: no ID, no location, no battery data. You can turn it off.

## [1.1.11]

- Live Power card and Health Insights: values Android does not report (voltage, current, temperature, session estimate, heat time, deep drops, failure risk, capacity health) are now hidden instead of showing "Unavailable". Removed the always-empty "Habit score" badge.

## [1.1.10]

- App updates: the update dialog now has a "Later" button, and if the download or install check fails you now see the exact reason (for example "Allow installs from this source" or a signature mismatch) instead of the dialog silently disappearing. The update stays offered so you can retry.
- App updates, more reliable: if GitHub's anonymous API limit blocks the update check, the app now falls back to a backup source instead of silently showing nothing. Settings has a new "Check for updates" button with a status line (up to date, update available, or the exact failure).

## [1.1.9]

- Battery tab: Voltage, Current and Phone Drain tiles now appear only when Android reports them, instead of showing "Unavailable".
- Festival banner: added Maha Navami (Oct 20) and the end of Durga Puja / Vijaya Dashami (Oct 21) for 2026.

## [1.1.8]

- Announcements now follow one fixed audio rule: they always play on the phone speaker first, and if a Bluetooth device is connected, the same announcement plays there right after. Android does not reliably allow both at the same instant, so this is one after the other. The "Audio Output Routing & Fallback" setting is removed. New "Mute announcements" control: pick 30 min, 1 hr, 2 hr or 8 hr and routine announcements are muted for that time; critical warnings still play.

## [1.1.7]

- Discharging no longer shows a blank screen: while the app is open and the phone is on battery, the Battery tab now shows live discharge data (power draw, current, voltage, temperature, level, time on battery) and a live graph that fills every second. Anything Android does not report is hidden instead of showing "Unavailable", and graph tabs with no data are hidden. Charging and discharging data are kept separate.

## [1.1.6]

- Fix crash: tapping SESSIONS & GRAPH on the Battery screen closed the app. The graph list was placed inside another scrolling list, which Android does not allow. It is now one list, and the Recent Charging Sessions card appears below the graph.
- Fix: the "Allow modifying system settings" switch on Android's Modify system settings screen was greyed out and could not be turned on. The app now declares the permission, so the switch works and you can allow it for the optional "Charging + screen off savings". You still allow it yourself in Android; the app never allows it.

## [1.1.5]

- Permission pop-ups instead of hunting in settings: when something the app uses is not allowed yet (notifications, Bluetooth devices, battery optimization exemption, brightness control if you turned on savings, location), you get ONE short Hinglish pop-up at a time that explains why, with Approve or Skip. Approve opens Android's own screen or dialog - you allow it there, the app never allows anything itself. Allowed permissions are never shown again. If you skip, a later launch asks again ("Aapne pehle skip kiya tha") with allow now, remind me later, or never ask again; never ask again is permanent.
- Festival banner inside the app (same as the website): on a festival day, a country's Independence Day (about 140 countries), or a condolence, a card with a matching accent colour and a live clock appears at the top. It is bundled in the app, so it works offline, and nothing is shown on other days. India first. No death anniversaries. Festival data covers 2026 and 2027; after that no festival banner is shown. Condolence entries are added by hand in the app code (see docs/BANNER.md).
- Monitoring > Hardware: rows that need your action are tappable and open the exact Android settings page, and re-check when you come back. Bluetooth LE shows AVAILABLE only when Bluetooth is on and permission is granted. Exact alarms now show NOT NEEDED because the app uses no exact alarms. Screen-off network optimization now shows UNSUPPORTED, because it needs a system-only permission that no user can grant to a normal app.

## [1.1.4]

- New update notification: once a day (only when there is internet, at a time Android chooses, no exact alarms) the app checks the official GitHub releases. If a newer version exists you get one status-bar notification, "Naya version available hai"; tapping it opens the app, where the existing verified in-app updater offers the download. Nothing is downloaded or installed automatically, and you get only one notification per version. The existing in-app update prompt is unchanged.

## [1.1.3]

- Optional in-app feedback and crash reports (Settings). Nothing is sent without your consent each time, and you first see the exact list: phone model, Android version, app version, and your message or the crash stack trace (code locations only). Nothing else is sent. A privacy policy page is linked from the card and the website.
- New optional "Charging + screen off savings" (Settings, off by default): while charging with the screen off, brightness is lowered and auto-sync is paused; your previous values are restored when the screen turns on or you unplug. Needs the "Modify system settings" permission; without it nothing changes and the card says so.

## [1.1.2]

- Fix wrong battery announcements while discharging: the app announced the lower 5% level as soon as the battery dropped below the previous one (75 to 74 announced "70 percent"). A level is now announced only when the battery actually reaches it (0, 5, 10 ... 100), in both charging and discharging, and never twice for the same level.
- Devices tab now says clearly when Bluetooth is off ("Bluetooth band hai - on karein") or when the Bluetooth permission is missing, instead of showing an empty list. It updates live when Bluetooth is switched on or off.

## [1.1.1]

- Fix the Devices tab not listing a connected Bluetooth device on some phones (reported on a Realme 9 Pro 5G). Connected devices are now found through more routes and one failing check no longer hides every device.
- A Bluetooth battery percentage is shown only when the phone and the device report it. Android has no public battery API for classic Bluetooth devices, so on some phones the card shows "Battery: Unavailable" instead of a made-up value.

## [1.1.0]

- Live Power card now shows live voltage (mV), current (mA), calculated battery-side power (W), battery temperature, battery percentage and a session duration timer, in both charging and discharging. Full and Not charging are shown separately. Time to full (charging) and time until empty (discharging) are estimated from the last 10 minutes of real percentage progress and show Calculating... or Unavailable when data is missing or inconsistent. Values the phone does not report show Unavailable, never a made-up number. Not yet verified on a physical device.
- Pin patched versions of vulnerable build-tool dependencies (Netty, Bouncy Castle, JDOM2, HttpClient, commons-lang3, Guava, jose4j) in the Gradle build. This changes the build, not app features. Some Dependabot alerts may stay open until they are re-scanned.
- Refresh the Security Policy (SECURITY.md) to describe the current permissions, data handling, release signing and planned solar rules, and add docs/AI_RULES.md with the project's working rules.

## [1.0.0]

- Record available Android battery observations, including charging state, temperature, voltage and current. Missing or stale readings are shown as unavailable or last known instead of invented measurements.
- Classify charging speed from observed battery-side power and track charging sessions. This is not a measurement of the charger's rated output.
- Add a charging-policy state engine with thermal thresholds. The new engine does not yet apply charger limits or system-setting changes; automatic battery protection and restoration are not complete.
- Export recorded observations and charging sessions to a PDF report. Capacity health, battery-failure risk, lifespan gains and unmeasured duration/episode values remain unavailable. The report is not a hardware diagnosis.
- Add GitHub update-checking groundwork: release notes, versionCode-based update detection, APK checksum/package/signing checks and Android user-confirmed installation. Future updates require a higher versionCode and the same signing identity.
- Add crash capture and stability-reporting groundwork. Automatic remote crash-report delivery is not connected to an operational backend endpoint yet; do not rely on automatic GitHub crash reports.
- Add guarded signed-APK release automation with Android build/unit tests, real-PDF instrumentation and CodeQL gates. Production signing credentials are not stored in source control.
