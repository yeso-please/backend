import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer as httpServer } from "node:http";
import { createServer as viteServer } from "vite";
import config from "../vite.config.js";
test("real local Vite proxy strips browser Origin, preserves JSON and cookies", async () => {
  let received;
  const upstream = httpServer(async (req, res) => {
    let body = "";
    for await (const c of req) body += c;
    received = {
      origin: req.headers.origin,
      authorization: req.headers.authorization,
      body,
    };
    res.setHeader("Content-Type", "application/json");
    res.setHeader(
      "Set-Cookie",
      "refresh_token=fixture; HttpOnly; Path=/api/auth; SameSite=Lax",
    );
    res.end('{"ok":true}');
  });
  await new Promise((resolve) => upstream.listen(0, "127.0.0.1", resolve));
  const cfg = config({ mode: "test" });
  cfg.server.proxy["/api"].target =
    `http://127.0.0.1:${upstream.address().port}`;
  const vite = await viteServer({
    ...cfg,
    configFile: false,
    logLevel: "silent",
    server: { ...cfg.server, port: 0, host: "127.0.0.1", strictPort: false },
  });
  try {
    await vite.listen();
    const port = vite.httpServer.address().port;
    const res = await fetch(`http://127.0.0.1:${port}/api/auth/login`, {
      method: "POST",
      headers: {
        Origin: `http://127.0.0.1:${port}`,
        "Content-Type": "application/json",
        Authorization: "Bearer fixture",
      },
      body: '{"email":"test@example.com"}',
    });
    assert.deepEqual(await res.json(), { ok: true });
    assert.equal(received.origin, undefined);
    assert.equal(received.authorization, "Bearer fixture");
    assert.equal(received.body, '{"email":"test@example.com"}');
    assert.match(res.headers.get("set-cookie"), /HttpOnly/);
  } finally {
    await vite.close();
    await new Promise((resolve) => upstream.close(resolve));
  }
});
