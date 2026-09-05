export const MAX_AMOUNT_RIAL = 1_000_000_000_000_000;
export function parseToman(value: string): number | null {
  const digits = value.trim().replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x6f0))
    .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x660));
  if (!/^(?:[0-9]+|[0-9]{1,3}(?:[,٬ ][0-9]{3})+)$/.test(digits)) return null;
  const amount = Number(digits.replace(/[,٬ ]/g, '')) * 10;
  return Number.isSafeInteger(amount) && amount > 0 && amount <= MAX_AMOUNT_RIAL ? amount : null;
}
export const fa = (n: number): string => Math.trunc(n).toLocaleString('fa-IR');
export const toman = (rial: number): string => fa(rial / 10);
