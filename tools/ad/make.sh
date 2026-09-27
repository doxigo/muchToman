#!/bin/sh
# The 20.5s 4:5 video ad (1080×1350, 30fps, H.264 + AAC at -14 LUFS), built from the repo's own
# captures so it follows them when they're retaken: the picture from ad.html (render.mjs), the
# soundtrack from the same cue sheet (sound.py), loudness-normalised in two passes and muxed.
# The copy lives in ad.html's CONFIG and must stay true to worker/public/index.html.
#
# Made with the motion-ad skill (https://www.danny.md/skills/motion-ad). To update the ad with it,
# install it (`curl -fsSL danny.md/skills/motion-ad.tar | tar -x -C ~/.claude/skills/`) and point it
# at this folder: ad.html stands in for its template, render.mjs for its render.cjs, and its rules
# hold — every claim on the landing page, real captures only.
#
# Needs pwa/'s dev dependencies (`cd pwa && npm ci && npx playwright install chromium`), GSAP
# (`cd tools/ad && npm ci`), ffmpeg, and python3 with numpy. The iPhone scene's PWA captures are
# committed; retake them with `node tools/ad/capture-pwa.mjs`. Preview in a browser: serve the repo
# root (`python3 -m http.server`) and open /tools/ad/ad.html.
# Usage: tools/ad/make.sh [out.mp4]      (default tools/ad/out/muchtoman-ad-4x5.mp4, not committed)
set -e
cd "$(dirname "$0")"
OUT=${1:-out/muchtoman-ad-4x5.mp4}
node render.mjs --out out/silent.mp4
python3 sound.py out/cues.json out/soundtrack.wav
LN=I=-14:TP=-1.5:LRA=11
M=$(ffmpeg -hide_banner -i out/soundtrack.wav -af loudnorm=$LN:print_format=json -f null - 2>&1 | sed -n '/^{/,/^}/p' |
  python3 -c "import json,sys; m=json.load(sys.stdin); print(f\"measured_I={m['input_i']}:measured_TP={m['input_tp']}:measured_LRA={m['input_lra']}:measured_thresh={m['input_thresh']}:offset={m['target_offset']}\")")
ffmpeg -v error -y -i out/silent.mp4 -i out/soundtrack.wav -filter_complex "[1:a]loudnorm=$LN:${M}:linear=true,aresample=48000[a]" \
  -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 256k -movflags +faststart "$OUT"
echo "$OUT"
