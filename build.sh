#!/usr/bin/env bash
# Builds better-run-logs.jar. Set STEAM to your steamapps folder if it is not the default.
set -euo pipefail
cd "$(dirname "$0")"
STEAM="${STEAM:-$HOME/.local/share/Steam/steamapps}"
CP="$STEAM/common/SlayTheSpire/desktop-1.0.jar:$STEAM/workshop/content/646570/1605060445/ModTheSpire.jar:$STEAM/workshop/content/646570/1605833019/BaseMod.jar"
rm -rf out && mkdir -p out
javac --release 8 -nowarn -cp "$CP" -d out src/betterrunlogs/*.java
cp ModTheSpire.json out/
(cd out && jar cf ../better-run-logs.jar .)
echo "built $(pwd)/better-run-logs.jar"
