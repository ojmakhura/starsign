import { SchemaPath, validate } from '@angular/forms/signals';

/** "YYYY-MM-DD" of a date in local time. */
export const toDateInput = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

/** Start / end of a "YYYY-MM-DD" day as an ISO instant, or null when blank. */
export const startOfDay = (value: string) =>
  value ? new Date(`${value}T00:00:00`).toISOString() : null;
export const endOfDay = (value: string) =>
  value ? new Date(`${value}T23:59:59.999`).toISOString() : null;

/** Schema rule: the "to" date of a range may not be before its "from" date. */
export function dateRange(from: SchemaPath<string>, to: SchemaPath<string>): void {
  validate(to, ({ value, valueOf }) => {
    const start = valueOf(from);
    return start && value() && value() < start
      ? { kind: 'dateRange', message: 'The end date is before the start date.' }
      : null;
  });
}
