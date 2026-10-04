import { lookup as dnsLookup } from 'node:dns';
import { request } from 'node:https';
import type { LookupFunction } from 'node:net';
import { isPublicIp, blockedHostName } from '../netguard.js';

export type DnsLookup = (
  host: string,
  opts: { all: true; verbatim?: boolean },
  cb: (err: Error | null, addrs: Array<{ address: string; family: number }>) => void,
) => void;

export interface SafeFetchOptions {
  maxBytes: number;
  timeoutMs: number;
  /** Test seam: replaces the DNS resolver. */
  lookupImpl?: DnsLookup;
}

/**
 * GET for client-supplied URLs (CIMD). HTTPS only, no redirects (any 3xx is an error), body capped while streaming,
 * overall timeout. The name is resolved ONCE through a custom `lookup`; every returned address must be public
 * and the socket connects to exactly the vetted address (TLS still validates the certificate for the host name),
 * so a DNS answer cannot change between the check and the connection (no rebinding window).
 */
export function safeHttpsGet(rawUrl: string, o: SafeFetchOptions): Promise<{ status: number; text: string }> {
  return new Promise((resolve, reject) => {
    let u: URL;
    try {
      u = new URL(rawUrl);
    } catch {
      return reject(new Error('bad url'));
    }
    if (u.protocol !== 'https:' || u.username || u.password) return reject(new Error('https only'));
    if (blockedHostName(u.hostname)) return reject(new Error('host not allowed'));
    const resolver: DnsLookup = o.lookupImpl ?? ((h, opts, cb) => dnsLookup(h, opts as any, cb as any));
    const lookup = ((hostname: string, lopts: any, cb: (...a: any[]) => void) => {
      resolver(hostname, { all: true, verbatim: true }, (err, addrs) => {
        if (err) return cb(err);
        if (!addrs.length || !addrs.every((a) => isPublicIp(a.address))) return cb(new Error('host resolves to a non-public address'));
        // Node's autoSelectFamily asks for the `all` form; otherwise answer with the first vetted address.
        if (lopts && typeof lopts === 'object' && lopts.all) return cb(null, addrs);
        cb(null, addrs[0].address, addrs[0].family);
      });
    }) as unknown as LookupFunction;
    const req = request(
      {
        protocol: 'https:',
        hostname: u.hostname,
        port: u.port || 443,
        path: `${u.pathname}${u.search}`,
        method: 'GET',
        headers: { accept: 'application/json', 'user-agent': 'daycue-relay' },
        lookup,
      },
      (res) => {
        const status = res.statusCode ?? 0;
        if (status >= 300 && status < 400) {
          res.destroy();
          return reject(new Error('redirects are not followed'));
        }
        const chunks: Buffer[] = [];
        let size = 0;
        res.on('data', (c: Buffer) => {
          size += c.length;
          if (size > o.maxBytes) {
            res.destroy();
            req.destroy();
            return reject(new Error('document too large'));
          }
          chunks.push(c);
        });
        res.on('end', () => resolve({ status, text: Buffer.concat(chunks).toString('utf8') }));
        res.on('error', reject);
      },
    );
    const hard = setTimeout(() => req.destroy(new Error('timeout')), o.timeoutMs);
    req.on('close', () => clearTimeout(hard));
    req.on('error', reject);
    req.end();
  });
}
