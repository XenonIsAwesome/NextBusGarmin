#!/bin/bash
# Installs the Android SDK pieces the companion app needs and creates the "nextbus" emulator.
# Run it yourself in a terminal: it asks you to review and accept the Android SDK licences.
#
# Prerequisite (already done by Claude on this machine): the command-line tools unzipped to
# $ANDROID_HOME/cmdline-tools/latest  (https://developer.android.com/studio#command-line-tools-only).
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
BIN="$ANDROID_HOME/cmdline-tools/latest/bin"
IMAGE="system-images;android-35;google_apis_playstore;x86_64"

echo "== 1/3 Android SDK licences (review them, answer y) =="
"$BIN/sdkmanager" --licenses

echo "== 2/3 Installing SDK packages (~2.5 GB) =="
"$BIN/sdkmanager" "platform-tools" "emulator" "platforms;android-35" "build-tools;35.0.0" "$IMAGE"

echo "== 3/3 Creating the emulator 'nextbus' (Pixel 7, Android 15, Play Store image) =="
echo no | "$BIN/avdmanager" create avd --force -n nextbus -k "$IMAGE" -d pixel_7
# 2 GB RAM and 8 GB of storage are plenty for this app.
CFG="$HOME/.android/avd/nextbus.avd/config.ini"
sed -i 's/^hw.ramSize=.*/hw.ramSize=2048/; s/^disk.dataPartition.size=.*/disk.dataPartition.size=8G/' "$CFG"

cat <<EOF

Done. Add this to ~/.bashrc (or export it before running Gradle / the emulator):
  export ANDROID_HOME=$ANDROID_HOME
  export PATH=\$PATH:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator

Start the emulator:   \$ANDROID_HOME/emulator/emulator -avd nextbus
EOF
