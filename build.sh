#!/usr/bin/env bash
# Builds Lazarus without Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner
#
# Requirements: Android SDK (build-tools + platform 36) and a JDK 17+.
#   ANDROID_HOME   SDK path          (default: $LOCALAPPDATA/Android/Sdk or ~/Android/Sdk)
#   BUILD_TOOLS    build-tools ver   (default: 36.0.0)
#   JAVA_HOME      JDK path          (default: Android Studio's bundled JBR on Windows)
#
# Signing: a release key is kept OUTSIDE the repo in ~/.lazarus/ (created on first build).
#   LAZARUS_KEYSTORE / LAZARUS_KEYPASS override it.  BACK THIS KEY UP — updates must be signed
#   with the same key, or users cannot update.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${LOCALAPPDATA:+$LOCALAPPDATA/Android/Sdk}}"; SDK="${SDK:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
PLATFORM="$SDK/platforms/android-36/android.jar"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/c/Program Files/Android/Android Studio/jbr" ]; then
  export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
fi
JBIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
EXE=""; BAT=""; SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe"; BAT=".bat"; SEP=";";; esac

OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex"

echo "[1/7] magisk zip"
rm -f assets/lazarus_sepolicy.zip
"${JBIN}jar" --create --no-manifest --file assets/lazarus_sepolicy.zip -C magisk/lazarus_sepolicy .

echo "[2/7] stubs (compile-only Xposed API, not packaged)"
"${JBIN}javac" -nowarn -source 17 -target 17 -cp "$PLATFORM" -d "$OUT/stubs" $(find stubs -name '*.java') 2>&1 | grep -v "^warning" || true

echo "[3/7] javac"
"${JBIN}javac" -Xlint:-options -source 17 -target 17 -cp "$PLATFORM$SEP$OUT/stubs" -d "$OUT/classes" $(find src -name '*.java')

echo "[4/7] d8"
"$BT/d8$BAT" --release --min-api 30 --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[5/7] aapt2"
"$BT/aapt2$EXE" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2$EXE" link -I "$PLATFORM" --manifest AndroidManifest.xml -A assets -o "$OUT/unaligned.apk" "$OUT/res.zip"
(cd "$OUT/dex" && "${JBIN}jar" uf ../unaligned.apk classes.dex)

echo "[6/7] zipalign"
"$BT/zipalign$EXE" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[7/7] sign"
KEYDIR="$HOME/.lazarus"
KS="${LAZARUS_KEYSTORE:-$KEYDIR/release.jks}"
if [ -z "${LAZARUS_KEYPASS:-}" ]; then
  if [ ! -f "$KS" ]; then
    mkdir -p "$KEYDIR"
    head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32 > "$KEYDIR/release.pass"
    "${JBIN}keytool" -genkeypair -keystore "$KS" -storetype PKCS12 -alias lazarus \
      -storepass "$(cat "$KEYDIR/release.pass")" -keyalg RSA -keysize 4096 -validity 25000 \
      -dname "CN=Lazarus, O=Albatross Microsystems" >/dev/null
    echo "    NEW release key created in $KEYDIR — back it up (keystore + release.pass)!"
  fi
  LAZARUS_KEYPASS="$(cat "$KEYDIR/release.pass")"
fi
VERSION=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
APK="lazarus-v$VERSION.apk"
"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass "pass:$LAZARUS_KEYPASS" --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner$BAT" verify "$APK"
ls -l "$APK"
