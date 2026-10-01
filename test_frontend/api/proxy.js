// Web-standard handler preserves raw JSON and multipart request bytes.
export default {
  async fetch(request) {
    const incoming = new URL(request.url);
    const ai = incoming.searchParams.get("service") === "ai";
    const error = (status, code, message) =>
      Response.json(
        { code, message },
        { status, headers: { "Cache-Control": "no-store" } },
      );
    if (ai && process.env.ENABLE_AI_LAB !== "true")
      return error(
        403,
        "AI_LAB_DISABLED",
        "배포 환경에서는 ENABLE_AI_LAB=true 설정이 필요합니다.",
      );
    const base = ai ? process.env.AI_URL : process.env.BACKEND_URL;
    if (!base)
      return error(
        503,
        "UPSTREAM_NOT_CONFIGURED",
        ai ? "AI_URL을 설정하세요." : "BACKEND_URL을 설정하세요.",
      );
    const path = incoming.searchParams.get("path") || "";
    if (
      !path ||
      path.includes("%") ||
      path.includes("..") ||
      path.includes("\\") ||
      path.startsWith("/") ||
      (ai &&
        !["health", "embeddings", "embeddings/batch", "explanations"].includes(
          path,
        ))
    )
      return error(400, "INVALID_PROXY_PATH", "API 경로가 올바르지 않습니다.");
    if (
      !["GET", "HEAD", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"].includes(
        request.method,
      )
    )
      return error(405, "INVALID_METHOD", "지원하지 않는 메서드입니다.");
    try {
      const target = new URL((ai ? "/" : "/api/") + path, base);
      incoming.searchParams.forEach((v, k) => {
        if (!["path", "service"].includes(k)) target.searchParams.append(k, v);
      });
      const headers = new Headers();
      for (const h of [
        "content-type",
        "accept",
        ...(!ai ? ["authorization", "cookie", "idempotency-key"] : []),
      ])
        if (request.headers.has(h)) headers.set(h, request.headers.get(h));
      const response = await fetch(target, {
        method: request.method,
        headers,
        body: ["GET", "HEAD"].includes(request.method)
          ? undefined
          : request.body,
        duplex: "half",
        redirect: "manual",
        signal: AbortSignal.timeout(110000),
      });
      const outgoing = new Headers({ "Cache-Control": "no-store" });
      for (const h of ["content-type", "retry-after"])
        if (response.headers.has(h)) outgoing.set(h, response.headers.get(h));
      if (!ai)
        for (const c of response.headers.getSetCookie())
          outgoing.append("Set-Cookie", c.replace(/;\s*Domain=[^;]+/gi, ""));
      const location = response.headers.get("location");
      if (location) {
        const u = new URL(location, target);
        if (u.origin !== target.origin || !u.pathname.startsWith("/api/"))
          return error(
            502,
            "INVALID_REDIRECT",
            "외부 리다이렉트를 차단했습니다.",
          );
        outgoing.set("Location", u.pathname + u.search);
      }
      return new Response(response.body, {
        status: response.status,
        headers: outgoing,
      });
    } catch {
      return error(
        502,
        "UPSTREAM_UNAVAILABLE",
        "서버에 연결하지 못했습니다. 서버 주소와 실행 상태를 확인하세요.",
      );
    }
  },
};
