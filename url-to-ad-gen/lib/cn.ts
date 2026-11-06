type ClassValue = string | false | null | undefined | Record<string, boolean>;

/** Tiny class-name joiner (keeps the dependency list short). */
export function cn(...inputs: ClassValue[]): string {
  const out: string[] = [];
  for (const input of inputs) {
    if (!input) continue;
    if (typeof input === 'string') {
      out.push(input);
      continue;
    }
    for (const [key, value] of Object.entries(input)) {
      if (value) out.push(key);
    }
  }
  return out.join(' ');
}
