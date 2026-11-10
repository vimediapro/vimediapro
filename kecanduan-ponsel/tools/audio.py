"""Synthesize backsong + SFX and mix with the voice-over.

Everything is generated procedurally (royalty-free by construction).
Inputs : build/timeline.json, build/cues.json, build/voice/*.mp3
Output : build/mix.wav (48 kHz stereo, length = timeline length)
"""
import json, os, subprocess
import numpy as np
from scipy.signal import butter, sosfilt, lfilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.path.join(ROOT, "build")
SR = 48000
TL = json.load(open(os.path.join(B, "timeline.json")))
CUES = json.load(open(os.path.join(B, "cues.json")))
LEN = TL["length"]
N = int(LEN * SR)
rng = np.random.default_rng(7)


def t_(d):
    return np.arange(int(d * SR)) / SR


def env(d, a=0.005, r=None, curve=6.0):
    t = t_(d)
    e = np.minimum(1, t / max(a, 1e-4))
    e *= np.exp(-curve * t / d) if r is None else np.exp(-t / r)
    return e


def lp(x, f, order=2):
    return sosfilt(butter(order, min(f, SR / 2 - 100) / (SR / 2), "low", output="sos"), x)


def hp(x, f, order=2):
    return sosfilt(butter(order, f / (SR / 2), "high", output="sos"), x)


def bp(x, f1, f2, order=2):
    return sosfilt(butter(order, [f1 / (SR / 2), min(f2, SR / 2 - 100) / (SR / 2)], "band", output="sos"), x)


def sweep(f0, f1, d, kind="exp"):
    t = t_(d)
    f = f0 * (f1 / f0) ** (t / d) if kind == "exp" else f0 + (f1 - f0) * t / d
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def noise(d):
    return rng.standard_normal(int(d * SR))


def varlp(x, f0, f1, block=256):
    """Noise through a low-pass whose cutoff glides f0->f1 (block-wise)."""
    out = np.zeros_like(x)
    nb = len(x) // block + 1
    zi = np.zeros(2)
    for i in range(nb):
        f = f0 * (f1 / f0) ** (i / max(1, nb - 1))
        p = np.exp(-2 * np.pi * f / SR)
        seg = x[i * block:(i + 1) * block]
        if not len(seg):
            break
        y, zi1 = lfilter([1 - p], [1, -p], seg, zi=zi[:1])
        y2, zi2 = lfilter([1 - p], [1, -p], y, zi=zi[1:])
        zi = np.array([zi1[0], zi2[0]])
        out[i * block:i * block + len(seg)] = y2
    return out


def norm(x, peak=1.0):
    m = np.max(np.abs(x)) + 1e-9
    return x / m * peak


# ------------------------------------------------------------------ SFX ---
def bell(f, d=1.0, partials=((1, 1), (2.76, .4), (5.4, .2), (8.9, .08))):
    t = t_(d)
    return sum(a * np.sin(2 * np.pi * f * k * t) * np.exp(-t * (3 + k * 2) / d) for k, a in partials) * np.minimum(1, t / .002)


def sfx(kind):
    if kind == "pop":
        return norm(sweep(380, 1100, .09) * env(.09, .002, curve=5) + .3 * noise(.09) * env(.09, .001, curve=40)) * .8
    if kind == "blip":
        a = np.sin(2 * np.pi * 880 * t_(.07)) * env(.07, .002, curve=4)
        b = np.sin(2 * np.pi * 1320 * t_(.11)) * env(.11, .002, curve=5)
        return np.concatenate([a, b]) * .55
    if kind in ("whoosh", "swoosh", "whooshout"):
        d = {"whoosh": .5, "swoosh": .32, "whooshout": .9}[kind]
        n = noise(d)
        f0, f1 = (300, 5000) if kind != "whooshout" else (5000, 200)
        x = varlp(n, f0, f1)
        e = np.sin(np.pi * np.linspace(0, 1, len(x))) ** 1.6
        x = norm(hp(x * e, 120)) * (.55 if kind == "swoosh" else .7)
        if kind == "whooshout":
            x[: int(.9 * SR)] += .5 * np.sin(2 * np.pi * 55 * t_(.9)) * env(.9, .3, curve=4)
        return x
    if kind == "snatch":
        x = varlp(noise(.22), 1500, 9000) * np.sin(np.pi * np.linspace(0, 1, int(.22 * SR)))
        return norm(hp(x, 800)) * .7 + .25 * sweep(300, 1400, .22) * env(.22, .01, curve=4)
    if kind == "boing":
        t = t_(.6)
        f = 180 + 160 * (1 - np.exp(-t * 8)) + 35 * np.sin(2 * np.pi * 14 * t) * np.exp(-t * 4)
        return np.sin(2 * np.pi * np.cumsum(f) / SR) * env(.6, .005, curve=5) * .6
    if kind == "impact":
        t = t_(.9)
        f = 40 + 90 * np.exp(-t * 18)
        x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 4.5)
        x += .35 * lp(noise(.9), 2500) * np.exp(-t * 14)
        return norm(np.tanh(1.6 * x)) * .9
    if kind == "count":
        out = np.zeros(int(1.7 * SR))
        n = 26
        for i in range(n):
            tt = 1.6 * (1 - (1 - i / n) ** 1.8)
            c = np.sin(2 * np.pi * (900 + i * 35) * t_(.03)) * env(.03, .001, curve=8) * .32
            s = int(tt * SR); out[s:s + len(c)] += c
        return out
    if kind == "ding":
        x = bell(1568, 1.2) * .45
        x[: int(.9 * SR)] += bell(2093, .9) * .2
        return x
    if kind == "tick":
        return np.sin(2 * np.pi * 2000 * t_(.025)) * env(.025, .0005, curve=10) * .5
    if kind == "bubble":
        return sweep(320, 1000, .1) * env(.1, .002, curve=3) * .5
    if kind == "lever":
        out = np.zeros(int(.5 * SR))
        for i in range(5):
            c = hp(noise(.012), 2000) * env(.012, .0005, curve=6) * .4
            s = int(i * .05 * SR); out[s:s + len(c)] += c
        th = np.sin(2 * np.pi * 120 * t_(.2)) * env(.2, .002, curve=9) * .6
        s = int(.3 * SR); out[s:s + len(th)] += th
        return out
    if kind == "spin":
        out = np.zeros(int(1.6 * SR)); tt = 0.0; i = 0
        while tt < 1.45:
            c = bp(noise(.015), 1500, 6000) * env(.015, .0005, curve=6) * .35
            s = int(tt * SR); out[s:s + len(c)] += c
            tt += .035 + .06 * (tt / 1.45) ** 2; i += 1
        return out
    if kind == "clunk":
        return (np.sin(2 * np.pi * 150 * t_(.14)) * env(.14, .001, curve=10) + .3 * bp(noise(.14), 800, 3000) * env(.14, .001, curve=30)) * .6
    if kind == "jackpot":
        out = np.zeros(int(1.8 * SR))
        for i, f in enumerate([1047, 1319, 1568, 2093, 2637]):
            b = bell(f, 1.0) * .3; s = int(i * .07 * SR); out[s:s + len(b)] += b
        out += .08 * hp(noise(1.8), 6000) * np.exp(-t_(1.8) * 2.5)
        return out
    if kind == "thud":
        t = t_(.45)
        return (np.sin(2 * np.pi * np.cumsum(70 + 80 * np.exp(-t * 30)) / SR) * np.exp(-t * 9) + .2 * lp(noise(.45), 900) * np.exp(-t * 20)) * .9
    if kind == "shimmer":
        out = np.zeros(int(1.2 * SR))
        for i, f in enumerate([1319, 1568, 1976, 2349, 2637, 3136]):
            b = np.sin(2 * np.pi * f * t_(.5)) * env(.5, .003, curve=6) * .16; s = int(i * .06 * SR); out[s:s + len(b)] += b
        return out
    if kind == "rise":
        d = 1.6; t = t_(d)
        return (sweep(200, 900, d) * .25 + norm(varlp(noise(d), 400, 6000)) * .25) * (t / d) ** 1.5 * np.minimum(1, (d - t) / .05)
    if kind == "alert":
        out = []
        for f in (660, 495, 660, 495):
            t = t_(.13)
            out.append(np.sign(np.sin(2 * np.pi * f * t)) * .12 * env(.13, .004, curve=2))
            out.append(np.zeros(int(.03 * SR)))
        return lp(np.concatenate(out), 3500)
    if kind == "morph":
        return sweep(300, 1200, .35) * env(.35, .01, curve=3) * .35 + sfx("shimmer")[: int(.35 * SR)] * .6
    if kind == "error":
        out = []
        for f in (311, 233):
            out.append(lp(np.sign(np.sin(2 * np.pi * f * t_(.16))), 2500) * .14 * env(.16, .004, curve=2)); out.append(np.zeros(int(.04 * SR)))
        return np.concatenate(out)
    if kind == "clock":
        out = np.zeros(int(1.4 * SR))
        for i in range(6):
            f = 2400 if i % 2 == 0 else 1800
            c = bp(noise(.02), f * .7, f * 1.3) * env(.02, .0005, curve=6) * .7 + np.sin(2 * np.pi * f * t_(.02)) * env(.02, .0005, curve=8) * .2
            s = int(i * .22 * SR); out[s:s + len(c)] += c
        return out
    if kind == "check":
        a = np.sin(2 * np.pi * 1047 * t_(.08)) * env(.08, .002, curve=4)
        b = bell(1568, .5) * 1.2
        return np.concatenate([a, b]) * .4
    if kind == "swipe":
        x = varlp(noise(.25), 2000, 8000) * np.sin(np.pi * np.linspace(0, 1, int(.25 * SR)))
        return norm(hp(x, 1500)) * .45
    raise ValueError(kind)


def scroll_ticks(t0, dur):
    """Soft tick each time a feed card passes (matches scrollAt() in motion.js)."""
    ev, k = [], 1
    while True:
        # 120x + 130x^2 = 300k
        x = (-120 + np.sqrt(120 ** 2 + 4 * 130 * 300 * k)) / (2 * 130)
        if x > dur:
            break
        ev.append(t0 + x); k += 1
    return ev


# ---------------------------------------------------------------- MUSIC ---
BPM = 110
BEAT = 60 / BPM
BAR = 4 * BEAT
# I - V - vi - IV in C major
CHORDS = [[48, 52, 55, 60, 64], [43, 47, 50, 55, 62], [45, 48, 52, 57, 64], [41, 45, 48, 53, 60]]
ROOTS = [36, 31, 33, 29]
mf = lambda m: 440 * 2 ** ((m - 69) / 12)


def add(buf, x, t, g=1.0, pan=0.0):
    s = int(t * SR)
    if s >= len(buf) or s + len(x) <= 0:
        return
    x = x[: len(buf) - s]
    l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
    buf[s:s + len(x), 0] += x * g * l * 1.414
    buf[s:s + len(x), 1] += x * g * r * 1.414


def kick():
    t = t_(.4)
    return np.sin(2 * np.pi * np.cumsum(45 + 110 * np.exp(-t * 28)) / SR) * np.exp(-t * 9) * np.minimum(1, t / .001)


def clap():
    n = bp(noise(.22), 900, 4500)
    e = np.zeros(len(n))
    for d in (0, .011, .022):
        s = int(d * SR); e[s:] += np.exp(-np.arange(len(n) - s) / SR * (60 if d < .02 else 14))
    return n * e * .5


def hat(open_=False):
    d = .2 if open_ else .045
    return hp(noise(d), 7000) * env(d, .001, curve=7 if open_ else 9) * .3


def pluck(f, d=.45):
    t = t_(d)
    x = np.sin(2 * np.pi * f * t) + .35 * np.sin(2 * np.pi * f * 3.98 * t) * np.exp(-t * 25) + .15 * np.sin(2 * np.pi * f * 2 * t)
    return x * np.exp(-t * 7) * np.minimum(1, t / .002)


def bass(f, d):
    t = t_(d)
    x = np.sin(2 * np.pi * f * t) + .3 * np.sin(2 * np.pi * 2 * f * t)
    return np.tanh(1.5 * x) * np.minimum(1, t / .005) * np.exp(-t * 2.5) * np.minimum(1, (d - t) / .02)


def pad(chord, d):
    t = t_(d)
    x = np.zeros(len(t))
    for m in chord[:4]:
        for det in (-.12, .12):
            f = mf(m + 12) * 2 ** (det / 12)
            for h in range(1, 6):
                x += np.sin(2 * np.pi * f * h * t + h) / h
    x = lp(x, 1800)
    return x * np.minimum(1, t / .4) * np.minimum(1, (d - t) / .4) / 40


def music():
    buf = np.zeros((N + SR, 2))
    sc = {s["id"]: s for s in TL["scenes"]}
    calm0, calm1 = sc["rem"]["start"], sc["dot"]["start"]  # breakdown section
    tips0 = sc["tips"]["start"]
    nbars = int(LEN / BAR) + 1
    for b in range(nbars):
        t0 = b * BAR
        ci = b % 4
        chord, root = CHORDS[ci], ROOTS[ci]
        intro = t0 < 2 * BAR
        calm = calm0 - .2 <= t0 < calm1 - BAR * .5
        add(buf, pad(chord, BAR + .3), t0, .9 if calm else .55)
        # plucked arpeggio, 8ths
        pat = [0, 2, 4, 3, 1, 2, 4, 2]
        for k in range(8):
            if calm and k % 2:
                continue
            add(buf, pluck(mf(chord[pat[k]] + 12)), t0 + k * BEAT / 2 + (0.018 if k % 2 else 0), .16, -.35 if k % 2 else .35)
        if t0 >= tips0:  # sparkle bells in the finale
            for k in (0, 3, 6):
                add(buf, bell(mf(chord[(k + b) % 5] + 24), .6) * .5, t0 + k * BEAT / 2, .1, .5)
        if intro:
            for k in range(8):
                add(buf, hat(), t0 + k * BEAT / 2, .25, .2)
            continue
        if not calm:
            for k in range(4):
                add(buf, kick(), t0 + k * BEAT, .75)
            for k in (1, 3):
                add(buf, clap(), t0 + k * BEAT, .45, 0)
            for k in range(8):
                add(buf, hat(open_=(k % 4 == 3)), t0 + k * BEAT / 2 + (0.02 if k % 2 else 0), .3 if k % 2 else .2, .25)
            for k, (o, d) in enumerate([(0, .75), (1, .5), (1.5, .4), (2, .75), (3, .4), (3.5, .4)]):
                add(buf, bass(mf(root + (12 if k == 4 else 0)), d * BEAT * 1.1), t0 + o * BEAT, .32)
        else:
            add(buf, bass(mf(root), BAR * .9), t0, .22)
            for k in (0, 2):
                add(buf, kick() * .6, t0 + k * BEAT, .45)
    m = buf[:N]
    # fade-in, and gentle fade right at the end (outro has its own audio)
    m[: int(.3 * SR)] *= np.linspace(0, 1, int(.3 * SR))[:, None]
    fo = int(1.2 * SR)
    m[-fo:] *= np.linspace(1, 0, fo)[:, None] ** 1.5
    return m


# ---------------------------------------------------------------- VOICE ---
def load_mp3(path):
    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"])
    return np.frombuffer(raw, np.float32).astype(np.float64)


def voice_track():
    v = np.zeros(N + SR)
    for s in TL["scenes"]:
        x = load_mp3(s["audio"])
        # light polish: high-pass rumble, gentle presence lift, soft compression
        x = hp(x, 80)
        x = x + .25 * bp(x, 2500, 6000)
        x = np.tanh(x * 1.6) / np.tanh(1.6)
        st = int(s["vstart"] * SR)
        v[st:st + len(x)] += x[: len(v) - st]
    return v[:N]


def main():
    v = voice_track()
    v = v / (np.max(np.abs(v)) + 1e-9) * .85
    m = music()
    # sidechain duck from voice envelope
    e = np.abs(v)
    a = np.exp(-1 / (.08 * SR))
    e = lfilter([1 - a], [1, -a], e)
    e = e / (e.max() + 1e-9)
    duck = 1 - .55 * np.clip(e * 3, 0, 1)
    m = m / (np.max(np.abs(m)) + 1e-9) * .5 * duck[:, None]

    fx = np.zeros((N + 2 * SR, 2))
    for c in CUES:
        if c["type"] == "scroll":
            for tt in scroll_ticks(c["t"], 6.0):
                if tt < LEN:
                    add(fx, sfx("tick") * .5, tt, .5, .3)
            continue
        x = sfx(c["type"])
        pan = (rng.random() - .5) * .5
        add(fx, x, c["t"], c["gain"] * .55, pan)
    fx = fx[:N]

    mix = m + fx + np.stack([v, v], 1)
    mix = np.tanh(mix * 1.1) / 1.1
    out = os.path.join(B, "mix.wav")
    pcm = (np.clip(mix, -1, 1) * 32767).astype("<i2")
    import wave
    w = wave.open(out, "wb"); w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes()); w.close()
    # stems for QA
    print("wrote", out, f"{len(mix) / SR:.2f}s")


if __name__ == "__main__":
    main()
