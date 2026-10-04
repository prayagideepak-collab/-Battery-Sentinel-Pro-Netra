# Security Policy

## Supported Versions

Battery Sentinel Pro Nethra is under active development. The first public release is v1.0.0. Only the latest published release receives security fixes.

| Version | Supported |
| ------- | --------- |
| Latest GitHub release (currently v1.0.0) | :white_check_mark: |
| Older releases | :x: |
| Development builds and CI test APKs | Best effort |

## Reporting a Vulnerability

If you discover a security vulnerability in Battery Sentinel Pro Nethra, please report it privately.

Please do **not** create a public GitHub issue for an undisclosed security vulnerability.

### Preferred Reporting Method

Use GitHub's private vulnerability reporting feature for this repository when available.

If private vulnerability reporting is not available, contact the repository maintainer privately through the GitHub repository/account rather than publicly disclosing the vulnerability.

### Include the Following Information

Please provide:

- A clear description of the vulnerability
- The affected version, release, or commit
- Steps required to reproduce the issue
- Expected behavior
- Actual behavior
- Potential security impact
- Relevant logs, screenshots, or proof-of-concept information when appropriate

Do not include passwords, API keys, authentication tokens, personal information, or other sensitive information in a vulnerability report.

## What Happens After a Report

Security reports will be reviewed and investigated.

When a report is received, the maintainer may:

1. Verify and reproduce the reported issue.
2. Determine the affected components and versions.
3. Assess the security impact.
4. Develop and test an appropriate fix.
5. Release a security update when necessary.
6. Publish appropriate security information after the issue has been addressed.

The exact response time may vary depending on the severity and complexity of the vulnerability.

## Responsible Disclosure

Please allow reasonable time for investigation and remediation before publicly disclosing a security vulnerability.

Public disclosure before a fix is available may increase risk to users.

## Security Scope

Security reports may include vulnerabilities involving:

- Android application security
- Permission handling
- Unauthorized access
- Sensitive data exposure
- Insecure local data storage
- Update and release mechanisms
- Network communication
- Dependency vulnerabilities
- Privilege escalation
- Code execution
- Authentication or authorization
- Other security vulnerabilities directly affecting Battery Sentinel Pro Nethra

## Out of Scope

The following should normally be reported through regular GitHub Issues instead:

- UI bugs
- Feature requests
- General usability problems
- Performance problems without a security impact
- Incorrect battery readings without a security impact
- General Android compatibility issues

If a normal bug also creates a security vulnerability, report it privately as a security issue.

## What the App Does With Your Data and Permissions

This section describes what the current code does. It is updated with the app.

### Live battery telemetry (charging and discharging)
- Voltage, current, temperature, percentage, power and time estimates are read on the device from Android battery APIs (the battery status broadcast and `BatteryManager`). They are not sent anywhere for display.
- The 1-second refresh loop runs only while the Live Power screen is visible.
- Power is calculated battery-side power (voltage x current). It is not wall-adapter wattage.
- Estimates (time to full, time until empty) come only from observed percentage progress. They show `Calculating...` or `Unavailable` when data is missing or inconsistent.

### Network Saving (screen off) suggestion
- The app can suggest switching down from 5G to 4G, or 4G to 3G, after the screen has been off. It uses these permissions:
  - `READ_PHONE_STATE`: to read the current network type. Requested at runtime.
  - Usage Access (`PACKAGE_USAGE_STATS`): to read recent mobile data use. Granted by you in Android Settings. If recent data use is 2 MB or more, no suggestion is made.
- By default the app only shows a notification. A normal app cannot change the preferred network type.
- **Experimental ADB switch:** `WRITE_SECURE_SETTINGS` is declared in the manifest but is not granted by Android to normal apps. Only you can grant it, from a computer, with `adb shell pm grant com.aistudio.batterysentinel.ntra android.permission.WRITE_SECURE_SETTINGS`. Without that grant the switch does nothing. This path is experimental and has not been verified on real devices. Do not grant it unless you understand it, and you can revoke it with `adb shell pm revoke` using the same package and permission.

### Other permissions
The manifest also declares `WRITE_SETTINGS` (Modify system settings), used only by the optional "Charging + screen off savings" to lower brightness while charging with the screen off; Android shows it as a switch that only you can turn on, and the app never grants it. It also declares internet and network state, notifications, boot completed, foreground service, battery-optimization request, approximate and precise location (weather and climate context), Bluetooth, vibration, and `REQUEST_INSTALL_PACKAGES` (for the in-app update flow; Android still asks you to approve every install). Report any permission you think is not needed.

### Solar monitoring (planned, not enabled)
No solar provider is enabled in the app today. Before any provider ships, these rules apply (see `docs/SOLAR_MONITORING_INTEGRATION_PLAN.md`):
- Provider credentials or tokens are stored on the device only, encrypted with Android Keystore-backed storage.
- They must be excluded from Android backup and device transfer. The current backup rule files are still the default templates, so this must be done before solar credentials are stored.
- They are never written to logs, crash reports, analytics, source control or app resources. Provider app secrets are never embedded in the APK.
- Official OAuth or token flows are preferred over collecting passwords. No scraping of private dashboards. Read-only in the first release.
- A provider is listed as supported only after a real, authorized account test returns real data.

### Anonymous usage count (active users)
- Once per UTC day (and once per month) the app adds 1 to a public counter in Firestore (`netra_active/battery-sentinel_<yyyyMMdd>` and `_<yyyyMM>`), so the Netra Eco website can show approximate active users.
- The request contains only the counter document name and "increment by 1". No device ID, install ID, account, location, battery data or app data is sent, and the app keeps no ID for this.
- A local flag stops repeats on the same day. A failed send is retried at the next open. It is on by default and can be turned off in Settings ("Share anonymous usage count").
- Firestore rules allow only creating a counter with value 1 or raising it by exactly 1; the counters are public to read. Anyone could in theory add extra +1s, so the figure is approximate, not exact people. Reinstalling or clearing data can count one person twice.

### Update check
- The app checks GitHub (the public release API, with a backup file on the project website) for a newer version. It sends no user data. Settings has a "Check for updates" button that shows the real status or error.

### Automatic crash reports
- When the app crashes, it saves a short report on the device. The next time the app opens, it sends that report by itself, with no button and no question. After a successful send the file is deleted; after a failed send it is kept and retried at the next start.
- The report contains only: phone model, Android version, app version, and the crash stack trace (exception class names and code locations; exception messages are dropped on purpose). It contains no name, email, location, files, device IDs or battery history.
- It goes through the same form pipeline as the website forms (FormSubmit) to the developer's email. The optional "Send feedback" form still sends only when the user presses Send.

### Honest data: no fake values
The app must not invent telemetry. A reading the phone or provider does not give is shown as `Unavailable`, never as a fake zero, a sample value or a guess. Readings differ between phones, and the app does not claim every reading works on every device.

### AI features
Some features call an AI service (Gemini) over the network. Do not enter secrets in AI prompts. Report any case where private data is sent that you did not expect.

## Saver (new in 1.1.15)
- Off by default. When on, and the battery temperature or level reaches the limits the user set, it can lower brightness to 10% and the screen timeout, ask Android to close background apps and clear notifications. It puts brightness and timeout back when the battery is normal again.
- Permissions: `KILL_BACKGROUND_PROCESSES` (normal permission; only ends background processes, it cannot Force stop and cannot touch foreground-service apps), "Modify system settings" (already used by the charging saver), and "Notification access" (granted by the user in Android settings; the listener only calls "clear all" and never reads or stores notification content). A `<queries>` entry for launchable apps lets the app see which apps could be closed; the list stays on the device.
- Never closed: Netra apps, the default phone, SMS, launcher and keyboard apps, common messaging apps and the clock. System apps are skipped. At most one run per 30 minutes.
- No network use, no data leaves the device, no new library.

## Charging temperature advice and charger hint (new in 1.1.17)

- While the phone is charging, the app watches the battery temperature trend and speaks early advice before the existing 40 and 45 degree warnings: close background apps and take off the case when the temperature is rising from 35 degrees, and unplug the charger when it is rising toward the danger zone at 38 degrees. If charging stays slow (under 5 W) for 5 minutes while the phone heats up, it says the charger or cable MAY be faulty.
- Honest limits: an Android app cannot cool the phone or change the charger. This only gives warnings and steps. The charger hint is a hint, not proof. If the temperature or power is not reported by the phone, nothing is said and nothing is guessed.
- Uses the existing announcement switch "thermal warning" (on by default). Permissions: none added. No new library.

## Announcements and charger advice (changed in 1.1.19)
- All spoken announcements go through one queue. The same words are not spoken twice within 20 seconds. Nothing is replayed on Bluetooth any more. Nothing is uploaded; the text is spoken by the phone's own text-to-speech.
- The charger advice speaks only when charging is slow and the battery temperature is rising. It is quiet when the temperature is normal, when the charger has shown it is fast, at 95% or more, and when level or power is not reported. The fixed 40 and 45 degree warnings are separate and unchanged.
- When this happens on a USB port, the advice says a file transfer or low power port may be the cause. That is a guess from the plug type: Android does not tell an app whether files are being copied. The app does not read file names, storage or the USB data. No new permission.

## Journey mode (new in 1.1.18)

- A manual switch in the Saver card for long trips. It never starts by itself and ends by itself after 12 hours.
- While it is on and the phone is not charging, the existing Saver actions (brightness and screen timeout, closing background processes, clearing notifications; each still has its own switch) run at the next battery reading instead of waiting for the level limit. They are put back when you plug in, turn Journey mode off or it ends.
- Honest limits: it adds no background service, no new permission and no new library; it uses only the battery readings the app already takes. Android does not let an app restrict other apps' data or battery. The only thing possible is to end background processes, and apps may restart, so the saving can be small. Closed apps and cleared notifications cannot be brought back.

## Installer file cleanup
- After an in-app update installs, the app restarts and deletes every downloaded installer file from its cache folder (`cache/updates/`) on start. A new download also replaces older files. If the user cancels the install, the file is removed the next time the app starts.

## Releases and Signing
- Release APKs are built by the repository's guarded release workflow from a reviewed commit on `main` and published on the GitHub Releases page.
- The workflow signs the APK with the project release key and checks that the signing certificate matches the earlier one before publishing. The key is stored as a repository secret and is never committed.
- Install APKs only from this repository's Releases page. CI test APKs are debug builds, signed with a throwaway key, and are not releases.
- Compare the SHA-256 digest shown on the release page with the file you downloaded.

## Dependencies
- Dependabot alerts and updates are enabled. Security alerts are reviewed and fixed through pull requests that must pass the build and tests. Alerts are not dismissed without a reason.
- Build-tool transitive dependencies with open alerts (Netty, Bouncy Castle, Apache HttpClient, Commons Lang, Guava, reached through Android Gradle Plugin test tooling) are forced to patched versions in `app/build.gradle.kts` and the root buildscript constraints. These are build-time only and are not shipped in the APK. Whether GitHub clears the matching alerts is checked after each change; alerts are never dismissed without the owner.
- Some alerts come from build-tool dependencies (Android Gradle Plugin and Gradle plugins), not code shipped in the APK. They are still tracked and fixed.

## Security Principles

Battery Sentinel Pro Nethra follows these principles:

- Use Android public APIs wherever possible.
- Request only permissions required by implemented functionality.
- Do not fabricate permission states or security status.
- Do not expose sensitive information unnecessarily.
- Do not claim security capabilities that the application does not actually implement.
- Show `Unavailable` when a value cannot be read. Never show invented values.
- Keep security-sensitive functionality subject to testing and verification.

## Development Status

Battery Sentinel Pro Nethra is in active development. v1.0.0 is the first published release. Features marked experimental or planned above have not been verified on real devices or are not implemented yet, and should not be assumed to provide production-level guarantees.
