/** Saves a blob as a download. */
export function saveFile(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

/** A CSV document from a header row and data rows. */
export function toCsv(rows: readonly (readonly unknown[])[]): Blob {
  const csv = rows
    .map((row) => row.map((value) => `"${String(value ?? '').replaceAll('"', '""')}"`).join(','))
    .join('\n');
  return new Blob([csv], { type: 'text/csv;charset=utf-8' });
}
