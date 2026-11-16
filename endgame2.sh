#!/usr/bin/env bash
# From 17:42 two of the five settlement minutes are known: bigger limits and sweeps (FLOW and RANGE untouched).
cd "$(dirname "$0")"
until [ "$(TZ=Europe/Copenhagen date +%H%M%S)" -ge 174200 ]; do sleep 2; done
sed -i '' 's/^max_position=.*/max_position=40/; s/^max_position.1_WIND=.*/max_position.1_WIND=40/; s/^max_position.2_CO2=.*/max_position.2_CO2=40/; s/^max_position.4_ETF=.*/max_position.4_ETF=40/; s/^take_size=.*/take_size=15/' bot.properties
echo "$(TZ=Europe/Copenhagen date +%H:%M:%S) ENDGAME stage 2: limits 40"
grep -E '^(max_position|take_size)' bot.properties
