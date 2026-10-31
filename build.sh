#!/usr/bin/env bash
# Builds better-run-logs.jar; `./build.sh test` runs the pure tests. Set STEAM to your steamapps folder if it is not found.
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${STEAM:-}" ]; then
  STEAM="$HOME/.local/share/Steam/steamapps"
  [ -d "$STEAM" ] || STEAM="$HOME/snap/steam/common/.local/share/Steam/steamapps"
fi
if [ "${1:-}" = "test" ]; then
  rm -rf out-test && mkdir -p out-test
  javac --release 8 -encoding UTF-8 -nowarn -d out-test src/betterrunlogs/UploadPlan.java test/UploadPlanTest.java
  java -ea -cp out-test UploadPlanTest
  exit 0
fi
CP="$STEAM/common/SlayTheSpire/desktop-1.0.jar:$STEAM/workshop/content/646570/1605060445/ModTheSpire.jar:$STEAM/workshop/content/646570/1605833019/BaseMod.jar"
rm -rf out && mkdir -p out
javac --release 8 -encoding UTF-8 -nowarn -cp "$CP" -d out src/betterrunlogs/*.java
cp ModTheSpire.json out/
(cd out && jar cf ../better-run-logs.jar .)
echo "built $(pwd)/better-run-logs.jar"
