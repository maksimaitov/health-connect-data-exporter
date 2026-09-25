#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

if ! java -version >/dev/null 2>&1; then
    for java_home_candidate in \
        /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
        if [[ -x "$java_home_candidate/bin/java" ]]; then
            export JAVA_HOME="$java_home_candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            break
        fi
    done
fi

if ! java -version >/dev/null 2>&1; then
    echo "Java 17 is required. Install it, then run this script again." >&2
    exit 1
fi

chmod +x gradlew
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk HealthConnectDataExporter-debug.apk

echo "Built: $project_dir/HealthConnectDataExporter-debug.apk"
