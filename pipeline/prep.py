"""Převod jízdních řádů PID (GTFS) na kompaktní data pro aplikaci Poslední spoj.

Použití: python prep.py <rozbalené GTFS> <výstupní složka> [YYYYMMDD,YYYYMMDD,...]
Bez seznamu dnů zpracuje všechny dny, které feed pokrývá (feed_info.txt).
Výstup: meta.json (zastávky, linky, pěší přechody, seznam dnů),
        conn_<den>.bin (gzip; spoje dne seřazené sestupně podle odjezdu),
        trips_<den>.json (linka, směr a tvar trasy každé jízdy),
        shapes.bin (gzip JSON; zjednodušené skutečné trasy linek jako encoded polyline).
"""
import pandas as pd, numpy as np, json, sys, datetime, math, os
SRC=sys.argv[1]; OUT=sys.argv[2]
os.makedirs(OUT, exist_ok=True)
if len(sys.argv) > 3:
    DATES=sys.argv[3].split(',')
else:
    fi=pd.read_csv(os.path.join(SRC,'feed_info.txt'),dtype=str)
    d0=datetime.datetime.strptime(fi.feed_start_date[0],'%Y%m%d'); d1=datetime.datetime.strptime(fi.feed_end_date[0],'%Y%m%d')
    DATES=[(d0+datetime.timedelta(days=k)).strftime('%Y%m%d') for k in range((d1-d0).days+1)]
R=lambda f,**k: pd.read_csv(os.path.join(SRC,f),dtype=str,keep_default_na=False,**k)

st=R('stops.txt'); st=st[st.location_type.isin(['0',''])].copy()
st['node']=np.where(st.asw_node_id!='',st.asw_node_id,'S'+st.stop_id)
st['lat']=st.stop_lat.astype(float); st['lon']=st.stop_lon.astype(float)
st['praha']=st.zone_id.str.split(',').apply(lambda z:'P' in z)
g=st.groupby('node')
nodes=pd.DataFrame({'name':g.stop_name.agg(lambda s:s.value_counts().index[0]),
  'lat':g.lat.mean(),'lon':g.lon.mean(),'praha':g.praha.any()}).reset_index()
nodes['i']=range(len(nodes)); nidx=dict(zip(nodes.node,nodes.i))
stop2node=dict(zip(st.stop_id,st.node.map(nidx)))

routes=R('routes.txt'); routes['i']=range(len(routes)); ridx=dict(zip(routes.route_id,routes.i))
trips=R('trips.txt'); cal=R('calendar.txt'); cd=R('calendar_dates.txt')
print('reading stop_times...',flush=True)
stt=R('stop_times.txt',usecols=['trip_id','arrival_time','departure_time','stop_id','stop_sequence','pickup_type','drop_off_type'])
stt['seq']=stt.stop_sequence.astype(int)
def tosec(s):
    p=s.str.split(':',expand=True).astype(int); return p[0]*3600+p[1]*60+p[2]
stt['arr']=tosec(stt.arrival_time); stt['dep']=tosec(stt.departure_time)
stt['n']=stt.stop_id.map(stop2node)
stt=stt.dropna(subset=['n']); stt['n']=stt.n.astype(int)
stt=stt.sort_values(['trip_id','seq'])

# node modes (bitmask: 1 tram,2 metro,4 train,8 bus,16 other)
modebit={'0':1,'1':2,'2':4,'3':8,'11':8}
tr_mode=trips.route_id.map(dict(zip(routes.route_id,routes.route_type))).map(lambda t:modebit.get(t,16))
tm=dict(zip(trips.trip_id,tr_mode))
nm=stt[['trip_id','n']].copy(); nm['b']=nm.trip_id.map(tm)
nodes['modes']=nodes.i.map(nm.groupby('n').b.agg(lambda s:int(np.bitwise_or.reduce(s.unique())))).fillna(0).astype(int)

# walking transfers between nodes within 450 m
lat=nodes.lat.values; lon=nodes.lon.values
kx=111320*math.cos(math.radians(50)); ky=110540
X=lon*kx; Y=lat*ky
cell=450; key=(np.floor(X/cell)).astype(int)*100000+(np.floor(Y/cell)).astype(int)
from collections import defaultdict
grid=defaultdict(list)
for i,k in enumerate(key): grid[k].append(i)
walk=[]
cx=np.floor(X/cell).astype(int); cy=np.floor(Y/cell).astype(int)
used=set(stt.n.unique())
for i in range(len(nodes)):
    if i not in used: continue
    for dx in (-1,0,1):
        for dy in (-1,0,1):
            for j in grid.get((cx[i]+dx)*100000+cy[i]+dy,[]):
                if j<=i or j not in used: continue
                d=math.hypot(X[i]-X[j],Y[j]-Y[i])
                if d<=450: walk.append((i,j,int(math.ceil(d*1.3/70))+1))
walk=np.array(walk,dtype=np.int32)
print('walk edges',len(walk))

meta={'nodes':[[r.name,round(r.lat,5),round(r.lon,5),int(r.praha),int(r.modes)] for r in nodes.itertuples()],
  'routes':[[r.route_short_name,int(r.route_type) if r.route_type.isdigit() else 3,r.route_color or '888888',0,r.route_id] for r in routes.itertuples()],
  'sources':['PID (ROPID)'],
  'walk':walk.flatten().tolist(),'days':[],
  'generated':datetime.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')}

# ---- skutečné trasy linek (shapes.txt), zjednodušené na ~8 m ----
def _dp(pts,eps):
    n=len(pts); keep=np.zeros(n,bool); keep[0]=keep[-1]=True; st=[(0,n-1)]
    while st:
        i,j=st.pop()
        if j<=i+1: continue
        a=pts[i]; b=pts[j]; ab=b-a; L=np.hypot(*ab); seg=pts[i+1:j]
        dd=np.abs(ab[0]*(seg[:,1]-a[1])-ab[1]*(seg[:,0]-a[0]))/L if L>0 else np.hypot(*(seg-a).T)
        k=int(np.argmax(dd))
        if dd[k]>eps: mm=i+1+k; keep[mm]=True; st+=[(i,mm),(mm,j)]
    return keep
def _enc(v):
    v=~(v<<1) if v<0 else (v<<1); out=''
    while v>=0x20: out+=chr((0x20|(v&0x1f))+63); v>>=5
    return out+chr(v+63)
print('shapes...',flush=True)
shp=pd.read_csv(os.path.join(SRC,'shapes.txt'),usecols=[0,1,2,3],dtype={'shape_id':str}).sort_values(['shape_id','shape_pt_sequence'])
shidx={}; shlist=[]
for sid,g in shp.groupby('shape_id',sort=False):
    la_=g.shape_pt_lat.values; lo_=g.shape_pt_lon.values
    k=_dp(np.stack([lo_*kx,la_*ky],1),8)
    la=np.round(la_[k]*1e5).astype(int); lo=np.round(lo_[k]*1e5).astype(int)
    s=_enc(int(la[0]))+_enc(int(lo[0]))
    for i in range(1,len(la)): s+=_enc(int(la[i]-la[i-1]))+_enc(int(lo[i]-lo[i-1]))
    shidx[sid]=len(shlist); shlist.append(s)
import gzip as _gz
with _gz.open(os.path.join(OUT,'shapes.bin'),'wb',9) as f: f.write(json.dumps(shlist).encode())

WD=['monday','tuesday','wednesday','thursday','friday','saturday','sunday']
for d in DATES:
    dt=datetime.datetime.strptime(d,'%Y%m%d'); wd=WD[dt.weekday()]
    act=set(cal[(cal[wd]=='1')&(cal.start_date<=d)&(cal.end_date>=d)].service_id)
    x=cd[cd.date==d]; act|=set(x[x.exception_type=='1'].service_id); act-=set(x[x.exception_type=='2'].service_id)
    tday=trips[trips.service_id.isin(act)].reset_index(drop=True)
    tday['ti']=range(len(tday)); tix=dict(zip(tday.trip_id,tday.ti))
    s=stt[stt.trip_id.isin(tix)]
    a=s.iloc[:-1].reset_index(drop=True); b=s.iloc[1:].reset_index(drop=True)
    ok=(a.trip_id.values==b.trip_id.values)
    a=a[ok]; b=b[ok]
    # flags: 1 = lze nastoupit v u, 2 = lze vystoupit ve v
    fl=(a.pickup_type.values!='1').astype(int)+2*(b.drop_off_type.values!='1').astype(int)
    c=pd.DataFrame({'u':a.n.values,'v':b.n.values,'dep':(a.dep.values//60),'arr':-((-b.arr.values)//60),'t':a.trip_id.map(tix).values,'f':fl})
    c=c.sort_values(['dep','arr'],ascending=[False,False])
    assert len(tday)<65535
    arr=np.stack([c.u,c.v,c.dep,c.arr,c.t],1).astype(np.uint16)
    fn=f'conn_{d}.bin'
    import gzip
    with gzip.open(os.path.join(OUT,fn),'wb',9) as f:
        f.write(np.uint32(len(c)).tobytes()); f.write(arr.T.copy().tobytes()); f.write(c.f.values.astype(np.uint8).tobytes())
    tinfo=[[ridx[r.route_id],r.trip_headsign,shidx.get(r.shape_id,-1)] for r in tday.itertuples()]
    with open(os.path.join(OUT,f'trips_{d}.json'),'w') as f: json.dump(tinfo,f,ensure_ascii=False,separators=(',',':'))
    meta['days'].append([d,wd])
    print(d,'trips',len(tday),'conns',len(c),os.path.getsize(os.path.join(OUT,fn))//1024,'KB',flush=True)
with open(os.path.join(OUT,'meta.json'),'w') as f: json.dump(meta,f,ensure_ascii=False,separators=(',',':'))
print('nodes',len(nodes),'meta KB',os.path.getsize(os.path.join(OUT,'meta.json'))//1024)
