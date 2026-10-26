import math, json
from pathlib import Path
P = [('SF',7,250,5),('SS',8,250,5),('MF',9,250,5),('MS',10,250,5),('LF',11,250,5),('LM',11,125,8),('LS',12,125,8)]
def seconds(n, sf, bw, cr):
    symbol=2**sf/(bw*1000)
    de=int(2**sf/bw>=16)
    symbols=8+max(math.ceil((8*n-4*sf+28+16)/(4*(sf-2*de)))*cr,0)
    return (16+4.25+symbols)*symbol
def tx(payloads,p): return sum(seconds(n+22,*p[1:]) for n in payloads)
def picture(n): return [67]+[30+min(198,n-i) for i in range(0,n,198)]
rows=[('Tagged text, 40 B French',[49]),('Tagged text, 200 B envelope',[200]),('Shared text, 40 B; 3 B name',[61]),('Shared text, 200 B; 3 B name',[221]),('Private text, 40 B; plain',[69]),('Private text, 40 B; keyed',[104]),('Private text, 191 B; plain',[220]),('Receipt, plain',[36]),('Receipt, keyed',[71]),('Hub receipt, 1 code',[10]),('Hub receipt, 25 codes',[202])]+[(f'Picture {n} B',picture(n)) for n in (500,1000,2000,3000,3200)]
lines=['| Event | Payload bytes | Sends | '+ ' | '.join(p[0] for p in P)+' |','|---|---|---|'+'---|'*len(P)]
counts=['| Event | '+' | '.join(p[0] for p in P)+' |','|---|'+'---|'*len(P)]
result=[]
for name,ns in rows:
    times=[tx(ns,p) for p in P]
    lines.append('| '+name+' | '+str(sum(ns))+' | '+str(len(ns))+' | '+' | '.join(f'{t:.3f}' for t in times)+' |')
    counts.append('| '+name+' | '+' | '.join(f'{math.floor(36/t)} / {math.floor(360/t)}' for t in times)+' |')
    result.append(dict(event=name,payloads=ns,seconds=times,counts_1=[math.floor(36/t) for t in times],counts_10=[math.floor(360/t) for t in times]))
base=Path(__file__).parent
(base/'tables.json').write_text(json.dumps(result,indent=2))
(base/'tables.md').write_text('\n'.join(lines)+'\n\n'+'\n'.join(counts))
print('\n'.join(lines));print('\n'.join(counts))
for n in (500,1000,2000,3000,3200):
    ns=picture(n); adjusted=[b+45 if b+45<=228 else b+10 if b+10<=228 else b for b in ns]
    legacy=sum(seconds(b+33,*P[4][1:])+seconds(36,*P[4][1:]) for b in ns)
    print('picture',n,'packets',ns,'tx',tx(ns,P[4]),'keys+acks upper',tx(adjusted,P[4]),'existing pictureSeconds',legacy)
for m in (10,20):
    text=tx([69],P[4]); pic=tx(picture(3000),P[4]); full=tx([228],P[4]); roundslot=full+.5
    print('shared',m,'text last wait',(m-1)*(text+.5),'picture all elapsed',m*(pic+17*.5),'picture hourly per person',360/pic/m,'one packet round',m*roundslot,'radio time text batch',m*text,'radio time picture batch',m*pic)
print('fullpacket',[(p[0],tx([228],p)) for p in P])
assert len(result)==16
assert all(len(picture(n))==1+math.ceil(n/198) for n in (500,1000,2000,3000,3200))
assert seconds(0,11,250,5)>0
