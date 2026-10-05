# Musicly promo video

`musicly-promo.mp4` — 1080×1920, 30 fps, ~46 s: narrated, captioned, with music and sound effects.

Everything is generated from code, so edits are cheap:

| Step | File | What it does |
|---|---|---|
| 1 | `tts.py` | Narration lines → `audio/lines/*.wav` (Piper, offline neural voice) and `timing.json` / `src/timing.js` |
| 2 | `src/` | The animation: `dom.js` (scenes), `timeline.js` (every beat is a function of time), `v.css`; brand assets come from `../branding/src` |
| 3 | `render.js` | Renders each frame with Playwright: `node render.js <outDir> 30 <shard> <shards>` (also writes `sfx.json`) |
| 4 | `audio.py` | Voice + procedural music + sound effects, ducked and loudness-normalised → `audio/mix.wav` |
| 5 | ffmpeg | `ffmpeg -framerate 30 -i frames/f%05d.jpg -i audio/mix.wav -c:v libx264 -crf 17 -pix_fmt yuv420p -c:a aac -b:a 192k musicly-promo.mp4` |

Setup: `pip install piper-tts`, and download the voice into `voices/`
(`en_US-ryan-high.onnx` + `.onnx.json` from huggingface.co/rhasspy/piper-voices). The voice model is not committed (120 MB).

Note: Piper is slightly random, so re-running `tts.py` changes the line lengths. Re-run steps 3–5 after it.
