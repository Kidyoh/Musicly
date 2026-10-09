"""Soundtrack: voiceover placed by timing.json, procedural music, and SFX from sfx.json, mixed with ducking."""
import json, subprocess, wave, os
import numpy as np

SR = 44100
rng = np.random.default_rng(7)
timing = json.load(open('timing.json')); sfx = json.load(open('sfx.json'))
TOTAL = timing['total']; N = int(TOTAL * SR)
T = lambda x: int(x * SR)

def read_wav(path):
    with wave.open(path) as w:
        a = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
        return a if w.getnchannels() == 1 else a.reshape(-1, w.getnchannels()).mean(1)
def write_wav(path, st):
    with wave.open(path, 'wb') as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(st, -1, 1) * 32767).astype('<i2').tobytes())
def add(buf, x, t0, pan=0.0, gain=1.0):
    i = T(t0); n = min(len(x), len(buf) - i)
    if n <= 0 or i < 0: return
    l = gain * (1 - max(0, pan)); r = gain * (1 + min(0, pan))
    buf[i:i + n, 0] += x[:n] * l; buf[i:i + n, 1] += x[:n] * r
def env(n, a=.005, d=.2, curve=4):
    t = np.arange(n) / SR
    return np.minimum(1, t / max(a, 1e-4)) * np.exp(-t * curve / max(d, 1e-3))
def sine(f, n, ph=0):
    t = np.arange(n) / SR; f = np.broadcast_to(f, (n,)) if np.ndim(f) == 0 else f
    return np.sin(2 * np.pi * np.cumsum(f) / SR + ph)
def lp(x, fc):  # one-pole low-pass, fc may be an array
    fc = np.broadcast_to(fc, x.shape); a = 1 - np.exp(-2 * np.pi * fc / SR); y = np.empty_like(x); s = 0.0
    for i in range(len(x)): s += a[i] * (x[i] - s); y[i] = s
    return y
def hp(x, fc): return x - lp(x, fc)

# ---------- voice ----------
voice = np.zeros(N, np.float32)
os.makedirs('audio/tmp', exist_ok=True)
for l in timing['lines']:
    out = f"audio/tmp/{l['i']:02d}.wav"
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', l['file'], '-ar', str(SR), '-ac', '1', '-c:a', 'pcm_s16le', out], check=True)
    x = read_wav(out); i = T(l['start']); voice[i:i + len(x)] += x[:N - i]
write_wav('audio/tmp/voice_raw.wav', np.stack([voice, voice], 1))
subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', 'audio/tmp/voice_raw.wav', '-af',
    'highpass=f=85,equalizer=f=220:t=q:w=1:g=1.5,equalizer=f=3200:t=q:w=1.2:g=2.5,acompressor=threshold=0.09:ratio=3.2:attack=6:release=120:makeup=3,alimiter=limit=0.9',
    '-c:a', 'pcm_s16le', 'audio/tmp/voice_p.wav'], check=True)
vp = read_wav('audio/tmp/voice_p.wav')[:N]
vp = np.pad(vp, (0, N - len(vp)))
# voice activity envelope (smooth) for ducking
w = int(.09 * SR); ve = np.sqrt(np.convolve(vp ** 2, np.ones(w) / w, 'same')); ve = np.convolve(ve > .012, np.ones(int(.25 * SR)) / int(.25 * SR), 'same'); ve = np.clip(ve * 1.6, 0, 1)

# ---------- music ----------
BPM = 100; beat = 60 / BPM
M = np.zeros((N, 2), np.float32)
midi = lambda m: 440 * 2 ** ((m - 69) / 12)
CH = [(57, [57, 60, 64, 67]), (53, [53, 57, 60, 64]), (48, [48, 52, 55, 59]), (55, [55, 59, 62, 67])]   # Am7 Fmaj7 Cmaj7 G
def zone(t, pts):  # piecewise-linear gain curve
    xs, ys = zip(*pts); return float(np.interp(t, xs, ys))
t_jam = next(s['t'] for s in sfx['sfx'] if s['type'] == 'hit' and s['t'] > 15)          # the drop, on the word "Jam."
t_calm = [l for l in timing['lines'] if l['i'] == 12][0]['start'] - .6                    # same-room section
t_out = [l for l in timing['lines'] if l['i'] == 13][0]['start']                          # outro
PAD = [(0, .0), (.3, .5), (t_jam - .1, .45), (t_jam, .6), (t_calm, .6), (t_calm + .6, .4), (t_out - .1, .4), (t_out, .75), (TOTAL - 1.4, .7), (TOTAL, 0)]
ARP = [(0, .0), (.8, .0), (1.4, .35), (t_jam - 1.0, .35), (t_jam, .6), (t_calm, .6), (t_calm + .5, .3), (t_out, .55), (TOTAL - 1.2, .5), (TOTAL, 0)]
BASS = [(0, 0), (4.0, 0), (4.6, .55), (t_jam - .05, .55), (t_jam, .9), (t_calm, .9), (t_calm + .4, .3), (t_out - .05, .3), (t_out, .8), (TOTAL - 1.0, .6), (TOTAL, 0)]
KICK = [(0, 0), (9.9, 0), (10.0, .5), (t_jam - .05, .5), (t_jam, 1), (t_calm - .2, 1), (t_calm + .1, 0), (t_out - .05, 0), (t_out, 1), (t_out + 2.3, .6), (TOTAL - 1.5, 0)]
bars_n = int(TOTAL / (4 * beat)) + 1
for b in range(bars_n):
    t0 = b * 4 * beat; root, notes = CH[b % 4]
    # pad: soft detuned stacks
    for k, m in enumerate(notes):
        g = zone(t0, PAD) * .055; n = T(4 * beat + .4)
        e = np.minimum(1, np.arange(n) / SR / .9) * np.minimum(1, (n - np.arange(n)) / SR / .6)
        for det, pan in ((-.0035, -.5), (.0035, .5)):
            f = midi(m) * (1 + det)
            s = sum(np.sin(2 * np.pi * f * h * np.arange(n) / SR + k) / h ** 1.6 for h in (1, 2, 3, 4)) * e
            add(M, s.astype(np.float32), t0, pan, g)
    # bass (root, off-beat push)
    gb = zone(t0, BASS)
    if gb > .01:
        for off, dur in ((0, 1.4), (2.5, .8)):
            n = T(dur * beat); f = midi(root - 12)
            s = (sine(f, n) + .3 * sine(f * 2, n)) * env(n, .01, dur * beat * .9, 3)
            add(M, (s * .55 * gb).astype(np.float32), t0 + off * beat)
    # arpeggio: 8ths, up-down pattern, with echo later
    ga = zone(t0, ARP)
    if ga > .01:
        pat = [0, 1, 2, 3, 2, 1, 2, 3] if ga < .5 else [0, 2, 1, 3, 2, 3, 1, 2]
        for k, idx in enumerate(pat):
            tt = t0 + k * beat / 2; m = notes[idx] + 12
            n = T(.55); f = midi(m); e = env(n, .003, .35, 5)
            s = (sine(f, n) + .35 * sine(f * 2, n) + .12 * sine(f * 3, n)) * e
            add(M, (s * .16 * ga).astype(np.float32), tt, pan=(-.35 if k % 2 else .35))
    # drums
    gk = zone(t0, KICK)
    if gk > .01:
        for k in range(4):
            tt = t0 + k * beat
            full = gk > .8
            if not full and k not in (0, 2): continue
            n = T(.32); fr = 45 + 90 * np.exp(-np.arange(n) / SR * 28)
            s = sine(fr, n) * env(n, .001, .25, 5) * (1 if full else .8)
            add(M, (s * .85 * gk).astype(np.float32), tt)
            if full and k in (1, 3):   # clap on 2 & 4
                n = T(.16); nz = hp(rng.standard_normal(n).astype(np.float32), 1200) * env(n, .001, .1, 6)
                add(M, nz * .30 * gk, tt, 0)
        if gk > .8:
            for k in range(8):   # off-beat hats
                if k % 2 == 0: continue
                n = T(.06); nz = hp(rng.standard_normal(n).astype(np.float32), 6500) * env(n, .001, .04, 6)
                add(M, nz * .10, t0 + k * beat / 2, .25 if k % 4 == 1 else -.25)
# echo on the whole arp-ish mix (cheap): feed-forward delay of music high band
echo = np.zeros_like(M)
for d, g in ((int(beat * .75 * SR), .22), (int(beat * 1.5 * SR), .12)):
    echo[d:] += hp(M[:-d, :].T[0], 700).reshape(-1, 1).repeat(2, 1) * g
M += echo

# ---------- SFX ----------
S = np.zeros((N, 2), np.float32)
hits = sorted(s['t'] for s in sfx['sfx'] if s['type'] == 'hit')
def put(y, t0, peak_db, g=1.0, pan=0.0):
    """Place an effect normalised to a target peak (dBFS), so levels are predictable."""
    y = np.asarray(y, np.float32); m = np.abs(y).max()
    if m > 0: add(S, y / m * 10 ** (peak_db / 20), t0, pan, g)
for s in sfx['sfx']:
    t, ty, g = s['t'], s['type'], s['vol']
    if ty in ('whoosh', 'swish'):
        dur = .85 if ty == 'whoosh' else .45; n = T(dur); x = rng.standard_normal(n).astype(np.float32)
        p = np.arange(n) / n; fc = 250 + 4200 * np.sin(np.pi * p) ** 1.5
        y = hp(lp(x, fc), 180) * np.sin(np.pi * p) ** 1.2
        put(y, t - dur * .45, -19 if ty == 'whoosh' else -23, g)
    elif ty == 'pop':
        n = T(.14); f = 420 + 700 * (1 - np.exp(-np.arange(n) / SR * 60)); put(sine(f, n) * env(n, .002, .08, 5), t, -21, g)
    elif ty == 'tap':
        n = T(.09); y = sine(1500, n) * env(n, .001, .03, 5) * .5 + lp(rng.standard_normal(n).astype(np.float32), 900) * env(n, .001, .05, 5) * .6
        put(y, t, -23, g)
    elif ty == 'type':
        n = T(.025); put(sine(2200 + rng.uniform(-300, 300), n) * env(n, .0005, .012, 5), t, -31, g, rng.uniform(-.2, .2))
    elif ty == 'ding':
        n = T(.9); put((sine(1318.5, n) + .5 * sine(1975.5, n) + .25 * sine(2637, n)) * env(n, .002, .45, 5), t, -25, g)
    elif ty == 'join':
        for k, f in enumerate((659, 988)):
            n = T(.5); put((sine(f, n) + .4 * sine(f * 2, n)) * env(n, .003, .3, 5), t + k * .11, -25, g)
    elif ty == 'hit':
        n = T(1.1); f = 38 + 70 * np.exp(-np.arange(n) / SR * 18)
        y = sine(f, n) * env(n, .001, .8, 4) + lp(rng.standard_normal(n).astype(np.float32), 1800) * env(n, .001, .25, 6) * .4
        put(y, t, -11, g)
        tail = hp(lp(rng.standard_normal(T(1.6)).astype(np.float32), 5000), 800) * env(T(1.6), .02, 1.2, 3)
        put(tail, t + .02, -29, g)
    elif ty == 'riser':
        end = next((h for h in hits if h > t), t + 2.0); dur = end - t; n = T(dur); p = np.arange(n) / n
        x = rng.standard_normal(n).astype(np.float32); y = hp(lp(x, 300 + 6000 * p ** 2.2), 200) * p ** 1.8 + sine(180 * (2 ** (p * 3.2)), n) * p ** 2 * .12
        put(y, t, -19, g)
    elif ty == 'swell':
        for k, m in enumerate((69, 72, 76, 81)):
            n = T(1.6); f = midi(m); put((sine(f, n) + .3 * sine(f * 2, n)) * env(n, .01, 1.0, 3), t + .1 + k * .09, -29, g, (k - 1.5) * .3)

# ---------- mix ----------
duck = 1 - .70 * ve
mix = np.zeros((N, 2), np.float32)
mix += M * duck[:, None] * .55
mix += S * (1 - .25 * ve)[:, None]
mix += np.stack([vp, vp], 1) * .55
# fades
fi = np.minimum(1, np.arange(N) / SR / .15); fo = np.minimum(1, (N - np.arange(N)) / SR / 1.2)
mix *= (fi * fo)[:, None]
write_wav('audio/tmp/mix_raw.wav', mix)
subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', 'audio/tmp/mix_raw.wav', '-af', 'loudnorm=I=-15:TP=-1.5:LRA=9', '-ar', '48000', '-c:a', 'pcm_s16le', 'audio/mix.wav'], check=True)
print('ok', round(TOTAL, 2), 's; drop at', round(t_jam, 2), 'calm', round(t_calm, 2), 'outro', round(t_out, 2))

# ---- level report ----
db = lambda x: 20 * np.log10(max(x, 1e-9))
act = ve > .5
print('voice  rms(active) %.1f dB  peak %.1f dB' % (db(np.sqrt(np.mean(vp[act] ** 2))), db(np.abs(vp).max())))
mm = (M * duck[:, None] * .55)
print('music  rms(overall) %.1f dB  rms(under voice) %.1f dB  rms(no voice) %.1f dB' % (db(np.sqrt(np.mean(mm ** 2))), db(np.sqrt(np.mean(mm[act] ** 2))), db(np.sqrt(np.mean(mm[~act] ** 2)))))
for ty in sorted({s['type'] for s in sfx['sfx']}):
    pk = 0
    for s in sfx['sfx']:
        if s['type'] == ty:
            a = S[T(s['t']) - T(.5): T(s['t']) + T(1.2)]
            pk = max(pk, np.abs(a).max() if len(a) else 0)
    print('sfx %-7s peak %.1f dB' % (ty, db(pk)))
