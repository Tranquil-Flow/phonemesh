"""GC draft cost model. No repository writes or radio operations."""
import math
import json

def air(payload):
    # android/hub/.../Airtime.kt: LongFast and 22-byte framing estimate.
    on_air = payload + 22
    symbol = (2 ** 11) / 250_000
    n = 8 + max(math.ceil((8 * on_air - 4 * 11 + 28 + 16) / (4 * 11)) * 5, 0)
    return (16 + 4.25 + n) * symbol

def row(n):
    r = n - 1
    # 80-byte text, 2-byte canonical language; group envelope 31 bytes.
    fan = 25 + 31 + 1 + 2 + 80
    sender = fan + 64  # P-256 raw signature, derived nonce
    shared = sender + 12  # explicit nonce, authenticated sender
    ack = 25 + 11
    group_ack = 25 + 31 + 8  # one target sender sequence + missing mask
    return dict(n=n, recipients=r,
        fan=dict(packets=r, bytes=r*fan, seconds=r*air(fan), keyed_seconds=r*air(fan+35), mailbox=r*(fan+20)),
        sender=dict(packets=1, bytes=sender, seconds=air(sender), mailbox=sender+20),
        shared=dict(packets=1, bytes=shared, seconds=air(shared), mailbox=shared+20),
        fan_receipts=dict(packets=r, bytes=r*ack, seconds=r*air(ack), keyed_seconds=r*air(ack+35)),
        group_receipts=dict(packets=r, bytes=r*group_ack, seconds=r*air(group_ack), keyed_seconds=r*air(group_ack+35)),
        all_sender_keys=n*r, removal_sender_keys=(n-1)*(n-2), removal_shared_keys=n-2,
        fan_pending_bytes=r*fan,
        session_baseline=r*(2+32+65+33+32+32+4+2+32+32+4+32+8+1+28))

rows=[row(n) for n in [3,5,10,20]]
print(json.dumps(rows,indent=2))
print('single packet seconds', {b:round(air(b),6) for b in [36,71,64,99,139,174,203,215,228]})
# GC picture envelope consumes 31 bytes, replaces the existing 1-byte kind.
# Reserve ratchet key AND optional 10-byte ack: 168 - 31 - 4 - 10 = 123.
part=123
head=60+31+16+32 # keyed, 32-byte sketch; optional ACK excluded
chunks=[min(part,3200-i) for i in range(0,3200,part)]
picture_sizes=[head]+[60+31+4+c for c in chunks]
assert len(chunks)==27 and max(picture_sizes)<=228
print('picture',dict(parts=len(chunks),packets=len(picture_sizes),bytes=sum(picture_sizes),seconds=sum(map(air,picture_sizes))))
for n in [3,5,10,20]:
 print('picture group',n,'packets',len(picture_sizes)*(n-1),'seconds',round(sum(map(air,picture_sizes))*(n-1),3))
# Removal control: full roster proposal, READY and COMMIT; existing transport receipts extra.
for n in [3,5,10,20]:
 r=n-2
 roster=75+8*(n-1)
 print('removal',n,'remaining remote',r,'roster body',roster,'controls',3*r,'airtime_if_fits',round(r*(air(60+roster)+2*air(60+31+16)),3) if roster<=168 else 'needs control chunking')
print('text caps', {'keyed_no_ack_lang2':168-31-1-2,'all_langs_keyed_ack':168-31-1-6-10})
print('new_full_session_vault_bytes',2+32+65+33+32+32+4+2+32+32+4+32+8+1+28)
print('max_skipped_bytes',50*(8+32+8+1+2+8))
print('key distribution packet airtime', {b:air(b) for b in [192,159]})
for n in [3,5,10,20]:
 print('keys', n, 'all sender setup', n*(n-1), round(n*(n-1)*air(192),3), 'shared setup', n-1, round((n-1)*air(159),3), 'sender removal', (n-1)*(n-2), round((n-1)*(n-2)*air(192),3), 'shared removal', n-2, round((n-2)*air(159),3))
for label,overhead in [('sender',25+64+31),('shared',25+64+31+12)]:
 capacity=228-overhead-4
 sizes=[overhead+16+32]+[overhead+4+min(capacity,3200-i) for i in range(0,3200,capacity)]
 print('broadcast picture',label,'capacity',capacity,'packets',len(sizes),'seconds',round(sum(map(air,sizes)),3))
for n in [3,5,10,20]:
 r=n-1
 print('with receipts',n, 'fan',round(r*(air(139)+air(36)),3), 'sender',round(air(203)+r*air(64),3),'shared',round(air(215)+r*air(64),3))
