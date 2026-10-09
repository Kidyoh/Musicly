# Musicly promo video

`musicly-promo.mp4` — 1080×1920, 30 fps, ~46 s: narrated, captioned, with music and sound effects.

Everything is generated from code, so edits are cheap:

| Step | File | What it does |
|---|---|---|
| 1 | `tts.py` | Narration lines → `audio/lines/*.wav` (Kokoro `af_heart`, an offline Apache-2.0 neural voice) and `timing.json` / `src/timing.js` |
| 2 | `src/` | The animation: `dom.js` (scenes), `timeline.js` (every beat is a function of time), `v.css`; brand assets come from `../branding/src` |
| 3 | `render.js` | Renders each frame with Playwright: `node render.js <outDir> 30 <shard> <shards>` (also writes `sfx.json`) |
| 4 | `audio.py` | Voice + procedural music + sound effects, ducked and loudness-normalised → `audio/mix.wav` |
| 5 | ffmpeg | `ffmpeg -framerate 30 -i frames/f%05d.jpg -i audio/mix.wav -c:v libx264 -crf 17 -pix_fmt yuv420p -c:a aac -b:a 192k musicly-promo.mp4` |

Setup: `pip install kokoro-onnx`, and download the model files into `voices/` from the
[kokoro-onnx releases](https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0):
`kokoro-v1.0.onnx` (326 MB) and `voices-v1.0.bin` (28 MB). They are not committed.
Change `VOICE` in `tts.py` to use another of Kokoro's voices (e.g. `af_bella`, `af_nicole`, or a male `am_*`).

Beats inside a spoken line are written for a design length (`DESIGN` in `src/timeline.js`) and scaled to the real length of
each clip, so a different voice or speed keeps the visuals on the right words. Re-run steps 3–5 after `tts.py`.
