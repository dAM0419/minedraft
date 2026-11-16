import random, statistics as st, sys, json
# 판매자 자율 호가 시장 시뮬레이터 (CaG 명세 03-economy.md 11절의 근거)
# 기본 인자가 최종 규칙이다: 재고 띠(lo, hi) + 물가 기준선(anchor) + 하한(floor_k) + 생산 쉬기(throttle).
#   run(anchor=False, lo=None)            -> "품절일 때만 인상" (버린 규칙)
#   run(issue_day=200, issue_amt=12000)   -> 200일에 통화량 2배
#   run(works=0, welfare=False)           -> 금고가 걷기만 하고 안 씀
# 생산량 수치(PRIMARY, CRAFT, TOOL_USER)는 임의값이다. 3단계에서 실제 작업 속도를 재서 바꾼다.
# 반환: (주간 기록 목록, 일별 물가 지수 목록)
# 가구=독신 1명. 하루 순서: 재료 구매 -> 생산 -> 소비 구매 -> 식사 -> 호가 조정. 7일마다 세금.
BASE = dict(wheat=3,carrot=3,bread=12,log=4,charcoal=6,plank=1,wood_tool=8,stone=1,coal=6,iron_ore=14,iron_tool=60,wool=6,bed=40,raw_meat=8,cooked_meat=14,milk=4,cake=30,furniture=30)
LUX=dict(cake=25,furniture=20,cooked_meat=6)
NUT = dict(carrot=12,bread=20,cooked_meat=32)   # 허기 회복량
FOOD=list(NUT)
# 직업: 1차 산출(하루), 가공 레시피(산출: (입력dict, 하루 최대))
PRIMARY = dict(farmer=dict(wheat=30,carrot=14), forester=dict(log=30), miner=dict(stone=120,coal=10,iron_ore=6),
               shepherd=dict(wool=12,raw_meat=3), rancher=dict(raw_meat=8,milk=6))
CRAFT = dict(cook=[('bread',dict(wheat=3),60),('cooked_meat',dict(raw_meat=1,charcoal=0.125),40),('cake',dict(wheat=3,milk=3),10)],
             forester=[('charcoal',dict(log=1.125),12)],
             carpenter=[('wood_tool',dict(log=1),12),('bed',dict(log=1,wool=3),4),('furniture',dict(log=2,wool=1),6)],
             smith=[('iron_tool',dict(iron_ore=3,charcoal=0.5,log=0.25),8)])
TOOL_USER = dict(farmer=0.12,forester=0.25,miner=0.5,shepherd=0.05,rancher=0.05)  # 철 도구 하루 소모량(개)
CAP=dict(farmer=10,cook=4,forester=5,carpenter=3,miner=8,smith=3,shepherd=4,rancher=5)
def run(N=40, days=300, step=0.05, target_days=3, tax=0.15, issue_day=None, issue_amt=0, anchor=True, mref=300.0, floor_k=0.5, lo=0.5, hi=1.5, rent=15, welfare=True, throttle=True, works=0.7, house=700, wtax=0.0, wex=300, seed=1, mix=None, verbose=False, jobchange=True):
    R=random.Random(seed)
    mix = mix or dict(farmer=8,cook=4,forester=4,carpenter=4,miner=4,smith=3,shepherd=3,rancher=5, guard=5)
    H=[]
    for j,n in mix.items():
        for _ in range(n): H.append(dict(job=j,w=300.0,inv={},ask={},ema={},hunger=70.0,tool=1.0,bed=0,own=0,lux=50.0,furn=0,la=0,inc7=[0.0]*7,cost={},cool=0,lvl=1))
    for h in H: h['la']=R.uniform(0.2,0.9)
    treasury=0.0; money0=sum(h['w'] for h in H)
    cpi_hist=[]; log=[]; P=1.0
    def listings(item, buyer):
        L=[(h['ask'].get(item,BASE[item]),h) for h in H if h is not buyer and h['inv'].get(item,0)-reserve(h,item)>=1 and item in sells(h)]
        L.sort(key=lambda x:x[0]); return L
    def sells(h):
        s=set(PRIMARY.get(h['job'],{})); s|={r[0] for r in CRAFT.get(h['job'],[])}; return s
    def reserve(h,item):
        r=0
        if item in FOOD: r=2
        for out,inp,cap in CRAFT.get(h['job'],[]):
            if item in inp:
                r=max(r, cap*inp[item] if item in PRIMARY.get(h['job'],{}) else 10**9)   # 재료는 팔지 않음(직접 캐는 재료는 하루치만 남김)
        return r
    def buy(b,item,qty,maxprice=None):
        got=0; spent=0
        for p,s in listings(item,b):
            if qty-got<=0: break
            if maxprice and p>maxprice: break
            avail=int(s['inv'][item]-reserve(s,item)); n=min(avail,int(qty-got+0.999), int(b['w']//p) if p>0 else 0)
            if n<=0: continue
            s['inv'][item]-=n; b['inv'][item]=b['inv'].get(item,0)+n; b['w']-=n*p; s['w']+=n*p
            s['sold'][item]=s['sold'].get(item,0)+n; s['inc7'][-1]+=n*p; got+=n; spent+=n*p
        if got: b['cost'][item]=0.8*b['cost'].get(item,spent/got)+0.2*spent/got
        return got,spent
    for d in range(days):
        for h in H: h['sold']={}; h['inc7']=h['inc7'][1:]+[0.0]
        if issue_day is not None and d==issue_day:   # 발행: 금고가 돈을 찍어 전 가구에 지출(구매·봉급으로 풀림)을 단순화
            for h in H: h['w']+=issue_amt/len(H)
        # 봉급(경비병)
        for h in H:
            if h['job']=='guard':
                pay=min(40,treasury); treasury-=pay; h['w']+=pay; h['inc7'][-1]+=pay
        R.shuffle(H)
        if welfare:   # 시장의 단순 정책: 굶는 가구에 금고 돈으로 식비 지급
            for h in H:
                if h['hunger']<50 and h['w']<20 and treasury>=20: h['w']+=20; treasury-=20
        # 재료·도구 구매
        for h in H:
            exp=0
            if h['job'] in TOOL_USER and h['tool']<0.3:
                g,s=buy(h,'iron_tool',1); 
                if g: h['tool']+=1; h['inv']['iron_tool']-=1; exp+=s
                else:
                    g,s=buy(h,'wood_tool',1)
                    if g: h['tool']+=0.25; h['inv']['wood_tool']-=1; exp+=s
            for out,inp,cap in CRAFT.get(h['job'],[]):
                tgt=max(2,target_days*h['ema'].get(out,1.0))
                want=max(0,min(cap,tgt-(h['inv'].get(out,0)-reserve(h,out))))
                for i,q in inp.items():
                    need=want*q-h['inv'].get(i,0)
                    if need>0 and i not in PRIMARY.get(h['job'],{}):
                        ucost=sum(h['cost'].get(k,BASE[k])*v for k,v in inp.items())
                        g,s=buy(h,i,need); exp+=s
            h['inc7'][-1]-=exp
        # 생산
        for h in H:
            eff=(1.0 if h.get('tool',1)>0 or h['job'] not in TOOL_USER else 0.4)*(1+0.15*(h['lvl']-1))
            for i,q in PRIMARY.get(h['job'],{}).items():
                if throttle and h['inv'].get(i,0)-reserve(h,i)>2*max(2,target_days*h['ema'].get(i,q/3)): continue   # 재고가 목표의 2배면 쉰다
                h['inv'][i]=h['inv'].get(i,0)+q*eff*R.uniform(0.8,1.2)
            if h['job'] in TOOL_USER: h['tool']=max(0,h['tool']-TOOL_USER[h['job']])
            for out,inp,cap in CRAFT.get(h['job'],[]):
                tgt=max(2,target_days*h['ema'].get(out,1.0))
                want=max(0,min(cap,tgt-(h['inv'].get(out,0)-reserve(h,out))))
                n=min([want]+[h['inv'].get(i,0)/q for i,q in inp.items()])
                n=int(n)
                if n>0:
                    for i,q in inp.items(): h['inv'][i]-=n*q
                    h['inv'][out]=h['inv'].get(out,0)+n
        # 소비: 식량(이틀치 확보), 침대
        for h in H:
            have=sum(h['inv'].get(f,0)*NUT[f] for f in FOOD)
            need=80-have   # 이틀치 허기 80
            tries=0
            while need>0 and tries<6:
                tries+=1
                opts=[]
                for f in FOOD:
                    L=listings(f,h)
                    if L: opts.append((L[0][0]/NUT[f],f))
                if not opts: break
                opts.sort(); f=opts[0][1]
                g,s=buy(h,f,max(1,need/NUT[f]))
                if g==0: break
                need-=g*NUT[f]
            if not h['bed'] and h['w']>150:
                g,s=buy(h,'bed',1)
                if g: h['bed']=1; h['inv']['bed']-=1
        # 사치 지출: 여윳돈의 25% x 사치 태도
        bp=min([h2['ask'].get('bread',12) for h2 in H if 'bread' in sells(h2)] or [12])
        for h in H:
            h['lux']=max(0,h['lux']-8)
            D=max(0,h['w']-6*bp-70); budget=0.25*D*h['la']
            guard=0
            while h['lux']<80 and budget>0 and guard<4:
                guard+=1
                opts=[]
                for f in LUX:
                    if f=='furniture' and h['furn']>=6: continue
                    L=listings(f,h)
                    if L and L[0][0]<=budget: opts.append((L[0][0]/LUX[f],f))
                if not opts: break
                opts.sort(); f=opts[0][1]
                g,sp=buy(h,f,1)
                if not g: break
                budget-=sp; h['lux']=min(100,h['lux']+LUX[f])
                if f=='furniture': h['furn']+=1; h['inv'][f]-=1
                elif f=='cake': h['inv'][f]-=1; h['hunger']=min(100,h['hunger']+28)
        # 식사
        for h in H:
            h['hunger']-=40
            for _ in range(6):
                if h['hunger']>=80: break
                fs=[f for f in FOOD if h['inv'].get(f,0)>=1]
                if not fs: break
                f=max(fs,key=lambda x:NUT[x]); h['inv'][f]-=1; h['hunger']=min(100,h['hunger']+NUT[f])
            h['hunger']=max(0,h['hunger'])
        # 호가 조정
        if anchor: P=P+(sum(h['w'] for h in H)/len(H)/mref-P)/14.0
        for h in H:
            for i in sells(h):
                sold=h['sold'].get(i,0); h['ema'][i]=0.7*h['ema'].get(i,1.0)+0.3*sold
                if sold>0 and h['inv'].get(i,0)-reserve(h,i)<1: h['ema'][i]=max(h['ema'][i],sold*1.5)
                stock=h['inv'].get(i,0)-reserve(h,i)
                p=h['ask'].get(i,BASE[i]); 
                inc=sum(h['cost'].get(k,BASE[k])*v for o,inp,_ in CRAFT.get(h['job'],[]) if o==i for k,v in inp.items())
                fl=max(1.0,inc*1.05,floor_k*BASE[i]*(P if anchor else 1))
                tg=max(2,target_days*h['ema'][i])
                if lo is None:
                    if sold>0 and stock<1: p=p*(1+step)
                    elif stock>hi*tg: p=p*(1-step)
                else:
                    if sold>0 and stock<lo*tg: p=p*(1+step)
                    elif stock>hi*tg: p=p*(1-step)
                    elif anchor:
                        ref=BASE[i]*P
                        if p<ref/(1+step/2): p*=1+step/2
                        elif p>ref*(1+step/2): p*=1-step/2
                h['ask'][i]=max(fl,p)
        if works and d%7==3:
            gov=dict(job='gov',w=treasury*works,inv={},ask={},ema={},cost={},sold={},inc7=[0]*7)
            start=gov['w']
            for it,share in (('stone',0.4),('log',0.3),('iron_tool',0.15),('furniture',0.15)):
                b=start*share; 
                for _ in range(40):
                    L=listings(it,gov)
                    if not L or L[0][0]>b or gov['w']<L[0][0]: break
                    g,sp=buy(gov,it,min(10,b//L[0][0]))
                    if not g: break
                    b-=sp
            treasury-= (start-gov['w'])
        # 세금(주 1회): 순소득의 tax
        if d%7==6:
            for h in H:
                inc=max(0,sum(h['inc7'])); t=min(h['w'],inc*tax+(0 if h['own'] else rent)); h['w']-=t; treasury+=t
                if wtax and h['w']>wex: t2=(h['w']-wex)*wtax; h['w']-=t2; treasury+=t2
                if house and not h['own'] and h['w']>house+200: h['w']-=house; treasury+=house; h['own']=1
            # 이직: 7일 순소득이 전체 중앙값의 0.6배 미만이고, 가장 잘 버는 직업 평균의 0.6배 미만이면 이동
            if jobchange and d>28:
                byjob={}
                for h in H: byjob.setdefault(h['job'],[]).append(sum(h['inc7']))
                avg={j:st.mean(v) for j,v in byjob.items() if j!='guard'}
                cnt={j:len(v) for j,v in byjob.items()}
                order=sorted(avg,key=avg.get,reverse=True)
                for h in H:
                    if h['job']=='guard': continue
                    h['cool']=max(0,h['cool']-7)
                    if h['cool']>0 or cnt[h['job']]<=1: continue
                    for best in order:
                        if best==h['job']: break
                        if cnt.get(best,0)<CAP[best] and sum(h['inc7'])<0.6*avg[best]:
                            cnt[h['job']]-=1; cnt[best]=cnt.get(best,0)+1
                            h['job']=best; h['cool']=14; h['lvl']=1; h['ask']={}; h['ema']={}; break
        # 숙련
        if d%20==19:
            for h in H: h['lvl']=min(5,h['lvl']+1)
        # 지표
        def minp(i):
            L=[h['ask'].get(i,BASE[i]) for h in H if i in sells(h) and h['inv'].get(i,0)>=1]
            return min(L) if L else None
        basket=[minp('bread') or BASE['bread']*3, minp('carrot') or BASE['carrot']*3, minp('cooked_meat') or BASE['cooked_meat']*3]
        cpi=(basket[0]*1+basket[1]*1+basket[2]*0.5)/(12+3+7)
        cpi_hist.append(cpi)
        if d%7==6:
            byjob={}
            for h in H: byjob.setdefault(h['job'],[]).append(sum(h['inc7']))
            log.append(dict(d=d,cpi=round(cpi,2),hung=round(st.mean(h['hunger'] for h in H),1),starv=sum(1 for h in H if h['hunger']<30),
                 tre=round(treasury),lux=round(st.mean(h['lux'] for h in H)),wq=[round(x) for x in st.quantiles([h['w'] for h in H],n=4)],jobs={j:(len(v),round(st.mean(v))) for j,v in sorted(byjob.items())},
                 p={i:round(minp(i),1) if minp(i) else None for i in ['wheat','bread','carrot','cooked_meat','cake','log','charcoal','iron_ore','iron_tool','wool','bed','furniture']}))
    return log,cpi_hist
if __name__=='__main__':
    log,c=run()
    for r in log[::6]+[log[-1]]: print(json.dumps(r,ensure_ascii=False))
