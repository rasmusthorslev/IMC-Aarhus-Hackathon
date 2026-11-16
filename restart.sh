#!/usr/bin/env bash
# Stops the running bot (it cancels its orders on exit) and starts a fresh one logging to logs/console-real.txt.
cd "$(dirname "$0")"
pkill -TERM -f 'imcbot.Main run'
while pgrep -f 'imcbot.Main run' >/dev/null; do sleep 0.5; done
nohup build/install/imc-bot/bin/imc-bot run >> logs/console-real.txt 2>&1 &
echo "started pid $!"
