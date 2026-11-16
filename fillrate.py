"""Our fills (count and lots) per product in the last N minutes."""
import glob, json, sys
from datetime import datetime, timedelta, timezone
minutes = float(sys.argv[1]) if len(sys.argv) > 1 else 5
since = datetime.now(timezone.utc) - timedelta(minutes=minutes)
counts = {}
for path in glob.glob('logs/run-*.jsonl'):
    for line in open(path):
        d = json.loads(line)
        if d['type'] == 'my_fill' and datetime.fromisoformat(d['t'].replace('Z', '+00:00')) >= since:
            c = counts.setdefault(d['product'], [0, 0]); c[0] += 1; c[1] += int(d['volume'])
total = sum(c[0] for c in counts.values())
print(f'last {minutes:g} min: {total} fills', ' '.join(f'{p}:{c[0]}f/{c[1]}lots' for p, c in sorted(counts.items())))
