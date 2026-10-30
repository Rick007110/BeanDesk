/** At most [limit] uses per key (an IP address) in each window; kept in memory. */
export class RateLimit {
  readonly limit: number;
  readonly windowMs: number;
  private readonly windows = new Map<string, { start: number; count: number }>();

  constructor(limit: number, windowMs: number) {
    this.limit = limit;
    this.windowMs = windowMs;
  }

  allow(key: string, now: number): boolean {
    let window = this.windows.get(key);
    if (!window || now - window.start >= this.windowMs) {
      if (this.windows.size > 10_000) this.forgetOld(now);
      window = { start: now, count: 0 };
      this.windows.set(key, window);
    }
    window.count++;
    return window.count <= this.limit;
  }

  private forgetOld(now: number): void {
    for (const [key, window] of this.windows) {
      if (now - window.start >= this.windowMs) this.windows.delete(key);
    }
  }
}
