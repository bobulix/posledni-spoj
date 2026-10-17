"""Průzkum zdrojů jízdních řádů pro celou ČR: stáhne feedy a vypíše jejich rozsah."""
import csv, io, json, os, sys, zipfile, urllib.request, glob, re
from collections import Counter

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

def atlas_urls(feed_id):
    for f in glob.glob('atlas/feeds/*.json'):
        try:
            d = json.load(open(f, encoding='utf-8'))
        except Exception:
            continue
        for feed in d.get('feeds', []):
            if feed.get('id') == feed_id:
                u = feed.get('urls', {})
                return u.get('static_current'), feed.get('license', {}), f
    return None, None, None

SOURCES = [
    ('PID', 'atlas', 'f-u2f-pražskáintegrovanádoprava'),
    ('IDS-JMK', 'atlas', 'f-u2e-idsjmk'),
    ('DPMO', 'atlas', 'f-u2u6-dpmo'),
    ('PMDP', 'atlas', 'f-plzen'),
    ('DPMLJ', 'atlas', 'f-u348-dpmlj'),
    ('IDZK', 'http', 'https://hoermalmeister.github.io/gtfs-rehost/idzk/id-zk-all.zip'),
    ('JDF-merged', 'http', 'https://github.com/0xaa55h/gtfs-processor/raw/refs/heads/gtfs/files/latest.zip'),
    ('JrUtil-JDF', 'http', 'https://data.jr.ggu.cz/results/latest/JDF_merged_GTFS.zip'),
    ('CZPTT', 'http', 'https://data.jr.ggu.cz/results/latest/CZPTT_GTFS.zip'),
]
# další kandidáti hledané v atlasu podle textu
EXTRA_PATTERNS = ['duk', 'iredo', 'odis', 'idol', 'idsok', 'idpk', 'ids-jk', 'idsjk', 'vydis', 'kodis', 'brno', 'ostrava', 'liberec', 'olomouc', 'plzen', 'ceske', 'hradec', 'pardubic', 'jihlav', 'karlov', 'zlin', 'usti']

report = {'sources': [], 'atlas_cz': []}
for f in glob.glob('atlas/feeds/*.json'):
    try:
        d = json.load(open(f, encoding='utf-8'))
    except Exception:
        continue
    for feed in d.get('feeds', []):
        fid = feed.get('id', '')
        url = feed.get('urls', {}).get('static_current')
        txt = (fid + ' ' + json.dumps(feed.get('urls', {})) + ' ' + f).lower()
        if url and ('.cz' in txt or fid.startswith('f-u2') or any(p in txt for p in EXTRA_PATTERNS)) and feed.get('spec', 'gtfs') == 'gtfs':
            if any(k in txt for k in ['.cz', 'czech', 'praha', 'brno', 'ostrava']) or fid.startswith(('f-u2', 'f-u3')):
                report['atlas_cz'].append({'id': fid, 'url': url, 'file': f})

def summarize(name, path):
    z = zipfile.ZipFile(path)
    def rows(fn):
        if fn not in z.namelist():
            return []
        return list(csv.DictReader(io.TextIOWrapper(z.open(fn), encoding='utf-8-sig')))
    stops = rows('stops.txt'); routes = rows('routes.txt'); trips = rows('trips.txt')
    cal = rows('calendar.txt'); cd = rows('calendar_dates.txt'); ag = rows('agency.txt')
    n_st = 0
    if 'stop_times.txt' in z.namelist():
        with z.open('stop_times.txt') as fh:
            for _ in fh:
                n_st += 1
    lats = [float(s['stop_lat']) for s in stops if s.get('stop_lat')]
    lons = [float(s['stop_lon']) for s in stops if s.get('stop_lon')]
    dates = sorted([c['start_date'] for c in cal] + [c['end_date'] for c in cal] + [c['date'] for c in cd])
    return {
        'name': name, 'size_mb': round(os.path.getsize(path) / 1e6, 1), 'files': z.namelist(),
        'agencies': len(ag), 'agency_names': [a.get('agency_name') for a in ag[:15]],
        'stops': len(stops), 'stops_no_coords': sum(1 for s in stops if not s.get('stop_lat')),
        'routes': len(routes), 'route_types': Counter(r.get('route_type') for r in routes).most_common(),
        'trips': len(trips), 'stop_times': n_st,
        'bbox': [min(lats), min(lons), max(lats), max(lons)] if lats else None,
        'dates': [dates[0], dates[-1]] if dates else None,
        'has_shapes': 'shapes.txt' in z.namelist(),
        'sample_stops': [s.get('stop_name') for s in stops[:8]],
        'sample_routes': [(r.get('route_short_name'), r.get('route_long_name'), r.get('agency_id')) for r in routes[:8]],
    }

for name, kind, ref in SOURCES:
    entry = {'name': name}
    try:
        if kind == 'atlas':
            url, lic, f = atlas_urls(ref)
            entry['atlas_file'] = f; entry['license'] = lic
        else:
            url = ref
        entry['url'] = url
        if not url:
            raise RuntimeError('URL nenalezena')
        path = os.path.join('dl', name + '.zip')
        os.makedirs('dl', exist_ok=True)
        req = urllib.request.Request(url, headers={'User-Agent': 'posledni-spoj/0.1'})
        with urllib.request.urlopen(req, timeout=300) as r, open(path, 'wb') as o:
            o.write(r.read())
        entry.update(summarize(name, path))
        # vzorky souborů pro párování linek mezi zdroji
        z = zipfile.ZipFile(path)
        samp = {}
        for fn in ['routes.txt', 'trips.txt', 'calendar.txt', 'route_sub_agencies.txt', 'agency.txt', 'stops.txt', 'feed_info.txt']:
            if fn in z.namelist():
                with z.open(fn) as fh:
                    lines = []
                    for k, line in enumerate(io.TextIOWrapper(fh, encoding='utf-8-sig')):
                        if k >= 12: break
                        lines.append(line.rstrip())
                samp[fn] = lines
        entry['samples'] = samp
    except Exception as e:
        entry['error'] = repr(e)
    print(json.dumps(entry, ensure_ascii=False)[:2000], flush=True)
    report['sources'].append(entry)

# živé zprávy PID (GTFS-RT alerts) – jde to bez klíče?
for name, url in [('PID-alerts', 'https://api.golemio.cz/v2/vehiclepositions/gtfsrt/alerts.pb'),
                  ('PID-rss', 'https://pid.cz/feed/rss-mimoradnosti')]:
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'posledni-spoj/0.1'})
        with urllib.request.urlopen(req, timeout=60) as r:
            b = r.read()
        report[name] = {'url': url, 'status': 200, 'bytes': len(b), 'head': b[:300].decode('utf-8', 'replace')}
    except Exception as e:
        report[name] = {'url': url, 'error': repr(e)}

json.dump(report, open(os.path.join(OUT, 'report.json'), 'w'), ensure_ascii=False, indent=1)
