# Health Connect Data Exporter

[Русская инструкция](docs/README.ru.md)

A small, open-source Android app that exports raw health and fitness records from
[Health Connect](https://developer.android.com/health-and-fitness/health-connect).
It is device- and vendor-independent: the source can be a smartwatch, smart ring,
heart-rate monitor, smart scale, fitness app, or any other app that writes records
to Health Connect.

The app is read-only, has no Internet permission, and processes every record locally.
After an export, it creates a ZIP archive that you can save with Android's standard
share sheet. An optional ADB helper is included for reproducible desktop exports.

## Supported records

- heart rate and resting heart rate;
- heart-rate variability (RMSSD);
- sleep sessions and sleep stages exposed by the source;
- oxygen saturation and respiratory rate;
- steps, distance, and active/total calories;
- workouts, laps, and segments;
- weight and height;
- VO2 max;
- Health Connect metadata, including source package, timestamps, recording method,
  and device information.

Each record type is written to its own newline-delimited JSON (`.ndjson`) file.
`manifest.json` contains record counts and any per-type errors. The files are packaged
as `health-connect-export-YYYYMMDD-HHMMSS.zip` when you tap **Share latest export**.

## Compatibility

- Android 9 (API 28) or newer;
- Google Play services;
- Health Connect installed on Android 9–13 or built into Android 14+;
- at least one source app configured to write data to Health Connect.

The phone brand does not matter. A record can be exported only if the source app has
actually written it to Health Connect. Some manufacturers expose only summaries and
do not share detailed samples or sleep stages.

## Install with Android Studio

1. Clone this repository.
2. Open it in a current version of Android Studio.
3. Let Gradle sync and install the requested Android SDK components.
4. Connect an Android phone with USB debugging enabled.
5. Select the `app` configuration and click **Run**.

## Build and install from the command line

Prerequisites: JDK 17, Android SDK 36, and Android Platform Tools (`adb`).

```bash
git clone https://github.com/TechLionDev/HealthConnectExporter.git
cd HealthConnectExporter
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The generic application ID is `com.techlion.healthconnectexporter`.

## Export on the phone

1. Open **Health Connect Data Exporter**.
2. Tap **Grant access & export**.
3. Approve the read-only Health Connect categories and historical access.
4. Wait for the completion message.
5. Tap **Share latest export** and save the ZIP wherever you choose.

No health data is sent to this project, its maintainers, or any server.

## Optional: pull and validate over USB

The helper requires a debug build and one authorized device connected through ADB.

```bash
python3 scripts/pull_health_export.py status
python3 scripts/pull_health_export.py pull
```

The second command saves a timestamped copy below `exports/`, validates every JSON
line, creates `summary.json`, and records SHA-256 checksums.

## Build shortcut

On macOS or Linux, after installing the prerequisites:

```bash
./install.sh
```

The script builds `HealthConnectDataExporter-debug.apk` in the repository root.

## Privacy and security

- The manifest does not request Internet access.
- Health permissions are read-only.
- Exports remain in private app storage until you explicitly share one.
- Shared archives contain sensitive health information and should be stored securely.
- The app does not bypass Health Connect permissions or access another app's private database.

## Known limitations

- Health Connect can return only data contributed by connected source apps.
- Exercise route coordinates require a separate consent flow and are not exported yet.
- The current exporter covers the common record types listed above, not every type in
  the evolving Health Connect API.
- Health Connect is not supported in Android work profiles.

## Contributing

Issues and pull requests are welcome. Please do not attach real health exports to a
public issue. Use synthetic or redacted samples when reporting parser problems.

## License

Released under the [MIT License](LICENSE).
