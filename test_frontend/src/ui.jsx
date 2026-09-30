import React, { useEffect, useState, useRef } from "react";
import { geoMercator, geoPath } from "d3-geo";
import { feature } from "topojson-client";
import { MapPin, LoaderCircle, X, RefreshCw } from "lucide-react";
export const labels = {
  RELAXED: "여유롭게 · 하루 목표 4곳",
  PACKED: "알차게 · 하루 목표 6곳",
  TASTE: "취향 반영 랜덤",
  RANDOM: "완전 랜덤",
  PERSONALIZED: "취향 기반 추천",
  TOUR_OFFICIAL: "TourAPI 공식 코스 폴백",
  RULE_BASED: "규칙 기반 폴백",
  WALK: "도보",
  CAR: "자동차",
  PUBLIC_TRANSIT: "대중교통",
  READY: "취향 분석 준비 완료",
  PENDING: "취향 분석 재시도 대기",
  FAILED: "취향 분석 실패 · 폴백 사용",
  NATURE: "자연",
  HISTORY_CULTURE: "역사·문화",
  ACTIVITY: "체험·레포츠",
  WALK_REST: "산책·휴식",
  ETC: "기타",
  PRIVATE: "나만 보기",
  FRIENDS: "친구 공개",
  LINK: "링크 공개",
  CITY: "시군구",
  EXACT: "정확한 위치",
  HIDDEN: "위치 숨김",
  DRAFT: "초안",
  PUBLISHED: "발행됨",
};
export const warnings = {
  ROUTE_TIME_ESTIMATED:
    "이동시간은 직선거리 기반 추정이며 실제 길찾기가 아닙니다.",
  PERSONALIZATION_FALLBACK:
    "취향 벡터를 사용할 수 없어 대체 방식으로 코스를 만들었습니다.",
  DENSITY_TARGET_NOT_MET:
    "관광지 후보가 부족하여 목표보다 적은 장소가 배치되었습니다.",
  TITLE_GENERATION_FAILED: "AI 제목 생성에 실패하여 규칙 제목을 사용했습니다.",
  ATTRACTION_NO_LONGER_RECOMMENDABLE:
    "추천 기준을 충족하지 않는 장소가 있습니다. 교체를 검토하세요.",
  ALL_CONDITIONS_IGNORED: "적용 가능한 조건이 없어 균등 추첨했습니다.",
};
export function Button({ children, variant = "", ...props }) {
  return (
    <button className={`btn ${variant}`} type="button" {...props}>
      {children}
    </button>
  );
}
export function Field({ label, children, ...props }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children || <input {...props} />}
    </label>
  );
}
export function Select({ value, onChange, options, ...props }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} {...props}>
      {options.map((o) => (
        <option
          key={typeof o === "string" ? o : o.value}
          value={typeof o === "string" ? o : o.value}
        >
          {typeof o === "string" ? labels[o] || o : o.label}
        </option>
      ))}
    </select>
  );
}
export function Panel({ children, className = "" }) {
  return <section className={`panel ${className}`}>{children}</section>;
}
export function Badge({ children, tone = "" }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}
export function Empty({ title = "아직 표시할 내용이 없어요", children }) {
  return (
    <div className="empty">
      <MapPin size={30} />
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}
export function Json({ data, title = "응답 원문" }) {
  return (
    <details className="json">
      <summary>{title}</summary>
      <pre>{JSON.stringify(data, null, 2)}</pre>
    </details>
  );
}
export function ErrorBox({ error, onRetry }) {
  if (!error) return null;
  return (
    <div className="error" role="alert">
      <strong>
        {error.code || "오류"} · {error.status || "연결 실패"}
      </strong>
      <p>{error.message}</p>
      {error.code === "TRIP_VERSION_CONFLICT" && (
        <p>
          다른 참여자가 여행을 수정했습니다. 최신 여행을 다시 불러온 뒤 변경
          내용을 확인하고 재시도하세요.
        </p>
      )}
      {error.data?.fieldErrors?.map((f, i) => (
        <p key={i}>
          {f.field}: {f.message}
        </p>
      ))}
      {error.data?.details && (
        <Json data={error.data.details} title="상세 원인" />
      )}
      {onRetry && (
        <Button variant="secondary" onClick={onRetry}>
          <RefreshCw size={15} />
          다시 불러오기
        </Button>
      )}
    </div>
  );
}
export function Loading() {
  return (
    <div className="loading">
      <LoaderCircle className="spin" /> 서버 응답을 기다리는 중입니다
    </div>
  );
}
export function Notice({ children }) {
  return <div className="notice">{children}</div>;
}
export function Modal({ title, onClose, children }) {
  const modalRef = useRef(null),
    closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const previous = document.activeElement;
    const f = (e) => {
      if (e.key === "Escape") closeRef.current();
      if (e.key === "Tab") {
        const controls = [
          ...modalRef.current.querySelectorAll(
            'button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),summary,[tabindex="0"]',
          ),
        ];
        const first = controls[0],
          last = controls.at(-1);
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last?.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first?.focus();
        }
      }
    };
    document.addEventListener("keydown", f);
    const old = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", f);
      document.body.style.overflow = old;
      previous?.focus();
    };
  }, []);
  return (
    <div className="overlay" onClick={onClose}>
      <section
        ref={modalRef}
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="row spread">
          <h2>{title}</h2>
          <Button autoFocus variant="ghost" aria-label="닫기" onClick={onClose}>
            <X />
          </Button>
        </div>
        {children}
      </section>
    </div>
  );
}
export function Tags({ options, value, onChange, max = 99 }) {
  return (
    <div className="chips">
      {options.map((t) => (
        <button
          type="button"
          key={t}
          className={`chip ${value.includes(t) ? "active" : ""}`}
          aria-pressed={value.includes(t)}
          disabled={!value.includes(t) && value.length >= max}
          onClick={() =>
            onChange(
              value.includes(t) ? value.filter((x) => x !== t) : [...value, t],
            )
          }
        >
          {t}
        </button>
      ))}
    </div>
  );
}
export function Photo({ src, alt = "", className = "" }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [src]);
  return src && !failed ? (
    <img
      className={`photo ${className}`}
      src={src}
      alt={alt}
      onError={() => setFailed(true)}
    />
  ) : (
    <div className={`photo placeholder ${className}`}>
      <MapPin size={24} />
      <span>사진 준비 중</span>
    </div>
  );
}
let geoPromise;
export function KoreaMap({ regions = [], selected, onSelect, pins = [] }) {
  const [geo, setGeo] = useState(null),
    [error, setError] = useState(false);
  useEffect(() => {
    geoPromise ??= fetch("/geo/sig.json")
      .then((r) => r.json())
      .then((t) => feature(t, Object.values(t.objects)[0]));
    geoPromise.then(setGeo).catch(() => setError(true));
  }, []);
  const projection = geo
    ? geoMercator().fitExtent(
        [
          [28, 26],
          [552, 565],
        ],
        geo,
      )
    : null;
  const path = projection ? geoPath(projection) : null;
  const normalize = (code) =>
    code === "47720"
      ? "27720"
      : code.startsWith("42")
        ? "51" + code.slice(2)
        : code.startsWith("45")
          ? "52" + code.slice(2)
          : code;
  const regionMap = new Map(regions.map((r) => [r.sigCd, r]));
  return (
    <div className="map">
      <div className="map-label">
        대한민국 <span>지역을 선택해 살펴보세요</span>
      </div>
      {error ? (
        <Empty title="지도 경계를 불러오지 못했습니다" />
      ) : !geo ? (
        <Loading />
      ) : (
        <svg viewBox="0 0 580 600" aria-label="대한민국 시군구 지도">
          {geo.features.map((f, i) => {
            const code = normalize(f.properties.SIG_CD);
            const r = regionMap.get(code);
            return (
              <path
                key={i}
                d={path(f)}
                className={`${selected === code ? "selected" : ""} ${r?.drawEligible ? "eligible" : ""}`}
                role={onSelect ? "button" : undefined}
                tabIndex={onSelect && r ? 0 : undefined}
                aria-label={`${r ? `${r.province} ${r.city}` : f.properties.SIG_KOR_NM}${r?.drawEligible ? " · 추첨 가능" : ""}`}
                onClick={() => r && onSelect?.(code)}
                onKeyDown={(e) => {
                  if (["Enter", " "].includes(e.key) && r) {
                    e.preventDefault();
                    onSelect?.(code);
                  }
                }}
              >
                <title>
                  {r ? `${r.province} ${r.city}` : f.properties.SIG_KOR_NM}
                </title>
              </path>
            );
          })}
          {pins
            .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lng))
            .map((p, i) => {
              const [x, y] = projection([p.lng, p.lat]);
              return (
                <g
                  key={p.diaryId || i}
                  transform={`translate(${x},${y})`}
                  role="button"
                  tabIndex={0}
                  onClick={() => p.onClick?.()}
                  onKeyDown={(e) => e.key === "Enter" && p.onClick?.()}
                >
                  <circle
                    r="11"
                    fill="#e77946"
                    stroke="white"
                    strokeWidth="3"
                  />
                  <title>{p.title}</title>
                </g>
              );
            })}
        </svg>
      )}
      <div className="map-key">
        <span />
        추첨 가능 지역 <span className="chosen" />
        선택한 지역 <small>경계: 데모의 2022 자료 · 코드 보정 적용</small>
      </div>
    </div>
  );
}
export function RouteMap({ items = [] }) {
  const points = items.filter(
    (i) =>
      i.type === "ATTRACTION" &&
      Number.isFinite(i.lat) &&
      Number.isFinite(i.lng),
  );
  if (!points.length) return <Empty title="동선 좌표가 없습니다" />;
  const xs = points.map((p) => p.lng),
    ys = points.map((p) => p.lat);
  const minX = Math.min(...xs),
    minY = Math.min(...ys);
  const dx = Math.max(...xs) - minX || 0.02,
    dy = Math.max(...ys) - minY || 0.02;
  const xy = points.map((p) => [
    35 + ((p.lng - minX) / dx) * 330,
    220 - ((p.lat - minY) / dy) * 180,
  ]);
  return (
    <div className="route-map">
      <svg viewBox="0 0 400 255" aria-label="장소 순서와 직선 동선">
        <polyline
          points={xy.map((p) => p.join(",")).join(" ")}
          fill="none"
          stroke="#248176"
          strokeWidth="2"
          strokeDasharray="5 5"
        />
        {xy.map(([x, y], i) => (
          <g key={i} transform={`translate(${x},${y})`}>
            <circle r="14" fill="#183e3b" />
            <text textAnchor="middle" dy="5" fill="white" fontSize="12">
              {i + 1}
            </text>
            <title>{points[i].name}</title>
          </g>
        ))}
      </svg>
      <small>실제 도로 경로가 아닌 좌표 연결 개요입니다.</small>
    </div>
  );
}
