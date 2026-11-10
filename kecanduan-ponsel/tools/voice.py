"""Generate Indonesian male voice-over per scene and build a timeline.

Output:
  build/voice/<id>.mp3     raw TTS per scene
  build/timeline.json      scene start/end + word-level timings (absolute seconds)
"""
import json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.path.join(ROOT, "build")
os.makedirs(os.path.join(B, "voice"), exist_ok=True)

CONTENT_LEN = float(sys.argv[1]) if len(sys.argv) > 1 else 85.0  # seconds before outro
LEAD_IN = 0.7   # silence before first line
TAIL = 1.2      # breathing room after last line

cfg = json.load(open(os.path.join(ROOT, "script.json")))


def ts(s):
    h, m, rest = s.split(":")
    sec, ms = rest.split(",")
    return int(h) * 3600 + int(m) * 60 + int(sec) + int(ms) / 1000


def dur(path):
    out = subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                   "-of", "default=nw=1:nk=1", path])
    return float(out)


def norm(w):
    return re.sub(r"[^0-9a-z]", "", w.lower())


scenes = []
for sc in cfg["scenes"]:
    mp3 = os.path.join(B, "voice", sc["id"] + ".mp3")
    srt = os.path.join(B, "voice", sc["id"] + ".srt")
    if not os.path.exists(mp3) or os.path.getsize(mp3) == 0:
        subprocess.check_call([sys.executable, os.path.join(ROOT, "tools", "tts.py"), sc.get("tts", sc["text"]), mp3,
                               cfg["rate"], cfg["pitch"], srt])
    # parse word boundaries
    blocks = open(srt).read().strip().split("\n\n")
    bounds = []
    for b in blocks:
        lines = b.strip().split("\n")
        if len(lines) < 3:
            continue
        a, z = lines[1].split(" --> ")
        bounds.append((norm(" ".join(lines[2:])), ts(a), ts(z)))
    # align display tokens (with punctuation) to boundaries
    tokens = sc["text"].split()
    words, bi = [], 0
    for tok in tokens:
        n = norm(tok)
        hit = None
        for j in range(bi, min(bi + 5, len(bounds))):
            if bounds[j][0] and (n.startswith(bounds[j][0]) or bounds[j][0].startswith(n)):
                hit = j
                break
        if hit is not None:
            words.append({"w": tok, "s": bounds[hit][1], "e": bounds[hit][2]})
            bi = hit + 1
        else:
            words.append({"w": tok, "s": None, "e": None})
    # interpolate missing timings
    for i, w in enumerate(words):
        if w["s"] is None:
            prev = words[i - 1]["e"] if i else 0.0
            nxt = next((x["s"] for x in words[i + 1:] if x["s"] is not None), prev + 0.3)
            w["s"], w["e"] = prev, max(prev + 0.15, nxt)
    scenes.append({"id": sc["id"], "text": sc["text"], "audio": mp3, "vdur": dur(mp3), "words": words})

speech = sum(s["vdur"] for s in scenes)
gap = (CONTENT_LEN - LEAD_IN - TAIL - speech) / (len(scenes) - 1)
print(f"speech={speech:.2f}s gap={gap:.2f}s")
if gap < 0.25:
    print("WARNING: voice too long for target length", file=sys.stderr)

t = LEAD_IN
for i, s in enumerate(scenes):
    s["vstart"] = round(t, 3)
    s["start"] = 0.0 if i == 0 else round(t - gap * 0.5, 3)
    for w in s["words"]:
        w["s"] = round(w["s"] + t, 3)
        w["e"] = round(w["e"] + t, 3)
    t += s["vdur"] + gap
for i, s in enumerate(scenes):
    s["end"] = scenes[i + 1]["start"] if i + 1 < len(scenes) else CONTENT_LEN
    print(f'{s["id"]:9s} {s["start"]:6.2f} -> {s["end"]:6.2f}  voice {s["vstart"]:6.2f} +{s["vdur"]:.2f}')

json.dump({"length": CONTENT_LEN, "scenes": scenes}, open(os.path.join(B, "timeline.json"), "w"),
          ensure_ascii=False, indent=1)
