import random, statistics as st, json
# CaG 시장 시뮬레이터 v2: 기준가 띠(하한~상한) + 주문 생산(내구재) + 예비 도구 + 금고 염가 수매 + 수요에 따른 직종 조정
BASE = dict(wheat=3,carrot=3,bread=12,log=4,charcoal=6,wood_tool=8,stone=1,coal=6,iron_ore=14,iron_tool=60,wool=6,bed=40,raw_meat=8,cooked_meat=14,milk=4,cake=30,furniture=30)
NUT = dict(carrot=12,bread=20,cooked_meat=32); FOOD=list(NUT)
LUX = dict(cake=25,furniture=20,cooked_meat=6)
DUR = {'iron_tool','wood_tool','bed','furniture'}            # 주문 생산 품목
PRIMARY = dict(farmer=dict(wheat=30,carrot=14), forester=dict(log=30), miner=dict(stone=120,coal=10,iron_ore=6),
               shepherd=dict(wool=12,raw_meat=3), rancher=dict(raw_meat=8,milk=6))
CRAFT = dict(cook=[('bread',dict(wheat=3),60),('cooked_meat',dict(raw_meat=1,charcoal=0.125),40),('cake',dict(wheat=3,milk=3),10)],
             forester=[('charcoal',dict(log=1.125),12)],
             carpenter=[('wood_tool',dict(log=1),12),('bed',dict(log=1,wool=3),4),('furniture',dict(log=2,wool=1),6)],
             smith=[('iron_tool',dict(iron_ore=3,charcoal=0.5,log=0.25),8)])
TOOL_USER = dict(farmer=0.12,forester=0.25,miner=0.5,shepherd=0.05,rancher=0.05)
LIFE=dict(iron_tool=1.0,wood_tool=0.25)
MAKER = {}
for j,d in PRIMARY.items():
    for i in d: MAKER.setdefault(i,j)
for j,rs in CRAFT.items():
    for o,_,_ in rs: MAKER.setdefault(o,j)
ESSENTIAL = ['farmer','cook','rancher','forester']
def run(days=420, seed=1, mix=None, step=0.05, tdays=3, tax=0.15, rent=15, lo=0.5, hi=1.5, floor_k=0.5, ceil_k=2.0, mref=300.0,
        orders=True, spare=True, floorbuy=True, buy_k=0.5, autoissue=False, surplus_only=True, release=True, planner=True, median=True, wealth=True, safety=True, works=0.0, issue_day=None, issue_amt=0, house=700, death_days=3):
    R=random.Random(seed)
    mix = mix or dict(farmer=8,cook=4,forester=4,carpenter=4,miner=4,smith=3,shepherd=3,rancher=5,guard=5)
    H=[dict(job=j,w=300.0,inv={},ask={},ema={},hunger=70.0,tool=1.0,spare=0,bed=0,own=0,lux=50.0,furn=0,la=R.uniform(0.2,0.9),inc7=[0.0]*7,cost={},lvl=1,zero=0,dead=0,today=0.0,ordered={})
       for j,n in mix.items() for _ in range(n)]
    T=dict(bal=0.0,issued=sum(h['w'] for h in H),ware={},spent_buy=0.0); P=1.0; cpi=[]; log=[]; OR=[]; unmet={}
    sells=lambda h: set(PRIMARY.get(h['job'],{}))|{r[0] for r in CRAFT.get(h['job'],[])}
    def reserve(h,i):
        r=2 if i in FOOD else 0
        for o,inp,cap in CRAFT.get(h['job'],[]):
            if i in inp: r=max(r, cap*inp[i] if i in PRIMARY.get(h['job'],{}) else 10**9)
        if i in DUR: r=max(r,sum(1 for o in OR if o['maker'] is h and o['item']==i))   # 주문분은 매물이 아니다
        return r
    def listings(i,b):
        L=[(h['ask'].get(i,BASE[i]*P),h) for h in H if h is not b and i in sells(h) and h['inv'].get(i,0)-reserve(h,i)>=1]
        L.sort(key=lambda x:x[0]); return L
    def pay(b,s,amt):
        b['w']-=amt; s['w']+=amt; s['inc7'][-1]+=amt; s['today']+=amt
    def buy(b,i,q,biz=False):
        got=0;sp=0
        for p,s in listings(i,b):
            if q-got<=0: break
            n=min(int(s['inv'][i]-reserve(s,i)),int(q-got+0.999),int(b['w']//p))
            if n<=0: continue
            s['inv'][i]-=n; b['inv'][i]=b['inv'].get(i,0)+n; pay(b,s,n*p); s['sold'][i]=s['sold'].get(i,0)+n; got+=n; sp+=n*p
        if got: b['cost'][i]=0.8*b['cost'].get(i,sp/got)+0.2*sp/got
        if biz: b['inc7'][-1]-=sp
        if got<q: unmet[i]=unmet.get(i,0)+(q-got)
        return got,sp
    def want_durable(b,i,biz=False):
        g,s=buy(b,i,1,biz)
        if g: return True
        if orders and not b['ordered'].get(i):
            ms=[h for h in H if h is not b and i in sells(h)]
            if ms:
                m=min(ms,key=lambda h:h['ask'].get(i,BASE[i]*P)); p=m['ask'].get(i,BASE[i]*P)
                if b['w']>=p:
                    dep=p*0.5; b['w']-=dep; m['w']+=dep; m['today']+=dep; m['inc7'][-1]+=dep
                    if biz: b['inc7'][-1]-=dep
                    OR.append(dict(buyer=b,maker=m,item=i,price=p,dep=dep,day=d,biz=biz)); b['ordered'][i]=1
        return False
    for d in range(days):
        for h in H: h['sold']={}; h['inc7']=h['inc7'][1:]+[0.0]; h['today']=0.0
        if issue_day==d:
            T['bal']+=issue_amt; T['issued']+=issue_amt
        for h in H:
            if h['job']=='guard':
                x=min(40,T['bal']); T['bal']-=x; h['w']+=x; h['inc7'][-1]+=x; h['today']+=x
        R.shuffle(H)
        # 1) 식량 먼저
        for h in H:
            need=80-sum(h['inv'].get(f,0)*NUT[f] for f in FOOD); t=0
            while need>0 and t<6:
                t+=1; o=[(L[0][0]/NUT[f],f) for f in FOOD for L in [listings(f,h)] if L]
                if not o: unmet['food']=unmet.get('food',0)+1; break
                f=min(o)[1]; g,s=buy(h,f,max(1,need/NUT[f]))
                if not g: break
                need-=g*NUT[f]
            if release and need>0:
                for f in FOOD:
                    pr=BASE[f]*P; n=int(min(T['ware'].get(f,0), need/NUT[f]+0.999, h['w']//pr))
                    if n>0: T['ware'][f]-=n; h['inv'][f]=h['inv'].get(f,0)+n; h['w']-=n*pr; T['bal']+=n*pr; need-=n*NUT[f]
        # 2) 도구(예비 포함), 재료
        for h in H:
            if h['job'] in TOOL_USER:
                if h['tool']<=0 and h['spare']>0: h['tool']=h['spare']; h['spare']=0
                short = (h['spare']<=0) if spare else (h['tool']<0.3)
                if short:
                    pi=min([x['ask'].get('iron_tool',BASE['iron_tool']*P) for x in H if x['job']=='smith'] or [1e9])
                    it='iron_tool' if h['w']>=2*pi else 'wood_tool'
                    if want_durable(h,it,True):
                        h['inv'][it]-=1; h['spare' if spare else 'tool']+=LIFE[it]
            for o,inp,cap in CRAFT.get(h['job'],[]):
                if o in DUR and orders: want=sum(1 for x in OR if x['maker'] is h and x['item']==o)-h['inv'].get(o,0)
                else: want=min(cap,max(2,tdays*h['ema'].get(o,1.0))-(h['inv'].get(o,0)-reserve(h,o)))
                h['_w_'+o]=max(0,want)
                for i,q in inp.items():
                    nd=h['_w_'+o]*q-h['inv'].get(i,0)
                    if nd>0 and i not in PRIMARY.get(h['job'],{}): buy(h,i,nd,True)
        # 3) 생산
        for h in H:
            eff=(1.0 if h['tool']>0 or h['job'] not in TOOL_USER else 0.4)*(1+0.15*(h['lvl']-1))*(0.5 if h['hunger']<=0 else 1)
            for i,q in PRIMARY.get(h['job'],{}).items():
                if h['inv'].get(i,0)-reserve(h,i)>2*max(2,tdays*h['ema'].get(i,q/3)): continue
                h['inv'][i]=h['inv'].get(i,0)+q*eff*R.uniform(0.8,1.2)
            if h['job'] in TOOL_USER: h['tool']=max(0,h['tool']-TOOL_USER[h['job']])
            for o,inp,cap in CRAFT.get(h['job'],[]):
                n=int(min([h.get('_w_'+o,0)]+[h['inv'].get(i,0)/q for i,q in inp.items()]))
                if n>0:
                    for i,q in inp.items(): h['inv'][i]-=n*q
                    h['inv'][o]=h['inv'].get(o,0)+n
        # 4) 주문 인도
        for o in OR[:]:
            m,b,i=o['maker'],o['buyer'],o['item']
            if m['inv'].get(i,0)>=1 and b['w']>=o['price']-o['dep']:
                m['inv'][i]-=1; rest=o['price']-o['dep']; pay(b,m,rest); m['sold'][i]=m['sold'].get(i,0)+1
                if o['biz']: b['inc7'][-1]-=rest
                b['ordered'][i]=0; OR.remove(o)
                if i in LIFE: b['spare' if spare else 'tool']+=LIFE[i]
                elif i=='bed': b['bed']=1
                elif i=='furniture': b['furn']+=1; b['lux']=min(100,b['lux']+LUX[i])
            elif d-o['day']>4:
                m['w']-=min(m['w'],o['dep']); b['w']+=o['dep']; b['ordered'][i]=0; OR.remove(o); unmet[i]=unmet.get(i,0)+1
        # 5) 여윳돈 지출
        bp=min([h2['ask'].get('bread',12*P) for h2 in H if 'bread' in sells(h2)] or [12*P])
        for h in H:
            h['lux']=max(0,h['lux']-8); D=max(0,h['w']-6*bp-70*P); bud=0.25*D*h['la']
            if not h['bed'] and D>BASE['bed']*P and want_durable(h,'bed'): h['bed']=1; h['inv']['bed']-=1
            g=0
            while (wealth or h['lux']<80) and bud>0 and g<3:
                g+=1; o=[(L[0][0]/LUX[f],f) for f in ('cake','cooked_meat') for L in [listings(f,h)] if L and L[0][0]<=bud]
                if (wealth or h['furn']<6) and bud>=BASE['furniture']*P and not h['ordered'].get('furniture'):
                    if want_durable(h,'furniture'): h['furn']+=1; h['inv']['furniture']-=1; h['lux']=min(100,h['lux']+LUX['furniture'])
                    bud-=BASE['furniture']*P; continue
                if not o: break
                f=min(o)[1]; gg,sp=buy(h,f,1)
                if not gg: break
                bud-=sp; h['lux']=min(100,h['lux']+LUX[f])
                if f=='cake': h['inv'][f]-=1; h['hunger']=min(100,h['hunger']+28)
        # 6) 금고 염가 수매: 그날 소득이 생계비에 못 미친 가구의 팔 재고를 하한가로
        if floorbuy:
            cf=min([h2['ask'].get(f,BASE[f]*P)/NUT[f] for h2 in H for f in FOOD if f in sells(h2) and h2['inv'].get(f,0)>=3] or [0.25*P])
            Lc=40*cf*1.5+rent/7.0
            for h in H:
                gap=Lc-h['today']
                if gap<=0 or h['job']=='guard': continue
                for i in sorted(sells(h),key=lambda x:-BASE[x]):
                    if i in DUR: continue
                    pr=BASE[i]*P*buy_k; n=int(min(h['inv'].get(i,0)-reserve(h,i)-(max(2,tdays*h['ema'].get(i,1.0)) if surplus_only else 0), gap//pr+1))
                    if n<=0: continue
                    cost=n*pr
                    if T['bal']<cost:
                        if autoissue: T['bal']+=cost; T['issued']+=cost
                        else: n=int(T['bal']//pr); cost=n*pr
                    if n<=0: continue
                    T['bal']-=cost; h['inv'][i]-=n; h['w']+=cost; h['inc7'][-1]+=cost; h['today']+=cost; T['ware'][i]=T['ware'].get(i,0)+n; T['spent_buy']+=cost; gap-=cost
                    if gap<=0: break
        # 7) 식사, 굶주림
        for h in H:
            h['hunger']-=40
            for _ in range(6):
                if h['hunger']>=80: break
                fs=[f for f in FOOD if h['inv'].get(f,0)>=1]
                if not fs: break
                f=max(fs,key=lambda x:NUT[x]); h['inv'][f]-=1; h['hunger']=min(100,h['hunger']+NUT[f])
            h['hunger']=max(0,h['hunger']); h['zero']=h['zero']+1 if h['hunger']<=0 else 0
            if h['zero']==death_days: h['dead']+=1
        # 8) 호가: 기준가 x P x 배수, 배수는 [floor_k, ceil_k]
        P+= ((st.median(h['w'] for h in H) if median else sum(h['w'] for h in H)/len(H))/mref-P)/14.0
        P=max(0.25,P)
        for h in H:
            for i in sells(h):
                sold=h['sold'].get(i,0); h['ema'][i]=0.7*h['ema'].get(i,1.0)+0.3*sold
                stock=h['inv'].get(i,0)-reserve(h,i)
                if sold>0 and stock<1: h['ema'][i]=max(h['ema'][i],sold*1.5)
                p=h['ask'].get(i,BASE[i]*P); ref=BASE[i]*P; tg=max(2,tdays*h['ema'][i])
                inc=sum(h['cost'].get(k,BASE[k]*P)*v for o,inp,_ in CRAFT.get(h['job'],[]) if o==i for k,v in inp.items())
                if i in DUR and orders:
                    nq=sum(1 for o in OR if o['maker'] is h and o['item']==i)
                    if nq>=3: p*=1+step
                    elif nq==0 and sold==0: p*=1-step
                elif sold>0 and stock<lo*tg: p*=1+step
                elif stock>hi*tg: p*=1-step
                elif p<ref/(1+step/2): p*=1+step/2
                elif p>ref*(1+step/2): p*=1-step/2
                h['ask'][i]=min(max(1.0,inc*1.05,floor_k*ref,p), max(ceil_k*ref,inc*1.05))
        # 9) 주간: 세금, 주택, 공공 구매, 직종 조정
        if d%7==6:
            for h in H:
                t=min(h['w'],max(0,sum(h['inc7']))*tax+(0 if h['own'] else rent)); h['w']-=t; T['bal']+=t
                if not h['own'] and h['w']>house*P+200*P: h['w']-=house*P; T['bal']+=house*P; h['own']=1
            if works:
                gov=dict(job='gov',w=T['bal']*works,inv={},ask={},ema={},cost={},sold={},inc7=[0]*7,today=0,ordered={}); st0=gov['w']
                for it,sh in (('stone',0.5),('log',0.5)):
                    b=st0*sh
                    for _ in range(40):
                        L=listings(it,gov)
                        if not L or L[0][0]>b: break
                        g,sp=buy(gov,it,min(10,b//L[0][0]))
                        if not g: break
                        b-=sp
                T['bal']-=st0-gov['w']
            if planner and d>20:
                by={}
                for h in H: by.setdefault(h['job'],[]).append(h)
                um={}
                for i,c in unmet.items():
                    j='cook' if i=='food' else MAKER.get(i); um[j]=um.get(j,0)+c*(3 if j in ESSENTIAL else 1)
                if um:
                    tgt=max(um,key=um.get)
                    cand=[h for h in H if h['job'] not in ('guard',tgt) and len(by[h['job']])>(2 if h['job'] in ESSENTIAL else 1) and um.get(h['job'],0)==0]
                    if cand:
                        mv=min(cand,key=lambda h:sum(h['inc7'])); mv['job']=tgt; mv['lvl']=1; mv['ask']={}; mv['ema']={}
            if planner and safety and d>20:
                cnt={}
                for h in H: cnt[h['job']]=cnt.get(h['job'],0)+1
                for h in H:
                    h['poor']=h.get('poor',0)+1 if sum(h['inc7'])<0.5*7*40*0.25*P*1.5 else 0
                    if h['poor']>=2 and h['job'] not in PRIMARY and h['job']!='guard' and cnt[h['job']]>1:
                        t=min(PRIMARY,key=lambda j:cnt.get(j,0)); cnt[h['job']]-=1; cnt[t]=cnt.get(t,0)+1
                        h['job']=t; h['lvl']=1; h['ask']={}; h['ema']={}; h['poor']=0
            unmet.clear()
        if d%20==19:
            for h in H: h['lvl']=min(5,h['lvl']+1)
        def mp(i):
            L=[h['ask'].get(i,BASE[i]*P) for h in H if i in sells(h) and h['inv'].get(i,0)>=1]; return min(L) if L else None
        c=((mp('bread') or 36*P)+(mp('carrot') or 9*P)+0.5*(mp('cooked_meat') or 42*P))/22; cpi.append(c)
        if d%7==6:
            by={}
            for h in H: by.setdefault(h['job'],[]).append(sum(h['inc7']))
            log.append(dict(d=d,cpi=round(c,2),P=round(P,2),starv=sum(1 for h in H if h['hunger']<30),zero=sum(1 for h in H if h['hunger']<=0),dead=sum(h['dead'] for h in H),
              tre=round(T['bal']),issued=round(T['issued']),buy=round(T['spent_buy']),lux=round(st.mean(h['lux'] for h in H)),
              wq=[round(x) for x in st.quantiles([h['w'] for h in H],n=4)],jobs={j:(len(v),round(st.mean(v))) for j,v in sorted(by.items())},
              tools=round(st.mean(h['tool']+h['spare'] for h in H if h['job'] in TOOL_USER),2), idle=sum(1 for h in H if h['job'] in TOOL_USER and h['tool']<=0), ware=dict(sorted((k,round(v)) for k,v in T['ware'].items()))))
    return log,cpi
def summ(tag,**kw):
    rs=[run(seed=s,**kw) for s in (1,2,3)]
    L=[r for lg,_ in rs for r in lg[30:]]; cs=[st.mean(st.mean(c[a:b]) for _,c in rs) for a,b in ((60,90),(200,230),(390,420))]
    inc=[v[1] for r in L for v in r['jobs'].values()]
    print('%-34s 굶음 %.1f | 허기0 %.1f | 3일 연속 허기0 누계 %.1f | 도구 없는 일꾼 %.1f | 사치 %.0f | 주간소득 %.0f | 금고 %.0f | 수매 누계 %.0f | 발행 누계 %.0f | 물가 %s'%(
        tag,st.mean(r['starv'] for r in L),st.mean(r['zero'] for r in L),st.mean(lg[-1]['dead'] for lg,_ in rs),st.mean(r['idle'] for r in L),st.mean(r['lux'] for r in L),st.median(inc),
        st.mean(r['tre'] for r in L),st.mean(lg[-1]['buy'] for lg,_ in rs),st.mean(lg[-1]['issued'] for lg,_ in rs),' '.join('%.2f'%x for x in cs)))
    return rs
if __name__=='__main__':
    summ('A 이전 규칙',orders=False,spare=False,floorbuy=False,planner=False,works=0.7,median=False,wealth=False)
    summ('B 주문 생산 + 예비 도구',floorbuy=False,planner=False,works=0.7,median=False,wealth=False)
    summ('C B + 여윳돈 소비 + 중앙값 기준선',floorbuy=False,planner=False,works=0.7)
    summ('D C + 직종 조정',floorbuy=False,works=0.7)
    r=summ('E D + 염가 수매(잔고 안에서)')
    print('   창고:',r[0][0][-1]['ware']); print('   직종:',r[0][0][-1]['jobs']); print('   지갑 사분위:',r[0][0][-1]['wq'])
    summ('F E, 소득세 30%',tax=0.30)
    r=summ('G E, 모자라면 발행',autoissue=True)
    print('   창고:',r[0][0][-1]['ware']); print('   직종:',r[0][0][-1]['jobs']); print('   지갑 사분위:',r[0][0][-1]['wq'])
    summ('H G, 평균 기준선(비교)',autoissue=True,median=False)
    summ('I G, 직종 조정 없음',autoissue=True,planner=False)
