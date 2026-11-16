#!/usr/bin/env bash
# Re-enable FLOW at 17:42:05, once two settlement minutes are known.
cd "$(dirname "$0")"
until [ "$(TZ=Europe/Copenhagen date +%H%M%S)" -ge 174205 ]; do sleep 2; done
sed -i '' 's/^disabled=.*/disabled=/' bot.properties
echo "$(TZ=Europe/Copenhagen date +%H:%M:%S) ENDGAME stage 3: FLOW re-enabled"
