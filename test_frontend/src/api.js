let token = null,
  refreshPromise = null;
const listeners = new Set();
let logs = [];
export const uploadLimitMB = Number(import.meta.env?.VITE_UPLOAD_LIMIT_MB || 0);
export const subscribeLogs = (fn) => {
  listeners.add(fn);
  return () => listeners.delete(fn);
};
export const getLogs = () => logs;
export function clearLogs() {
  logs = [];
  listeners.forEach((fn) => fn());
}
export function setToken(value) {
  token = value || null;
}
export function redact(value, key = "") {
  if (
    /password|token|email|profileText|note|embeddingBase64|personalReason/i.test(
      key,
    )
  )
    return "[비공개]";
  if (Array.isArray(value)) return value.map((v) => redact(v));
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.entries(value).map(([k, v]) => [k, redact(v, k)]),
    );
  if (typeof value === "string")
    return value.replace(/\b(?:iv|sl|ss|fl|dl|rs)_[^\s/"?]+/g, "[비공개]");
  return value;
}
export class ApiError extends Error {
  constructor(data, status) {
    super(
      data?.message || data?.detail?.code || data?.code || `HTTP ${status}`,
    );
    this.status = status;
    this.data = data;
    this.code = data?.code || data?.detail?.code;
  }
}
export async function api(
  path,
  { method = "GET", body, ai = false, retry = true } = {},
) {
  if (!path.startsWith("/") || path.startsWith("//"))
    throw new Error("API 경로가 올바르지 않습니다.");
  if (body instanceof FormData && uploadLimitMB > 0) {
    const total = [...body.values()].reduce(
      (n, v) => n + (v instanceof Blob ? v.size : 0),
      0,
    );
    if (total > uploadLimitMB * 1024 * 1024)
      throw new Error(
        `이 배포의 업로드 합계는 ${uploadLimitMB}MB까지입니다. 더 큰 파일은 로컬 프론트에서 테스트하세요.`,
      );
  }
  const started = performance.now(),
    sentToken = token,
    logId = crypto.randomUUID();
  logs = [
    {
      id: logId,
      time: new Date().toLocaleTimeString("ko-KR"),
      method,
      path: redact(path),
      ai,
      status: 0,
      pending: true,
      ms: 0,
      request:
        body instanceof FormData
          ? {
              multipart: [...body.entries()].map(([field, value]) => ({
                field,
                ...(value instanceof Blob
                  ? { type: value.type, sizeBytes: value.size }
                  : { value: redact(value, field) }),
              })),
            }
          : redact(body),
      response: undefined,
    },
    ...logs,
  ].slice(0, 60);
  listeners.forEach((fn) => fn());
  let status = 0,
    data,
    contentType = "",
    responseBytes = 0;
  try {
    const response = await fetch((ai ? "/ai-api" : "/api") + path, {
      method,
      credentials: ai ? "omit" : "include",
      headers: {
        ...(body !== undefined && !(body instanceof FormData)
          ? { "Content-Type": "application/json" }
          : {}),
        ...(!ai && token && !path.startsWith("/shared/")
          ? { Authorization: `Bearer ${token}` }
          : {}),
      },
      body:
        body === undefined
          ? undefined
          : body instanceof FormData
            ? body
            : JSON.stringify(body),
      signal: AbortSignal.timeout(120000),
    });
    status = response.status;
    contentType = response.headers.get("content-type") || "";
    const raw = await response.text();
    responseBytes = new TextEncoder().encode(raw).length;
    try {
      data = raw ? JSON.parse(raw) : null;
    } catch {
      data = { message: raw || "비어 있는 응답" };
    }
    if (
      status === 401 &&
      !ai &&
      retry &&
      !path.startsWith("/auth/") &&
      !path.startsWith("/shared/")
    ) {
      if (token === sentToken) await refresh();
      return api(path, { method, body, ai, retry: false });
    }
    if (!response.ok) throw new ApiError(data, status);
    if (!ai && data?.accessToken) setToken(data.accessToken);
    if (path === "/auth/logout") setToken(null);
    return data;
  } catch (error) {
    if (!data && !status)
      data = {
        code: "CONNECTION_FAILED",
        message:
          "연결 실패: 백엔드/AI 서버 실행 여부와 프록시 설정을 확인하세요.",
      };
    if (error instanceof ApiError) throw error;
    throw new ApiError(data, status);
  } finally {
    logs = logs.map((log) =>
      log.id === logId
        ? {
            ...log,
            status,
            pending: false,
            ms: Math.round(performance.now() - started),
            response: redact(data),
            contentType,
            responseBytes,
          }
        : log,
    );
    listeners.forEach((fn) => fn());
  }
}
export async function refresh() {
  const perform = () => api("/auth/refresh", { method: "POST", retry: false });
  if (!refreshPromise)
    refreshPromise = (
      globalThis.navigator?.locks
        ? navigator.locks.request("tripin-refresh", perform)
        : perform()
    )
      .catch((e) => {
        setToken(null);
        window.dispatchEvent(new Event("session-expired"));
        throw e;
      })
      .finally(() => {
        refreshPromise = null;
      });
  return refreshPromise;
}
export const query = (obj) => {
  const p = new URLSearchParams();
  Object.entries(obj).forEach(([k, v]) => {
    if (v !== "" && v !== null && v !== undefined) p.set(k, String(v));
  });
  return p.toString();
};
export const seoulToday = () =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
export function tomorrow() {
  const d = new Date(`${seoulToday()}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}
