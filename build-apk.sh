#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"

tool_dir=/workspace/scratch/ytm-hu-tools/apk
android_jar="${ANDROID_JAR:-$tool_dir/android-35/android.jar}"
build_tools="${ANDROID_BUILD_TOOLS:-$tool_dir/android-15}"
output=build
mkdir -p "$output"
temp=$(mktemp -d "$output/apk.XXXXXXXX")
trap 'rm -rf -- "$temp"' EXIT
mkdir "$temp/classes" "$temp/dex"

mapfile -d '' sources < <(find app/src/main/java -name '*.java' -print0)
if [[ -n "${ECJ_JAR:-}" || ! $(command -v javac) ]]; then
    java -jar "${ECJ_JAR:-$tool_dir/ecj.jar}" -8 -encoding UTF-8 \
        -classpath "$android_jar" -d "$temp/classes" "${sources[@]}"
else
    javac --release 8 -encoding UTF-8 -classpath "$android_jar" -d "$temp/classes" "${sources[@]}"
fi

"$build_tools/aapt2" compile --dir app/src/main/res -o "$temp/resources.zip"
"$build_tools/aapt2" link -I "$android_jar" --manifest app/src/main/AndroidManifest.xml \
    -o "$temp/unsigned.apk" "$temp/resources.zip"
mapfile -d '' classes < <(find "$temp/classes" -name '*.class' -print0)
"$build_tools/d8" --min-api 27 --lib "$android_jar" --output "$temp/dex" "${classes[@]}"
(cd "$temp/dex" && zip -q ../unsigned.apk classes.dex)
"$build_tools/zipalign" -f 4 "$temp/unsigned.apk" "$temp/aligned.apk"

keystore="$output/debug.keystore"
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -keystore "$keystore" -storepass android -keypass android \
        -alias androiddebugkey -dname 'CN=Android Debug' -keyalg RSA -keysize 2048 -validity 10000
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-pass pass:android \
    --key-pass pass:android --out "$temp/signed.apk" "$temp/aligned.apk"
"$build_tools/apksigner" verify --min-sdk-version 27 "$temp/signed.apk"
mv "$temp/signed.apk" "$output/ytm-head-unit-debug.apk"
printf 'APK: %s/%s\n' "$PWD" "$output/ytm-head-unit-debug.apk"
