"""Per product: P&L if it settled at our current fair value, and the settlement price where P&L = 0."""
import glob, json, subprocess
out = subprocess.run(['./profit.sh'], capture_output=True, text=True).stdout.splitlines()
positions = json.loads(out[1].split('positions:', 1)[1])
fair_line = None
for path in sorted(glob.glob('logs/run-*.jsonl')):
    for line in open(path):
        if '"type":"fair"' in line:
            fair_line = line
fairs = {p: v['fair'] for p, v in json.loads(fair_line)['values'].items()}
for x in sorted(positions, key=lambda x: x['product']):
    p, pos, cash = x['product'], x['netPosition'], x['cash']
    f = fairs.get(p)
    if pos == 0:
        print(f'{p:14s} flat, locked P&L {cash:+.0f}')
        continue
    be = -cash / pos
    side = 'above' if pos > 0 else 'below'
    print(f'{p:14s} pos {pos:+4.0f}  P&L at fair {f:.0f}: {cash + pos * f:+6.0f}   break-even: settle {side} {be:.1f}')
