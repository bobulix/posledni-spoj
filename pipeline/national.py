"""Celostátní data pro Poslední spoj: kombinace krajských a celostátních jízdních řádů.

Použití: python national.py <složka se zdroji> <výstup> [počet dní]
Ve složce zdrojů je pro každý zdroj podsložka s rozbaleným GTFS (název = klíč v SOURCES).
Chybějící zdroj se přeskočí (vlaky a celostátní autobusy jsou povinné).

Princip:
  1. Každý zdroj má prioritu. Kvalitní krajská data (PID, IDS JMK, ...) mají přednost,
     vlaky bereme z celostátních dat Správy železnic (CZPTT), zbytek autobusů z CIS JŘ (JDF).
  2. Jízda z méně prioritního zdroje se zahodí, pokud se aspoň polovina jejích zastavení
     (místo do ~400 m + stejná minuta odjezdu) shoduje s už přijatou jízdou.
     Krajský vlak, který se shoduje s vlakem ze CZPTT, předá vlaku jen své označení (např. S7).
  3. Zastávky se v rámci zdroje seskupí podle názvu (PID podle uzlu), mezi zdroji se sloučí,
     pokud jsou do 150 m se stejným názvem, nebo do 30 m.
Výstup (formát 2): meta.json, conn_<den>.bin (gzip: uint32 n, sloupce u,v uint32, dep,arr uint16,
  trip uint32, flags uint8), trips_<den>.json [route, headsign, shape], shapes.bin.
"""
import pandas as pd, numpy as np, json, sys, datetime, math, os, re, gzip, unicodedata, time
from collections import defaultdict

SRC = sys.argv[1]; OUT = sys.argv[2]; NDAYS = int(sys.argv[3]) if len(sys.argv) > 3 else 14
os.makedirs(OUT, exist_ok=True)
T0 = time.time()
def log(*a): print(f'[{time.time()-T0:6.0f}s]', *a, flush=True)

# klíč, popis zdroje pro aplikaci, druh: regional / trains / buses
SOURCES = [
    ('PID', 'PID (ROPID)', 'regional'),
    ('JMK', 'IDS JMK (KORDIS JMK)', 'regional'),
    ('IDZK', 'IDZK (Zlínský kraj)', 'regional'),
    ('DPMO', 'DPMO (MHD Olomouc)', 'regional'),
    ('PMDP', 'PMDP (MHD Plzeň)', 'regional'),
    ('DPMLJ', 'DPMLJ (MHD Liberec)', 'regional'),
    ('CZPTT', 'Správa železnic (CZPTT)', 'trains'),
    ('JDF', 'CIS JŘ (autobusy)', 'buses'),
]
KX = 111320 * math.cos(math.radians(49.8)); KY = 110540

# ---------- režim dopravy ----------
# vnitřní typ: 0 tram, 1 metro, 2 vlak, 3 bus, 4 loď, 5 lanovka, 11 trolejbus
def simple_type(t):
    try: t = int(t)
    except: return 3
    if t in (0, 1, 2, 3, 4, 5, 6, 7, 11, 12): return {6: 5, 7: 5, 12: 2}.get(t, t)
    if 100 <= t < 200: return 2
    if 400 <= t < 500: return 1
    if t == 800: return 11
    if 900 <= t < 1000: return 0
    if t in (1000, 1200) or 1000 <= t < 1100: return 4
    if 1300 <= t < 1500: return 5
    return 3
MODEBIT = {0: 1, 1: 2, 2: 4, 3: 8, 11: 8}

def norm(s):
    s = unicodedata.normalize('NFKD', s.lower())
    return re.sub(r'[^a-z0-9]', '', ''.join(c for c in s if not unicodedata.combining(c)))

def pretty(s):
    s = re.sub(r',(?=\S)', ', ', s.strip())
    return re.sub(r'\s+', ' ', s)

def tosec(col):
    col = col.fillna('')
    ok = col.str.len() >= 7
    p = col.where(ok, '0:0:0').str.split(':', expand=True).astype(int)
    v = p[0] * 3600 + p[1] * 60 + p[2]
    return v.where(ok, -1).astype(np.int32).values

# ---------- načtení jednoho zdroje ----------
def window_dates(cal, cd):
    today = datetime.date.today()
    return [(today + datetime.timedelta(days=k)).strftime('%Y%m%d') for k in range(NDAYS)]

WD = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday']

def load_source(key, desc, kind, dates):
    d = os.path.join(SRC, key)
    if not os.path.exists(os.path.join(d, 'stop_times.txt')):
        log(key, 'chybí, přeskočeno'); return None
    R = lambda f, **k: pd.read_csv(os.path.join(d, f), dtype=str, keep_default_na=False, encoding='utf-8-sig', **k) if os.path.exists(os.path.join(d, f)) else None
    stops = R('stops.txt'); routes = R('routes.txt'); trips = R('trips.txt')
    cal = R('calendar.txt'); cd = R('calendar_dates.txt')
    for df in (stops, routes, trips):
        df.columns = [c.strip() for c in df.columns]
    # aktivní služby po dnech
    act = {}
    for day in dates:
        dt = datetime.datetime.strptime(day, '%Y%m%d'); wd = WD[dt.weekday()]
        a = set()
        if cal is not None and len(cal):
            a = set(cal[(cal[wd] == '1') & (cal.start_date <= day) & (cal.end_date >= day)].service_id)
        if cd is not None and len(cd):
            x = cd[cd.date == day]
            a |= set(x[x.exception_type == '1'].service_id); a -= set(x[x.exception_type == '2'].service_id)
        act[day] = a
    allact = set().union(*act.values())
    trips = trips[trips.service_id.isin(allact)].reset_index(drop=True)
    if 'trip_headsign' not in trips: trips['trip_headsign'] = ''
    if 'shape_id' not in trips: trips['shape_id'] = ''
    tcode = {t: i for i, t in enumerate(trips.trip_id)}
    # zastávky
    stops = stops[stops.get('location_type', pd.Series('0', index=stops.index)).isin(['0', ''])].reset_index(drop=True)
    scode = {s: i for i, s in enumerate(stops.stop_id)}
    # zastavení (po částech kvůli paměti)
    cols = ['trip_id', 'arrival_time', 'departure_time', 'stop_id', 'stop_sequence', 'pickup_type', 'drop_off_type']
    head = pd.read_csv(os.path.join(d, 'stop_times.txt'), nrows=0, encoding='utf-8-sig').columns
    use = [c for c in cols if c in head]
    parts = []
    for ch in pd.read_csv(os.path.join(d, 'stop_times.txt'), dtype=str, keep_default_na=False, usecols=use,
                          encoding='utf-8-sig', chunksize=2_000_000):
        t = ch.trip_id.map(tcode); s = ch.stop_id.map(scode)
        ok = t.notna() & s.notna(); ch = ch[ok]
        if not len(ch): continue
        arr = tosec(ch.arrival_time); dep = tosec(ch.departure_time)
        arr = np.where(arr < 0, dep, arr); dep = np.where(dep < 0, arr, dep)
        pk = (ch.pickup_type.values != '1') if 'pickup_type' in ch else np.ones(len(ch), bool)
        dr = (ch.drop_off_type.values != '1') if 'drop_off_type' in ch else np.ones(len(ch), bool)
        parts.append(pd.DataFrame({'t': t[ok].astype(np.int32).values, 's': s[ok].astype(np.int32).values,
            'seq': ch.stop_sequence.astype(np.int32).values, 'arr': arr, 'dep': dep,
            'pk': pk, 'dr': dr}))
    stt = pd.concat(parts, ignore_index=True)
    stt = stt[stt.dep >= 0].sort_values(['t', 'seq'], kind='stable').reset_index(drop=True)
    rtype = dict(zip(routes.route_id, routes.route_type))
    trips['type'] = trips.route_id.map(lambda r: simple_type(rtype.get(r, '3')))
    log(key, 'zastávek', len(stops), 'jízd', len(trips), 'zastavení', len(stt))
    return dict(key=key, desc=desc, kind=kind, stops=stops, routes=routes, trips=trips, stt=stt, act=act)

# ---------- označení linky ----------
def route_label(src, r):
    s = (r.get('route_short_name') or '').strip()
    if src == 'JDF' and re.fullmatch(r'\d{6}', s):
        # MHD (typ 704 / 800 / 900): číslo linky = poslední trojčíslí, jinak licenční číslo
        if str(r.get('route_type', '')) in ('704', '800', '900', '0', '11'):
            return str(int(s[3:])) if int(s[3:]) else s
        return s
    return s or (r.get('route_long_name') or '')[:20]

# ---------- hlavní běh ----------
dates = window_dates(None, None)
log('dny', dates[0], '…', dates[-1])
srcs = [x for x in (load_source(k, d, kd, dates) for k, d, kd in SOURCES) if x is not None]
assert any(s['key'] == 'CZPTT' for s in srcs) and any(s['key'] == 'JDF' for s in srcs)

# --- uzly: v rámci zdroje podle názvu (PID: asw uzel), mezi zdroji podle polohy a názvu ---
allst = []
for si, s in enumerate(srcs):
    st = s['stops']
    used = np.zeros(len(st), bool); used[s['stt'].s.unique()] = True
    lat = pd.to_numeric(st.stop_lat, errors='coerce').values; lon = pd.to_numeric(st.stop_lon, errors='coerce').values
    name = st.stop_name.map(pretty).values
    if s['key'] == 'PID':
        grp = np.where(st.asw_node_id != '', 'N' + st.asw_node_id, 'S' + st.stop_id)
        praha = st.zone_id.str.split(',').apply(lambda z: 'P' in z).values
    else:
        grp = np.array(['n' + norm(x) for x in name])
        praha = np.array([norm(x).startswith('praha') for x in name])
    allst.append(pd.DataFrame({'src': si, 'sid': np.arange(len(st)), 'grp': grp, 'name': name, 'lat': lat, 'lon': lon,
                               'praha': praha, 'used': used}))
A = pd.concat(allst, ignore_index=True)
A = A[A.used & A.lat.between(47, 52) & A.lon.between(11, 20)].reset_index(drop=True)
A['X'] = A.lon * KX; A['Y'] = A.lat * KY

# union-find
par = np.arange(len(A))
def find(i):
    while par[i] != i:
        par[i] = par[par[i]]; i = par[i]
    return i
def union(i, j):
    a, b = find(i), find(j)
    if a != b: par[max(a, b)] = min(a, b)

X = A.X.values; Y = A.Y.values; S = A.src.values; G = A.grp.values
NM = np.array([norm(x) for x in A.name.values])
def cellmap(cell):
    cx = np.floor(X / cell).astype(np.int64); cy = np.floor(Y / cell).astype(np.int64)
    grid = defaultdict(list)
    for i in range(len(A)): grid[(cx[i], cy[i])].append(i)
    return cx, cy, grid
# 1) v rámci zdroje: stejná skupina do 400 m
cx, cy, grid = cellmap(400)
for i in range(len(A)):
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for j in grid.get((cx[i] + dx, cy[i] + dy), ()):
                if j <= i or S[i] != S[j]: continue
                if G[i] == G[j] and math.hypot(X[i] - X[j], Y[i] - Y[j]) <= 400: union(i, j)
# 2) mezi zdroji: stejný název do 150 m, jinak do 30 m
cx, cy, grid = cellmap(150)
for i in range(len(A)):
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for j in grid.get((cx[i] + dx, cy[i] + dy), ()):
                if j <= i or S[i] == S[j]: continue
                dd = math.hypot(X[i] - X[j], Y[i] - Y[j])
                if dd <= 30 or (dd <= 150 and NM[i] == NM[j]): union(i, j)
root = np.array([find(i) for i in range(len(A))])
A['root'] = root
log('zastávek celkem', len(A), 'uzlů', len(np.unique(root)))

# --- zastavení všech zdrojů do jedné tabulky s globálními uzly ---
gst = {}  # (src, sid) -> řádek A
for si in range(len(srcs)):
    m = A[A.src == si]; arr = np.full(len(srcs[si]['stops']), -1, np.int64); arr[m.sid.values] = m.index.values
    gst[si] = arr

# priorita: krajská data (kromě vlaků) < CZPTT < krajské vlaky < JDF
def prio(si, typ):
    k = srcs[si]['kind']
    if k == 'regional': return si if typ != 2 else 100 + si
    if k == 'trains': return 50
    return 200

TR = []  # globální seznam jízd
for si, s in enumerate(srcs):
    t = s['trips']; rmap = {r['route_id']: r for r in s['routes'].to_dict('records')}
    for r in t.itertuples():
        TR.append((si, r.Index, r.route_id, r.trip_headsign, r.shape_id, r.type, r.service_id))
TRdf = pd.DataFrame(TR, columns=['src', 'lt', 'route_id', 'head', 'shape', 'type', 'svc'])
TRdf['prio'] = [prio(a, b) for a, b in zip(TRdf.src, TRdf.type)]
toff = {}; off = 0
for si, s in enumerate(srcs): toff[si] = off; off += len(s['trips'])

E = []
for si, s in enumerate(srcs):
    st = s['stt']
    row = gst[si][st.s.values]
    E.append(pd.DataFrame({'g': st.t.values + toff[si], 'row': row, 'arr': st.arr.values, 'dep': st.dep.values,
                           'pk': st.pk.values, 'dr': st.dr.values}))
E = pd.concat(E, ignore_index=True)
E = E[E.row >= 0].reset_index(drop=True)
E['node'] = A.root.values[E.row.values]
log('zastavení celkem', len(E))

# --- deduplikace jízd (pro každý den zvlášť, po úrovních priority) ---
CELL = 400
def ev_keys(rows, dep, dm=0, dx=0, dy=0):
    cxx = np.floor(X[rows] / CELL).astype(np.int64) + dx; cyy = np.floor(Y[rows] / CELL).astype(np.int64) + dy
    return ((cxx * 4000 + cyy) * 3000 + (dep // 60 + dm))

Eg = E.g.values; Erow = E.row.values; Edep = E.dep.values
assert (np.diff(Eg) >= 0).all()
starts = np.searchsorted(Eg, np.arange(len(TRdf) + 1))  # E je seřazené podle g
label_from = {}  # globální jízda -> označení převzaté z krajského vlaku
day_trips = {}
stats = []
for day in dates:
    active = np.zeros(len(TRdf), bool)
    for si, s in enumerate(srcs):
        a = s['act'][day]; o = toff[si]
        active[o:o + len(s['trips'])] = s['trips'].service_id.isin(a).values
    keep = np.zeros(len(TRdf), bool)
    acc_keys = np.zeros(0, np.int64); acc_g = np.zeros(0, np.int64)
    st_row = {'den': day}
    for p in sorted(TRdf.prio[active].unique()):
        cand = np.where(active & (TRdf.prio.values == p))[0]
        # zastavení kandidátů
        sel = np.zeros(len(E), bool)
        lo = starts[cand]; hi = starts[cand + 1]
        idx = np.concatenate([np.arange(a, b) for a, b in zip(lo, hi)]) if len(cand) else np.zeros(0, np.int64)
        g = Eg[idx]; rows = Erow[idx]; dep = Edep[idx]
        if len(acc_keys):
            k = ev_keys(rows, dep)
            hit = np.zeros(len(idx), bool); best = np.full(len(idx), -1, np.int64)
            sk = np.argsort(acc_keys); ak = acc_keys[sk]; ag = acc_g[sk]
            for dm in (-1, 0, 1):
                kk = k + dm
                pos = np.searchsorted(ak, kk); pos = np.minimum(pos, len(ak) - 1)
                h = ak[pos] == kk
                best = np.where(h & ~hit, ag[pos], best); hit |= h
            cnt = pd.Series(hit.astype(int)).groupby(g).agg(['sum', 'size'])
            dup = cnt.index.values[(cnt['sum'] >= 2) & (cnt['sum'] * 2 >= cnt['size'])]
            # krajské vlaky předají označení
            if p >= 100 and p < 200 and len(dup):
                bdf = pd.DataFrame({'g': g, 'b': best})
                bdf = bdf[(bdf.b >= 0) & bdf.g.isin(dup)]
                for gg, bb in bdf.groupby('g').b.agg(lambda s: s.value_counts().index[0]).items():
                    label_from[int(bb)] = int(gg)
        else:
            dup = np.zeros(0, np.int64)
        ok = np.setdiff1d(cand, dup)
        keep[ok] = True
        st_row[f'p{p}'] = f'{len(ok)}/{len(cand)}'
        # přidání událostí přijatých jízd (posunuté do sousedních buněk)
        m = np.isin(g, ok)
        if m.any():
            r2 = rows[m]; d2 = dep[m]; g2 = g[m]
            nk = [ev_keys(r2, d2, 0, dx, dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1)]
            acc_keys = np.concatenate([acc_keys] + nk); acc_g = np.concatenate([acc_g] + [g2] * 9)
    day_trips[day] = np.where(keep)[0]
    st_row['jízd'] = int(keep.sum())
    stats.append(st_row)
    log(day, st_row)

# --- uzly použité ve výstupu ---
alltr = np.unique(np.concatenate([day_trips[d] for d in dates]))
used_rows = np.unique(Erow[np.concatenate([np.arange(starts[t], starts[t + 1]) for t in alltr])])
used_roots = np.unique(A.root.values[used_rows])
nidx = {r: i for i, r in enumerate(used_roots)}
NN = len(used_roots)
SRCPRI = {si: (0 if s['kind'] == 'regional' else 1 if s['kind'] == 'trains' else 2) for si, s in enumerate(srcs)}
Au = A[A.root.isin(used_roots)].copy(); Au['pr'] = Au.src.map(SRCPRI)
nodes = []
gb = Au.sort_values('pr').groupby('root', sort=False)
info = {}
for r, gr in gb:
    nm = gr.name.value_counts()
    best = gr[gr.pr == gr.pr.min()].name.value_counts().index[0]
    info[r] = (best, gr.lat.mean(), gr.lon.mean(), bool(gr.praha.any()))

# režimy uzlů
modes = np.zeros(NN, np.int64)
tmode = np.array([MODEBIT.get(t, 16) for t in TRdf.type.values])
for t in alltr:
    rr = A.root.values[Erow[starts[t]:starts[t + 1]]]
    for r in np.unique(rr): modes[nidx[r]] |= tmode[t]

# pěší přechody do 450 m
nlat = np.array([info[r][1] for r in used_roots]); nlon = np.array([info[r][2] for r in used_roots])
NX = nlon * KX; NY = nlat * KY
cxn = np.floor(NX / 450).astype(np.int64); cyn = np.floor(NY / 450).astype(np.int64)
grid = defaultdict(list)
for i in range(NN): grid[(cxn[i], cyn[i])].append(i)
walk = []
for i in range(NN):
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for j in grid.get((cxn[i] + dx, cyn[i] + dy), ()):
                if j <= i: continue
                dd = math.hypot(NX[i] - NX[j], NY[i] - NY[j])
                if dd <= 450: walk.append((i, j, int(math.ceil(dd * 1.3 / 70)) + 1))
log('uzlů', NN, 'pěších přechodů', len(walk))

# linky (globální seznam)
routes = []; ridx = {}
for si, s in enumerate(srcs):
    for r in s['routes'].to_dict('records'):
        ridx[(si, r['route_id'])] = len(routes)
        col = (r.get('route_color') or '').strip()
        routes.append([route_label(s['key'], r), simple_type(r.get('route_type', '3')), col if re.fullmatch(r'[0-9A-Fa-f]{6}', col) else '', si])

# tvary tras (jen zdroje se shapes.txt)
def _dp(pts, eps):
    n = len(pts); keep = np.zeros(n, bool); keep[0] = keep[-1] = True; stck = [(0, n - 1)]
    while stck:
        i, j = stck.pop()
        if j <= i + 1: continue
        a = pts[i]; b = pts[j]; ab = b - a; L = np.hypot(*ab); seg = pts[i + 1:j]
        dd = np.abs(ab[0] * (seg[:, 1] - a[1]) - ab[1] * (seg[:, 0] - a[0])) / L if L > 0 else np.hypot(*(seg - a).T)
        k = int(np.argmax(dd))
        if dd[k] > eps: mm = i + 1 + k; keep[mm] = True; stck += [(i, mm), (mm, j)]
    return keep
def _enc(v):
    v = ~(v << 1) if v < 0 else (v << 1); out = ''
    while v >= 0x20: out += chr((0x20 | (v & 0x1f)) + 63); v >>= 5
    return out + chr(v + 63)
shidx = {}; shlist = []
used_shapes = set(zip(TRdf.src.values[alltr], TRdf['shape'].values[alltr]))
for si, s in enumerate(srcs):
    f = os.path.join(SRC, s['key'], 'shapes.txt')
    if not os.path.exists(f): continue
    shp = pd.read_csv(f, usecols=['shape_id', 'shape_pt_lat', 'shape_pt_lon', 'shape_pt_sequence'], dtype={'shape_id': str}, encoding='utf-8-sig')
    shp = shp[shp.shape_id.map(lambda x: (si, x) in used_shapes)].sort_values(['shape_id', 'shape_pt_sequence'])
    for sid, g in shp.groupby('shape_id', sort=False):
        la_ = g.shape_pt_lat.values; lo_ = g.shape_pt_lon.values
        k = _dp(np.stack([lo_ * KX, la_ * KY], 1), 8)
        la = np.round(la_[k] * 1e5).astype(int); lo = np.round(lo_[k] * 1e5).astype(int)
        e = _enc(int(la[0])) + _enc(int(lo[0]))
        for i in range(1, len(la)): e += _enc(int(la[i] - la[i - 1])) + _enc(int(lo[i] - lo[i - 1]))
        shidx[(si, sid)] = len(shlist); shlist.append(e)
with gzip.open(os.path.join(OUT, 'shapes.bin'), 'wb', 9) as f: f.write(json.dumps(shlist).encode())
log('tvarů tras', len(shlist))

meta = {'format': 2,
        'nodes': [[info[r][0], round(info[r][1], 5), round(info[r][2], 5), int(info[r][3]), int(modes[i])] for i, r in enumerate(used_roots)],
        'routes': routes, 'sources': [s['desc'] for s in srcs],
        'walk': np.array(walk, np.int32).flatten().tolist(), 'days': [],
        'generated': datetime.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ'), 'stats': stats}

Eroot = A.root.values[Erow]
Enode = np.array([nidx.get(r, -1) for r in Eroot]) if False else pd.Series(Eroot).map(nidx).fillna(-1).astype(np.int64).values
T_src = TRdf.src.values; T_rid = TRdf.route_id.values; T_head = TRdf['head'].values; T_shape = TRdf['shape'].values
Earr = E.arr.values; Epk = E.pk.values; Edr = E.dr.values
for day in dates:
    tl = day_trips[day]
    idx = np.concatenate([np.arange(starts[t], starts[t + 1]) for t in tl])
    loc = np.repeat(np.arange(len(tl)), starts[tl + 1] - starts[tl])
    a = idx[:-1]; b = idx[1:]; ok = loc[:-1] == loc[1:]
    a = a[ok]; b = b[ok]; ti = loc[:-1][ok]
    u = Enode[a]; v = Enode[b]
    keepc = (u != v) & (u >= 0) & (v >= 0)
    a, b, ti, u, v = a[keepc], b[keepc], ti[keepc], u[keepc], v[keepc]
    dep = Edep[a] // 60; arr = -((-Earr[b]) // 60)
    arr = np.maximum(arr, dep)
    fl = Epk[a].astype(np.uint8) + 2 * Edr[b].astype(np.uint8)
    o = np.lexsort((-arr, -dep))
    n = len(o)
    with gzip.open(os.path.join(OUT, f'conn_{day}.bin'), 'wb', 9) as f:
        f.write(np.uint32(n).tobytes())
        f.write(u[o].astype(np.uint32).tobytes()); f.write(v[o].astype(np.uint32).tobytes())
        f.write(dep[o].astype(np.uint16).tobytes()); f.write(arr[o].astype(np.uint16).tobytes())
        f.write(ti[o].astype(np.uint32).tobytes()); f.write(fl[o].astype(np.uint8).tobytes())
    tinfo = []
    for t in tl:
        si = int(T_src[t]); rid = T_rid[t]
        ri = ridx[(si, rid)]
        lab = label_from.get(int(t))
        h = pretty(str(T_head[t]))
        e = [ri, h, shidx.get((si, T_shape[t]), -1)]
        if lab is not None:
            e.append(routes[ridx[(int(T_src[lab]), T_rid[lab])]][0])
        tinfo.append(e)
    with open(os.path.join(OUT, f'trips_{day}.json'), 'w') as f: json.dump(tinfo, f, ensure_ascii=False, separators=(',', ':'))
    dt = datetime.datetime.strptime(day, '%Y%m%d')
    meta['days'].append([day, WD[dt.weekday()]])
    log(day, 'jízd', len(tl), 'spojů', n, os.path.getsize(os.path.join(OUT, f'conn_{day}.bin')) // 1024, 'KB',
        'trips', os.path.getsize(os.path.join(OUT, f'trips_{day}.json')) // 1024, 'KB')
with open(os.path.join(OUT, 'meta.json'), 'w') as f: json.dump(meta, f, ensure_ascii=False, separators=(',', ':'))
log('meta KB', os.path.getsize(os.path.join(OUT, 'meta.json')) // 1024, 'hotovo')
