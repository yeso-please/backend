import { test } from "node:test";
import assert from "node:assert/strict";
import {
  api,
  setToken,
  redact,
  clearLogs,
  getLogs,
  query,
} from "../src/api.js";
globalThis.window = new EventTarget();
const response = (data, status = 200) =>
  new Response(data === null ? null : JSON.stringify(data), { status });
test("concurrent expired requests share one refresh, retry with the new bearer", async () => {
  const original = globalThis.fetch;
  let refreshes = 0,
    calls = 0;
  setToken("old");
  globalThis.fetch = async (url, options) => {
    if (url === "/api/auth/refresh") {
      refreshes++;
      await new Promise((r) => setTimeout(r, 15));
      return response({ accessToken: "new" });
    }
    calls++;
    return options.headers.Authorization === "Bearer new"
      ? response({ ok: true })
      : response({ code: "AUTH_UNAUTHENTICATED" }, 401);
  };
  try {
    const rs = await Promise.all([api("/trips"), api("/users/me")]);
    assert.deepEqual(rs, [{ ok: true }, { ok: true }]);
    assert.equal(refreshes, 1);
    assert.equal(calls, 4);
  } finally {
    globalThis.fetch = original;
    setToken(null);
  }
});
test("shared sessions and AI calls never carry the user bearer", async () => {
  const original = globalThis.fetch;
  setToken("secret");
  const seen = [];
  globalThis.fetch = async (url, o) => {
    seen.push(o);
    return response({ code: "SHARE_SESSION_INVALID" }, 401);
  };
  try {
    await assert.rejects(api("/shared/courses"));
    await assert.rejects(api("/health", { ai: true }));
    assert.equal(seen.length, 2);
    assert.ok(seen.every((o) => !o.headers.Authorization));
    assert.equal(seen[0].credentials, "include");
    assert.equal(seen[1].credentials, "omit");
  } finally {
    globalThis.fetch = original;
    setToken(null);
  }
});
test("204, multipart and version errors preserve their contracts", async () => {
  const original = globalThis.fetch;
  const seen = [];
  globalThis.fetch = async (url, o) => {
    seen.push(o);
    return url.endsWith("/schedule")
      ? response(
          { code: "TRIP_VERSION_CONFLICT", details: { version: 7 } },
          409,
        )
      : response(null, 204);
  };
  try {
    const body = new FormData();
    body.append("files", new Blob(["data"]), "test.png");
    assert.equal(
      await api("/diaries/1/photos", { method: "POST", body }),
      null,
    );
    assert.equal(seen[0].headers["Content-Type"], undefined);
    await assert.rejects(
      api("/courses/1/schedule", {
        method: "PATCH",
        body: { version: 1, operations: [] },
      }),
      (e) => e.code === "TRIP_VERSION_CONFLICT" && e.data.details.version === 7,
    );
    assert.equal(seen.length, 2);
  } finally {
    globalThis.fetch = original;
  }
});
test("diagnostics redact credentials, private notes and secret link paths", () => {
  const r = redact({
    password: "abc",
    accessToken: "xyz",
    likedTrips: [{ note: "private" }],
    path: "/invites/iv_abc/accept",
  });
  assert.equal(r.password, "[비공개]");
  assert.equal(r.likedTrips[0].note, "[비공개]");
  assert.equal(r.path, "/invites/[비공개]/accept");
  clearLogs();
  assert.equal(getLogs().length, 0);
  assert.equal(
    query({ q: "바다", empty: "", zero: 0 }),
    "q=%EB%B0%94%EB%8B%A4&zero=0",
  );
});

test("requests are logged immediately and completed in place without truncating JSON", async () => {
  const original = globalThis.fetch;
  let done;
  clearLogs();
  globalThis.fetch = () =>
    new Promise((resolve) => {
      done = resolve;
    });
  try {
    const pending = api("/example", {
      method: "POST",
      body: { hello: "world", password: "secret" },
    });
    assert.equal(getLogs().length, 1);
    assert.equal(getLogs()[0].pending, true);
    assert.equal(getLogs()[0].request.password, "[비공개]");
    const id = getLogs()[0].id,
      body = { nested: { text: "x".repeat(2000) } };
    done(response(body));
    await pending;
    assert.equal(getLogs().length, 1);
    assert.equal(getLogs()[0].id, id);
    assert.equal(getLogs()[0].pending, false);
    assert.deepEqual(getLogs()[0].response, body);
  } finally {
    globalThis.fetch = original;
    clearLogs();
  }
});
