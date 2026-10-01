import React, { useEffect, useState, useSyncExternalStore } from "react";
import {
  X,
  Copy,
  Trash2,
  Search,
  Maximize2,
  Minimize2,
  Radio,
  LoaderCircle,
  ArrowDownUp,
} from "lucide-react";
import { subscribeLogs, getLogs, clearLogs } from "./api";
import { Button, Badge } from "./ui";
import "./diagnostics.css";

function JsonText({ value }) {
  const text = JSON.stringify(value, null, 2);
  if (text === undefined) return null;
  const tokens = text.split(
    /("(?:\\.|[^"\\])*"\s*:|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
  );
  return (
    <pre className="inspector-json">
      <code>
        {tokens.map((part, i) => {
          const kind = /^".*:\s*$/.test(part)
            ? "key"
            : part.startsWith('"')
              ? "string"
              : /^(true|false|null)$/.test(part)
                ? "literal"
                : /^-?\d/.test(part)
                  ? "number"
                  : "";
          return (
            <span className={kind ? `json-${kind}` : undefined} key={i}>
              {part}
            </span>
          );
        })}
      </code>
    </pre>
  );
}
function BodyPane({ title, value, pending, empty, query, meta }) {
  const [copied, setCopied] = useState(false),
    [copyError, setCopyError] = useState(false);
  useEffect(() => {
    setCopied(false);
    setCopyError(false);
  }, [value]);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(JSON.stringify(value, null, 2));
      setCopied(true);
      setCopyError(false);
    } catch {
      setCopyError(true);
    }
  };
  return (
    <section className="body-pane" aria-label={title}>
      <div className="body-pane-head">
        <div>
          <strong>{title}</strong>
          <small>{meta}</small>
        </div>
        <Button
          variant="ghost"
          disabled={value === undefined || pending}
          onClick={copy}
          aria-label={`${title} 복사`}
        >
          <Copy size={14} />
          {copied ? "복사됨" : "JSON 복사"}
        </Button>
      </div>
      <div className="body-pane-content">
        {query && Object.keys(query).length > 0 && (
          <div className="query-block">
            <h4>Query parameters</h4>
            <JsonText value={query} />
          </div>
        )}
        {query && Object.keys(query).length > 0 && <h4>Body</h4>}
        {pending ? (
          <p className="body-empty">
            <LoaderCircle size={18} className="spin" />
            응답을 기다리고 있습니다…
          </p>
        ) : value === undefined || value === null ? (
          <p className="body-empty">{empty}</p>
        ) : (
          <JsonText value={value} />
        )}
        {copyError && (
          <p role="status" className="copy-error">
            복사 권한이 없습니다. JSON 텍스트를 선택해 복사하세요.
          </p>
        )}
      </div>
    </section>
  );
}
export function Diagnostics({ onClose }) {
  const logs = useSyncExternalStore(subscribeLogs, getLogs);
  const [search, setSearch] = useState(""),
    [filter, setFilter] = useState("all"),
    [follow, setFollow] = useState(true),
    [selected, setSelected] = useState(null),
    [maximized, setMaximized] = useState(false);
  useEffect(() => {
    document.body.classList.add("inspector-open");
    return () => document.body.classList.remove("inspector-open");
  }, []);
  useEffect(() => {
    const fn = (e) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", fn);
    return () => window.removeEventListener("keydown", fn);
  }, [onClose]);
  const visible = logs.filter(
    (l) =>
      (filter === "all" ||
        (filter === "error" && !l.pending && (l.status >= 400 || !l.status)) ||
        (filter === "pending" && l.pending)) &&
      (
        l.method +
        " " +
        l.path +
        " " +
        l.status +
        " " +
        (l.response?.code || "")
      )
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  const current =
    (follow ? visible[0] : visible.find((l) => l.id === selected)) ||
    visible[0];
  const params = {};
  if (current) {
    new URL(current.path, location.origin).searchParams.forEach((v, k) => {
      params[k] = Object.hasOwn(params, k) ? [].concat(params[k], v) : v;
    });
  }
  return (
    <aside
      id="api-inspector"
      className={`api-inspector ${maximized ? "maximized" : ""}`}
      aria-label="API 요청·응답 기록"
    >
      <div className="inspector-toolbar">
        <div className="row">
          <ArrowDownUp size={18} />
          <h2>API 요청 · 응답</h2>
          <Badge>{logs.length} / 60</Badge>
        </div>
        <div className="row">
          <button
            className={`follow-button ${follow ? "active" : ""}`}
            onClick={() => {
              setFollow(!follow);
              setSelected(current?.id);
            }}
            aria-pressed={follow}
          >
            <Radio size={15} />
            {follow ? "최신 요청 따라가기" : "선택한 기록 유지"}
          </button>
          <Button variant="ghost" onClick={clearLogs} disabled={!logs.length}>
            <Trash2 size={15} />
            비우기
          </Button>
          <Button
            variant="ghost"
            aria-label={maximized ? "기록 패널 축소" : "기록 패널 확대"}
            onClick={() => setMaximized(!maximized)}
          >
            {maximized ? <Minimize2 size={17} /> : <Maximize2 size={17} />}
          </Button>
          <Button variant="ghost" aria-label="API 기록 닫기" onClick={onClose}>
            <X size={21} />
          </Button>
        </div>
      </div>
      <div className="inspector-workspace">
        <section className="request-list" aria-label="요청 목록">
          <div className="inspector-filters">
            <label className="inspector-search">
              <Search size={15} />
              <input
                aria-label="API 기록 검색"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="경로·메서드·상태 검색"
              />
            </label>
            <select
              aria-label="API 기록 필터"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            >
              <option value="all">전체 요청</option>
              <option value="error">오류만</option>
              <option value="pending">응답 대기</option>
            </select>
          </div>
          <div className="request-list-scroll">
            {!visible.length ? (
              <p className="inspector-empty">
                {logs.length
                  ? "검색 조건에 맞는 요청이 없습니다."
                  : "화면의 기능을 실행하면 여기에 요청이 바로 표시됩니다."}
              </p>
            ) : (
              visible.map((l) => (
                <button
                  key={l.id}
                  className={`request-entry ${current?.id === l.id ? "selected" : ""}`}
                  aria-pressed={current?.id === l.id}
                  onClick={() => {
                    setSelected(l.id);
                    setFollow(false);
                  }}
                >
                  <div className="request-entry-top">
                    <b className={`method ${l.method.toLowerCase()}`}>
                      {l.method}
                    </b>
                    <Badge
                      tone={
                        !l.pending && (l.status >= 400 || !l.status)
                          ? "danger"
                          : ""
                      }
                    >
                      {l.pending ? "대기" : l.status || "ERR"}
                    </Badge>
                    <small>
                      {l.pending ? "요청 중" : `${l.ms.toLocaleString()} ms`}
                    </small>
                  </div>
                  <code>
                    {l.ai ? "/ai-api" : "/api"}
                    {l.path}
                  </code>
                  <small>{l.time}</small>
                </button>
              ))
            )}
          </div>
        </section>
        <div className="request-detail">
          {current ? (
            <>
              <div className="request-detail-head">
                <div>
                  <b>{current.method}</b>
                  <code>
                    {current.ai ? "/ai-api" : "/api"}
                    {current.path}
                  </code>
                </div>
                <div className="row">
                  <Badge
                    tone={
                      !current.pending &&
                      (current.status >= 400 || !current.status)
                        ? "danger"
                        : ""
                    }
                  >
                    {current.pending
                      ? "요청 중"
                      : current.status
                        ? `HTTP ${current.status}`
                        : "연결 실패"}
                  </Badge>
                  <span>{current.time}</span>
                  {!current.pending && (
                    <>
                      <span>{current.ms.toLocaleString()} ms</span>
                      <span>
                        {(current.responseBytes / 1024).toFixed(1)} KB
                      </span>
                    </>
                  )}
                </div>
              </div>
              <div className="body-columns" key={current.id}>
                <BodyPane
                  title="요청 본문"
                  value={current.request}
                  empty="요청 본문이 없습니다."
                  query={params}
                  meta={
                    current.request?.multipart
                      ? "multipart/form-data · 파일 정보"
                      : "Request body · JSON"
                  }
                />
                <BodyPane
                  title="응답 본문"
                  value={current.response}
                  pending={current.pending}
                  empty={
                    current.status === 204
                      ? "204 No Content · 정상 처리되었으며 응답 본문이 없습니다."
                      : "응답 본문이 없습니다."
                  }
                  meta={current.contentType || "Response body · JSON"}
                />
              </div>
            </>
          ) : (
            <div className="inspector-empty detail-empty">
              <ArrowDownUp size={30} />
              <h3>요청과 응답을 한눈에</h3>
              <p>
                요청을 실행하거나 왼쪽 기록을 선택하세요.
                <br />
                본문은 접지 않고 바로 보여드립니다.
              </p>
            </div>
          )}
        </div>
      </div>
      <div className="inspector-footnote">
        이 탭의 최근 60건 · 인증 정보·이메일·개인 메모는 [비공개] 표시 · 파일
        바이너리는 기록하지 않음
      </div>
    </aside>
  );
}
