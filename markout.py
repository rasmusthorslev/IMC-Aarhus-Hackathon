"""Fill quality: for each of our fills, edge vs fair value 60 s later (positive = good fill)."""
import glob, json, sys
from datetime import datetime, timedelta

fairs, fills = [], []
for path in sorted(glob.glob('logs/run-*.jsonl')):
    for line in open(path):
        d = json.loads(line)
        t = datetime.fromisoformat(d['t'].replace('Z', '+00:00'))
        if d['type'] == 'fair':
            fairs.append((t, {p: v['fair'] for p, v in d['values'].items()}))
        elif d['type'] == 'my_fill':
            fills.append((t, d))
fairs.sort(key=lambda x: x[0])

def fair_at(t, product):
    best = None
    for ft, values in fairs:
        if ft <= t:
            best = values.get(product)
        else:
            break
    return best

totals = {}
for t, d in fills:
    later = fair_at(t + timedelta(seconds=60), d['product'])
    if later is None:
        continue
    bought = d.get('buyer') == sys.argv[1]
    sign = 1 if bought else -1
    edge = sign * (later - d['price'])
    s = totals.setdefault(d['product'], [0, 0.0])
    s[0] += int(d['volume']); s[1] += edge * d['volume']
for product, (lots, edge) in sorted(totals.items()):
    print(f'{product:14s} lots {lots:4d}  edge vs fair+60s {edge:+8.0f}  per lot {edge / lots:+6.1f}')
