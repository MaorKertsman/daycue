import { serve } from '@hono/node-server';
import { getConnInfo } from '@hono/node-server/conninfo';
import { createApp } from '../app.js';
import { Relay, assertOwnerSecretStrength } from '../relay.js';
import { FileStore, MemoryStore, type Store } from '../store.js';
import { FcmHttpV1Sender, NoopWakeSender, type WakeSender } from '../wake.js';
import { safeHttpsGet } from './safefetch.js';

/** Node entry point: `npm run dev` locally, `node dist/node/server.js` on Render. Config via environment only. */
async function main() {
  const env = process.env;
  const port = Number(env.PORT ?? 8787);
  const baseUrl = env.DAYCUE_BASE_URL ?? env.RENDER_EXTERNAL_URL ?? `http://localhost:${port}`;
  const ownerSecret = env.DAYCUE_OWNER_SECRET;
  if (!ownerSecret) {
    console.error('DAYCUE_OWNER_SECRET is required. Generate one with: npm run gen-secret');
    process.exit(1);
  }
  try {
    assertOwnerSecretStrength(ownerSecret);
  } catch (e) {
    console.error((e as Error).message);
    process.exit(1);
  }

  let store: Store;
  let storeDesc: string;
  if (env.DATABASE_URL) {
    const { default: pg } = await import('pg');
    const { PgStore } = await import('../pgstore.js');
    // TLS is verified by default. Render's internal database URL needs DATABASE_SSL=false (private network);
    // DATABASE_SSL=insecure keeps TLS but skips certificate verification (not recommended).
    const ssl = env.DATABASE_SSL === 'false' ? false : env.DATABASE_SSL === 'insecure' ? { rejectUnauthorized: false } : { rejectUnauthorized: true };
    const pool = new pg.Pool({ connectionString: env.DATABASE_URL, max: 3, ssl });
    const s = new PgStore(pool);
    await s.init();
    store = s;
    storeDesc = 'postgres (DATABASE_URL)';
  } else if (env.DAYCUE_DATA_FILE) {
    store = new FileStore(env.DAYCUE_DATA_FILE);
    storeDesc = `file ${env.DAYCUE_DATA_FILE}`;
  } else if (env.DAYCUE_ALLOW_MEMORY_STORE === 'true') {
    store = new MemoryStore();
    storeDesc = 'MEMORY ONLY (data is lost on restart)';
  } else {
    store = new FileStore('.data/relay.json');
    storeDesc = 'file .data/relay.json';
  }

  let wake: WakeSender = new NoopWakeSender();
  if (env.FCM_SERVICE_ACCOUNT_JSON) wake = FcmHttpV1Sender.fromJson(env.FCM_SERVICE_ACCOUNT_JSON);

  const trustedHosts = env.DAYCUE_ALLOWED_REDIRECT_HOSTS?.split(',').map((h) => h.trim().toLowerCase()).filter(Boolean);
  const relay = new Relay({
    baseUrl,
    ownerSecret,
    store,
    wake,
    trustedRedirectHosts: trustedHosts,
    requirePhoneApprovalForNewGrants: env.DAYCUE_REQUIRE_PHONE_APPROVAL !== 'false',
    // Client metadata documents are fetched through a resolver that vets every address and connects to the vetted one.
    cimdFetch: (url, o) => safeHttpsGet(url, o),
  });

  // Render terminates TLS and appends the client address to X-Forwarded-For: trust exactly one proxy hop there.
  const hops = env.DAYCUE_TRUSTED_PROXY_HOPS !== undefined ? Number(env.DAYCUE_TRUSTED_PROXY_HOPS) : env.RENDER ? 1 : 0;
  const app = createApp(relay, {
    trustProxyHops: hops,
    sourceOf: hops > 0 ? undefined : (c) => {
      try {
        return getConnInfo(c).remote.address;
      } catch {
        return undefined;
      }
    },
  });
  setInterval(() => void relay.maintenance().catch(() => {}), 3600_000).unref();

  const server = serve({ fetch: app.fetch, port, hostname: env.HOST ?? '0.0.0.0' }, (i) => {
    console.log(`DayCue relay listening on :${i.port}`);
    console.log(`  base URL : ${baseUrl}`);
    console.log(`  MCP      : ${baseUrl}/mcp`);
    console.log(`  storage  : ${storeDesc}`);
    console.log(`  push     : ${env.FCM_SERVICE_ACCOUNT_JSON ? 'FCM configured' : 'not configured (phone relies on its own sync)'}`);
    console.log(`  proxy    : ${hops > 0 ? `trusting ${hops} proxy hop(s) for client addresses` : 'socket address'}`);
    console.log(`  new grants need phone approval: ${env.DAYCUE_REQUIRE_PHONE_APPROVAL !== 'false'}`);
  });

  // Persist pending store writes before exiting (FileStore coalesces writes).
  let closing = false;
  const shutdown = () => {
    if (closing) return;
    closing = true;
    void (async () => {
      try {
        await store.flush?.();
      } finally {
        server.close?.();
        process.exit(0);
      }
    })();
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}
void main();
