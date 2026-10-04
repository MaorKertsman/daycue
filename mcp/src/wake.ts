import { bytesToB64u, b64ToBytes } from './util.js';

/** Sends a "wake and sync" push. The message carries NO config data. */
export interface WakeSender {
  wake(token: string, reason: 'command' | 'activity'): Promise<{ ok: boolean; detail?: string }>;
}

export class NoopWakeSender implements WakeSender {
  async wake() {
    return { ok: false, detail: 'push not configured' };
  }
}

export class FakeWakeSender implements WakeSender {
  sent: Array<{ token: string; reason: string }> = [];
  failNext = false;
  async wake(token: string, reason: 'command' | 'activity') {
    if (this.failNext) {
      this.failNext = false;
      return { ok: false, detail: 'fake failure' };
    }
    this.sent.push({ token, reason });
    return { ok: true };
  }
}

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

/**
 * FCM HTTP v1 sender. Needs the owner's Firebase service account JSON (never committed).
 * Live verification pending: no credentials were available, only the request shape is unit-tested.
 */
export class FcmHttpV1Sender implements WakeSender {
  private cached?: { token: string; exp: number };
  constructor(
    private sa: ServiceAccount,
    private fetchImpl: typeof fetch = fetch,
    private now: () => number = Date.now,
  ) {}

  static fromJson(json: string, fetchImpl?: typeof fetch) {
    return new FcmHttpV1Sender(JSON.parse(json) as ServiceAccount, fetchImpl);
  }

  private async accessToken(): Promise<string> {
    if (this.cached && this.cached.exp - 60_000 > this.now()) return this.cached.token;
    const tokenUri = this.sa.token_uri ?? 'https://oauth2.googleapis.com/token';
    const iat = Math.floor(this.now() / 1000);
    const b64 = (o: unknown) => bytesToB64u(new TextEncoder().encode(JSON.stringify(o)));
    const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
      iss: this.sa.client_email,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: tokenUri,
      iat,
      exp: iat + 3600,
    })}`;
    const pem = this.sa.private_key.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
    const key = await crypto.subtle.importKey(
      'pkcs8',
      b64ToBytes(pem),
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['sign'],
    );
    const sig = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned)));
    const res = await this.fetchImpl(tokenUri, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: `${unsigned}.${bytesToB64u(sig)}`,
      }),
    });
    if (!res.ok) throw new Error(`fcm oauth ${res.status}`);
    const j = (await res.json()) as { access_token: string; expires_in: number };
    this.cached = { token: j.access_token, exp: this.now() + j.expires_in * 1000 };
    return j.access_token;
  }

  async wake(token: string, reason: 'command' | 'activity') {
    try {
      const at = await this.accessToken();
      const res = await this.fetchImpl(`https://fcm.googleapis.com/v1/projects/${this.sa.project_id}/messages:send`, {
        method: 'POST',
        headers: { authorization: `Bearer ${at}`, 'content-type': 'application/json' },
        body: JSON.stringify({
          message: {
            token,
            data: { type: 'sync', reason },
            android: { priority: 'HIGH', ttl: '120s' },
          },
        }),
      });
      return res.ok ? { ok: true } : { ok: false, detail: `fcm ${res.status}` };
    } catch (e) {
      return { ok: false, detail: `fcm error: ${(e as Error).message}` };
    }
  }
}
