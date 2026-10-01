import { test } from "node:test";
import assert from "node:assert/strict";
import proxy from "../api/proxy.js";
test("deployment proxy preserves raw body, HTTP-only cookies and relative redirect", async () => {
  const saved = globalThis.fetch;
  process.env.BACKEND_URL = "https://backend.invalid";
  let captured;
  globalThis.fetch = async (url, opts) => {
    captured = {
      url: String(url),
      opts,
      body: await new Response(opts.body).text(),
    };
    return new Response(null, {
      status: 303,
      headers: {
        "Set-Cookie":
          "share_session=secret; HttpOnly; Domain=backend.invalid; Path=/api/shared; SameSite=Lax",
        Location: "https://backend.invalid/api/shared/courses",
      },
    });
  };
  try {
    const res = await proxy.fetch(
      new Request(
        "https://front.invalid/api/proxy?service=backend&path=shared/courses/sl_test&limit=4",
        {
          method: "POST",
          headers: {
            "content-type": "application/json",
            authorization: "Bearer user",
          },
          body: '{"a":1}',
        },
      ),
    );
    assert.equal(
      captured.url,
      "https://backend.invalid/api/shared/courses/sl_test?limit=4",
    );
    assert.equal(captured.body, '{"a":1}');
    assert.equal(res.status, 303);
    assert.equal(res.headers.get("location"), "/api/shared/courses");
    assert.match(res.headers.get("set-cookie"), /HttpOnly/);
    assert.doesNotMatch(res.headers.get("set-cookie"), /Domain=/);
  } finally {
    globalThis.fetch = saved;
    delete process.env.BACKEND_URL;
  }
});
test("AI proxy is opt-in and does not forward cookies or bearer credentials", async () => {
  process.env.AI_URL = "https://ai.invalid";
  delete process.env.ENABLE_AI_LAB;
  const req = () =>
    new Request("https://front.invalid/api/proxy?service=ai&path=health", {
      headers: {
        authorization: "Bearer secret",
        cookie: "refresh_token=secret",
      },
    });
  assert.equal((await proxy.fetch(req())).status, 403);
  const saved = globalThis.fetch;
  process.env.ENABLE_AI_LAB = "true";
  globalThis.fetch = async (url, o) => {
    assert.equal(o.headers.has("authorization"), false);
    assert.equal(o.headers.has("cookie"), false);
    return Response.json({ status: "UP" });
  };
  try {
    assert.equal((await proxy.fetch(req())).status, 200);
    assert.equal(
      (
        await proxy.fetch(
          new Request("https://front.invalid/api/proxy?service=ai&path=admin"),
        )
      ).status,
      400,
    );
  } finally {
    globalThis.fetch = saved;
    delete process.env.AI_URL;
    delete process.env.ENABLE_AI_LAB;
  }
});
test("proxy rejects traversal and external redirects", async () => {
  process.env.BACKEND_URL = "https://backend.invalid";
  const saved = globalThis.fetch;
  try {
    assert.equal(
      (
        await proxy.fetch(
          new Request("https://front.invalid/api/proxy?path=..%2Fadmin"),
        )
      ).status,
      400,
    );
    globalThis.fetch = async () =>
      new Response(null, {
        status: 303,
        headers: { location: "https://outside.invalid/" },
      });
    assert.equal(
      (
        await proxy.fetch(
          new Request(
            "https://front.invalid/api/proxy?path=shared/courses/token",
          ),
        )
      ).status,
      502,
    );
  } finally {
    globalThis.fetch = saved;
    delete process.env.BACKEND_URL;
  }
});
