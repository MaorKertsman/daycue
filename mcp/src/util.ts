// Platform-neutral helpers: WebCrypto only (Node >= 20, Workers, Deno).

const enc = new TextEncoder();

export function bytesToB64u(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Accepts base64 or base64url. */
export function b64ToBytes(input: string): Uint8Array<ArrayBuffer> {
  const norm = input.replace(/-/g, '+').replace(/_/g, '/').replace(/\s+/g, '');
  const padded = norm + '='.repeat((4 - (norm.length % 4)) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export async function sha256Hex(text: string): Promise<string> {
  const d = await crypto.subtle.digest('SHA-256', enc.encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function randomToken(prefix: string, bytes = 32): string {
  const b = new Uint8Array(bytes);
  crypto.getRandomValues(b);
  return `${prefix}_${bytesToB64u(b)}`;
}

export function randomId(prefix: string): string {
  return randomToken(prefix, 12);
}

const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
/** One-time pairing code, 10 chars (~49 bits), displayed as XXXXX-XXXXX. */
export function randomPairCode(): string {
  const b = new Uint8Array(10);
  crypto.getRandomValues(b);
  const chars = [...b].map((x) => CODE_ALPHABET[x % CODE_ALPHABET.length]).join('');
  return `${chars.slice(0, 5)}-${chars.slice(5)}`;
}
export function normalizePairCode(code: string): string {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

/** Constant-time comparison by comparing digests. */
export async function secretEquals(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([sha256Hex(a), sha256Hex(b)]);
  let diff = 0;
  for (let i = 0; i < ha.length; i++) diff |= ha.charCodeAt(i) ^ hb.charCodeAt(i);
  return diff === 0;
}

/** Deterministic JSON (sorted keys) used for payload hashing. */
export function canonicalJson(v: unknown): string {
  if (v === null || typeof v !== 'object') return JSON.stringify(v) ?? 'null';
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(',')}]`;
  const o = v as Record<string, unknown>;
  return `{${Object.keys(o)
    .filter((k) => o[k] !== undefined)
    .sort()
    .map((k) => `${JSON.stringify(k)}:${canonicalJson(o[k])}`)
    .join(',')}}`;
}

function derToRaw(der: Uint8Array<ArrayBuffer>, size = 32): Uint8Array<ArrayBuffer> {
  if (der[0] !== 0x30) throw new Error('bad DER');
  let o = 2;
  if (der[1] & 0x80) o = 2 + (der[1] & 0x7f);
  const readInt = (): Uint8Array<ArrayBuffer> => {
    if (der[o] !== 0x02) throw new Error('bad DER int');
    const len = der[o + 1];
    let v = der.slice(o + 2, o + 2 + len);
    o += 2 + len;
    while (v.length > size && v[0] === 0) v = v.slice(1);
    if (v.length > size) throw new Error('bad DER int size');
    const out = new Uint8Array(size);
    out.set(v, size - v.length);
    return out;
  };
  const r = readInt();
  const s = readInt();
  const raw = new Uint8Array(size * 2);
  raw.set(r, 0);
  raw.set(s, size);
  return raw;
}

/**
 * Verify an ECDSA P-256 / SHA-256 signature. `publicKeySpki` is base64(url) X.509 SubjectPublicKeyInfo
 * (Android `PublicKey.getEncoded()`, .NET `ExportSubjectPublicKeyInfo`). `signature` is base64(url) of
 * raw r||s (64 bytes) or ASN.1 DER (Java `SHA256withECDSA` output).
 */
export async function verifyEcdsaP256(publicKeySpki: string, message: string, signature: string): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey(
      'spki',
      b64ToBytes(publicKeySpki),
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['verify'],
    );
    let sig = b64ToBytes(signature);
    if (sig.length !== 64) sig = derToRaw(sig);
    return await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, sig, enc.encode(message));
  } catch {
    return false;
  }
}

export interface Clock {
  now(): number;
  sleep(ms: number): Promise<void>;
}
export const systemClock: Clock = {
  now: () => Date.now(),
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
};

export function iso(ms: number | undefined): string | undefined {
  return ms === undefined ? undefined : new Date(ms).toISOString();
}
