// The landing page's images (worker/public/) from docs/screenshots, docs/store and the PWA's bank
// logos, so the page follows the README captures whenever those are retaken. Needs ImageMagick
// built with WebP. Usage: node tools/site/assets.mjs
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const at = (path) => fileURLToPath(new URL(`../../${path}`, import.meta.url));
const magick = (...args) => execFileSync('magick', args);

// Where each capture starts: under its status bar (the captures' says "no internet" and wears a
// VPN shield; the page draws a clean strip instead), or further down, to open on the part the
// band is about. The page's callout crops are measured from these tops. A dark capture that sat
// scrolled a few pixels off its light twin gets its own top, so one crop lands on both.
const TOPS = {
  home: 130, 'home-dark': 130,
  ledger: 130, 'ledger-dark': 130,
  'family-assets': 168, 'family-assets-dark': 168,
  report: 130, 'report-dark': 130,
  budget: 130, 'budget-dark': 130,
  categories: 714, 'categories-dark': 705,
  installments: 938, 'installments-dark': 754,
  family: 130, 'family-dark': 130,
};
mkdirSync(at('worker/public/img'), { recursive: true });
for (const [name, top] of Object.entries(TOPS)) {
  magick(at(`docs/screenshots/${name}.png`), '-crop', `1080x${2400 - top}+0+${top}`, '+repage',
    '-resize', '720x', '-quality', '82', at(`worker/public/img/${name}.webp`));
}

magick(at('docs/store/promo-total.jpg'), '-resize', '1200x', '-quality', '85', at('worker/public/og.jpg'));

// The banks the SMS reader knows (Sms.kt `Bank`, less Ayandeh), one sprite of <view>s the page
// addresses as banks.svg#<id>: one request, fetched only when the list scrolls near.
const BANKS = ['BLU', 'SAMAN', 'MELLAT', 'MELLI', 'SADERAT', 'TEJARAT', 'SEPAH', 'KESHAVARZI',
  'PASARGAD', 'PARSIAN', 'REFAH', 'EGHTESAD_NOVIN', 'KHAVARMIANEH', 'RESALAT', 'DEY', 'POST_BANK',
  'MASKAN', 'MEHR_IRAN'];
const logos = readFileSync(at('pwa/src/logos.tsx'), 'utf8');
const sprite = BANKS.map((key, i) => {
  const m = logos.match(new RegExp(`^  ${key}: \\['([^']+)', '(.*)'\\],?$`, 'm'));
  if (!m) throw new Error(`no logo for ${key}`);
  const id = key.toLowerCase().replace(/_/g, '-');
  return `<view id="${id}" viewBox="${i * 28} 0 28 28"/><svg x="${i * 28}" width="28" height="28" viewBox="${m[1]}">${m[2]}</svg>`;
});
writeFileSync(at('worker/public/img/banks.svg'),
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${BANKS.length * 28} 28">${sprite.join('')}</svg>\n`);
