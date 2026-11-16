# IMC bot manual

All commands run in `imc-bot/`. Settings in `bot.properties` apply live within ~2 s (no restart).

## Emergency
- Stop trading, cancel everything: set `paused=true` in `bot.properties`.
- Stop one product: `disabled=3_FLOW` (comma-separate several).
- Kill the bot: `pkill -TERM -f 'imcbot.Main run'` (cancels its orders on the way out).

## Run
- Build: `./gradlew -q installDist`
- Start: `build/install/imc-bot/bin/imc-bot run >> logs/console.txt 2>&1 &`
- Watch: `tail -f logs/console.txt`
- Profit and positions: `./profit.sh`
- Read-only check of exchange and data: `build/install/imc-bot/bin/imc-bot probe 10`

## Tuning (bot.properties)
- `max_position` (10-30): max lots per product. Lower = safer.
- `quote_size` (1-5) / `take_size` (1-5): lots per quote / per take.
- `quote_k` (0.03-0.15): quote distance from fair. Higher = wider, fewer fills.
- `take_k` (0.2-1.0): how far through fair before we take an order. Higher = pickier.
- `model_weight` (0-0.5): share of our data model vs market mid in fair value.
- `requests_per_second`: keep at 0.9 (exchange limit is 1/s).
- `forecast.1_WIND=370` etc.: set a predicted final value; blended by `forecast_weight`.
- `session_open`: start of the WIND_RANGE window (15:00 per rules sheet).

## Symptoms
- No fills: lower `quote_k`.
- Losing right after fills: raise `quote_k`, or lower `model_weight`.
- One product keeps losing: add it to `disabled`.
- Position stuck at limit: lower `max_position` to force unwinding.
- "429" / rate limited: lower `requests_per_second`.
- "stale" lines: Energinet is down; the bot pulls quotes until data returns.

## Rules that matter
Final value = average 17:40-17:45. Score is per product: any loss in a product costs points.
Logs: `logs/run-*.jsonl` (every fill, trade, quote, fair value), `logs/console*.txt`.
