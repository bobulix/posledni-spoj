"""Ladění: co je ve zdroji JDF o dané lince / zastávce. python debug_line.py <gtfs dir> <linka,...> <část názvu zastávky>"""
import pandas as pd, sys, os
d = sys.argv[1]; lines = sys.argv[2].split(','); stopq = sys.argv[3]
R = lambda f: pd.read_csv(os.path.join(d, f), dtype=str, keep_default_na=False)
routes = R('routes.txt'); trips = R('trips.txt'); cal = R('calendar.txt')
cd = R('calendar_dates.txt') if os.path.exists(os.path.join(d, 'calendar_dates.txt')) else None
stops = R('stops.txt')
print('zastávky:', stops[stops.stop_name.str.contains(stopq)][['stop_id', 'stop_name', 'stop_lat', 'stop_lon']].to_string())
for ln in lines:
    r = routes[routes.route_short_name == ln]
    print('\n=== linka', ln, len(r), 'variant'); print(r.to_string()[:1500])
    t = trips[trips.route_id.isin(r.route_id)]
    print('jízd', len(t))
    c = cal[cal.service_id.isin(t.service_id)]
    print(c.drop_duplicates(subset=[x for x in c.columns if x != 'service_id']).head(15).to_string())
    if cd is not None:
        x = cd[cd.service_id.isin(t.service_id)]
        print('výjimek', len(x), x[x.date.between('20261017', '20261031')].head(10).to_string())
# zastavení na zastávce
sid = set(stops[stops.stop_name.str.contains(stopq)].stop_id)
n = 0
for ch in pd.read_csv(os.path.join(d, 'stop_times.txt'), dtype=str, keep_default_na=False, chunksize=2_000_000, usecols=['trip_id', 'stop_id', 'departure_time']):
    x = ch[ch.stop_id.isin(sid)]
    if len(x):
        x = x.merge(trips[['trip_id', 'route_id', 'service_id']], on='trip_id')
        print(x.head(20).to_string()); n += len(x)
print('zastavení celkem', n)
