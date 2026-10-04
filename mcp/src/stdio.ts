#!/usr/bin/env node
import { StdioServerTransport } from '@modelcontextprotocol/server/stdio';
import { HttpClientApi } from './clientApi.js';
import { buildMcpServer } from './tools.js';
import { SCOPES } from './types.js';

/**
 * Local stdio MCP server for Claude Code. Talks to the relay with a paired client token.
 *   DAYCUE_RELAY_URL     e.g. http://localhost:8787 or https://<service>.onrender.com
 *   DAYCUE_CLIENT_TOKEN  from POST /v1/owner/client-tokens (never hard-coded or committed)
 * All logging goes to stderr; stdout is the MCP channel.
 */
export async function startStdio(env = process.env) {
  const url = env.DAYCUE_RELAY_URL;
  const token = env.DAYCUE_CLIENT_TOKEN;
  if (!url || !token) {
    console.error('DAYCUE_RELAY_URL and DAYCUE_CLIENT_TOKEN must be set (see docs/setup/MCP.md).');
    process.exit(1);
  }
  const api = new HttpClientApi(url, token);
  let scopes: string[] = [...SCOPES];
  try {
    scopes = (await api.whoami()).scopes;
  } catch (e) {
    console.error(`warning: could not reach the relay at startup (${(e as Error).message}); exposing all tools, calls will report errors until it is reachable.`);
  }
  const server = buildMcpServer(api, scopes);
  await server.connect(new StdioServerTransport());
}

if (process.argv[1] && /stdio\.(ts|js)$/.test(process.argv[1])) void startStdio();
