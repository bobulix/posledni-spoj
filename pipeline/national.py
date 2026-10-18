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
# --- doplnění chybějících souřadnic zastávek (CIS JŘ je u části zastávek nemá) ---
# 1) stejný název zastávky v jiném zdroji nebo v OpenStreetMap (zastávky), 2) obec z OSM,
# 3) odhad z polohy sousedních zastávek na trasách. Víc kandidátů = ten nejblíž odhadu.
def valid(la, lo): return np.isfinite(la) & np.isfinite(lo) & (la > 47.5) & (la < 51.5) & (lo > 11.8) & (lo < 19.2)
ref_stop = defaultdict(list); ref_place = defaultdict(list)
osmf = os.path.join(SRC, 'osm_stops.csv')
if os.path.exists(osmf):
    o = pd.read_csv(osmf, sep='\t', header=None, names=['lat', 'lon', 'name', 'place'], dtype=str, keep_default_na=False, quoting=3)
    o['la'] = pd.to_numeric(o.lat, errors='coerce'); o['lo'] = pd.to_numeric(o.lon, errors='coerce')
    o = o[valid(o.la.values, o.lo.values) & (o.name != '')]
    for nm_, la, lo, pl in zip(o.name.values, o.la.values, o.lo.values, o.place.values):
        (ref_place if pl else ref_stop)[norm(nm_)].append((la, lo))
    log('OSM: zastávek', sum(o.place == ''), 'obcí', sum(o.place != ''))
else:
    log('OSM zastávky nejsou k dispozici')
reff = os.path.join(SRC, 'ref_stops.txt')
if os.path.exists(reff):
    rs = pd.read_csv(reff, dtype=str, keep_default_na=False)
    rla = pd.to_numeric(rs.stop_lat, errors='coerce').values; rlo = pd.to_numeric(rs.stop_lon, errors='coerce').values
    okr = valid(rla, rlo)
    for nm_, a_, b_ in zip(rs.stop_name.values[okr], rla[okr], rlo[okr]): ref_stop[norm(nm_)].append((a_, b_))
    log('referenční zastávky', int(okr.sum()))
for s in srcs:
    st = s['stops']; la = pd.to_numeric(st.stop_lat, errors='coerce').values; lo = pd.to_numeric(st.stop_lon, errors='coerce').values
    ok = valid(la, lo)
    for nm_, a_, b_ in zip(st.stop_name.values[ok], la[ok], lo[ok]): ref_stop[norm(nm_)].append((a_, b_))
for s in srcs:
    st = s['stops']
    la = pd.to_numeric(st.stop_lat, errors='coerce').values.astype(float); lo = pd.to_numeric(st.stop_lon, errors='coerce').values.astype(float)
    ok = valid(la, lo)
    used = np.zeros(len(st), bool); used[s['stt'].s.unique()] = True
    miss = np.where(~ok & used)[0]
    if not len(miss): continue
    # sousedé na trasách
    stt = s['stt']; sa = stt.s.values; ta = stt.t.values
    same = ta[1:] == ta[:-1]
    nb = defaultdict(set)
    for a_, b_ in zip(sa[:-1][same], sa[1:][same]):
        if a_ != b_: nb[a_].add(b_); nb[b_].add(a_)
    names_ = st.stop_name.values
    method = defaultdict(int)
    for it in range(6):
        left = [i for i in miss if not ok[i]]
        if not left: break
        changed = False
        for i in left:
            kn = [j for j in nb[i] if ok[j]]
            est = (np.mean([la[j] for j in kn]), np.mean([lo[j] for j in kn])) if kn else None
            def pick(cands, maxd):
                if not cands: return None
                if est is None: return cands[0] if len(cands) == 1 else None
                d = [math.hypot((c[0] - est[0]) * KY, (c[1] - est[1]) * KX) for c in cands]
                k = int(np.argmin(d)); return cands[k] if d[k] <= maxd else None
            nm_ = names_[i]
            c = pick(ref_stop.get(norm(nm_), []), 15000)
            how = 'název'
            if c is None and ',' in nm_:
                # „Obec,část“ → zastávka „část“ v OSM do 6 km od obce
                town_ = ref_place.get(norm(nm_.split(',')[0]), [])
                part_ = ref_stop.get(norm(nm_.split(',', 1)[1]), [])
                near_ = [q for q in part_ if any(math.hypot((q[0] - t_[0]) * KY, (q[1] - t_[1]) * KX) < 6000 for t_ in town_)]
                c = pick(near_, 15000); how = 'část'
                if c is None and est is None and len({(round(q[0], 3), round(q[1], 3)) for q in near_}) == 1: c = near_[0]
            if c is None and it >= 1:
                c = pick(ref_place.get(norm(nm_.split(',')[0]), []), 15000); how = 'obec'
            if c is None and it >= 2 and est is not None and len(kn) >= 2:
                c = est; how = 'sousedé'
            if c is not None:
                la[i], lo[i] = c; ok[i] = True; changed = True; method[how] += 1
        if not changed and it >= 2: break
    st['stop_lat'] = la.astype(str); st['stop_lon'] = lo.astype(str)
    with open(os.path.join(OUT, 'bez_souradnic.txt'), 'a') as f_:
        for i in miss:
            if not ok[i]: f_.write(f"{s['key']}\t{names_[i]}\n")
    log(s['key'], 'bez souřadnic', len(miss), 'doplněno', dict(method), 'zbývá', int((~ok[miss]).sum()))

allst = []
for si, s in enumerate(srcs):
    st = s['stops']
    used = np.zeros(len(st), bool); used[s['stt'].s.unique()] = True
    lat = pd.to_numeric(st.stop_lat, errors='coerce').values; lon = pd.to_numeric(st.stop_lon, errors='coerce').values
    name = st.stop_name.map(pretty).values
    # MHD bez názvu obce: doplníme obec, aby šly zastávky najít a sloučit s CIS JŘ („Brno, Hlavní nádraží“)
    city = {'DPMO': 'Olomouc', 'PMDP': 'Plzeň', 'DPMLJ': 'Liberec'}.get(s['key'])
    if city:
        name = np.array([x if x.startswith(city) else f'{city}, {x}' for x in name])
    if s['key'] == 'JMK' and 'zone_id' in st:
        brno = st.zone_id.isin(['100', '101']).values
        name = np.array([f'Brno, {x}' if b and not x.startswith('Brno') else x for x, b in zip(name, brno)])
    if s['key'] == 'PID':
        grp = np.where(st.asw_node_id != '', 'N' + st.asw_node_id, 'S' + st.stop_id)
        praha = st.zone_id.str.split(',').apply(lambda z: 'P' in z).values
        # pražské zastávky PID nemají v názvu obec: „Hlavní nádraží“ → „Praha, Hlavní nádraží“
        name = np.array([f'Praha, {x}' if p_ and not x.startswith('Praha') else x for x, p_ in zip(name, praha)])
    else:
        grp = np.array(['n' + norm(x) for x in name])
        praha = np.array([norm(x).startswith('praha') for x in name])
    allst.append(pd.DataFrame({'src': si, 'sid': np.arange(len(st)), 'grp': grp, 'name': name, 'lat': lat, 'lon': lon,
                               'praha': praha, 'used': used, 'raw': st.stop_name.str.strip().values}))
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
NM = np.array([re.sub(r'^praha', '', norm(x)) for x in A.name.values])
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
    t = s['trips']; rsn = dict(zip(s['routes'].route_id, s['routes'].route_short_name))
    rlab = {r['route_id']: route_label(s['key'], r) for r in s['routes'].to_dict('records')}
    rraw = dict(zip(s['routes'].route_id, s['routes'].route_type))
    for r in t.itertuples():
        TR.append((si, r.Index, r.route_id, r.trip_headsign, r.shape_id, r.type, r.service_id, f"{si}:{rsn.get(r.route_id, '')}",
                   rlab.get(r.route_id, ''), str(rraw.get(r.route_id, ''))))
TRdf = pd.DataFrame(TR, columns=['src', 'lt', 'route_id', 'head', 'shape', 'type', 'svc', 'line', 'label', 'raw'])
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
# Jízda je duplikát, pokud se aspoň polovina jejích zastavení shoduje s JEDNOU už přijatou jízdou
# stejného druhu (vlak / ostatní): místo do ~200–600 m (buňky 200 m + sousední) a odjezd ±1 min.
CELL = 200
TRAINCLS = (TRdf.type.values == 2).astype(np.int64)
def ev_keys(rows, dep, cls, dx=0, dy=0):
    cxx = np.floor(X[rows] / CELL).astype(np.int64) + dx; cyy = np.floor(Y[rows] / CELL).astype(np.int64) + dy
    return (((cxx * 40000 + cyy) * 3000 + dep // 60) * 2 + cls)

Eg = E.g.values; Erow = E.row.values; Edep = E.dep.values
Estop = (E.pk.values | E.dr.values)  # skutečné zastavení (ne jen průjezd)
LINE = TRdf.line.values
assert (np.diff(Eg) >= 0).all()
starts = np.searchsorted(Eg, np.arange(len(TRdf) + 1))  # E je seřazené podle g
label_from = {}  # globální jízda -> označení převzaté z krajského vlaku
# MHD z CIS JŘ, kterou pokrývají krajská data (stejné číslo linky na stejných místech), vynecháme celou
REG = np.array([srcs[i]['kind'] == 'regional' for i in TRdf.src.values])
MHD = np.array([srcs[i]['key'] == 'JDF' for i in TRdf.src.values]) & TRdf.raw.isin(['704', '800', '900', '0', '11']).values
cxe = np.floor(X[Erow] / 300).astype(np.int64); cye = np.floor(Y[Erow] / 300).astype(np.int64)
LAB = TRdf.label.values
rg = pd.DataFrame({'lab': LAB[Eg], 'cx': cxe, 'cy': cye})[REG[Eg]].drop_duplicates()
regset = set()
for dx in (-1, 0, 1):
    for dy in (-1, 0, 1):
        regset.update(zip(rg.lab.values, rg.cx.values + dx, rg.cy.values + dy))
jm = pd.DataFrame({'line': LINE[Eg], 'lab': LAB[Eg], 'cx': cxe, 'cy': cye})[MHD[Eg]].drop_duplicates()
jm['hit'] = [(a, b, c) in regset for a, b, c in zip(jm.lab.values, jm.cx.values, jm.cy.values)]
share = jm.groupby('line').hit.mean()
covered = set(share.index[share >= 0.5])
pre_drop = np.isin(LINE, list(covered)) & MHD
log('MHD z CIS JŘ pokrytá krajskými daty: linek', len(covered), 'jízd', int(pre_drop.sum()), 'z', int(MHD.sum()))

day_trips = {}
stats = []
DBG_DAY = next((d for d in dates if datetime.datetime.strptime(d, '%Y%m%d').weekday() < 5), dates[0])
dbg = []
for day in dates:
    active = np.zeros(len(TRdf), bool)
    for si, s in enumerate(srcs):
        a = s['act'][day]; o = toff[si]
        active[o:o + len(s['trips'])] = s['trips'].service_id.isin(a).values
    active &= ~pre_drop
    keep = np.zeros(len(TRdf), bool)
    acc = []  # DataFrame(k, b) přijatých událostí
    st_row = {'den': day}
    for p in sorted(TRdf.prio[active].unique()):
        cand = np.where(active & (TRdf.prio.values == p))[0]
        lens = starts[cand + 1] - starts[cand]
        idx = np.concatenate([np.arange(a, b) for a, b in zip(starts[cand], starts[cand + 1])]) if len(cand) else np.zeros(0, np.int64)
        idx = idx[Estop[idx]]
        lens = np.bincount(Eg[idx] - 0, minlength=len(TRdf))[cand] if len(idx) else lens
        g = Eg[idx]; rows = Erow[idx]; dep = Edep[idx]; cls = TRAINCLS[g]
        k = ev_keys(rows, dep, cls)
        dup = np.zeros(0, np.int64)
        if acc:
            AC = pd.concat(acc, ignore_index=True)
            ev = np.arange(len(idx))
            hits = []
            for dm in (-1, 0, 1):
                q = pd.DataFrame({'k': k + dm * 2, 'e': ev})
                hits.append(q.merge(AC, on='k')[['e', 'b']])
            H = pd.concat(hits, ignore_index=True).drop_duplicates()
            H['g'] = g[H.e.values]
            pc = H.groupby(['g', 'b']).size().rename('n').reset_index().sort_values('n', ascending=False).drop_duplicates('g')
            size = pd.Series(lens, index=cand)
            pc['size'] = size.loc[pc.g.values].values
            same = (pc.n >= 2) & (pc.n * 2 >= pc['size'])
            if 100 <= p < 200:  # krajský vlak: 3 shodná zastavení s jedním vlakem stačí
                same |= pc.n >= 3
            d = pc[same]
            dup = d.g.values
            if p >= 200 and len(dup):
                # linka, jejíž aspoň 80 % jízd je v krajských datech, se bere z krajských dat celá
                lc = pd.Series(LINE[cand]); ld = pd.Series(np.isin(cand, dup))
                share = ld.groupby(lc).mean()
                whole = set(share.index[share >= 0.8])
                dup = np.union1d(dup, cand[lc.isin(whole).values])
            if 100 <= p < 200:
                for gg, bb in zip(d.g.values, d.b.values):
                    if TRAINCLS[bb]: label_from[int(bb)] = int(gg)
            if day == DBG_DAY:
                for r in pc.itertuples(): dbg.append((int(r.g), int(p), int(r.n), int(r.size), int(r.b)))
        ok = np.setdiff1d(cand, dup)
        keep[ok] = True
        st_row[f'p{p}'] = f'{len(ok)}/{len(cand)}'
        m = np.isin(g, ok)
        if m.any():
            r2 = rows[m]; d2 = dep[m]; g2 = g[m]; c2 = cls[m]
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    acc.append(pd.DataFrame({'k': ev_keys(r2, d2, c2, dx, dy), 'b': g2}))
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
    # název pro IDOS: úřední název z CIS JŘ (např. „Žerotín,u kaple“), jinak ze Správy železnic či PID
    # stejně pojmenovaná zastávka z CIS JŘ má přednost, jinak se název převede do tvaru CIS („Obec,část“)
    keys_ = [srcs[x]['key'] for x in gr.src.values]
    idos = None
    for k_, nm2, raw_ in zip(keys_, gr.name.values, gr.raw.values):
        if k_ == 'JDF' and nm2 == best: idos = raw_; break
    if idos is None:
        idos = best.replace(', ', ',')
        if 'CZPTT' in keys_: idos = re.sub(r'\.\s+(?=[a-záčďéěíňóřšťúůýž])', '.', idos)
    info[r] = (best, gr.lat.mean(), gr.lon.mean(), bool(gr.praha.any()), idos)

# stejné názvy na různých místech (např. 9× „Nová Ves“): doplníme nejbližší větší obec
names_ = {r: info[r][0] for r in used_roots}
tc = defaultdict(list)
for r, nm_ in names_.items():
    t_ = nm_.split(',')[0].strip()
    tc[t_].append(r)
bigtowns = [(t_, np.mean([info[r][1] for r in rs]), np.mean([info[r][2] for r in rs])) for t_, rs in tc.items() if len(rs) >= 20]
bt_lat = np.array([b[1] for b in bigtowns]); bt_lon = np.array([b[2] for b in bigtowns])
byname = defaultdict(list)
for r, nm_ in names_.items(): byname[nm_].append(r)
renamed = 0
for nm_, rs in byname.items():
    if len(rs) < 2: continue
    la = np.array([info[r][1] for r in rs]); lo = np.array([info[r][2] for r in rs])
    if (la.max() - la.min()) * KY < 2000 and (lo.max() - lo.min()) * KX < 2000: continue
    # shluky do 2 km
    lab_ = list(range(len(rs)))
    for i in range(len(rs)):
        for j in range(i):
            if math.hypot((la[i] - la[j]) * KY, (lo[i] - lo[j]) * KX) < 2000:
                a_, b_ = lab_[i], lab_[j]
                lab_ = [b_ if x == a_ else x for x in lab_]
    if len(set(lab_)) < 2: continue
    for c in set(lab_):
        mem = [k for k in range(len(rs)) if lab_[k] == c]
        cla = la[mem].mean(); clo = lo[mem].mean()
        dd = np.hypot((bt_lat - cla) * KY, (bt_lon - clo) * KX)
        if len(dd) and nm_.split(',')[0].strip() != bigtowns[int(np.argmin(dd))][0]:
            suffix = f' ({bigtowns[int(np.argmin(dd))][0]})'
        else:
            suffix = f' ({cla:.2f}, {clo:.2f})'
        for k in mem:
            r = rs[k]; info[r] = (nm_ + suffix,) + info[r][1:]; renamed += 1
log('přejmenováno kvůli stejným názvům', renamed)

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
        routes.append([route_label(s['key'], r), simple_type(r.get('route_type', '3')), col if re.fullmatch(r'[0-9A-Fa-f]{6}', col) else '', si,
                       r['route_id'] if s['key'] == 'PID' else ''])

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

# ---------- zpráva o deduplikaci (jeden pracovní den) ----------
def tdesc(t):
    si = int(TRdf.src.values[t]); r = routes[ridx[(si, TRdf.route_id.values[t])]]
    rows_ = Erow[starts[t]:starts[t + 1]]
    a_ = A.name.values[rows_[0]] if len(rows_) else '?'; b_ = A.name.values[rows_[-1]] if len(rows_) else '?'
    return f"{srcs[si]['key']}:{r[0]} {a_} {Edep[starts[t]]//3600:02d}:{Edep[starts[t]]//60%60:02d} → {b_}"
D = pd.DataFrame(dbg, columns=['g', 'p', 'hit', 'size', 'best'])
with open(os.path.join(OUT, 'dedupe.txt'), 'w') as f:
    f.write(f'den {DBG_DAY}\n')
    for p, gr in D.groupby('p'):
        dup = gr[(gr.hit >= 2) & (gr.hit * 2 >= gr['size'])]
        part = gr[(gr.hit >= 2) & (gr.hit * 2 < gr['size'])]
        f.write(f'\n=== priorita {p}: kandidátů {len(gr)}, zahozeno {len(dup)}, částečná shoda (ponecháno) {len(part)}\n')
        bs = dup.best.map(lambda b: srcs[int(TRdf.src.values[b])]['key'] if b >= 0 else '?').value_counts()
        f.write('zahozeno kvůli zdroji: ' + ', '.join(f'{k} {v}' for k, v in bs.items()) + '\n')
        for r in dup.sample(min(12, len(dup)), random_state=1).itertuples():
            f.write(f'  ZAHOZ {r.hit}/{r.size}  {tdesc(r.g)}  ≈  {tdesc(r.best) if r.best >= 0 else "?"}\n')
        for r in part.sample(min(12, len(part)), random_state=1).itertuples():
            f.write(f'  ČÁST  {r.hit}/{r.size}  {tdesc(r.g)}  ≈  {tdesc(r.best) if r.best >= 0 else "?"}\n')
        if p >= 200:
            pr = part.g.map(lambda t: routes[ridx[(int(TRdf.src.values[t]), TRdf.route_id.values[t])]][0]).value_counts().head(40)
            f.write('linky s částečnou shodou: ' + ', '.join(f'{k}×{v}' for k, v in pr.items()) + '\n')
log('zpráva o deduplikaci hotova')

# ---------- výstup (formát 3): jeden soubor jízdních řádů na celé období ----------
# Jízdy se rozloží na "vzory" (posloupnost uzlů + možnost nástupu/výstupu) a "profily"
# (vzor + jízdní doby od prvního odjezdu). Jízda = profil + čas odjezdu; pro každý den bitová maska jízd.
Enode = pd.Series(A.root.values[Erow]).map(nidx).fillna(-1).astype(np.int64).values
Earr = E.arr.values; Epk = E.pk.values; Edr = E.dr.values
T_src = TRdf.src.values; T_rid = TRdf.route_id.values; T_head = TRdf['head'].values; T_shape = TRdf['shape'].values
assert NN < 65535
pat_id = {}; pats = []          # (nodes tuple, flags tuple)
prof_id = {}; profs = []        # (pat, offsets tuple)
strings = []; str_id = {}
def sid_(x):
    if x not in str_id: str_id[x] = len(strings); strings.append(x)
    return str_id[x]
trows = []
for t in alltr:
    a, b = starts[t], starts[t + 1]
    nn = Enode[a:b]; ok = nn >= 0
    nn = nn[ok]; ar = Earr[a:b][ok]; dp = Edep[a:b][ok]; pk = Epk[a:b][ok]; dr = Edr[a:b][ok]
    # sloučení po sobě jdoucích zastavení ve stejném uzlu
    keepi = np.ones(len(nn), bool); keepi[1:] = nn[1:] != nn[:-1]
    if not keepi.all():
        grp = np.cumsum(keepi) - 1
        first = np.where(keepi)[0]; last = np.r_[first[1:] - 1, len(nn) - 1]
        ar = ar[first]; dp = dp[last]; pk = np.maximum.reduceat(pk.astype(int), first).astype(bool)
        dr = np.maximum.reduceat(dr.astype(int), first).astype(bool); nn = nn[first]
    if len(nn) < 2: continue
    dm = dp // 60; am = -((-ar) // 60); am = np.maximum(am, np.r_[dm[0], dm[:-1]]); dm = np.maximum(dm, am)
    am[0] = dm[0]
    st0 = int(dm[0])
    fl = tuple((pk.astype(int) | (dr.astype(int) << 1)).tolist())
    pkey = (tuple(nn.tolist()), fl)
    pi = pat_id.get(pkey)
    if pi is None: pi = pat_id[pkey] = len(pats); pats.append(pkey)
    offs = tuple(np.stack([am - st0, dm - st0], 1).flatten().tolist())
    qkey = (pi, offs)
    qi = prof_id.get(qkey)
    if qi is None: qi = prof_id[qkey] = len(profs); profs.append(qkey)
    si = int(T_src[t])
    lab = label_from.get(int(t))
    labs = routes[ridx[(int(T_src[lab]), T_rid[lab])]][0] if lab is not None else ''
    trows.append((int(t), qi, st0, ridx[(si, T_rid[t])], sid_(pretty(str(T_head[t]))), shidx.get((si, T_shape[t]), -1), sid_(labs) if labs else -1))
TT = pd.DataFrame(trows, columns=['g', 'prof', 'start', 'route', 'head', 'shape', 'label']).sort_values(['route', 'prof', 'start']).reset_index(drop=True)
pos_of = dict(zip(TT.g, TT.index))
log('vzorů', len(pats), 'profilů', len(profs), 'jízd', len(TT))

import struct, io
buf = io.BytesIO(); w = buf.write
w(b'PSJ3')
w(struct.pack('<I', len(pats)))
w(np.array([len(p[0]) for p in pats], np.uint16).tobytes())
w(np.array([n for p in pats for n in p[0]], np.uint16).tobytes())
w(np.array([x for p in pats for x in p[1]], np.uint8).tobytes())
w(struct.pack('<I', len(profs)))
w(np.array([q[0] for q in profs], np.uint32).tobytes())
w(np.array([x for q in profs for x in q[1]], np.uint16).tobytes())
w(struct.pack('<I', len(TT)))
for col, dt_ in (('prof', np.uint32), ('start', np.uint16), ('route', np.uint32), ('head', np.uint32), ('shape', np.int32), ('label', np.int32)):
    w(TT[col].values.astype(dt_).tobytes())
sb = json.dumps(strings, ensure_ascii=False).encode()
w(struct.pack('<I', len(sb))); w(sb)
w(struct.pack('<I', len(dates)))
for day in dates:
    bits = np.zeros(len(TT), bool)
    bits[[pos_of[int(t)] for t in day_trips[day] if int(t) in pos_of]] = True
    w(np.packbits(bits, bitorder='little').tobytes())
raw = buf.getvalue()
with gzip.open(os.path.join(OUT, 'timetable.bin'), 'wb', 9) as f: f.write(raw)
log('timetable.bin', len(raw) // 1024, 'KB nekomprimovaně,', os.path.getsize(os.path.join(OUT, 'timetable.bin')) // 1024, 'KB gzip')

meta = {'format': 3,
        'nodes': [[info[r][0], round(info[r][1], 5), round(info[r][2], 5), int(info[r][3]), int(modes[i]), info[r][4]] for i, r in enumerate(used_roots)],
        'routes': routes, 'sources': [s['desc'] for s in srcs],
        'walk': np.array(walk, np.int32).flatten().tolist(),
        'days': [[d, WD[datetime.datetime.strptime(d, '%Y%m%d').weekday()]] for d in dates],
        'generated': datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'), 'stats': stats}
with open(os.path.join(OUT, 'meta.json'), 'w') as f: json.dump(meta, f, ensure_ascii=False, separators=(',', ':'))
with gzip.open(os.path.join(OUT, 'meta.json.gz'), 'wb', 9) as f: f.write(json.dumps(meta, ensure_ascii=False, separators=(',', ':')).encode())
log('meta KB', os.path.getsize(os.path.join(OUT, 'meta.json')) // 1024, 'gzip', os.path.getsize(os.path.join(OUT, 'meta.json.gz')) // 1024, 'hotovo')

# ---------- podkladová síť pro webový prototyp (base.json) ----------
# třídy: 0 vlak, 1 bus/trolejbus/ostatní, 2 tramvaj, 3 metro; přímé úseky mezi uzly v jednotkách 25 m
UNIT = 25
cls_of = {2: 0, 0: 2, 1: 3}
pat_cls = {}
ttype = TRdf.type.values
for g_, q in zip(TT.g.values, TT.prof.values):
    pi_ = profs[q][0]; c = cls_of.get(int(ttype[g_]), 1)
    pat_cls[pi_] = min(pat_cls.get(pi_, 9), c)
segs = {0: set(), 1: set(), 2: set(), 3: set()}
for pi_, c in pat_cls.items():
    nn = pats[pi_][0]
    for a_, b_ in zip(nn[:-1], nn[1:]):
        segs[c].add((min(a_, b_), max(a_, b_)))
for c in (1, 2, 3):  # úsek vlaku se nekreslí znovu jako bus
    segs[c] -= segs[0]
lines = {}
for c, ss in segs.items():
    L = []
    for a_, b_ in ss:
        xa = int(round(NX[a_] / UNIT)); ya = int(round(NY[a_] / UNIT)); xb = int(round(NX[b_] / UNIT)); yb = int(round(NY[b_] / UNIT))
        if abs(xa - xb) + abs(ya - yb) > 4000: continue  # dálkové skoky bez zastávek nekreslíme
        L.append([xa, ya, xb - xa, yb - ya])
    lines[str(c)] = L
with open(os.path.join(OUT, 'base.json'), 'w') as f:
    json.dump({'unit': UNIT, 'kx': KX, 'ky': KY, 'lines': lines}, f, separators=(',', ':'))
log('base.json', {k: len(v) for k, v in lines.items()}, os.path.getsize(os.path.join(OUT, 'base.json')) // 1024, 'KB')
