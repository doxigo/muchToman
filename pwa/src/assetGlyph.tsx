/**
 * AssetGlyph.kt: a mark per fixed asset, in the pen everything else writes with — one stroke,
 * round caps, nothing filled. Currencies are their own signs, not flags (a flag is a country),
 * and where the categories already draw the object (car, house, chart) the asset reuses that
 * exact drawing. Crypto and bank rows keep their real logos; the toman keeps its own mark.
 */
import { BANK_ID } from './catalog';
import type { AssetType } from './catalog';
import { Canvas, CategoryIcon, PEN, arcTo } from './categoryIcon';

export const ASSET_GLYPHS = [
  'CARD', 'DOLLAR', 'EURO', 'POUND', 'LIRA', 'KRONE', 'DIRHAM', 'CDOLLAR',
  'INGOT', 'SLABS', 'COIN', 'PLOT',
  'WHEEL', 'HOUSE', 'CHART',
] as const;
export type AssetGlyphId = typeof ASSET_GLYPHS[number];

/** The drawn mark for a fixed asset, or null where a logo, the toman mark or letters do the job. */
export function assetGlyph(type: AssetType): AssetGlyphId | null {
  switch (type.id) {
    case BANK_ID: return 'CARD';
    case 'usd': return 'DOLLAR';
    case 'eur': return 'EURO';
    case 'gbp': return 'POUND';
    case 'try': return 'LIRA';
    case 'nok': return 'KRONE';
    case 'aed': return 'DIRHAM';
    case 'cad': return 'CDOLLAR';
    case 'car': return 'WHEEL';
    case 'house': return 'HOUSE';
    case 'land': return 'PLOT';
  }
  switch (type.kind) {
    case 'GOLD': return 'INGOT';
    case 'SILVER': return 'SLABS';
    case 'COIN': return 'COIN';
    case 'STOCK': return 'CHART';
    default: return null;
  }
}

/**
 * Decorative: the asset's name sits beside it everywhere. The default stroke keeps the tab bar's
 * nib — 2 in a box of 24 — at whatever size the row sets.
 */
export function AssetGlyph({ glyph, size, color = 'currentColor', stroke = size / 12, class: cls }: {
  glyph: AssetGlyphId; size: number; color?: string; stroke?: number; class?: string;
}) {
  // The categories already draw these three objects; one object, one drawing.
  if (glyph === 'WHEEL' || glyph === 'HOUSE' || glyph === 'CHART') {
    return <CategoryIcon glyph={glyph} size={size} color={color} stroke={stroke} class={cls} />;
  }
  const o = size * 0.06;
  const b = size - 2 * o;
  const X = (f: number) => o + b * f;
  const pen = { ...PEN, 'stroke-width': stroke };
  const line = (x1: number, y1: number, x2: number, y2: number) => <path {...pen} d={`M${X(x1)} ${X(y1)} L${X(x2)} ${X(y2)}`} />;
  // Compose's arcTo over Rect(left, top, right, bottom), in the box's fractions.
  const arc = (l: number, t: number, r: number, btm: number, start: number, sweep: number, move = false) =>
    arcTo(X((l + r) / 2), X((t + btm) / 2), b * (r - l) / 2, b * (btm - t) / 2, start, sweep, move);
  const P = (...points: number[]) => points.map(X).join(' ');

  let marks;
  switch (glyph) {
    // A bank card: the stripe is what says «کارت» rather than the banknote's rounded box.
    case 'CARD':
      marks = <>
        <rect {...pen} x={X(0.108)} y={X(0.217)} width={b * 0.783} height={b * 0.567} rx={b * 0.125} />
        <path {...pen} stroke-linecap="butt" d={`M${X(0.108)} ${X(0.392)} L${X(0.892)} ${X(0.392)}`} />
        {line(0.258, 0.617, 0.442, 0.617)}
      </>;
      break;
    case 'DOLLAR':
      marks = <>
        {line(0.5, 0.121, 0.5, 0.879)}
        <path {...pen} d={`M${P(0.696, 0.238)} L${P(0.413, 0.238)} ${arc(0.273, 0.238, 0.552, 0.517, 270, -180)}` +
          ` L${P(0.588, 0.517)} ${arc(0.448, 0.517, 0.727, 0.796, 270, 180)} L${P(0.296, 0.796)}`} />
      </>;
      break;
    case 'EURO':
      marks = <>
        <path {...pen} d={`M${P(0.733, 0.221)} C${P(0.679, 0.171, 0.613, 0.142, 0.542, 0.142)}` +
          ` C${P(0.371, 0.142, 0.233, 0.304, 0.233, 0.5)} C${P(0.233, 0.696, 0.371, 0.858, 0.542, 0.858)}` +
          ` C${P(0.613, 0.858, 0.679, 0.829, 0.733, 0.779)}`} />
        {line(0.15, 0.413, 0.55, 0.413)}
        {line(0.15, 0.588, 0.496, 0.588)}
      </>;
      break;
    case 'POUND':
      marks = <>
        {line(0.263, 0.846, 0.746, 0.846)}
        <path {...pen} d={`M${P(0.304, 0.846)} C${P(0.375, 0.775, 0.408, 0.704, 0.408, 0.608)} L${P(0.408, 0.35)}` +
          ` C${P(0.408, 0.238, 0.483, 0.154, 0.588, 0.154)} C${P(0.663, 0.154, 0.717, 0.192, 0.742, 0.25)}`} />
        {line(0.279, 0.517, 0.592, 0.517)}
      </>;
      break;
    case 'LIRA':
      marks = <>
        <path {...pen} d={`M${P(0.417, 0.125)} L${P(0.417, 0.583)} C${P(0.417, 0.767, 0.542, 0.858, 0.683, 0.842)}` +
          ` C${P(0.775, 0.829, 0.833, 0.758, 0.833, 0.658)}`} />
        {line(0.229, 0.471, 0.596, 0.279)}
        {line(0.229, 0.654, 0.596, 0.463)}
      </>;
      break;
    // «kr» written out: the krone has no sign of its own, and a flag is a country.
    case 'KRONE':
      marks = <>
        {line(0.225, 0.204, 0.225, 0.796)}
        {line(0.471, 0.392, 0.233, 0.592)}
        {line(0.338, 0.504, 0.488, 0.796)}
        {line(0.613, 0.458, 0.613, 0.796)}
        <path {...pen} d={`M${P(0.613, 0.579)} C${P(0.629, 0.504, 0.692, 0.458, 0.775, 0.467)}`} />
      </>;
      break;
    // The mark the UAE gave the dirham in 2025: a D crossed twice, the way the euro is.
    case 'DIRHAM':
      marks = <>
        {line(0.321, 0.175, 0.321, 0.825)}
        <path {...pen} d={`M${P(0.321, 0.175)} L${P(0.442, 0.175)} C${P(0.646, 0.175, 0.788, 0.308, 0.788, 0.5)}` +
          ` C${P(0.788, 0.692, 0.646, 0.825, 0.442, 0.825)} L${P(0.321, 0.825)}`} />
        {line(0.175, 0.404, 0.733, 0.404)}
        {line(0.175, 0.596, 0.733, 0.596)}
      </>;
      break;
    // $ is taken, and one mark must not mean two monies: C$, the notation the currency goes by.
    case 'CDOLLAR':
      marks = <>
        <path {...pen} d={arc(0.133, 0.321, 0.492, 0.679, -45, -270, true)} />
        {line(0.708, 0.258, 0.708, 0.792)}
        <path {...pen} d={`M${P(0.842, 0.333)} L${P(0.654, 0.333)} ${arc(0.558, 0.333, 0.75, 0.525, 270, -180)}` +
          ` L${P(0.758, 0.525)} ${arc(0.663, 0.525, 0.854, 0.717, 270, 180)} L${P(0.575, 0.717)}`} />
      </>;
      break;
    // Wider at the base — the basket is the trapezoid that opens the other way.
    case 'INGOT':
      marks = <>
        <path {...pen} d={`M${P(0.338, 0.321)} L${P(0.663, 0.321)} L${P(0.813, 0.679)} L${P(0.188, 0.679)}Z`} />
        {line(0.425, 0.5, 0.575, 0.5)}
      </>;
      break;
    // Two flat sheets where gold is one deep ingot, a full stroke of daylight between them.
    case 'SLABS':
      marks = <>
        <path {...pen} d={`M${P(0.371, 0.263)} L${P(0.629, 0.263)} L${P(0.692, 0.429)} L${P(0.308, 0.429)}Z`} />
        <path {...pen} d={`M${P(0.288, 0.571)} L${P(0.713, 0.571)} L${P(0.783, 0.738)} L${P(0.217, 0.738)}Z`} />
      </>;
      break;
    // One coin face-on, the rim well inside the edge so the two circles never fuse.
    case 'COIN':
      marks = <>
        <circle {...pen} cx={X(0.5)} cy={X(0.5)} r={b * 0.371} />
        <circle {...pen} cx={X(0.5)} cy={X(0.5)} r={b * 0.229} />
      </>;
      break;
    // A surveyed قطعه with a sprout on it: the asset, not the landscape.
    case 'PLOT':
      marks = <>
        <path {...pen} d={`M${P(0.179, 0.746)} L${P(0.333, 0.529)} L${P(0.821, 0.529)} L${P(0.667, 0.746)}Z`} />
        {line(0.467, 0.529, 0.467, 0.388)}
        <path {...pen} d={`M${P(0.467, 0.388)} C${P(0.388, 0.396, 0.333, 0.35, 0.325, 0.267)} C${P(0.404, 0.258, 0.458, 0.304, 0.467, 0.388)}Z`} />
        <path {...pen} d={`M${P(0.467, 0.388)} C${P(0.475, 0.304, 0.529, 0.258, 0.608, 0.267)} C${P(0.6, 0.35, 0.546, 0.396, 0.467, 0.388)}Z`} />
      </>;
      break;
  }
  return <Canvas size={size} color={color} class={cls}>{marks}</Canvas>;
}
