import type { Clock } from './util.js';
import { RelayError } from './types.js';

/**
 * In-memory abuse limits for a single-process relay. State is deliberately NOT written to the Store:
 * failed requests must never cost disk I/O, and the maps are bounded. A restart resets them (documented).
 * Check-and-increment is synchronous, so it is atomic with respect to parallel requests.
 */

/** Fixed-window counter keyed by an arbitrary string (usually a source address). */
export class RateLimiter {
  private m = new Map<string, { n: number; start: number }>();
  constructor(private clock: Clock, private maxKeys = 10_000) {}

  /** Counts one hit; throws 429 with Retry-After when `limit` hits per `windowMs` are exceeded. */
  hit(key: string, limit: number, windowMs: number): void {
    const now = this.clock.now();
    let e = this.m.get(key);
    if (!e || now - e.start >= windowMs) {
      e = { n: 0, start: now };
      this.m.delete(key);
      this.m.set(key, e);
      this.prune(now, windowMs);
    }
    e.n++;
    if (e.n > limit) {
      throw new RelayError('rate_limited', 'Too many requests. Slow down.', 429, undefined, Math.max(1, Math.ceil((e.start + windowMs - now) / 1000)));
    }
  }

  private prune(now: number, windowMs: number) {
    if (this.m.size <= this.maxKeys) return;
    for (const [k, v] of this.m) if (now - v.start >= windowMs) this.m.delete(k);
    // Still over the cap: drop the oldest entries (Map keeps insertion order).
    while (this.m.size > this.maxKeys) this.m.delete(this.m.keys().next().value as string);
  }
}

interface GuardEntry {
  count: number;
  windowStart: number;
  strikes: number;
  lockUntil: number;
}

export interface GuardOptions {
  /** Failed attempts allowed per source per window before that source is locked. */
  perSourceMax: number;
  windowMs: number;
  /** Total verification attempts per minute across all sources before UNKNOWN sources are throttled. */
  globalPerMinute: number;
  maxLockMs: number;
  baseLockMs: number;
}

export const DEFAULT_GUARD: GuardOptions = { perSourceMax: 5, windowMs: 15 * 60_000, globalPerMinute: 120, maxLockMs: 15 * 60_000, baseLockMs: 30_000 };

/**
 * Secret-attempt guard. `reserve(source)` is called BEFORE the comparison and counts the attempt immediately
 * (so parallel guesses cannot slip through); `release(source)` is called on success.
 * Lockouts are per source: an anonymous caller can only lock out its own address. Sources that have
 * authenticated before ("known") are exempt from the global ceiling, so a distributed flood cannot lock the owner out.
 */
export class AttemptGuard {
  private m = new Map<string, GuardEntry>();
  private known = new Map<string, number>();
  private gStart = 0;
  private gCount = 0;
  constructor(private clock: Clock, private o: GuardOptions = DEFAULT_GUARD, private maxKeys = 10_000) {}

  reserve(source: string): void {
    const now = this.clock.now();
    let e = this.m.get(source);
    if (!e) {
      e = { count: 0, windowStart: now, strikes: 0, lockUntil: 0 };
      this.m.set(source, e);
      if (this.m.size > this.maxKeys) this.prune(now);
    }
    if (e.lockUntil > now) throw this.locked(e.lockUntil - now);
    if (e.lockUntil && e.lockUntil <= now) {
      e.lockUntil = 0;
      e.count = 0;
      e.windowStart = now;
    }
    if (now - e.windowStart >= this.o.windowMs) {
      e.count = 0;
      e.windowStart = now;
      e.strikes = 0;
    }
    if (!this.known.has(source)) {
      if (now - this.gStart >= 60_000) {
        this.gStart = now;
        this.gCount = 0;
      }
      this.gCount++;
      if (this.gCount > this.o.globalPerMinute) throw this.locked(Math.max(1000, this.gStart + 60_000 - now));
    }
    e.count++;
    if (e.count > this.o.perSourceMax) {
      e.strikes++;
      const ms = Math.min(this.o.maxLockMs, this.o.baseLockMs * 2 ** (e.strikes - 1));
      e.lockUntil = now + ms;
      throw this.locked(ms);
    }
  }

  /** Successful authentication: give the attempt back and remember the source as known. */
  release(source: string): void {
    const e = this.m.get(source);
    if (e) e.count = Math.max(0, e.count - 1);
    this.known.delete(source);
    this.known.set(source, this.clock.now());
    while (this.known.size > 20) this.known.delete(this.known.keys().next().value as string);
  }

  private locked(ms: number) {
    return new RelayError('locked', 'Too many failed attempts. Try again later.', 429, undefined, Math.max(1, Math.ceil(ms / 1000)));
  }

  private prune(now: number) {
    for (const [k, v] of this.m) if (v.lockUntil <= now && now - v.windowStart >= this.o.windowMs) this.m.delete(k);
    while (this.m.size > this.maxKeys) this.m.delete(this.m.keys().next().value as string);
  }
}
