# imc-bot

Java trading bot from the IMC Aarhus Trading Hackathon (Nov 2026). Five products settled on live Danish power data from Energinet: wind, CO2, power imports, an ETF of those three, and the wind range over the session. We finished 3rd on the weighted score and 2nd on P&L (+14,159), profitable in every product.

Team: Rasmus Thorslev and Simon Glob.

## How it works

- Prices each product from Energinet's live data using the settlement formulas. It fetches each minute right after it's published and leans a bit with the recent trend.
- The house market maker lagged the data by 3–5 minutes, so the bot takes its stale quotes first, sweeping several price levels in one order. The exchange only allowed 1 request/sec.
- Quotes around fair value the rest of the time, and leans its quotes to work positions back towards flat.
- Wind range is priced as the range so far plus how much it usually grew over the rest of the afternoon, based on 47 past days.

The biggest win was a 100-lot short on `5_WIND_RANGE`. The market bid ~170 while the data said it would settle around 115–135, and it settled at ~115, worth about +5,500.

## Run

```
cp .env.example .env    # exchange URL + login
./gradlew -q installDist
build/install/imc-bot/bin/imc-bot probe 10   # read-only check
build/install/imc-bot/bin/imc-bot run
```

Settings (limits, sizes, edges) are in `bot.properties` and reload live. `paused=true` stops trading. `./profit.sh` shows P&L and positions.
