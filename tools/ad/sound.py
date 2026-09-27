"""The video ad's soundtrack, synthesized from nothing but numpy and timed from ad.html's cue sheet.

    python3 tools/ad/sound.py tools/ad/out/cues.json tools/ad/out/soundtrack.wav [--levels]

A 120 bpm track in D major (D–Bm–G–A, a bar every 2s) plus sound design: every tap, whoosh, tick and
chime sits on a cue from ad.html, so the picture and the sound share one clock. No samples, no downloads.
--levels prints each bus per second; the balance was set by measurement against a mastered-pop octave
curve, so check any change the same way (and by ear).
"""
import json
import sys
import wave

import numpy as np

SR = 48000
rng = np.random.default_rng(1405)
cues = json.load(open(sys.argv[1] if len(sys.argv) > 1 else 'cues.json'))
OUT = sys.argv[2] if len(sys.argv) > 2 else 'soundtrack.wav'
DUR = cues['duration']
N = int(SR * DUR)
BEAT = 0.5
music = np.zeros((2, N))
sfx = np.zeros((2, N))
rev = np.zeros((2, N))          # reverb send


def mtof(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def tt(n):
    return np.arange(n) / SR


def place(bus, sig, t, gain=1.0, pan=0.0, send=0.0):
    """Mix a mono or stereo signal into a bus at time t, equal-power panned, optionally into the reverb."""
    if sig.shape[-1] < N:
        sig = sig.copy()
        k = min(sig.shape[-1], int(.004 * SR))
        sig[..., -k:] *= np.linspace(1, 0, k)
    if sig.ndim == 1:
        a = (np.clip(pan, -1, 1) + 1) * np.pi / 4
        sig = np.stack([sig * np.cos(a), sig * np.sin(a)]) * np.sqrt(2)
    i = int(round(t * SR))
    if i < 0:
        sig, i = sig[:, -i:], 0
    j = min(N, i + sig.shape[1])
    if j <= i:
        return
    bus[:, i:j] += gain * sig[:, :j - i]
    if send:
        rev[:, i:j] += gain * send * sig[:, :j - i]


def fft_filter(x, lp=None, hp=None, order=2, bp=None, q=1.0):
    """Zero-phase static filter in the frequency domain (Butterworth magnitudes)."""
    n = x.shape[-1]
    X = np.fft.rfft(x, axis=-1)
    f = np.fft.rfftfreq(n, 1 / SR)
    f[0] = 1e-3
    H = np.ones_like(f)
    if lp:
        H /= np.sqrt(1 + (f / lp) ** (2 * order))
    if hp:
        H /= np.sqrt(1 + (hp / f) ** (2 * order))
    if bp:
        H /= np.sqrt(1 + q * q * (f / bp - bp / f) ** 2)
    return np.fft.irfft(X * H, n, axis=-1)


def stft_filter(x, response, n_fft=2048, hop=512):
    """Time-varying filter: response(freqs, t) -> gain per bin, applied frame by frame (Hann, overlap-add)."""
    mono = x.ndim == 1
    x2 = x[None] if mono else x
    pad = n_fft
    X = np.pad(x2, ((0, 0), (pad, pad)))
    L = X.shape[1]
    out = np.zeros_like(X)
    wsum = np.zeros(L)
    win = np.hanning(n_fft + 1)[:-1]
    freqs = np.fft.rfftfreq(n_fft, 1 / SR)
    freqs[0] = 1e-3
    for s in range(0, L - n_fft, hop):
        H = response(freqs, (s + n_fft / 2 - pad) / SR)
        seg = X[:, s:s + n_fft] * win
        out[:, s:s + n_fft] += np.fft.irfft(np.fft.rfft(seg, axis=-1) * H, n_fft, axis=-1) * win
        wsum[s:s + n_fft] += win * win
    out = out / np.maximum(wsum, 1e-6)
    out = out[:, pad:pad + x2.shape[1]]
    return out[0] if mono else out


def lp_resp(fc_fn, order=2):
    return lambda f, t: 1 / np.sqrt(1 + (f / fc_fn(t)) ** (2 * order))


def bp_resp(fc_fn, q=1.2):
    return lambda f, t: 1 / np.sqrt(1 + q * q * (f / fc_fn(t) - fc_fn(t) / f) ** 2)


def fftconv(a, b):
    n = len(a) + len(b) - 1
    m = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(a, m) * np.fft.rfft(b, m), m)[:n]


def env_adsr(n, a=.005, d=.1, s=.7, r=.2, hold=None):
    t = tt(n)
    hold = (n / SR - r) if hold is None else hold
    e = np.where(t < a, t / a, s + (1 - s) * np.exp(-(t - a) / max(d, 1e-4)))
    rel = t > hold
    e[rel] *= np.exp(-(t[rel] - hold) / (r / 4))
    return e


def fade(y, ms=6):
    k = min(len(y), int(SR * ms / 1000))
    y[-k:] *= np.linspace(1, 0, k)
    return y


# ———————————————————————————————— oscillators ————————————————————————————————
def saw(f, n, ph0=0.0):
    """Band-limited sawtooth (polyBLEP); f may be a scalar or a per-sample array."""
    dt = (np.full(n, f) if np.isscalar(f) else f) / SR
    ph = (ph0 + np.cumsum(dt)) % 1.0
    y = 2 * ph - 1
    m = ph < dt
    x = ph[m] / dt[m]
    y[m] -= 2 * x - x * x - 1
    m = ph > 1 - dt
    x = (ph[m] - 1) / dt[m]
    y[m] -= x * x + 2 * x + 1
    return y


def supersaw(f, n, voices=7, spread=16, width=.8):
    out = np.zeros((2, n))
    cents = np.linspace(-spread, spread, voices)
    for k, c in enumerate(cents):
        y = saw(f * 2 ** (c / 1200), n, rng.random())
        pan = (k / (voices - 1) * 2 - 1) * width
        a = (pan + 1) * np.pi / 4
        out[0] += y * np.cos(a)
        out[1] += y * np.sin(a)
    return out / voices * 1.6


def pluck(f, dur=.42, bright=1.0, detune=6):
    """Additive pluck: every partial decays faster than the one below it — a filter envelope for free."""
    n = int(dur * SR)
    t = tt(n)
    out = np.zeros((2, n))
    for ch, dc in enumerate((-detune, detune)):
        fr = f * 2 ** (dc / 1200)
        kmax = int(min(28, SR * .45 / fr))
        y = np.zeros(n)
        for k in range(1, kmax + 1):
            y += np.sin(2 * np.pi * k * fr * t + rng.random() * 6.28) / k * np.exp(-t * (4 + 2.6 * k * bright))
        out[ch] = y
    out *= np.minimum(1, t / .002)
    return out * .55


def bell(f, dur=1.2, ratio=1.4, index=3.0, decay=.55):
    t = tt(int(dur * SR))
    I = index * np.exp(-t / .16)
    y = np.sin(2 * np.pi * f * t + I * np.sin(2 * np.pi * f * ratio * t)) * np.exp(-t / decay)
    y += .35 * np.sin(2 * np.pi * f * 2 * t) * np.exp(-t / (decay * .4))
    return fade(y * np.minimum(1, t / .002))


# ———————————————————————————————— drums ————————————————————————————————
def kick():
    n = int(.55 * SR)
    t = tt(n)
    f = 46 + 130 * np.exp(-t / .032) + 60 * np.exp(-t / .004)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / .22) * np.minimum(1, t / .001)
    click = fft_filter(rng.standard_normal(n), hp=2500) * np.exp(-t / .0025) * .35
    return fade(np.tanh(2.0 * (body + click)) / np.tanh(2.0))


def clap():
    n = int(.5 * SR)
    t = tt(n)
    e = np.zeros(n)
    for off in (0, .008, .017, .026):
        i = int(off * SR)
        e[i:] += np.exp(-np.arange(n - i) / SR / .0055)
    e += .55 * np.exp(-np.maximum(t - .026, 0) / .13) * (t > .026)
    y = fft_filter(rng.standard_normal(n), hp=950, lp=7000) * e
    return fade(y / np.abs(y).max())


def hat(open_=False):
    n = int((.4 if open_ else .09) * SR)
    t = tt(n)
    metal = sum(np.sign(np.sin(2 * np.pi * f * t)) for f in (317, 474, 598, 812, 1042, 1310))
    y = fft_filter(rng.standard_normal(n) * .7 + metal * .12, hp=7200) * np.exp(-t / (.1 if open_ else .017))
    return fade(y / np.abs(y).max())


def shaker():
    n = int(.1 * SR)
    t = tt(n)
    y = fft_filter(rng.standard_normal(n), bp=8500, q=1.4) * (t / .012 * np.exp(1 - t / .012))
    return fade(y / np.abs(y).max())


def snare():
    n = int(.25 * SR)
    t = tt(n)
    tone = np.sin(2 * np.pi * 190 * t) * np.exp(-t / .05)
    noise = fft_filter(rng.standard_normal(n), hp=1500, lp=9000) * np.exp(-t / .09)
    y = tone * .5 + noise
    return fade(y / np.abs(y).max())


# ———————————————————————————————— sound design ————————————————————————————————
def noise_sweep(dur, fc_fn, q=1.2, env=None):
    n = int(dur * SR)
    y = stft_filter(rng.standard_normal(n), bp_resp(lambda t: fc_fn(np.clip(t / dur, 0, 1)), q))
    e = env(np.linspace(0, 1, n)) if env else np.sin(np.pi * np.linspace(0, 1, n)) ** 2
    return y * e / (np.abs(y).max() + 1e-9)


def whoosh(dur=.6, f0=500, f1=3500, f2=None, q=1.3, peak=.45):
    """Band of air sweeping f0 → f1 (→ f2), swelling to `peak` of the way through."""
    def fc(p):
        if f2 is None:
            return f0 * (f1 / f0) ** p
        return np.where(p < peak, f0 * (f1 / f0) ** (p / peak), f1 * (f2 / f1) ** ((p - peak) / (1 - peak)))
    env = lambda p: np.where(p < peak, (p / peak) ** 2, np.exp(-(p - peak) / (1 - peak) * 4))
    return fft_filter(noise_sweep(dur, fc, q, env), lp=7000) * .8


def riser(dur=1.5, f0=250, f1=9000):
    n = int(dur * SR)
    t = tt(n)
    p = t / dur
    air = noise_sweep(dur, lambda q: f0 * (f1 / f0) ** q, 1.0, lambda q: q ** 2.2)
    tone = saw(110 * 2 ** (p * 3), n) * p ** 3 * .25
    tone = fft_filter(tone, lp=5000)
    return air + tone


def impact(dur=2.6):
    n = int(dur * SR)
    t = tt(n)
    f = 38 + 50 * np.exp(-t / .12)
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / .75) * np.minimum(1, t / .002)
    thud = fft_filter(rng.standard_normal(n), lp=260) * np.exp(-t / .22) * 2.2
    crash = fft_filter(rng.standard_normal(n), hp=3800) * np.exp(-t / 1.1) * .22
    y = np.tanh(1.4 * (boom * .6 + thud * .7)) + crash * 1.3
    return fade(y / np.abs(y).max(), 80)


def swell(dur=.6):
    """A reversed bloom that sucks into the next downbeat."""
    y = noise_sweep(dur, lambda p: 400 * (6000 / 400) ** p, .8, lambda p: p ** 3.2)
    return y


def tap():
    n = int(.09 * SR)
    t = tt(n)
    thump = np.sin(2 * np.pi * (150 + 90 * np.exp(-t / .006)) * t) * np.exp(-t / .022)
    tick = fft_filter(rng.standard_normal(n), bp=3200, q=2) * np.exp(-t / .0035)
    y = thump * .9 + tick * .6
    return fade(y / np.abs(y).max())


def tick_():
    n = int(.012 * SR)
    t = tt(n)
    y = np.sin(2 * np.pi * 3400 * t) * np.exp(-t / .0018) + fft_filter(rng.standard_normal(n), hp=3000) * np.exp(-t / .001) * .5
    return fade(y)


def pop(f):
    n = int(.16 * SR)
    t = tt(n)
    fr = f * (1 + .9 * np.exp(-t / .012))
    y = np.sin(2 * np.pi * np.cumsum(fr) / SR) * np.exp(-t / .05) * np.minimum(1, t / .001)
    y += .3 * np.sin(2 * np.pi * np.cumsum(fr * 2) / SR) * np.exp(-t / .025)
    return fade(y)


def thock():
    n = int(.14 * SR)
    t = tt(n)
    y = np.sin(2 * np.pi * (170 + 80 * np.exp(-t / .01)) * t) * np.exp(-t / .04)
    y += fft_filter(rng.standard_normal(n), lp=2500) * np.exp(-t / .006) * .6
    return fade(y / np.abs(y).max())


def marker():
    return noise_sweep(.16, lambda p: 1800 * (5500 / 1800) ** p, 2.2, lambda p: np.sin(np.pi * p) ** 1.5)


def chime(notes, gap=.07, dur=1.4):
    out = np.zeros(int((dur + gap * len(notes)) * SR))
    for k, m in enumerate(notes):
        b = bell(mtof(m), dur, ratio=2.0, index=1.2, decay=.5)
        i = int(k * gap * SR)
        out[i:i + len(b)] += b
    return out / len(notes) ** .5


# ———————————————————————————————— music ————————————————————————————————
CHORDS = {  # pad voicings (add9), bass root, arpeggio notes
    'D': ([50, 57, 62, 66, 69, 76], 38, [74, 78, 81, 86]),
    'Bm': ([47, 54, 59, 62, 66, 73], 35, [71, 74, 78, 83]),
    'G': ([43, 50, 55, 59, 62, 69], 31, [67, 71, 74, 79]),
    'A': ([45, 52, 57, 61, 64, 71], 33, [69, 73, 76, 81]),
}
BRAND = cues.get('brand', 16.0)                  # the brand's hit: the last riser, the snare roll and the lead hook lead to it
PROG = [['D', 'Bm', 'G', 'A'][b % 4] for b in range(int(BRAND // 2))]
PROG[-2:] = ['G', 'A']                           # arrive through G–A, then D under the end card
PROG += ['D'] * 3
ARP = [0, 1, 2, 1, 3, 1, 2, 1, 0, 1, 2, 1, 3, 2, 1, 2]
bar = lambda t: PROG[min(int(t // 2), len(PROG) - 1)]

BREAK = tuple(cues.get('break', (11.5, 16.0)))   # calm bars, no kick, the filter breathes: ad.html's iPhone scene, wide.html's loans
END = cues.get('end', 18.0)                      # final hit; the pad rings out after it
kicks = [b * BEAT for b in range(int(DUR / BEAT)) if 2.0 <= b * BEAT < END and not (BREAK[0] <= b * BEAT < BREAK[1])] + [END]
_idx = np.zeros(N)
for _kt in kicks:
    if int(_kt * SR) < N:
        _idx[int(_kt * SR)] = int(_kt * SR)
_dt = (np.arange(N) - np.maximum.accumulate(_idx)) / SR
DUCK = np.where(np.maximum.accumulate(_idx > 0), 1 - .55 * np.exp(-_dt / .16) * np.minimum(1, _dt / .004 + .2), 1.0)

# pad: one supersaw chord per bar, through a lowpass that follows the story
pad = np.zeros((2, N))
for b in range(0, int(DUR // 2) + 1):
    t0 = b * 2.0
    if t0 > END:
        break
    notes, _, _ = CHORDS[PROG[min(b, len(PROG) - 1)]]
    length = 2.35 if t0 < END else DUR - t0 + .5
    n = int(length * SR)
    ch = sum(supersaw(mtof(m), n) for m in notes) / len(notes) ** .5
    e = env_adsr(n, a=.18 if b else .05, d=1.0, s=.85, r=.5, hold=length - .45 if t0 < END else length)
    place(pad, ch * e, t0 - (.08 if b else 0))


def pad_fc(t):
    if t < 2:
        return 700 + 500 * t / 2
    if BREAK[0] <= t < BREAK[1]:
        p = (t - BREAK[0]) / (BREAK[1] - BREAK[0])
        return 500 * (5200 / 500) ** (p ** 1.6)
    if t >= END:
        return 2600 * np.exp(-(t - END) / 1.6) + 600
    return 2600


pad = stft_filter(pad, lp_resp(pad_fc, 2))
pad = fft_filter(pad, hp=150)
_lvl = np.ones(N)
_tb = np.arange(N) / SR
_brk = (_tb >= BREAK[0]) & (_tb < BREAK[1])
_lvl[_brk] = .62 + .38 * ((_tb[_brk] - BREAK[0]) / (BREAK[1] - BREAK[0])) ** 2
_lvl[_tb < 2.0] = 2.2
place(music, pad * DUCK * _lvl, 0, .22, send=.3)

# bass: pumping off-beat eighths in the groove, long notes in the break, all ducked by the kick
bass = np.zeros((2, N))
for i in range(int(DUR / (BEAT / 2))):
    t0 = i * BEAT / 2
    if t0 < 2.0 or t0 >= END + .01:
        continue
    _, root, _ = CHORDS[bar(t0)]
    if BREAK[0] <= t0 < BREAK[1]:
        if abs(t0 % 2) > 1e-6:
            continue
        length = 2.0
    else:
        if i % 2 == 0:
            continue                      # off-beats only: the kick owns the beat
        length = .22
    n = int(length * SR)
    t = tt(n)
    f = mtof(root)
    y = .7 * np.sin(2 * np.pi * f * t) + .5 * np.tanh(2.5 * saw(f * 2, n)) + .18 * np.sin(2 * np.pi * f * 3 * t)
    e = env_adsr(n, a=.004, d=.08, s=.8, r=.06 if length < 1 else .4, hold=length - .05)
    place(bass, y * e, t0)
if END < DUR:
    n = int(2.2 * SR)
    t = tt(n)
    place(bass, (np.sin(2 * np.pi * mtof(38) * t) + .2 * np.tanh(3 * saw(mtof(50), n))) * np.exp(-t / .9), END)
bass = fft_filter(bass, lp=1400)

# arpeggio plucks, sixteenths, brighter as the story goes on
arp = np.zeros((2, N))
for i in range(int(DUR / (BEAT / 4))):
    t0 = i * BEAT / 4
    if t0 >= END:
        break
    notes = CHORDS[bar(t0)][2]
    m = notes[ARP[i % 16]]
    bright = .55 if t0 < 2 else (.8 if BREAK[0] <= t0 < BREAK[1] else 1.0)
    vel = (.9 if t0 < 2 else .8) * (1.15 if i % 4 == 0 else 1)
    place(arp, pluck(mtof(m), .38, bright), t0, vel)
place(music, arp, 0, .2, send=.25)
# ping-pong echo of the plucks, dotted eighth
d = int(.375 * SR)
echo = np.zeros((2, N))
src = arp.mean(axis=0)
for k in range(1, 5):
    if k * d >= N:
        break
    echo[k % 2, k * d:] += .42 ** k * src[:N - k * d]
place(music, fft_filter(echo, lp=3500, hp=400), 0, .1)

# chord stabs on the wall's four words, and a lead hook for the brand
stabs = np.zeros((2, N))
for tw in cues['words']:
    notes, _, _ = CHORDS[bar(tw)]
    n = int(.5 * SR)
    ch = sum(supersaw(mtof(m + 12), n, voices=5, spread=10) for m in notes[1:5]) / 2
    e = np.exp(-tt(n) / .12) * np.minimum(1, tt(n) / .003)
    place(stabs, fft_filter(ch * e, lp=5200, hp=300), tw)
place(music, stabs, 0, .2, send=.3)
lead = np.zeros((2, N))
for dt, m, ln in [(0, 78, .5), (.5, 81, .5), (1.0, 86, .75), (1.75, 88, .25), (2.0, 86, 1.6)]:
    place(lead, pluck(mtof(m), ln + .5, 1.2, detune=9), BRAND + dt)
place(music, lead, 0, .3, send=.35)

# drums
K, CL, HH, OH, SH, SN = kick(), clap(), hat(), hat(True), shaker(), snare()
for b in range(int(DUR / BEAT)):
    t0 = b * BEAT
    in_break = BREAK[0] <= t0 < BREAK[1]
    if 2.0 <= t0 < END and not in_break:
        place(music, K, t0, .95)
    if 4.0 <= t0 < END and not in_break and b % 2 == 1:
        place(music, CL, t0, .34, send=.2)
    if 2.0 <= t0 < END:
        place(music, HH, t0 + BEAT / 2, .16 if not in_break else .07, pan=.25)
        if cues['wall'] <= t0 < cues['wall'] + 3 or BRAND <= t0 < END:
            place(music, SH, t0 + BEAT / 4, .1, pan=-.3)
            place(music, SH, t0 + 3 * BEAT / 4, .07, pan=-.3)
        if b % 4 == 3 and not in_break:
            place(music, OH, t0 + BEAT / 2, .1, pan=.35)
place(music, K, END, 1.0)
# a snare roll that tightens into the brand
for k in range(40):
    p = k / 40
    t0 = BRAND - 1.0 + (1 - (1 - p) ** 1.6) * 1.0
    place(music, SN, t0, .05 + .22 * p ** 2, pan=.1 * np.sin(k), send=.15)

music += bass * DUCK * .55

# risers, swells and impacts at the scene changes
place(music, riser(1.4), 2.0 - 1.4, .22, send=.2)
place(music, swell(.5), 2.0 - .5, .3)
place(music, impact(), 2.0, .55, send=.25)
place(music, riser(.5, 600, 7000), cues['wall'] - .5, .18)
place(music, impact(2.0), cues['wall'], .42, send=.25)
place(music, riser(1.6), BRAND - 1.6, .26, send=.2)
place(music, swell(.45), BRAND - .45, .32)
place(music, impact(3.0), BRAND, .62, send=.3)
place(music, impact(2.5), END, .5, send=.3)
place(music, hat(True), END, .18, send=.3)
# the intro: the beat is already there, muffled, until the drop opens it at 2s
_mk = fft_filter(np.concatenate([K, np.zeros(SR // 10)]), lp=260)
for _b in range(4):
    place(music, _mk, _b * BEAT, .9 if _b else 1.1)
place(music, impact(1.6), 0, .3, send=.3)
# the first frame: a soft bloom under the question
n = int(1.8 * SR)
t = tt(n)
bloom = sum(np.sin(2 * np.pi * mtof(m) * t) for m in (50, 57, 62, 66)) * np.exp(-t / .6) * np.minimum(1, t / .01) / 4
place(music, bloom, 0, .5, send=.35)

# ———————————————————————————————— sound effects on the cue sheet ————————————————————————————————
C = cues
# chips: tuned pops climbing the D major pentatonic, panned to where each chip lands
for i, x in enumerate(C['chipX']):
    place(sfx, pop(mtof([74, 76, 78, 81, 83, 86, 88, 90, 93, 95, 98][i % 11])), C['chipsOut'] + i * C['chipGap'] + .05, .16, pan=x * .7, send=.25)
# the pinch pulls everything in
place(sfx, whoosh(.55, 3000, 500, q=1.0, peak=.8), C['converge'] - .05, .32)
# the odometer: one tick per digit that rolls past, from each strip's own easing (power3.out)
for i, (D, d) in enumerate(zip(C['strips'], C['stripDur'])):
    pan = -.55 + 1.1 * i / (len(C['strips']) - 1)
    for k in range(1, D + 1):
        p = 1 - (1 - k / D) ** (1 / 3)
        place(sfx, tick_(), C['drop'] + p * d, .055 if k < D else .18, pan=pan)
# the scan: air and a glassy tone travelling right to left with the beam
n = int(C['scanDur'] * SR)
t = tt(n)
p = t / C['scanDur']
ease = np.where(p < .5, 2 * p * p, 1 - (-2 * p + 2) ** 2 / 2)   # power1.inOut, the beam's own easing
posx = 1 - ease * 2                     # +1 (right) → -1 (left)
glass = (np.sin(2 * np.pi * 2350 * t + 3 * np.sin(2 * np.pi * 7 * t)) * .5 + np.sin(2 * np.pi * 3525 * t) * .3) * np.sin(np.pi * p) ** .6
air = noise_sweep(C['scanDur'], lambda q: 1200 * (5000 / 1200) ** np.sin(np.pi * q), 1.6, lambda q: np.sin(np.pi * q) ** .8)
scan = glass * .35 + air
a = (posx + 1) * np.pi / 4
place(sfx, np.stack([scan * np.cos(a), scan * np.sin(a)]) * np.sqrt(2), C['scan'], .26, send=.25)
# the camera pulls out; the screen changes tab
place(sfx, whoosh(.9, 300, 2600, 700, q=1.0, peak=.5), C['zoom'] - .05, .34)
place(sfx, tap() * .5, C['tabSwap'], .1)
# the SMS: a two-note chime, the marker, a tap, and the row landing
place(sfx, chime([88, 93], gap=.11, dur=1.3), C['notif'] + .02, .34, pan=.1, send=.3)
for tm in C['marks']:
    place(sfx, marker(), tm, .16, pan=.2)
place(sfx, tap(), C['notifTap'], .4, pan=.1)
place(sfx, whoosh(.55, 2600, 500, q=1.4, peak=.35), C['morph'] + .03, .22)
place(sfx, thock(), C['land'], .38, send=.1)
# the wall: a swoosh as the phone steps back, a tap and a tick on each word
place(sfx, whoosh(1.0, 250, 1800, 500, q=.9, peak=.35), C['wall'] - .05, .4)
for tw, x in zip(C['words'], C['pan']['wall']):
    place(sfx, tap(), tw, .38, pan=x * .6)
    place(sfx, whoosh(.25, 900, 4000, q=1.6, peak=.6), tw - .12, .08)
# into the iPhone scene (ad.html)
if 'toIphone' in C:
    place(sfx, whoosh(.7, 400, 3200, 900, q=1.0, peak=.45), C['toIphone'], .3)
    place(sfx, whoosh(.6, 250, 1200, q=1.0, peak=.7), C['ipIn'], .18)
    for tm in (C['shareTap'], C['addTap'], C['confirmTap'], C['iconTap']):
        place(sfx, tap(), tm, .36)
    place(sfx, whoosh(.35, 500, 2400, q=1.3, peak=.6), C['sheetUp'], .16)
    place(sfx, whoosh(.35, 500, 2400, q=1.3, peak=.6), C['dialogUp'], .16)
    place(sfx, whoosh(.3, 3500, 800, q=1.2, peak=.3), C['swipe'], .2)
    place(sfx, pop(mtof(81)), C['iconPop'] + .05, .3, send=.3)
    place(sfx, chime([86, 90, 93, 98], gap=.05, dur=.9), C['iconPop'] + .08, .14, send=.4)
    place(sfx, whoosh(.55, 600, 5000, q=1.0, peak=.7), C['appOpen'], .24)
# loans (wide.html): the page changes, the people who owe you lift out, a tap opens one of them
if 'personTap' in C:
    place(sfx, tap() * .5, C['loans'], .1)
    place(sfx, whoosh(.5, 400, 2400, q=1.1, peak=.6), C['lift'] - .05, .22)
    place(sfx, pop(mtof(78)), C['lift'] + .1, .2, pan=C['pan']['person'] * .5, send=.3)
    place(sfx, tap(), C['personTap'], .4, pan=C['pan']['person'] * .5)
    place(sfx, whoosh(.45, 2600, 600, q=1.3, peak=.4), C['personTap'] + .1, .18)
# family (wide.html): into it off the wall's last tap, the phone rising, two lifts, the line rolling, the lock
if 'toFamily' in C:
    place(sfx, whoosh(.7, 400, 3200, 900, q=1.0, peak=.45), C['toFamily'], .3)
    place(sfx, whoosh(.6, 250, 1200, q=1.0, peak=.7), C['famIn'], .18)
    for tm in (C['famLift'], C['famLift2']):
        place(sfx, whoosh(.5, 400, 2400, q=1.1, peak=.6), tm - .05, .2)
        place(sfx, pop(mtof(78)), tm + .1, .18, pan=-.3, send=.3)
    place(sfx, tap() * .5, C['famSwap'], .1)
    place(sfx, whoosh(.5, 2200, 700, q=1.2, peak=.5), C['famScroll'], .14)
    place(sfx, whoosh(.3, 900, 4000, q=1.6, peak=.6), C['subRoll'] - .05, .1)
    place(sfx, chime([81, 86], gap=.07, dur=1.0), C['lock'] + .05, .16, pan=.2, send=.35)
# the brand: a whoosh in, a sparkle while the mark draws, pops for the buttons, chimes for the taps
place(sfx, whoosh(.6, 300, 3000, q=.9, peak=.75), C['toEnd'], .3)
for k, m in enumerate([74, 78, 81, 86, 90, 93]):
    place(sfx, bell(mtof(m + 12), .9, ratio=3.0, index=.8, decay=.35), C['logo'] + .08 + k * .11, .05, pan=-.4 + k * .16, send=.45)
for tm in C['ctas']:
    place(sfx, pop(mtof(69)), tm + .04, .14, send=.2)
place(sfx, pop(mtof(81)), C['chip'] + .04, .1, send=.2)
for k, tm in enumerate(C.get('ctaTaps', [])):
    place(sfx, tap(), tm, .4, pan=C['pan']['ends'][k] * .5)
    place(sfx, chime([81, 86] if k == 0 else [86, 90], gap=.06, dur=1.2), tm + .03, .2, send=.35)

# ———————————————————————————————— reverb, mix, master ————————————————————————————————
def make_ir(rt60=1.7, lp=5500):
    n = int(rt60 * 1.1 * SR)
    t = tt(n)
    ir = np.zeros((2, n))
    for c in range(2):
        ir[c] = fft_filter(rng.standard_normal(n), lp=lp, hp=250) * np.exp(-6.9 * t / rt60)
        ir[c, :int(.012 * SR)] = 0                     # pre-delay
    return ir / np.sqrt((ir ** 2).sum(axis=1, keepdims=True))


ir = make_ir()
wet = np.stack([fftconv(rev[0], ir[0]), fftconv(rev[1], ir[1])])[:, :N]
mix = music * .4 + sfx + wet * .5
if '--levels' in sys.argv:
    for name, bus in (('music', music * .4), ('sfx', sfx), ('reverb', wet * .5)):
        mono = bus.mean(axis=0)
        print(f'{name:7s}', ' '.join(f'{20 * np.log10(np.sqrt((mono[i:i + SR] ** 2).mean()) + 1e-9):4.0f}' for i in range(0, N, SR)))
mix = fft_filter(mix, hp=32)


def master_eq(x):
    X = np.fft.rfft(x, axis=-1)
    f = np.fft.rfftfreq(x.shape[-1], 1 / SR)
    lf = np.log2(np.maximum(f, 1))
    db = (-6 / (1 + np.exp((lf - np.log2(95)) * 5))          # low shelf, -6 dB under ~95 Hz
          + 5.0 * np.exp(-((lf - np.log2(380)) / .9) ** 2)    # warmth around 380 Hz
          - 2.0 * np.exp(-((lf - np.log2(1600)) / .8) ** 2)   # ease 1–2 kHz
          - 5.0 / (1 + np.exp(-(lf - np.log2(11000)) * 4)))   # soften the air
    return np.fft.irfft(X * 10 ** (db / 20), x.shape[-1], axis=-1)


mix = master_eq(mix)
# glue: a slow RMS compressor, then a soft clip
_e = np.cumsum(np.insert((mix ** 2).mean(axis=0), 0, 0))
_w = int(.05 * SR)
rms = np.sqrt(np.maximum(np.pad((_e[_w:] - _e[:-_w]) / _w, (_w // 2, _w - 1 - _w // 2), mode='edge'), 0))
thr = .25
gain = np.where(rms > thr, (thr / np.maximum(rms, 1e-9)) ** .35, 1.0)
mix *= gain
mix = np.tanh(mix * 1.1) / np.tanh(1.1)
# fade the very end
k = int(.6 * SR)
mix[:, -k:] *= np.linspace(1, 0, k) ** 2
peak = np.abs(mix).max()
mix = mix / peak * .89

pcm = (np.clip(mix.T, -1, 1) * 32767).astype('<i2')
with wave.open(OUT, 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print(OUT, f'{DUR:.2f}s', 'peak before normalise', round(float(peak), 3))
