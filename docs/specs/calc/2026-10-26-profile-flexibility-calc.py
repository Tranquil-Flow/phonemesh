import math, json, io, random
from PIL import Image, ImageDraw, features
from pathlib import Path

def lum(h):
    c=[int(h[i:i+2],16)/255 for i in (0,2,4)]
    c=[v/12.92 if v<=0.04045 else ((v+0.055)/1.055)**2.4 for v in c]
    return sum(a*b for a,b in zip(c,[.2126,.7152,.0722]))
def contrast(a,b):
    a,b=sorted([lum(a),lum(b)])
    return (b+.05)/(a+.05)
def airtime(p):
    return (16+4.25+8+max(math.ceil((8*(p+22)-44+28+16)/44)*5,0))*2048/250000
colours=['E53935','FB8C00','FDD835','43A047','1E88E5','8E24AA','EC407A','6D4C41','00796B','00ACC1','3949AB','C0CA33','FFB74D','B39DDB','546E7A','9E9E9E']
palette=[{'index':i,'hex':h,'white':round(contrast(h,'FFFFFF'),3),'ink':round(contrast(h,'111111'),3),'pick':'white' if contrast(h,'FFFFFF')>=4.5 else ('ink' if contrast(h,'111111')>=4.5 else 'black')} for i,h in enumerate(colours)]
images=[]
for n in [48,64]:
    for kind in ['flat','drawn-symbol','seeded-noise']:
        im=Image.new('RGB',(n,n),'#00796B')
        if kind=='drawn-symbol':
            d=ImageDraw.Draw(im);d.ellipse((n//4,n//8,3*n//4,5*n//8),fill='#FDD835');d.rectangle((n//6,n//2,5*n//6,7*n//8),fill='#111111')
        if kind=='seeded-noise':
            r=random.Random(26);im=Image.frombytes('RGB',(n,n),bytes(r.randrange(256) for _ in range(n*n*3)))
        for q in [60,80]:
            f=io.BytesIO();im.save(f,format='WEBP',quality=q,method=6)
            images.append({'side':n,'fixture':kind,'quality':q,'webp_bytes':len(f.getvalue())})

def ac(n):return sum(1 for y in range(n) for x in range(0 if y else 1,n) if x*n<n*(n-y))
thumb={'opaque_square':5+math.ceil((ac(7)+2*ac(3))/2),'alpha_square':6+math.ceil((ac(5)+2*ac(3)+ac(5))/2)}
prefix='https://tranquil-flow.github.io/phonemesh/add#'
b64=lambda n: (n*8+5)//6
rows=[]
for N in [24,40]:
    for A in [0,4,32]:
        card=140+N+A; qr=card+33; hello=108+N+A; update=9+N+A
        rows.append(dict(N=N,A=A,card=card,qr=qr,qr_text=len(prefix)+b64(qr),link_text=len(prefix)+b64(card),hello=hello,update_plain=25+update,update_keyed=60+update,update_keyed_ack=70+update))
result={'palette':palette,'webp':images,'webp_lib':features.version('webp'),'thumbhash':thumb,'rgba':{str(n):n*n*4 for n in [48,64]},'rows':rows,'prefix_bytes':len(prefix),'airtime':{str(p):round(airtime(p),6) for p in [36,58,62,68,72,93,97,103,107,126,136,142,152,164,180,226,228]},'legacy':{str(n):{'card':134+n,'qr':167+n,'hello':102+n,'link_text':len(prefix)+b64(134+n),'qr_text':len(prefix)+b64(167+n)} for n in [24,40]},'hypothetical_photo':{str(w):{'card_delta':6+w+(w>255),'card_n24':164+w+(w>255),'qr_n24':197+w+(w>255),'hello_n24':132+w+(w>255),'shared_delta':3+w+(w>255),'qr_text':len(prefix)+b64(197+w+(w>255)),'lower_bound_packets':math.ceil(w/198)} for w in [24,25,512,1024]}}
Path(__file__).with_name('calculations.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
