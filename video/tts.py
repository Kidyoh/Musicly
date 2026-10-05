"""Narration: one clip per line with Piper (offline neural voice), plus the timing the video is built on."""
import json, wave, sys
from piper import PiperVoice, SynthesisConfig

VOICE = 'voices/en_US-ryan-high.onnx'
# (text, gap before the NEXT line in seconds)
LINES = [
    ("Meet Musicly.", 0.30),
    ("Every song you love, in one calm player.", 0.75),
    ("Run a Telegram channel? Add a bot, and every song you post plays in full, and saves to your phone.", 0.75),
    ("Your own music.", 0.28),
    ("Live radio from around the world.", 0.28),
    ("And lyrics that sing along, in time.", 0.75),
    ("Controls on your lock screen. Widgets on your home screen.", 0.85),
    ("But here's the best part. Jam.", 0.55),
    ("Start a Jam, and share the code.", 0.30),
    ("Friends join from anywhere, and hear the same song, in sync, on their own phone.", 0.80),
    ("Everyone can add songs. From your library, or straight from their own phone.", 0.80),
    ("You stay in control. Friends can pause and skip only if you allow it.", 0.80),
    ("Together in the same room? No internet needed.", 0.85),
    ("Musicly. Your music, together.", 0.35),
    ("Download it on GitHub.", 0.0),
]
LEAD = 0.45   # silence before the first word
TAIL = 1.6    # music-only ending

voice = PiperVoice.load(VOICE)
cfg = SynthesisConfig(length_scale=0.93, noise_scale=0.6, noise_w_scale=0.7)
t = LEAD
out = []
for i, (text, gap) in enumerate(LINES):
    path = f'audio/lines/{i:02d}.wav'
    with wave.open(path, 'wb') as w:
        voice.synthesize_wav(text, w, syn_config=cfg)
    with wave.open(path) as w:
        dur = w.getnframes() / w.getframerate()
    out.append({'i': i, 'text': text, 'start': round(t, 3), 'dur': round(dur, 3), 'file': path})
    t += dur + gap
total = round(t + TAIL, 3)
json.dump({'lines': out, 'total': total}, open('timing.json', 'w'), indent=1)
open('src/timing.js', 'w').write('window.TIMING = ' + json.dumps({'lines': out, 'total': total}) + ';')
for l in out:
    print(f"{l['i']:2d} {l['start']:6.2f}–{l['start']+l['dur']:6.2f}  {l['text']}")
print('total', total)
