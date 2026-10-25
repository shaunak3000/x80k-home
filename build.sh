#!/bin/bash
# Builds build/app.apk with Google's SDK tools only (no Gradle, no downloads), then checks it against the repo's rules.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/37.0.0"
JAR="$SDK/platforms/android-31/android.jar"
JDK="${JAVA_HOME:?Set JAVA_HOME to a JDK 21}"
KS="$HOME/.android/debug.keystore"
PKG_DIR=io/github/shaunak3000/tvhome
OUT=build

fail() { echo "FAIL: $*" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen"

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -Xlint:all,-options -Werror \
  -d "$OUT/classes" $(find app/src "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 31 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')

# A fixed timestamp and no compression, so the same source always produces the same bytes.
(cd "$OUT" && TZ=UTC touch -t 198001010000 classes.dex && TZ=UTC zip -q -X -0 base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/unsigned.apk"

perms=$("$BT/aapt2" dump permissions "$OUT/unsigned.apk" | sed -nE "s/^uses-permission: name='([^']+)'.*/\1/p" | tr '\n' ' ')
[ "$perms" = "android.permission.INTERNET " ] || fail "the app may ask for internet access and nothing else; it asks for: ${perms:-nothing}"
urls=$(grep -rhoE 'https?://[^"]*' app | grep -vxE 'https://(commons|thumb|upload)\.wikimedia\.org/.*|http://schemas\.android\.com/apk/res/android' || true)
[ -z "$urls" ] || fail "web addresses other than Wikimedia's in the app: $urls"
foreign=$("$BT/dexdump" "$OUT/classes.dex" | awk -F"'" '/Class descriptor/ {print $2}' | grep -v "^L$PKG_DIR/" || true)
[ -z "$foreign" ] || fail "code from outside this repo: $foreign"
extra=$(unzip -Z1 "$OUT/unsigned.apk" | grep -vxE 'AndroidManifest.xml|classes.dex|resources.arsc|(assets|res)/.+' || true)
[ -z "$extra" ] || fail "unexpected files in the APK: $extra"
size=$(wc -c < "$OUT/unsigned.apk" | tr -d ' ')
[ "$size" -le 102400 ] || fail "the APK is $size bytes; the budget is 102400"
lines=$(cat $(find app/src -name '*.java') | wc -l | tr -d ' ')
[ "$lines" -le 1000 ] || fail "the code is $lines lines; the budget is 1000, small enough to read in an evening"

mkdir -p "$(dirname "$KS")"
[ -f "$KS" ] || "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --v4-signing-enabled false --out "$OUT/app.apk" "$OUT/unsigned.apk"

echo "OK: internet for Wikimedia photos only, only our code, $size bytes, $lines lines"
echo "sha256 (unsigned): $(shasum -a 256 "$OUT/unsigned.apk" | cut -d' ' -f1)"
