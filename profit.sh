#!/usr/bin/env bash
# Prints our profit per product and positions from the exchange. Login comes from .env.
cd "$(dirname "$0")"
set -a; [ -f .env ] && . ./.env; set +a
: "${CMI_URL:?set CMI_URL in .env}" "${CMI_USERNAME:?set CMI_USERNAME in .env}" "${CMI_PASSWORD:?set CMI_PASSWORD in .env}"
T=$(curl -s -D - -o /dev/null -H 'Content-Type: application/json' -d "{\"username\":\"$CMI_USERNAME\",\"password\":\"$CMI_PASSWORD\"}" "$CMI_URL/api/user/authenticate" | awk -F': ' 'tolower($1)=="authorization"{print $2}' | tr -d '\r')
echo "profit:    $(curl -s -H "Authorization: $T" "$CMI_URL/api/profit/current-user")"
echo "positions: $(curl -s -H "Authorization: $T" "$CMI_URL/api/position/current-user")"
