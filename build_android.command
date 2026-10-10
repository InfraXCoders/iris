#!/bin/bash
# Builds the Android app (APK) on this Mac. Run:  bash build_android.command
# First run installs Java 17 and the Android command-line tools with Homebrew (about 1.5 GB, one time).
# Result: BMPCC-Control-v<version>-b<build>.apk in this folder. Send it to a tester (WhatsApp, Drive, email).
cd "$(dirname "$0")" || exit 1
mkdir -p logs
set -o pipefail

command -v brew >/dev/null || { echo "Install Homebrew first: https://brew.sh"; exit 1; }

# 1. Java 17 (java_home falls back to any JDK when 17 is missing, so check with --failfast)
JAVA17=""
if /usr/libexec/java_home -v 17 --failfast >/dev/null 2>&1; then
  JAVA17="$(/usr/libexec/java_home -v 17 --failfast)"
fi
BREW_JDK="$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
if [ -z "$JAVA17" ] && [ -x "$BREW_JDK/bin/java" ]; then JAVA17="$BREW_JDK"; fi
if [ -z "$JAVA17" ]; then
  echo "Installing Java 17…"
  brew install openjdk@17 || exit 1
  JAVA17="$BREW_JDK"
fi
"$JAVA17/bin/java" -version 2>&1 | grep -q '"17' || { echo "Java 17 not found at $JAVA17"; exit 1; }
export JAVA_HOME="$JAVA17"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Java: $JAVA_HOME"

# 2. Android SDK (command-line tools, no Android Studio needed)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SDKMANAGER="$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1)"
if [ -z "$SDKMANAGER" ]; then
  echo "Installing the Android command-line tools…"
  brew install --cask android-commandlinetools || exit 1
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  BREW_TOOLS="$(brew --prefix)/share/android-commandlinetools/cmdline-tools/latest"
  if [ -d "$BREW_TOOLS" ] && [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    cp -R "$BREW_TOOLS" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  [ -x "$SDKMANAGER" ] || SDKMANAGER="$(command -v sdkmanager)"
  [ -n "$SDKMANAGER" ] || { echo "sdkmanager not found after installing android-commandlinetools."; exit 1; }
fi
if [ ! -d "$ANDROID_HOME/platforms/android-35" ] || [ ! -d "$ANDROID_HOME/build-tools/35.0.0" ]; then
  echo "Installing Android SDK 35 (accepting the SDK licences)…"
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
  "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0" || exit 1
fi
echo "sdk.dir=$ANDROID_HOME" > android/local.properties

# 3. Next build number (shown in the app and in the APK name)
VPROPS=android/version.properties
VERSION="$(grep '^versionName=' "$VPROPS" | cut -d= -f2 | tr -d '[:space:]')"
BUILD="$(( $(grep '^buildNumber=' "$VPROPS" | cut -d= -f2 | tr -d '[:space:]') + 1 ))"
perl -pi -e "s/^buildNumber=.*/buildNumber=$BUILD/" "$VPROPS"
APK="BMPCC-Control-v$VERSION-b$BUILD.apk"

# 4. Test and build
echo "Building version $VERSION (build $BUILD). First time downloads Gradle and libraries, a few minutes…"
if (cd android && bash ./gradlew --no-daemon :core:test :app:assembleDebug) > logs/android-build.log 2>&1; then
  rm -f BMPCC-Control-android.apk BMPCC-Control-v*.apk
  cp android/app/build/outputs/apk/debug/app-debug.apk "$APK"
  echo
  echo "BUILD OK: $(pwd)/$APK"
  echo "In the app, the home screen shows: v$VERSION · build $BUILD"
  echo "Send that file to your tester. On a phone connected by USB you can also run:"
  echo "  $ANDROID_HOME/platform-tools/adb install -r $APK"
  open -R "$APK" 2>/dev/null
else
  echo "BUILD FAILED. The important lines:"
  grep -E "^e: |error:|FAILED|What went wrong" -A3 logs/android-build.log | head -60
  echo "Full log: logs/android-build.log  (paste the lines above to Claude)"
  exit 1
fi
