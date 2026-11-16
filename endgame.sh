#!/usr/bin/env bash
# Switches bot.properties to end-of-day settings at fixed Danish times.
cd "$(dirname "$0")"
wait_until() { until [ "$(TZ=Europe/Copenhagen date +%H%M%S)" -ge "$1" ]; do sleep 2; done; }
wait_until 173500
sed -i '' 's/^momentum_k=.*/momentum_k=0/' bot.properties
echo "$(TZ=Europe/Copenhagen date +%H:%M:%S) ENDGAME momentum off"
wait_until 173900
sed -i '' 's/^model_weight=.*/model_weight=1.0/; s/^take_k=.*/take_k=0.05/; s/^max_position=.*/max_position=25/; s/^max_position.1_WIND=.*/max_position.1_WIND=25/; s/^max_position.2_CO2=.*/max_position.2_CO2=25/; s/^max_position.3_FLOW=.*/max_position.3_FLOW=10/; s/^max_position.4_ETF=.*/max_position.4_ETF=25/; s/^forecast_weight=.*/forecast_weight=0/' bot.properties
echo "$(TZ=Europe/Copenhagen date +%H:%M:%S) ENDGAME final-window settings on"
grep -E '^(momentum_k|model_weight|take_k|max_position|forecast_weight)' bot.properties
