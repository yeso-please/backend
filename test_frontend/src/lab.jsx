import React, { useEffect, useState } from "react";
import {
  Play,
  Activity,
  FlaskConical,
  Search,
  CheckCircle2,
} from "lucide-react";
import contract from "./generated/contract.json";
import { api, redact, query } from "./api";
import {
  Button,
  Panel,
  Field,
  Select,
  ErrorBox,
  Json,
  Notice,
  Badge,
  Loading,
  labels,
} from "./ui";
const aiOperations = [
  {
    id: "ai-health",
    title: "모델 준비 상태",
    method: "GET",
    path: "/health",
    body: null,
  },
  {
    id: "ai-embed",
    title: "프로필 임베딩 · template 1",
    method: "POST",
    path: "/embeddings",
    body: {
      requestId: "frontend-lab",
      modelVersion: "mminilm-l12-v1",
      templateVersion: 1,
      profile: {
        travelMbti: "INFP",
        scheduleDensity: "RELAXED",
        experienceTags: ["바다", "산책"],
        excludeTags: [],
        likedTrips: [],
      },
    },
  },
  {
    id: "ai-embed2",
    title: "프로필 임베딩 · template 2",
    method: "POST",
    path: "/embeddings",
    body: {
      requestId: "frontend-lab",
      modelVersion: "mminilm-l12-v1",
      templateVersion: 2,
      profile: {
        travelStyles: { 1: 3, 3: 4, 5: 5, 6: 4 },
        travelMotives: [1],
        likedRegions: ["강릉"],
      },
    },
  },
  {
    id: "ai-batch",
    title: "관광지 일괄 임베딩",
    method: "POST",
    path: "/embeddings/batch",
    body: {
      modelVersion: "mminilm-l12-v1",
      templateVersion: 2,
      items: [
        {
          id: "test-place",
          name: "테스트 해변",
          contentTypeId: "12",
          regionName: "테스트 지역",
          description: "실험용 입력: 바다를 바라보며 산책하는 장소",
        },
      ],
    },
  },
  {
    id: "ai-explain",
    title: "코스 소개 · 장소별 추천 이유",
    method: "POST",
    path: "/explanations",
    body: {
      requestId: "frontend-lab",
      regionName: "테스트 지역",
      days: 1,
      places: [
        {
          id: "test-place",
          name: "테스트 해변",
          regionName: "테스트 지역",
          description: "실험용 입력: 바다를 바라보며 산책하는 장소",
          day: 1,
          order: 1,
          matchedFeatures: ["산책"],
        },
      ],
    },
  },
];
export function Lab({ user }) {
  const [tab, setTab] = useState("backend"),
    [search, setSearch] = useState(""),
    [chosen, setChosen] = useState(contract[0]),
    [path, setPath] = useState(contract[0].path),
    [body, setBody] = useState(JSON.stringify(contract[0].body, null, 2) || ""),
    [files, setFiles] = useState([]),
    [result, setResult] = useState(undefined),
    [error, setError] = useState(null),
    [busy, setBusy] = useState(false),
    [health, setHealth] = useState(null),
    [profile, setProfile] = useState(null),
    [prefs, setPrefs] = useState(null),
    [tripId, setTripId] = useState(""),
    [checks, setChecks] = useState([]);
  const choose = (o) => {
    setChosen(o);
    setPath(o.path);
    setBody(o.body ? JSON.stringify(o.body, null, 2) : "");
    setResult(undefined);
    setError(null);
    setFiles([]);
  };
  const inspect = async () => {
    setBusy(true);
    const list = [];
    for (const [name, fn] of [
      ["백엔드 공개 API", () => api("/onboarding/questions")],
      ["AI 모델 상태", () => api("/health", { ai: true })],
      ...(user
        ? [
            ["내 취향 상태", () => api("/onboarding/me")],
            ["추천 기본값", () => api("/me/preferences")],
          ]
        : []),
    ]) {
      try {
        const r = await fn();
        list.push({ name, ok: true, data: r });
        if (name === "AI 모델 상태") setHealth(r);
        if (name === "내 취향 상태") setProfile(r);
        if (name === "추천 기본값") setPrefs(r);
      } catch (e) {
        list.push({ name, ok: false, error: e.message });
      }
    }
    setChecks(list);
    setBusy(false);
  };
  const send = async () => {
    setError(null);
    setResult(undefined);
    setBusy(true);
    try {
      if (/[{}]/.test(path))
        throw new Error(
          "경로의 {tripId}, {token} 등을 실제 값으로 바꿔 주세요.",
        );
      if (!path.startsWith("/") || path.startsWith("//"))
        throw new Error("/로 시작하는 API 경로를 입력하세요.");
      let payload;
      if (chosen.multipart) {
        payload = new FormData();
        if (!files.length) throw new Error("업로드할 사진을 선택하세요.");
        files.forEach((f) => payload.append("files", f));
      } else if (!["GET", "DELETE"].includes(chosen.method) && body.trim())
        payload = JSON.parse(body);
      if (
        chosen.method === "DELETE" &&
        !confirm(
          "이 요청은 서버 데이터를 삭제하거나 관계·링크를 폐기합니다. 실행할까요?",
        )
      )
        return;
      setResult(
        await api(path, {
          method: chosen.method,
          body: payload,
          ai: tab === "ai",
        }),
      );
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const importCourse = async () => {
    setError(null);
    setBusy(true);
    try {
      if (!/^\d+$/.test(tripId)) throw new Error("여행 ID를 입력하세요.");
      const c = await api(`/courses/${tripId}`);
      const places = [];
      for (const d of c.days)
        for (const p of d.items.filter((x) => x.type === "ATTRACTION")) {
          const detail = await api(`/attractions/${p.attractionId}`);
          places.push({
            id: String(p.attractionId),
            name: p.name,
            regionName: c.regionName,
            description: detail.description || "",
            day: d.dayIndex + 1,
            order: places.length + 1,
            matchedFeatures: [],
          });
        }
      choose(aiOperations[4]);
      setBody(
        JSON.stringify(
          {
            requestId: crypto.randomUUID(),
            regionName: c.regionName,
            days: c.days.length,
            places,
          },
          null,
          2,
        ),
      );
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">API & AI OBSERVATORY</div>
          <h1>응답까지 들여다보는 실험실</h1>
          <p>문서 기반 API 탐색과 AI 단독 실험을 한곳에서.</p>
        </div>
        <Button onClick={inspect} disabled={busy}>
          <Activity size={17} />
          연결 · 상태 점검
        </Button>
      </div>
      <div className="health-grid">
        {checks.length ? (
          checks.map((c) => (
            <Panel key={c.name}>
              <Badge tone={c.ok ? "" : "danger"}>
                {c.ok ? "응답 수신" : "실패"}
              </Badge>
              <h3>{c.name}</h3>
              <p>
                {c.error ||
                  c.data?.status ||
                  c.data?.submission?.tasteStatus ||
                  c.data?.courseTasteMode ||
                  c.data?.questionVersion}
              </p>
              <Json data={c.data} />
            </Panel>
          ))
        ) : (
          <Notice>
            상태 점검으로 백엔드 연결, AI 모델 버전·차원, 내 취향 상태를
            확인하세요.
          </Notice>
        )}
      </div>
      {health && (
        <Notice>
          AI 모델: <b>{health.modelVersion}</b> · {health.dimension ?? "미준비"}
          차원 · 템플릿 {health.templateVersions?.join(", ")} · {health.status}.
          백엔드 기본 설정은 <code>demo-embedding-v1</code>, AI 기본값은{" "}
          <code>mminilm-l12-v1</code>입니다. 백엔드의 EMBEDDING_MODEL_VERSION을
          실행 중인 AI 모델 버전과 맞춰야 합니다.
        </Notice>
      )}
      {prefs && (
        <Panel>
          <div className="row spread">
            <h3>내 코스 추천 기본값</h3>
            <Select
              value={prefs.courseTasteMode}
              options={["TASTE", "RANDOM"]}
              onChange={async (v) => {
                try {
                  setPrefs(
                    await api("/me/preferences", {
                      method: "PATCH",
                      body: { courseTasteMode: v },
                    }),
                  );
                } catch (e) {
                  setError(e);
                }
              }}
            />
          </div>
        </Panel>
      )}
      <div className="tabs">
        {[
          ["backend", `백엔드 API ${contract.length}개`],
          ["ai", "AI 단독 실험"],
        ].map(([v, t]) => (
          <button
            key={v}
            className={tab === v ? "active" : ""}
            onClick={() => {
              setTab(v);
              choose(v === "ai" ? aiOperations[0] : contract[0]);
              setSearch("");
            }}
          >
            {t}
          </button>
        ))}
      </div>
      {tab === "ai" && (
        <Notice>
          AI 서버 직접 호출은 진단용입니다. 소개문·RAG 결과는 현재 백엔드 Course
          응답에 저장되지 않습니다. 예시 입력은 테스트 데이터이며 실제 관광
          정보로 사용하지 않습니다. 배포 시 서버 측 AI_URL과 ENABLE_AI_LAB
          설정이 필요합니다.
        </Notice>
      )}
      <div className="lab-layout">
        <Panel className="endpoint-list">
          <Field
            label="기능·경로 검색"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="코스, 공유, /trips…"
          />
          {(tab === "ai" ? aiOperations : contract)
            .filter((o) =>
              (o.title + " " + o.path + " " + o.section)
                .toLowerCase()
                .includes(search.toLowerCase()),
            )
            .map((o) => (
              <button
                key={o.id}
                className={chosen.id === o.id ? "selected" : ""}
                onClick={() => choose(o)}
              >
                <span className={`method ${o.method.toLowerCase()}`}>
                  {o.method}
                </span>
                <span>
                  <b>
                    {o.section} {o.title}
                  </b>
                  <small>{o.path.split("?")[0]}</small>
                </span>
              </button>
            ))}
        </Panel>
        <div className="stack">
          <Panel>
            <div className="row spread">
              <h2>{chosen.title}</h2>
              <Badge>{chosen.method}</Badge>
            </div>
            <Field
              label="API 경로 · 경로 변수와 쿼리를 실제 값으로 바꾸세요"
              value={path}
              onChange={(e) => setPath(e.target.value)}
            />
            {tab === "ai" && chosen.id === "ai-explain" && (
              <div className="row">
                <Field
                  label="실제 여행 ID"
                  value={tripId}
                  onChange={(e) => setTripId(e.target.value)}
                />
                <Button
                  variant="secondary"
                  disabled={busy || !user}
                  onClick={importCourse}
                >
                  현재 코스·상세 설명 가져오기
                </Button>
              </div>
            )}
            {!["GET", "DELETE"].includes(chosen.method) &&
              (chosen.multipart ? (
                <Field label="files · multipart 사진 업로드">
                  <input
                    type="file"
                    multiple
                    accept="image/jpeg,image/png,image/webp"
                    onChange={(e) => setFiles([...e.target.files])}
                  />
                </Field>
              ) : (
                <Field label="JSON 요청 본문 (빈 칸이면 본문 없이 전송)">
                  <textarea
                    className="code-editor"
                    rows={14}
                    value={body}
                    onChange={(e) => setBody(e.target.value)}
                    spellCheck={false}
                  />
                </Field>
              ))}
            <Notice>
              이곳의 호출은 실제 서버 데이터를 사용합니다. 예시
              ID·날짜·version은 현재 응답에 맞게 바꿔야 합니다. 정상 사용자
              흐름은 여행 화면에서 테스트하세요.
            </Notice>
            <Button disabled={busy} onClick={send}>
              <Play size={16} />
              {busy ? "응답 대기 중…" : "요청 실행"}
            </Button>
            <ErrorBox error={error} />
            {result !== undefined && (
              <div className="response">
                <h3>서버 응답 {result === null ? "· 본문 없는 성공" : ""}</h3>
                {result?.aiGenerated && <Badge>AI가 작성</Badge>}
                {result?.title && <h2>{result.title}</h2>}
                {result?.intro && <p>{result.intro}</p>}
                {result?.dimension && <VectorInfo result={result} />}
                <pre>{JSON.stringify(redact(result), null, 2)}</pre>
              </div>
            )}
          </Panel>
          {tab === "backend" && (
            <Panel>
              <h3>
                계약 원문 · {chosen.source}.md §{chosen.section}
              </h3>
              <details>
                <summary>요청 제약 · 응답 · 오류 확인</summary>
                <pre className="contract-doc">{chosen.doc}</pre>
              </details>
            </Panel>
          )}
        </div>
      </div>
    </>
  );
}
function VectorInfo({ result }) {
  const encoded = result.embeddingBase64 || result.items?.[0]?.embeddingBase64;
  if (!encoded) return null;
  try {
    const bin = atob(encoded),
      bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0)),
      v = new DataView(bytes.buffer);
    let norm = 0;
    for (let i = 0; i < bytes.length; i += 4)
      norm += v.getFloat32(i, true) ** 2;
    return (
      <Notice>
        차원 {result.dimension} · 바이트 {bytes.length} · L2 norm{" "}
        {Math.sqrt(norm).toFixed(6)} ·{" "}
        {bytes.length === result.dimension * 4
          ? "float32 길이 일치"
          : "길이 불일치"}
        {result.items && ` · 배치 ${result.items.length}건 중 첫 벡터`}
      </Notice>
    );
  } catch {
    return <Notice>벡터 디코딩에 실패했습니다.</Notice>;
  }
}
