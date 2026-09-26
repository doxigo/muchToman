/**
 * The app's drawn marks — TabBar.kt's five tab glyphs, Timeline.kt's plus/search/check, Family.kt's
 * person, Ui.kt's bars and caret — in the same one pen: round caps, round joins, 2 at 24.
 * Coordinates are the Kotlin fractions of the drawing box, so a reader can hold the two side by side.
 */
import type { ComponentChildren } from 'preact';

type Mark = { size?: number; color?: string; class?: string; label?: string };
const PEN = { fill: 'none', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' } as const;

function Svg({ size = 24, label, class: cls, children }: Mark & { children: ComponentChildren }) {
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} class={cls} aria-hidden={label ? undefined : 'true'}
      role={label ? 'img' : undefined} aria-label={label} style={{ flex: 'none', display: 'block' }}>
      {children}
    </svg>
  );
}

// ---- tab glyphs: drawn into the 18 box inside a 24 canvas (inset 3) ---------------------------

export type TabId = 'HOME' | 'LEDGER' | 'BUDGET' | 'ASSETS' | 'REPORT';
const B = 18; const O = 3;
const X = (f: number) => O + B * f;

export function TabIcon({ tab, color = 'currentColor' }: { tab: TabId; color?: string }) {
  const s = { ...PEN, stroke: color, 'stroke-width': 2 };
  switch (tab) {
    case 'HOME':
      return (
        <Svg>
          <path {...s} d={`M${X(.04)} ${X(.44)} L${X(.5)} ${X(.04)} L${X(.96)} ${X(.44)} L${X(.96)} ${X(.96)} L${X(.04)} ${X(.96)} Z`} />
          <path {...s} d={`M${X(.34)} ${X(.96)} L${X(.34)} ${X(.76)} Q${X(.5)} ${X(.56)} ${X(.66)} ${X(.76)} L${X(.66)} ${X(.96)}`} />
        </Svg>
      );
    case 'LEDGER': {
      const left = X(.06); const right = X(.94); const top = X(.08); const foot = X(.92); const step = (right - left) / 4;
      let d = `M${left} ${foot} L${left} ${top} L${right} ${top} L${right} ${foot}`;
      for (let i = 0; i < 4; i++) { const x = right - step * i; d += ` L${x - step / 2} ${foot - B * .11} L${x - step} ${foot}`; }
      const inset = (right - left) * .16;
      return (
        <Svg>
          <path {...s} d={d} />
          {[1, .55].map((len, i) => {
            const y = X(.34 + i * .19);
            return <path key={i} {...s} d={`M${left + inset} ${y} L${left + inset + (right - left - inset * 2) * len} ${y}`} />;
          })}
        </Svg>
      );
    }
    case 'BUDGET':
      return (
        <Svg>
          <circle {...s} cx={12} cy={12} r={9 * .88} />
          <circle {...s} cx={12} cy={12} r={9 * .44} />
          <circle cx={12} cy={12} r={9 * .1} fill={color} />
        </Svg>
      );
    case 'ASSETS': {
      // The near coin knocks a hole of r + 2.4 out of the far one, so the two read as one in front.
      const r = B * .26; const near = [X(.32), X(.68)]; const far = [X(.68), X(.32)];
      const id = `coin-knock-${color.replace(/[^a-z0-9]/gi, '')}`;
      return (
        <Svg>
          <defs>
            <mask id={id} maskUnits="userSpaceOnUse" x="0" y="0" width="24" height="24">
              <rect width="24" height="24" fill="#fff" />
              <circle cx={near[0]} cy={near[1]} r={r + 2.4} fill="#000" />
            </mask>
          </defs>
          <circle {...s} cx={far[0]} cy={far[1]} r={r} mask={`url(#${id})`} />
          <circle {...s} cx={near[0]} cy={near[1]} r={r} />
        </Svg>
      );
    }
    case 'REPORT':
      return (
        <Svg>
          {[.55, .28, 0].map((top, i) => {
            const x = X(.14 + i * .36);
            return <path key={i} {...s} d={`M${x} ${O + B * top + 1} L${x} ${O + B - 1}`} />;
          })}
        </Svg>
      );
  }
}

// ---- small marks ------------------------------------------------------------------------------

export function PlusMark({ size = 20, color = 'currentColor' }: Mark) {
  const s = { ...PEN, stroke: color, 'stroke-width': 2.2 };
  return <Svg size={size}><path {...s} d={`M${size * .5} ${size * .14} L${size * .5} ${size * .86} M${size * .14} ${size * .5} L${size * .86} ${size * .5}`} /></Svg>;
}

export function SearchMark({ size = 20, color = 'currentColor' }: Mark) {
  const s = { ...PEN, stroke: color, 'stroke-width': 2.2 };
  return (
    <Svg size={size}>
      <circle {...s} cx={size * .44} cy={size * .44} r={size * .27} />
      <path {...s} d={`M${size * .64} ${size * .64} L${size * .86} ${size * .86}`} />
    </Svg>
  );
}

export function CheckMark({ size = 40, color = 'currentColor' }: Mark) {
  return <Svg size={size}><path {...PEN} stroke={color} stroke-width={3} d={`M${size * .22} ${size * .56} L${size * .42} ${size * .74} L${size * .78} ${size * .3}`} /></Svg>;
}

/** Family.kt CompanionGlyph: a head and the top of a pair of shoulders. */
export function PersonMark({ size = 24, color = 'currentColor' }: Mark) {
  const i = size * .125; const w = size - 2 * i;
  const s = { ...PEN, stroke: color, 'stroke-width': 2 };
  // drawArc(start 180°, sweep 180°) over the box (0.08w, 0.56h, 0.84w × 0.8h): the upper half-ellipse.
  const left = i + w * .08; const right = i + w * .92; const cy = i + w * .56 + w * .4; const ry = w * .4;
  return (
    <Svg size={size}>
      <circle {...s} cx={i + w * .5} cy={i + w * .26} r={w * .2} />
      <path {...s} d={`M${left} ${cy} A${w * .42} ${ry} 0 0 1 ${right} ${cy}`} />
    </Svg>
  );
}

/** Ui.kt BarsIcon: filled, for the گزارش circle beside filled marks. */
export function BarsIcon({ size = 24, color = 'currentColor', label }: Mark) {
  const b = size - 4; const w = b * .2;
  return (
    <Svg size={size} label={label}>
      <g transform="translate(2 2)" fill={color}>
        <rect x={0} y={b * .45} width={w} height={b * .55} rx={w / 2} />
        <rect x={(b - w) / 2} y={b * .2} width={w} height={b * .8} rx={w / 2} />
        <rect x={b - w} y={0} width={w} height={b} rx={w / 2} />
      </g>
    </Svg>
  );
}

export function TrendCaret({ up, color = 'currentColor', size = 9 }: { up: boolean; color?: string; size?: number }) {
  const d = up ? `M${size / 2} 0 L${size} ${size} L0 ${size} Z` : `M0 0 L${size} 0 L${size / 2} ${size} Z`;
  return <Svg size={size}><path d={d} fill={color} /></Svg>;
}

/** Material's Rounded.Refresh, the one imported glyph the home circles use. */
export function RefreshMark({ size = 24, color = 'currentColor', spinning = false }: Mark & { spinning?: boolean }) {
  return (
    <Svg size={size} class={spinning ? 'spin' : undefined}>
      <path fill={color} transform={`scale(${size / 24})`} d="M17.65 6.35A7.96 7.96 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
    </Svg>
  );
}

export function LockMark({ size = 24, color = 'currentColor' }: Mark) {
  return (
    <Svg size={size}>
      <path fill={color} transform={`scale(${size / 24})`} d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM9 8V6c0-1.66 1.34-3 3-3s3 1.34 3 3v2H9z" />
    </Svg>
  );
}

/** A chevron toward the end of the line — left in RTL, which is "further on" for her. */
export function Chevron({ size = 18, color = 'currentColor', back = false }: Mark & { back?: boolean }) {
  const s = { ...PEN, stroke: color, 'stroke-width': 2 };
  const d = back ? `M${size * .38} ${size * .22} L${size * .66} ${size * .5} L${size * .38} ${size * .78}`
    : `M${size * .62} ${size * .22} L${size * .34} ${size * .5} L${size * .62} ${size * .78}`;
  return <Svg size={size}><path {...s} d={d} /></Svg>;
}

export function CloseMark({ size = 20, color = 'currentColor' }: Mark) {
  const s = { ...PEN, stroke: color, 'stroke-width': 2.2 };
  return <Svg size={size}><path {...s} d={`M${size * .22} ${size * .22} L${size * .78} ${size * .78} M${size * .78} ${size * .22} L${size * .22} ${size * .78}`} /></Svg>;
}
