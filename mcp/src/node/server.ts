import { serve } from '@hono/node-server';
import { createApp } from '../app.js';
import { Relay } from '../relay.js';
import { FileStore, MemoryStore, type Store } from '../store.js';
import { FcmHttpV1Sender, NoopWakeSender, type WakeSender } from '../wake.js';

/** Node entry point: `npm run dev` locally, `node dist/node/server.js` on Render. Config via environment only. */
async function main() {
  const env = process.env;
  const port = Number(env.PORT ?? 8787);
  const baseUrl = env.DAYCUE_BASE_URL ?? env.RENDER_EXTERNAL_URL ?? `http://localhost:${port}`;
  const ownerSecret = env.DAYCUE_OWNER_SECRET;
  if (!ownerSecret) {
    console.error('DAYCUE_OWNER_SECRET is required (>= 24 chars). Generate one: node -e "console.log(require(\'crypto\').randomBytes(32).toString(\'base64url\'))"');
    process.exit(1);
  }

  let store: Store;
  let storeDesc: string;
  if (env.DATABASE_URL) {
    const { default: pg } = await import('pg');
    const { PgStore } = await import('../pgstore.js');
    const pool = new pg.Pool({ connectionString: env.DATABASE_URL, max: 3, ssl: env.DATABASE_SSL === 'false' ? false : { rejectUnauthorized: false } });
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

  const relay = new Relay({ baseUrl, ownerSecret, store, wake });
  const app = createApp(relay);
  setInterval(() => void relay.maintenance().catch(() => {}), 3600_000).unref();

  serve({ fetch: app.fetch, port, hostname: env.HOST ?? '0.0.0.0' }, (i) => {
    console.log(`DayCue relay listening on :${i.port}`);
    console.log(`  base URL : ${baseUrl}`);
    console.log(`  MCP      : ${baseUrl}/mcp`);
    console.log(`  storage  : ${storeDesc}`);
    console.log(`  push     : ${env.FCM_SERVICE_ACCOUNT_JSON ? 'FCM configured' : 'not configured (phone relies on its own sync)'}`);
  });
}
void main();
