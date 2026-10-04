/** Platform-neutral address and host-name checks used before the relay fetches a client-supplied URL (CIMD). */

/** Lowercases and strips every trailing dot ("localhost." is the same host as "localhost"). */
export function normalizeHost(host: string): string {
  return host.toLowerCase().replace(/\.+$/, '');
}

const BLOCKED_SUFFIXES = ['.localhost', '.local', '.internal', '.localdomain', '.home.arpa', '.lan', '.intranet', '.corp'];

/** Name-level check (no DNS). True means: refuse. */
export function blockedHostName(rawHost: string): boolean {
  const host = normalizeHost(rawHost);
  if (!host) return true;
  if (host === 'localhost' || host === 'ip6-localhost' || host === 'metadata') return true;
  if (BLOCKED_SUFFIXES.some((s) => host.endsWith(s))) return true;
  if (!host.includes('.')) return true; // single label
  if (host.startsWith('[') || host.includes(':')) return true; // IPv6 literal
  if (/^[0-9.x]+$/i.test(host) || /^\d+$/.test(host)) return true; // any numeric/dotted IPv4 form
  return false;
}

function parseV4(s: string): number[] | undefined {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(s);
  if (!m) return undefined;
  const o = m.slice(1).map(Number);
  return o.every((x) => x <= 255) ? o : undefined;
}

function v4Public([a, b, c]: number[]): boolean {
  if (a === 0 || a === 10 || a === 127) return false;
  if (a === 100 && b >= 64 && b <= 127) return false; // CGNAT
  if (a === 169 && b === 254) return false; // link-local, cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return false;
  if (a === 192 && b === 0 && (c === 0 || c === 2)) return false; // IETF protocol, TEST-NET-1
  if (a === 192 && b === 168) return false;
  if (a === 192 && b === 88 && c === 99) return false;
  if (a === 198 && (b === 18 || b === 19)) return false; // benchmarking
  if (a === 198 && b === 51 && c === 100) return false;
  if (a === 203 && b === 0 && c === 113) return false;
  if (a >= 224) return false; // multicast, reserved, broadcast
  return true;
}

/** Expands an IPv6 literal into 8 16-bit groups, or undefined if malformed. */
function parseV6(input: string): number[] | undefined {
  let s = input.replace(/^\[|\]$/g, '').split('%')[0].toLowerCase();
  let tail: number[] = [];
  const v4 = /(\d+\.\d+\.\d+\.\d+)$/.exec(s);
  if (v4) {
    const o = parseV4(v4[1]);
    if (!o) return undefined;
    tail = [(o[0] << 8) | o[1], (o[2] << 8) | o[3]];
    s = s.slice(0, -v4[1].length) + '0:0';
  }
  const halves = s.split('::');
  if (halves.length > 2) return undefined;
  const parse = (x: string) => (x === '' ? [] : x.split(':').map((g) => (/^[0-9a-f]{1,4}$/.test(g) ? parseInt(g, 16) : NaN)));
  const head = parse(halves[0]);
  const rest = halves.length === 2 ? parse(halves[1]) : [];
  let groups: number[];
  if (halves.length === 2) {
    const fill = 8 - head.length - rest.length;
    if (fill < 0) return undefined;
    groups = [...head, ...new Array(fill).fill(0), ...rest];
  } else groups = head;
  if (groups.length !== 8 || groups.some((g) => Number.isNaN(g))) return undefined;
  if (tail.length) {
    groups[6] = tail[0];
    groups[7] = tail[1];
  }
  return groups;
}

/** True only for globally routable unicast addresses (IPv4 or IPv6). Unparseable input is NOT public. */
export function isPublicIp(ip: string): boolean {
  const v4 = parseV4(ip);
  if (v4) return v4Public(v4);
  const g = parseV6(ip);
  if (!g) return false;
  const allZeroTo = (n: number) => g.slice(0, n).every((x) => x === 0);
  if (allZeroTo(7) && (g[7] === 0 || g[7] === 1)) return false; // :: and ::1
  if (allZeroTo(5) && g[5] === 0xffff) return v4Public([g[6] >> 8, g[6] & 255, g[7] >> 8, g[7] & 255]); // ::ffff:a.b.c.d
  if (allZeroTo(5) && g[5] === 0) return false; // IPv4-compatible
  if (g[0] === 0x64 && g[1] === 0xff9b) return v4Public([g[6] >> 8, g[6] & 255, g[7] >> 8, g[7] & 255]); // NAT64
  if ((g[0] & 0xfe00) === 0xfc00) return false; // unique local
  if ((g[0] & 0xffc0) === 0xfe80) return false; // link-local
  if ((g[0] & 0xffc0) === 0xfec0) return false; // site-local
  if ((g[0] & 0xff00) === 0xff00) return false; // multicast
  if (g[0] === 0x2001 && g[1] === 0x0db8) return false; // documentation
  if (g[0] === 0x2002) return v4Public([g[1] >> 8, g[1] & 255, g[2] >> 8, g[2] & 255]); // 6to4
  return (g[0] & 0xe000) === 0x2000; // only global unicast 2000::/3
}
