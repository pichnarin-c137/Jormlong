#!/usr/bin/env bash
# Builds the fat jar and packages it as a .deb with jpackage.
set -euo pipefail
cd "$(dirname "$0")"

for tool in mvn jpackage; do
    if ! command -v "$tool" > /dev/null; then
        echo "error: $tool not found on PATH" >&2
        exit 1
    fi
done

mvn -q clean package

rm -rf dist
mkdir -p dist/input
cp target/jormlong-1.0.0.jar dist/input/

jpackage --type deb --name jormlong --app-version 1.0.0 \
  --input dist/input --main-jar jormlong-1.0.0.jar --main-class com.jormlong.Launcher \
  --icon packaging/jormlong.png --description "Lightweight screen recorder" \
  --vendor Jormlong --linux-shortcut --linux-menu-group "AudioVideo;Video;Recorder" \
  --linux-app-category video --dest dist

# jpackage's release-suffix naming varies across JDK versions, so glob
echo "built: $(ls dist/jormlong_*.deb)"
