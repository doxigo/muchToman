// The video ad's picture: plays ad.html's paused GSAP timeline frame by frame in Chromium and pipes
// the frames to ffmpeg. Motion blur is real accumulation: --blur N renders N sub-frames spread over
// half of each frame interval (a 180° shutter) and ffmpeg averages them. Also writes cues.json, the
// cue sheet sound.py times the soundtrack from. make.sh runs the whole thing; alone:
//   node tools/ad/render.mjs --out out/silent.mp4 [--fps 30] [--blur 4] [--from 0 --to 20.5]
//   node tools/ad/render.mjs --stills 0,2.5,6.9      (crisp JPEGs into out/, for checking a frame)
// --loop renders ad.html?loop, the landing page's silent hero cut, instead (no cues.json: it has no sound).
import { chromium } from '../../pwa/node_modules/playwright/index.mjs';
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const argv = process.argv.slice(2);
const arg = (name, fallback) => { const i = argv.indexOf(`--${name}`); return i < 0 ? fallback : argv[i + 1]; };
const fps = Number(arg('fps', 30));
const blur = Number(arg('blur', 4));
const loop = argv.includes('--loop');
const out = fileURLToPath(new URL('out/', import.meta.url));
mkdirSync(out, { recursive: true });

// file:// pages may not load a file:// font, or fetch icon.svg, without the last flag
const browser = await chromium.launch({ args: ['--force-color-profile=srgb', '--font-render-hinting=none', '--allow-file-access-from-files'] });
const page = await browser.newPage({ viewport: { width: 1080, height: 1350 }, deviceScaleFactor: 1 });
page.on('pageerror', (e) => console.error('[ad.html]', e.message));
await page.goto(new URL(loop ? 'ad.html?render&loop' : 'ad.html?render', import.meta.url).href);
await page.evaluate(() => window.ready);
const duration = await page.evaluate(() => window.DURATION);
if (!loop) writeFileSync(out + 'cues.json', JSON.stringify(await page.evaluate(() => window.CUES), null, 1));
const cdp = await page.context().newCDPSession(page);
const shot = async (t, quality = 95) => {
  await page.evaluate((t) => window.seek(t), t);
  return Buffer.from((await cdp.send('Page.captureScreenshot', { format: 'jpeg', quality })).data, 'base64');
};

if (arg('stills')) {
  for (const t of arg('stills').split(',').map(Number)) writeFileSync(`${out}${loop ? 'loop' : 'still'}-${t.toFixed(2)}.jpg`, await shot(t, 92));
} else {
  const from = Number(arg('from', 0));
  const to = Math.min(Number(arg('to', duration)), duration);
  const frames = Math.round((to - from) * fps);
  // JPEG frames are full-range BT.601; players expect limited-range BT.709, so convert on the way out
  const vf = (blur > 1 ? `tmix=frames=${blur},select='eq(mod(n\\,${blur})\\,${blur - 1})',` : '') +
    `setpts=N/(${fps}*TB),scale=in_color_matrix=bt601:out_color_matrix=bt709:in_range=full:out_range=limited,format=yuv420p`;
  const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps * blur), '-c:v', 'mjpeg', '-i', '-',
    '-vf', vf, '-r', String(fps), '-c:v', 'libx264', '-preset', 'slow', '-crf', '15', '-profile:v', 'high', '-tune', 'animation',
    '-color_range', 'tv', '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709',
    '-x264-params', 'colorprim=bt709:transfer=bt709:colormatrix=bt709', '-movflags', '+faststart', arg('out', out + 'silent.mp4')],
    { stdio: ['pipe', 'inherit', 'inherit'] });
  const write = (buf) => new Promise((ok) => (ff.stdin.write(buf) ? ok() : ff.stdin.once('drain', ok)));
  for (let f = 0; f < frames; f++) {
    for (let s = 0; s < blur; s++) await write(await shot(Math.min(from + (f + (blur > 1 ? s / blur * .5 : 0)) / fps, duration - 1e-4)));
    if (f % 30 === 0) process.stdout.write(`\rframe ${f}/${frames}`);
  }
  ff.stdin.end();
  await new Promise((ok) => ff.on('close', ok));
  process.stdout.write(`\r${frames} frames\n`);
}
await browser.close();
