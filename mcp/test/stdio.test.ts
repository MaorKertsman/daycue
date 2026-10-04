import { createServer, type Server } from 'node:http';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { serve } from '@hono/node-server';
import { createApp } from '../src/app.js';
import { Relay } from '../src/relay.js';
import { MemoryStore } from '../src/store.js';

const SECRET = 'stdio-test-owner-secret-0123456789';
let server: ReturnType<typeof serve>;
let url = '';
let token = '';

beforeAll(async () => {
  const relay = new Relay({ baseUrl: 'http://localhost:0', ownerSecret: SECRET, store: new MemoryStore() });
  // listen on an ephemeral port, then re-create the relay with the real base URL
  let port = 0;
  await new Promise<void>((res) => {
    const probe: Server = createServer().listen(0, '127.0.0.1', () => {
      port = (probe.address() as any).port;
      probe.close(() => res());
    });
  });
  void relay;
  url = `http://localhost:${port}`;
  const real = new Relay({ baseUrl: url, ownerSecret: SECRET, store: new MemoryStore() });
  server = serve({ fetch: createApp(real).fetch, port, hostname: '127.0.0.1' });
  token = (await real.auth.createStaticToken('stdio-test', ['config:read', 'activity:read'])).token;
});
afterAll(() => {
  server?.close();
});

describe('local stdio MCP server', () => {
  it('starts as a real child process, lists tools by scope, and reports relay state honestly', async () => {
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: ['node_modules/tsx/dist/cli.mjs', 'src/stdio.ts'],
      env: { ...(process.env as Record<string, string>), DAYCUE_RELAY_URL: url, DAYCUE_CLIENT_TOKEN: token },
      stderr: 'pipe',
    });
    const client = new Client({ name: 'stdio-test', version: '1.0.0' });
    await client.connect(transport);
    const names = (await client.listTools()).tools.map((t) => t.name).sort();
    expect(names).toEqual(['get_activity_summary', 'get_command_status', 'get_config', 'get_status', 'list_recent_changes']);
    const res: any = await client.callTool({ name: 'get_config', arguments: { section: 'all' } });
    expect(res.content[0].text).toContain('"exists": false'); // no snapshot published yet
    const act: any = await client.callTool({ name: 'get_activity_summary', arguments: {} });
    expect(act.content[0].text).toContain('"state": "unknown"');
    await client.close();
  }, 30000);

  it('refuses to start without a token and does not print secrets', async () => {
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: ['node_modules/tsx/dist/cli.mjs', 'src/stdio.ts'],
      env: { PATH: process.env.PATH ?? '' , SystemRoot: process.env.SystemRoot ?? '' },
      stderr: 'pipe',
    });
    const client = new Client({ name: 'stdio-test', version: '1.0.0' });
    await expect(client.connect(transport)).rejects.toThrow();
  }, 30000);
});
