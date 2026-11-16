"""Fill quality against the market mid 60 s and 120 s later, from console status lines."""
import glob, json, re, sys
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
DK = ZoneInfo('Europe/Copenhagen')
today = datetime.now(DK).date()
mids = {}
pat = re.compile(r'\| (\S+) fair \S+ mkt (\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)')
for path in glob.glob('logs/console*.txt'):
    for line in open(path):
        if len(line) < 9 or line[2] != ':' or '| ' not in line:
            continue
        try:
            t = datetime.combine(today, datetime.strptime(line[:8], '%H:%M:%S').time(), DK)
        except ValueError:
            continue
        for product, b, a in pat.findall(line):
            b, a = float(b), float(a)
            if a - b <= {'1_WIND': 14, '2_CO2': 24, '3_FLOW': 31, '4_ETF': 41, '5_WIND_RANGE': 6}.get(product, 30) * 1.5:
                mids.setdefault(product, []).append((t, (a + b) / 2))
for v in mids.values():
    v.sort()
def mid_at(product, t):
    best = None
    for mt, m in mids.get(product, []):
        if mt <= t: best = m
        else: break
    return best
since = datetime.now(timezone.utc) - timedelta(minutes=float(sys.argv[2]) if len(sys.argv) > 2 else 999)
res = {}
for path in glob.glob('logs/run-*.jsonl'):
    for line in open(path):
        d = json.loads(line)
        if d['type'] != 'my_fill': continue
        t = datetime.fromisoformat(d['t'].replace('Z', '+00:00'))
        if t < since: continue
        sign = 1 if d.get('buyer') == sys.argv[1] else -1
        for lag in (60, 120):
            m = mid_at(d['product'], t + timedelta(seconds=lag))
            if m is None: continue
            r = res.setdefault((d['product'], lag), [0, 0.0])
            r[0] += int(d['volume']); r[1] += sign * (m - d['price']) * d['volume']
for (p, lag), (lots, e) in sorted(res.items()):
    print(f'{p:14s} +{lag}s  lots {lots:3d}  edge {e:+7.0f}  per lot {e / lots:+6.1f}')
