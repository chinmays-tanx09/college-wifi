#!/bin/bash
# usage: runtests.sh [r8]
set -e
set +o pipefail
cd "$(dirname "$0")"
BT="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/35.0.0"
PURE="Accounts Detect Dev Drivers Html Http LiveNote MiniJson Portal Release Session SophosProtocol Usage PortalUsage"
rm -rf /tmp/p51t && mkdir -p /tmp/p51t/c
FILES=""; for f in $PURE; do FILES="$FILES src/bot/paradox/wifi/$f.java"; done
timeout -k 5 120 javac -nowarn --release 8 -encoding UTF-8 -d /tmp/p51t/c $FILES test/V5Test.java test/UpdTest.java test/H51.java test/Repro51.java test/Hang51Test.java test/UsageTest.java 2>&1 | grep -v '^Note:' || true
CP=/tmp/p51t/c
if [ "$1" = r8 ]; then
  (cd /tmp/p51t/c && zip -qr ../t.jar .)
  timeout -k 5 240 java -Xmx2g -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --classfile --lib /usr/lib/jvm/default-java --pg-conf test/r8test.pro --output /tmp/p51t/r8.jar /tmp/p51t/t.jar 2>&1 | grep -v 'Proguard configuration\|^Info\|^Warning' | head -20 || true
  CP=/tmp/p51t/r8.jar
fi
echo "== V5Test"; timeout -k 5 600 java -cp $CP bot.paradox.wifi.V5Test > /tmp/p51t/v5.txt 2>&1 || true; tail -2 /tmp/p51t/v5.txt; grep FAIL /tmp/p51t/v5.txt | head
echo "== UpdTest"; timeout -k 5 120 java -cp $CP bot.paradox.wifi.UpdTest > /tmp/p51t/upd.txt 2>&1 || true; tail -2 /tmp/p51t/upd.txt; grep FAIL /tmp/p51t/upd.txt | head
echo "== Hang51Test"; timeout -k 5 600 java -cp $CP bot.paradox.wifi.Hang51Test > /tmp/p51t/h51.txt 2>&1 || true; tail -2 /tmp/p51t/h51.txt; grep FAIL /tmp/p51t/h51.txt | head
echo "== Repro51"; timeout -k 5 120 java -cp $CP bot.paradox.wifi.Repro51 > /tmp/p51t/repro.txt 2>&1 || true; grep 'RESULT\|PASS\|FAIL' /tmp/p51t/repro.txt
echo "== UsageTest (v5.2)"; timeout -k 5 120 java -cp $CP bot.paradox.wifi.UsageTest > /tmp/p51t/usage.txt 2>&1 || true; tail -1 /tmp/p51t/usage.txt; grep FAIL /tmp/p51t/usage.txt | head
