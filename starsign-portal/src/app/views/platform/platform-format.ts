/** Small formatting and date helpers shared by the platform administration pages. */

/** Human-readable byte size, e.g. "12.4 MB"; null/undefined → "—". */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) {
    return '—';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let value = bytes;
  let unit = 0;
  while (Math.abs(value) >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${unit === 0 ? value : value.toFixed(value >= 100 ? 0 : 1)} ${units[unit]}`;
}

/** A limit value, or "Unlimited" when null. */
export function formatLimit(value: number | null | undefined): string {
  return value === null || value === undefined ? 'Unlimited' : value.toLocaleString();
}

/** yyyy-MM-dd for a date input / LocalDate query parameter (local time). */
export function toDateInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** The yyyy-MM-dd date `days` days before today. */
export function daysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return toDateInput(date);
}

/** ISO instant at the start of a yyyy-MM-dd local day (search criteria), or null. */
export function startOfDay(day: string): string | null {
  return day ? new Date(`${day}T00:00:00`).toISOString() : null;
}

/** ISO instant at the end of a yyyy-MM-dd local day (search criteria), or null. */
export function endOfDay(day: string): string | null {
  return day ? new Date(`${day}T23:59:59.999`).toISOString() : null;
}

/** Enum constant → "Title case words". */
export function humanise(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const words = value.replaceAll('_', ' ').replaceAll('-', ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
