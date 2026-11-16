"""Every 5 s, turns the bot's logs into dashboard/data.json: market mid vs data value per product."""
import glob, json, re, time
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

DK = ZoneInfo('Europe/Copenhagen')
START = '16:00'
pat = re.compile(r'\| (\S+) fair (\S+?)±\S+ mkt (\S+)/(\S+)')


def minutes_since_start(hhmmss):
    h, m, s = (int(x) for x in hhmmss.split(':'))
    return (h * 60 + m + s / 60) - 16 * 60


def build():
    out = {}
    for line in open('logs/console-real.txt'):
        if ' fair ' not in line or line[:5] < START:
            continue
        t = minutes_since_start(line[:8])
        for product, _, bid, ask in pat.findall(line):
            try:
                mid = (float(bid) + float(ask)) / 2
            except ValueError:
                continue
            series = out.setdefault(product, {'market': [], 'data': []})
            if not series['market'] or t - series['market'][-1][0] >= 0.1:
                series['market'].append([round(t, 2), mid])
    for path in sorted(glob.glob('logs/run-*.jsonl')):
        for line in open(path):
            if '"type":"fair"' not in line:
                continue
            d = json.loads(line)
            local = datetime.fromisoformat(d['t'].replace('Z', '+00:00')).astimezone(DK)
            if local.strftime('%H:%M') < START:
                continue
            t = minutes_since_start(local.strftime('%H:%M:%S'))
            for product, v in d['values'].items():
                out.setdefault(product, {'market': [], 'data': []})['data'].append([round(t, 2), round(v['fair'], 1)])
    return out


while True:
    try:
        with open('dashboard/data.json.tmp', 'w') as f:
            json.dump({'updated': datetime.now(DK).strftime('%H:%M:%S'), 'products': build()}, f)
        import os
        os.replace('dashboard/data.json.tmp', 'dashboard/data.json')
    except Exception as e:
        print('update failed:', e, flush=True)
    time.sleep(5)
