import React, { useEffect, useState } from "react";
import {
  CalendarDays,
  MapPin,
  Shuffle,
  Plus,
  RefreshCw,
  Users,
  ArrowUp,
  ArrowDown,
  Search,
  Settings2,
} from "lucide-react";
import { api, query, tomorrow, seoulToday, addDays } from "./api";
import { navigate } from "./main";
import {
  Button,
  Panel,
  Field,
  Select,
  ErrorBox,
  Empty,
  Loading,
  Badge,
  Json,
  Notice,
  Photo,
  KoreaMap,
  RouteMap,
  Modal,
  labels,
  warnings,
} from "./ui";
import { Links } from "./social";
const categories = [
  "NATURE",
  "HISTORY_CULTURE",
  "ACTIVITY",
  "WALK_REST",
  "ETC",
];
export function Discover({ user }) {
  const [date, setDate] = useState(tomorrow()),
    [nights, setNights] = useState(1),
    [transport, setTransport] = useState("CAR"),
    [density, setDensity] = useState("RELAXED"),
    [regions, setRegions] = useState([]),
    [selected, setSelected] = useState(""),
    [card, setCard] = useState(null),
    [mode, setMode] = useState("RANDOM"),
    [conditions, setConditions] = useState(["MY_TASTE"]),
    [lat, setLat] = useState(""),
    [lng, setLng] = useState(""),
    [error, setError] = useState(null),
    [cardError, setCardError] = useState(null),
    [busy, setBusy] = useState(false),
    [check, setCheck] = useState(null),
    [unavailable, setUnavailable] = useState([]),
    [created, setCreated] = useState(null);
  useEffect(() => {
    let live = true;
    api("/regions?" + query({ days: nights + 1, scheduleDensity: density }))
      .then((r) => live && setRegions(r.regions))
      .catch((e) => live && setError(e));
    return () => {
      live = false;
    };
  }, [nights, density]);
  useEffect(() => {
    if (!selected) return;
    let live = true;
    setCard(null);
    setCardError(null);
    api(`/regions/${selected}/card`)
      .then((r) => live && setCard(r))
      .catch((e) => live && setCardError(e));
    return () => {
      live = false;
    };
  }, [selected]);
  useEffect(() => {
    setCheck(null);
    if (!date) return;
    let live = true;
    const end = new Date(`${date}T00:00:00Z`);
    end.setUTCDate(end.getUTCDate() + nights);
    api(
      "/trips/unavailable-dates?" +
        query({ from: date, to: end.toISOString().slice(0, 10) }),
    )
      .then((r) => live && setUnavailable(r))
      .catch((e) => live && setError(e));
    return () => {
      live = false;
    };
  }, [date, nights]);
  const validate = async () => {
    const r = await api("/trips/context/check", {
      method: "POST",
      body: { startDate: date, nights },
    });
    setCheck(r);
    return r;
  };
  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      if ((lat === "") !== (lng === ""))
        throw new Error("출발지 위도와 경도를 함께 입력하세요.");
      let trip = created;
      if (!trip) {
        const r = await validate();
        if (!r.available) return;
        trip = await api("/trips", {
          method: "POST",
          body: {
            startDate: date,
            nights,
            transport,
            ...(lat !== ""
              ? { originLat: Number(lat), originLng: Number(lng) }
              : {}),
          },
        });
        setCreated(trip);
      }
      try {
        const drawResult = await api(`/trips/${trip.id}/region`, {
          method: "POST",
          body: {
            mode,
            conditions: mode === "CONDITIONAL" ? conditions : [],
            ...(mode === "MANUAL" ? { sigCd: selected } : {}),
            scheduleDensity: density,
            version: trip.version,
          },
        });
        sessionStorage.setItem(
          `draw-result-${trip.id}`,
          JSON.stringify(drawResult),
        );
        navigate(`/course/${trip.id}`);
      } catch (e) {
        setError(e);
      }
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const r = regions.find((x) => x.sigCd === selected);
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">DISCOVER YOUR NEXT CHAPTER</div>
          <h1>
            어디든 좋아요.
            <br />
            <em>나답게 떠나면 되니까.</em>
          </h1>
          <p>날짜를 고르고, 새로운 지역을 발견해 보세요.</p>
        </div>
        <div className="stat">
          <strong>{regions.filter((r) => r.drawEligible).length}</strong>
          <span>이번 일정으로 떠날 수 있는 지역</span>
        </div>
      </div>
      {!user.onboardingCompleted && (
        <Notice>
          <Button onClick={() => navigate("/onboarding")}>
            여행 취향 설문 먼저 완료하기
          </Button>
        </Notice>
      )}
      <div className="discover-layout">
        <div>
          <KoreaMap
            regions={regions}
            selected={selected}
            onSelect={setSelected}
          />
          <div className="map-below">
            <span>지도에서 지역을 골라 소개를 볼 수 있습니다.</span>
            <span>
              {nights + 1}일 · {labels[density]}
            </span>
          </div>
          {selected && (
            <Panel className="region-card">
              <ErrorBox error={cardError} />
              {card ? (
                <>
                  <Photo src={card.heroImage?.url} alt={card.city} />
                  <div>
                    <Badge>{card.province}</Badge>
                    <h2>{card.city}</h2>
                    <h3>{card.title}</h3>
                    {card.introduction?.map((p, i) => (
                      <p key={i}>{p}</p>
                    ))}
                    <div className="chips">
                      {card.characteristics?.map((t) => (
                        <Badge key={t}>{t}</Badge>
                      ))}
                    </div>
                    <small>
                      {card.heroImage?.sourceName} · {card.heroImage?.license}
                    </small>
                    <Sources items={card.sources} />
                    <Button
                      variant="secondary"
                      disabled={!r?.drawEligible}
                      onClick={() => setMode("MANUAL")}
                    >
                      이 지역 직접 선택
                    </Button>
                    <Json data={card} />
                  </div>
                </>
              ) : (
                !cardError && <Loading />
              )}
              {r && !r.drawEligible && (
                <Notice>추첨 불가: {r.ineligibleReasons?.join(", ")}</Notice>
              )}
            </Panel>
          )}
        </div>
        <div className="stack">
          <Panel>
            <div className="section-head">
              <CalendarDays size={20} />
              <h2>이번 여행의 시작</h2>
            </div>
            <fieldset disabled={!!created || busy}>
              <div className="two-col">
                <Field
                  label="출발일"
                  type="date"
                  value={date}
                  onChange={(e) => setDate(e.target.value)}
                  required
                />
                <Field label="여행 기간">
                  <Select
                    value={nights}
                    onChange={(x) => setNights(Number(x))}
                    options={Array.from({ length: 7 }, (_, i) => ({
                      value: i,
                      label: i === 0 ? "당일치기" : `${i}박 ${i + 1}일`,
                    }))}
                  />
                </Field>
              </div>
              <Field label="이동수단">
                <Select
                  value={transport}
                  onChange={setTransport}
                  options={["CAR", "WALK", "PUBLIC_TRANSIT"]}
                />
              </Field>
            </fieldset>
            <Field label="여행 속도">
              <Select
                value={density}
                onChange={setDensity}
                options={["RELAXED", "PACKED"]}
              />
            </Field>
            <details>
              <summary>출발 위치 설정 · 거리 조건에 사용</summary>
              <div className="two-col">
                <Field
                  label="위도"
                  type="number"
                  step="any"
                  value={lat}
                  onChange={(e) => setLat(e.target.value)}
                  disabled={!!created}
                />
                <Field
                  label="경도"
                  type="number"
                  step="any"
                  value={lng}
                  onChange={(e) => setLng(e.target.value)}
                  disabled={!!created}
                />
              </div>
              <Button
                variant="ghost"
                disabled={!!created}
                onClick={() =>
                  navigator.geolocation?.getCurrentPosition(
                    (p) => {
                      setLat(String(p.coords.latitude));
                      setLng(String(p.coords.longitude));
                    },
                    (e) => setError(e),
                  )
                }
              >
                현재 위치 사용
              </Button>
            </details>
            {date && addDays(date, nights) < seoulToday() && (
              <Notice>
                이미 끝난 날짜입니다. 지난 여행 기록으로 만들어지고, 여행기를
                쓰기 전까지 지역과 코스를 정할 수 있습니다. 친구 초대는 할 수
                없습니다.
              </Notice>
            )}
            {unavailable.length > 0 && (
              <Notice>
                선택한 기간에 기존 여행 {unavailable.length}개가 있습니다.
                {unavailable.map((t) => (
                  <p key={t.tripId}>
                    {t.startDate} ~ {t.endDate}
                  </p>
                ))}
              </Notice>
            )}
            <Button
              variant="secondary wide"
              disabled={busy || !date}
              onClick={async () => {
                setError(null);
                try {
                  await validate();
                } catch (e) {
                  setError(e);
                }
              }}
            >
              날짜 중복 확인
            </Button>
            {check && (
              <Notice>
                {check.available
                  ? "사용 가능한 날짜입니다."
                  : "기존 여행과 겹칩니다."}{" "}
                · 추첨 가능 {check.eligibleRegionCount}곳
                {check.conflicts?.map((t) => (
                  <p key={t.tripId}>
                    {t.title} · {t.startDate} ~ {t.endDate}
                  </p>
                ))}
              </Notice>
            )}
          </Panel>
          <Panel className="draw-panel">
            <h2>어떻게 발견할까요?</h2>
            <div className="segmented">
              {[
                ["RANDOM", "완전 랜덤"],
                ["CONDITIONAL", "조건 랜덤"],
                ["MANUAL", "직접 선택"],
              ].map(([v, t]) => (
                <button
                  key={v}
                  className={mode === v ? "active" : ""}
                  onClick={() => setMode(v)}
                >
                  {t}
                </button>
              ))}
            </div>
            {mode === "RANDOM" ? (
              <p>추첨 가능한 모든 지역에서 같은 확률로 골라요.</p>
            ) : mode === "CONDITIONAL" ? (
              <div className="stack">
                {[
                  ["MY_TASTE", "내 취향 반영"],
                  ["DISTANCE", "출발지에서 가까울수록"],
                ].map(([v, t]) => (
                  <label className="check" key={v}>
                    <input
                      type="checkbox"
                      checked={conditions.includes(v)}
                      onChange={(e) =>
                        setConditions(
                          e.target.checked
                            ? [...conditions, v]
                            : conditions.filter((x) => x !== v),
                        )
                      }
                    />
                    {t}
                  </label>
                ))}
                <small>
                  거리 상한이 아닌 확률 가중치입니다. 적용되지 않은 조건은
                  결과에 표시됩니다.
                </small>
              </div>
            ) : (
              <Field label="직접 선택할 지역">
                <Select
                  value={selected}
                  onChange={setSelected}
                  options={[
                    { value: "", label: "지역을 선택하세요" },
                    ...regions
                      .filter((r) => r.drawEligible)
                      .map((r) => ({
                        value: r.sigCd,
                        label: `${r.province} ${r.city}`,
                      })),
                  ]}
                />
              </Field>
            )}
            <ErrorBox error={error} />
            {created && (
              <Notice>
                여행 #{created.id}는 생성되었습니다. 지역 선택만 다시 시도하거나{" "}
                <button
                  className="text-link"
                  onClick={() => navigate(`/course/${created.id}`)}
                >
                  생성한 여행 열기
                </button>
                를 선택하세요.
              </Notice>
            )}
            <Button
              variant="wide"
              disabled={
                busy ||
                !user.onboardingCompleted ||
                !date ||
                (mode === "MANUAL" && !r?.drawEligible) ||
                (mode === "CONDITIONAL" && !conditions.length)
              }
              onClick={create}
            >
              <Shuffle size={18} />
              {busy
                ? "여행을 준비하는 중…"
                : created
                  ? "지역 선택 다시 시도"
                  : "여행 만들고 지역 발견하기"}
            </Button>
            <small>
              만드는 즉시 내 여행에 저장되고 해당 날짜가 예약됩니다.
            </small>
          </Panel>
        </div>
      </div>
    </>
  );
}
export function Sources({ items = [] }) {
  return (
    <div className="sources">
      {items.map((s, i) =>
        s.url && /^https?:\/\//.test(s.url) ? (
          <a key={i} href={s.url} target="_blank" rel="noreferrer">
            {s.name || s.title || "출처"}
          </a>
        ) : (
          <span key={i}>{s.name || s.title}</span>
        ),
      )}
    </div>
  );
}
export function Trips() {
  const [trips, setTrips] = useState(null),
    [period, setPeriod] = useState(""),
    [error, setError] = useState(null);
  const load = async () => {
    setError(null);
    try {
      setTrips(await api("/trips?" + query({ period })));
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
  }, [period]);
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">MY JOURNEYS</div>
          <h1>차곡차곡, 나의 여행</h1>
          <p>함께 만드는 여행과 다녀온 기억을 한곳에서.</p>
        </div>
        <Button onClick={() => navigate("/")}>
          <Plus size={18} />새 여행
        </Button>
      </div>
      <div className="row spread">
        <div className="tabs">
          {[
            ["", "전체"],
            ["UPCOMING", "다가오는 여행"],
            ["PAST", "지난 여행"],
          ].map(([v, t]) => (
            <button
              className={period === v ? "active" : ""}
              key={v}
              onClick={() => setPeriod(v)}
            >
              {t}
            </button>
          ))}
        </div>
        <Button variant="ghost" onClick={load}>
          <RefreshCw size={16} />
          새로고침
        </Button>
      </div>
      <ErrorBox error={error} onRetry={load} />
      {!trips ? (
        !error && <Loading />
      ) : !trips.length ? (
        <Empty title="첫 번째 여행을 만들어 보세요">
          지역을 발견하고 나만의 코스를 만들 수 있어요.
        </Empty>
      ) : (
        <div className="trip-grid">
          {trips.map((t) => (
            <Panel key={t.tripId} className="trip-card">
              <div className="trip-card-top">
                <MapPin size={29} />
                <Badge>
                  {t.endDate < seoulToday()
                    ? t.retroactive
                      ? "지난 여행 기록"
                      : "지난 여행"
                    : t.hasCourse
                      ? "코스 준비됨"
                      : "여행 준비 중"}
                </Badge>
              </div>
              <small>{t.regionName || "지역 선택 전"}</small>
              <h2>{t.title}</h2>
              <p>
                {t.startDate} — {t.endDate}
              </p>
              <div className="row muted">
                <Users size={16} />
                {t.participants?.map((p) => p.nickname).join(", ")}
              </div>
              <div className="row">
                <Button onClick={() => navigate(`/course/${t.tripId}`)}>
                  여행 열기
                </Button>
                {t.myDiaryId ? (
                  <Button
                    variant="secondary"
                    onClick={() => navigate(`/diary/${t.myDiaryId}`)}
                  >
                    내 여행기
                  </Button>
                ) : (
                  t.endDate < seoulToday() && (
                    <Button
                      variant="secondary"
                      onClick={async () => {
                        try {
                          const d = await api(`/courses/${t.tripId}/diary`, {
                            method: "POST",
                            body: {},
                          });
                          navigate(`/diary/${d.diaryId}`);
                        } catch (e) {
                          if (e.code === "DIARY_ALREADY_EXISTS_FOR_TRIP")
                            navigate(`/diary/${e.data.details.diaryId}`);
                          else setError(e);
                        }
                      }}
                    >
                      여행기 쓰기
                    </Button>
                  )
                )}
              </div>
            </Panel>
          ))}
        </div>
      )}
    </>
  );
}
export function Course({ id, user, sharedCourse }) {
  const [context, setContext] = useState(null),
    [course, setCourse] = useState(sharedCourse || null),
    [error, setError] = useState(null),
    [busy, setBusy] = useState(false),
    [day, setDay] = useState(0),
    [density, setDensity] = useState("RELAXED"),
    [taste, setTaste] = useState(""),
    [picker, setPicker] = useState(null),
    [links, setLinks] = useState(false),
    [region, setRegion] = useState(""),
    [selection, setSelection] = useState(() => {
      try {
        return JSON.parse(sessionStorage.getItem(`draw-result-${id}`));
      } catch {
        return null;
      }
    }),
    [participants, setParticipants] = useState([]),
    [settings, setSettings] = useState(false),
    [pins, setPins] = useState(null),
    [pinCategory, setPinCategory] = useState(""),
    [detail, setDetail] = useState(null),
    [move, setMove] = useState(null),
    [hasDiary, setHasDiary] = useState(false);
  const readonly = !!sharedCourse || course?.myRole === "VIEWER";
  const pastEnded =
    (course?.endDate || context?.endDate || "9999") < seoulToday();
  // 지난 여행 기록(retroactive)은 여행기를 쓰기 전까지 고칠 수 있다. 초대는 항상 막힌다.
  const ended = pastEnded && !(context?.retroactive && !hasDiary);
  const load = async () => {
    if (readonly) return;
    setError(null);
    try {
      const ctx = await api(`/trips/${id}/context`);
      setContext(ctx);
      setDensity(ctx.scheduleDensity || "RELAXED");
      setRegion(ctx.regionSigCd || "");
      if (ctx.hasCourse) setCourse(await api(`/courses/${id}`));
      else setCourse(null);
      setParticipants(await api(`/trips/${id}/participants`));
      if (ctx.retroactive) {
        const mine = await api("/trips?period=PAST");
        setHasDiary(
          !!mine.find((t) => String(t.tripId) === String(id))?.myDiaryId,
        );
      }
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
  }, [id]);
  const run = async (fn) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const update = (c) => {
    setCourse(c);
    setContext((x) =>
      x
        ? {
            ...x,
            version: c.version,
            hasCourse: true,
            scheduleDensity: c.scheduleDensity,
          }
        : x,
    );
    setDensity(c.scheduleDensity);
  };
  const edit = async (op) => {
    const c = await api(`/courses/${id}/schedule`, {
      method: "PATCH",
      body: { version: course.version, operations: [op] },
    });
    update(c);
  };
  const showPins = async (cursor) => {
    try {
      const r = await api(
        `/regions/${region}/attractions?` +
          query({ category: pinCategory, cursor, limit: 50 }),
      );
      setPins((old) =>
        cursor ? { ...r, items: [...old.items, ...r.items] } : r,
      );
    } catch (e) {
      setError(e);
    }
  };
  const items = course?.days?.find((d) => d.dayIndex === day)?.items || [];
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">
            {readonly ? "SHARED JOURNEY · READ ONLY" : "YOUR TRAVEL ITINERARY"}
          </div>
          <h1>{course?.title || "여행을 완성해 볼까요?"}</h1>
          <p>
            {course?.regionName || region || "지역 선택 전"} ·{" "}
            {course?.startDate || context?.startDate} —{" "}
            {course?.endDate || context?.endDate}
          </p>
        </div>
        <div className="row">
          <Badge>
            {readonly
              ? "읽기 전용"
              : `여행 #${id} · v${course?.version ?? context?.version ?? "—"}`}
          </Badge>
          {!readonly && (
            <>
              <Button variant="secondary" onClick={load}>
                <RefreshCw size={16} />
                최신 여행
              </Button>
              <Button variant="secondary" onClick={() => setLinks(!links)}>
                <Users size={16} />
                초대 · 공유
              </Button>
            </>
          )}
        </div>
      </div>
      <ErrorBox error={error} onRetry={load} />
      {ended && (
        <Notice>
          종료된 여행은 읽기 전용입니다. 내 여행에서 여행기를 작성할 수
          있습니다.
        </Notice>
      )}
      {pastEnded && !ended && (
        <Notice>
          지난 여행 기록 중입니다. 여행기를 쓰면 지역과 코스를 더 바꿀 수
          없습니다.
        </Notice>
      )}
      {links && (
        <TripCollaboration
          id={id}
          ended={pastEnded}
          participants={participants}
          onError={setError}
        />
      )}
      <div className="course-layout">
        <div>
          {!readonly && !ended && context && (
            <Panel className="generation">
              <div className="row spread">
                <h2>코스 만들기</h2>
                <Button variant="ghost" onClick={() => setSettings(!settings)}>
                  <Settings2 size={16} />
                  여행 설정
                </Button>
              </div>
              {!context.regionSigCd && (
                <Notice>먼저 여행 설정에서 지역을 정해 주세요.</Notice>
              )}
              <div className="two-col">
                <Field label="일정 밀도">
                  <Select
                    value={density}
                    onChange={setDensity}
                    options={["RELAXED", "PACKED"]}
                  />
                </Field>
                <Field label="추천 방식">
                  <Select
                    value={taste}
                    onChange={setTaste}
                    options={[
                      { value: "", label: "내 프로필 기본값" },
                      "TASTE",
                      "RANDOM",
                    ]}
                  />
                </Field>
              </div>
              <Button
                disabled={busy || !context.regionSigCd}
                onClick={() => {
                  if (
                    course &&
                    !confirm(
                      "다른 참여자의 편집과 식당 선택도 사라집니다. 코스를 다시 생성할까요?",
                    )
                  )
                    return;
                  run(async () =>
                    update(
                      await api(`/courses/${id}/generate`, {
                        method: "POST",
                        body: {
                          version: course?.version ?? context.version,
                          scheduleDensity: density,
                          ...(taste ? { tasteMode: taste } : {}),
                        },
                      }),
                    ),
                  );
                }}
              >
                <Shuffle size={17} />
                {busy
                  ? "서버 처리 중…"
                  : course
                    ? "새로운 코스로 다시 생성"
                    : "내 취향으로 코스 생성"}
              </Button>
            </Panel>
          )}
          {settings && context && (
            <TripSettings
              context={context}
              density={density}
              onChange={async (r) => {
                setSelection(r);
                await load();
              }}
              run={run}
              busy={busy}
            />
          )}{" "}
          {selection && (
            <Panel>
              <h3>지역 선택 결과</h3>
              <p>
                {selection.province} {selection.city} · 후보{" "}
                {selection.candidateCount ?? "—"}곳
              </p>
              {selection.ignoredConditions?.map((c, i) => (
                <Notice key={i}>
                  {c.condition === "MY_TASTE" ? "내 취향" : "출발지 거리"} 조건
                  미적용:{" "}
                  {c.reason === "TASTE_NOT_READY"
                    ? "취향 벡터가 준비되지 않았습니다."
                    : c.reason === "ORIGIN_MISSING"
                      ? "출발 위치가 없습니다."
                      : c.reason}
                </Notice>
              ))}
              {selection.warnings?.map((w, i) => (
                <Notice key={i}>{warnings[w] || w}</Notice>
              ))}
              <Json
                data={selection}
                title="지역 선택 결과 · 적용/무시된 조건"
              />
            </Panel>
          )}
          {!course ? (
            <Empty
              title={busy ? "코스를 만들고 있어요" : "아직 코스가 없습니다"}
            >
              지역을 정하고 코스를 생성하면 방문 순서가 표시됩니다.
            </Empty>
          ) : (
            <>
              <div className="tabs days">
                {course.days.map((d) => (
                  <button
                    className={day === d.dayIndex ? "active" : ""}
                    key={d.dayIndex}
                    onClick={() => setDay(d.dayIndex)}
                  >
                    DAY {d.dayIndex + 1}
                    <small>{d.date}</small>
                  </button>
                ))}
              </div>
              <div className="timeline">
                {items.map((item, index) => (
                  <article
                    className={`stop ${item.type === "MEAL" ? "meal" : ""}`}
                    key={item.itemId}
                  >
                    <div className="stop-number">{index + 1}</div>
                    <div className="stop-body">
                      {item.travelFromPreviousMinutes != null && (
                        <small>
                          이전 관광지에서 약 {item.travelFromPreviousMinutes}분
                          · 직선거리 추정
                        </small>
                      )}
                      <div className="stop-main">
                        {item.type === "ATTRACTION" && (
                          <Photo src={item.thumbnailUrl} alt={item.name} />
                        )}
                        <div className="grow">
                          <Badge>
                            {item.type === "MEAL"
                              ? item.meal === "LUNCH"
                                ? "점심"
                                : "저녁"
                              : labels[item.category] || item.category}
                          </Badge>
                          <h3>
                            {item.type === "MEAL"
                              ? item.restaurant?.name || "식당 미정"
                              : item.name}
                          </h3>
                          <p>{item.address || item.restaurant?.address}</p>
                          {item.reason && (
                            <p className="reason">{item.reason}</p>
                          )}
                          {item.restaurant?.representativeMenu && (
                            <p>{item.restaurant.representativeMenu}</p>
                          )}
                          <small>
                            체류 {item.durationMinutes}분
                            {item.estimated ? " · 추정" : ""}
                          </small>
                          {item.restaurant && (
                            <Sources items={item.restaurant.sources} />
                          )}
                        </div>
                      </div>
                      {!readonly && (
                        <div className="row stop-actions">
                          {item.type === "ATTRACTION" && (
                            <Button
                              variant="ghost"
                              onClick={() =>
                                run(async () =>
                                  setDetail(
                                    await api(
                                      `/attractions/${item.attractionId}`,
                                    ),
                                  ),
                                )
                              }
                            >
                              장소 상세
                            </Button>
                          )}
                          {!ended && (
                            <>
                              <Button
                                variant="secondary"
                                disabled={busy}
                                onClick={() => setPicker({ item, day })}
                              >
                                {item.type === "MEAL"
                                  ? "식당 선택"
                                  : "장소 교체"}
                              </Button>
                              {item.type === "ATTRACTION" ? (
                                <Button
                                  variant="ghost"
                                  disabled={busy}
                                  onClick={() =>
                                    run(() =>
                                      edit({
                                        op: "REMOVE",
                                        itemId: item.itemId,
                                      }),
                                    )
                                  }
                                >
                                  삭제
                                </Button>
                              ) : (
                                item.restaurant && (
                                  <Button
                                    variant="ghost"
                                    disabled={busy}
                                    onClick={() =>
                                      run(() =>
                                        edit({
                                          op: "CLEAR_RESTAURANT",
                                          itemId: item.itemId,
                                        }),
                                      )
                                    }
                                  >
                                    선택 해제
                                  </Button>
                                )
                              )}
                              <Button
                                variant="ghost"
                                disabled={busy || index === 0}
                                aria-label={`${item.name || item.meal} 위로`}
                                onClick={() =>
                                  run(() =>
                                    edit({
                                      op: "MOVE",
                                      itemId: item.itemId,
                                      dayIndex: day,
                                      position: index - 1,
                                    }),
                                  )
                                }
                              >
                                <ArrowUp size={15} />
                              </Button>
                              <Button
                                variant="ghost"
                                disabled={busy || index === items.length - 1}
                                aria-label={`${item.name || item.meal} 아래로`}
                                onClick={() =>
                                  run(() =>
                                    edit({
                                      op: "MOVE",
                                      itemId: item.itemId,
                                      dayIndex: day,
                                      position: index + 1,
                                    }),
                                  )
                                }
                              >
                                <ArrowDown size={15} />
                              </Button>
                              {item.type === "ATTRACTION" && (
                                <Button
                                  variant="ghost"
                                  onClick={() =>
                                    setMove({
                                      itemId: item.itemId,
                                      dayIndex: day,
                                      position: index,
                                    })
                                  }
                                >
                                  날짜 이동
                                </Button>
                              )}
                            </>
                          )}
                        </div>
                      )}
                    </div>
                  </article>
                ))}
              </div>
              {!readonly && !ended && (
                <Button
                  variant="secondary wide"
                  disabled={busy}
                  onClick={() => setPicker({ day })}
                >
                  <Plus size={17} />
                  관광지 추가
                </Button>
              )}
            </>
          )}
        </div>
        <aside className="stack">
          <Panel>
            <div className="section-head">
              <MapPin size={18} />
              <h2>오늘의 동선</h2>
            </div>
            <RouteMap items={items} />
          </Panel>
          {course && (
            <Panel>
              <h2>추천의 근거</h2>
              <Badge>
                {labels[course.recommendationMode] || course.recommendationMode}
              </Badge>
              <p>
                제목 생성:{" "}
                {course.titleSource === "LLM" ? "AI가 작성" : "규칙 기반"}
              </p>
              {course.tasteBasis && (
                <p>취향 기준: {course.tasteBasis.nickname}</p>
              )}
              {course.updatedBy && (
                <p>마지막 편집: {course.updatedBy.nickname}</p>
              )}
              {course.warnings?.map((w, i) => (
                <Notice key={i}>
                  {warnings[w.code] || w.code}
                  {w.dayIndex != null && ` · ${w.dayIndex + 1}일차`}
                </Notice>
              ))}
              <Json data={course} />
            </Panel>
          )}
          {!readonly && region && (
            <Panel>
              <h2>지역 관광지 탐색</h2>
              <Field label="유형">
                <Select
                  value={pinCategory}
                  onChange={setPinCategory}
                  options={[{ value: "", label: "전체 유형" }, ...categories]}
                />
              </Field>
              <Button variant="secondary" onClick={() => showPins()}>
                관광지 불러오기
              </Button>
              {pins?.items.map((p) => (
                <div className="list-row" key={p.attractionId}>
                  <button
                    className="text-link"
                    onClick={() =>
                      run(async () =>
                        setDetail(await api(`/attractions/${p.attractionId}`)),
                      )
                    }
                  >
                    {p.name}
                  </button>
                  <Badge tone={p.recommendable ? "" : "warn"}>
                    {p.recommendable ? "추천 가능" : "추천 불가"}
                  </Badge>
                </div>
              ))}
              {pins?.nextCursor && (
                <Button
                  variant="ghost"
                  onClick={() => showPins(pins.nextCursor)}
                >
                  더 불러오기
                </Button>
              )}
            </Panel>
          )}
          {!readonly && (
            <Panel>
              <h3>참여자 {participants.length} / 8</h3>
              <p>
                {participants
                  .map((p) => `${p.nickname}${p.isCreator ? " (생성자)" : ""}`)
                  .join(", ")}
              </p>
              <Button
                variant="danger"
                onClick={() => {
                  if (
                    confirm(
                      "이 여행에서 탈퇴할까요? 마지막 참여자라면 여행과 코스가 삭제됩니다.",
                    )
                  )
                    run(async () => {
                      await api(`/trips/${id}/participants/me`, {
                        method: "DELETE",
                      });
                      navigate("/trips");
                    });
                }}
              >
                여행 탈퇴
              </Button>
            </Panel>
          )}
        </aside>
      </div>
      {picker && (
        <CandidatePicker
          tripId={id}
          picker={picker}
          onClose={() => setPicker(null)}
          onSelect={async (choice) => {
            await edit(
              picker.item?.type === "MEAL"
                ? {
                    op: "SET_RESTAURANT",
                    itemId: picker.item.itemId,
                    selectionToken: choice.selectionToken,
                  }
                : picker.item
                  ? {
                      op: "REPLACE",
                      itemId: picker.item.itemId,
                      attractionId: choice.attractionId,
                    }
                  : {
                      op: "ADD",
                      dayIndex: picker.day,
                      attractionId: choice.attractionId,
                    },
            );
            setPicker(null);
          }}
        />
      )}
      {detail && (
        <Modal title={detail.name} onClose={() => setDetail(null)}>
          <Photo src={detail.images?.[0]?.url} alt={detail.name} />
          <p>{detail.description}</p>
          <p>{detail.address}</p>
          <p>
            이용시간: {detail.useTime || "정보 없음"} · 휴무:{" "}
            {detail.restDate || "정보 없음"}
          </p>
          <Notice>
            운영시간은 원천 제공 정보이며 실시간 영업을 보장하지 않습니다.
          </Notice>
          <Sources items={detail.sources} />
          {!detail.recommendable && (
            <Notice>{detail.notRecommendableReasons?.join(", ")}</Notice>
          )}
          {course && !readonly && !ended && (
            <Button
              disabled={!detail.recommendable || busy}
              onClick={() =>
                run(async () => {
                  await edit({
                    op: "ADD",
                    dayIndex: day,
                    attractionId: detail.attractionId,
                  });
                  setDetail(null);
                })
              }
            >
              현재 날짜에 추가
            </Button>
          )}
          <Json data={detail} />
        </Modal>
      )}
      {move && (
        <Modal title="장소 날짜·순서 이동" onClose={() => setMove(null)}>
          <Field label="이동할 날짜">
            <Select
              value={move.dayIndex}
              onChange={(v) =>
                setMove({ ...move, dayIndex: Number(v), position: 0 })
              }
              options={course.days.map((d) => ({
                value: d.dayIndex,
                label: d.date,
              }))}
            />
          </Field>
          <Field
            label="목록 위치 (0부터)"
            type="number"
            min={0}
            max={Math.max(
              0,
              (course.days.find((d) => d.dayIndex === move.dayIndex)?.items
                .length || 0) -
                (course.days.find((d) =>
                  d.items.some((i) => i.itemId === move.itemId),
                )?.dayIndex === move.dayIndex
                  ? 1
                  : 0),
            )}
            value={move.position}
            onChange={(e) =>
              setMove({ ...move, position: Number(e.target.value) })
            }
          />
          <Button
            disabled={busy}
            onClick={() =>
              run(async () => {
                await edit({ op: "MOVE", ...move });
                setMove(null);
              })
            }
          >
            이동 저장
          </Button>
          <ErrorBox error={error} />
        </Modal>
      )}
    </>
  );
}
function TripSettings({ context, density, onChange, run, busy }) {
  const [transport, setTransport] = useState(context.transport),
    [lat, setLat] = useState(context.originLat ?? ""),
    [lng, setLng] = useState(context.originLng ?? ""),
    [mode, setMode] = useState("RANDOM"),
    [sig, setSig] = useState(""),
    [rs, setRs] = useState([]),
    [conditions, setConditions] = useState(["MY_TASTE"]);
  useEffect(() => {
    api(
      "/regions?" +
        query({ days: context.nights + 1, scheduleDensity: density }),
    )
      .then((r) => setRs(r.regions))
      .catch(() => {});
  }, [context.nights, density]);
  return (
    <Panel>
      <h2>여행 설정</h2>
      <Field label="이동수단">
        <Select
          value={transport}
          onChange={setTransport}
          options={["CAR", "WALK", "PUBLIC_TRANSIT"]}
        />
      </Field>
      <div className="two-col">
        <Field
          label="출발 위도"
          type="number"
          step="any"
          value={lat}
          onChange={(e) => setLat(e.target.value)}
        />
        <Field
          label="출발 경도"
          type="number"
          step="any"
          value={lng}
          onChange={(e) => setLng(e.target.value)}
        />
      </div>
      <Button
        variant="secondary"
        disabled={busy}
        onClick={() =>
          run(async () => {
            if ((lat === "") !== (lng === ""))
              throw new Error("위도와 경도를 함께 입력하세요.");
            await api(`/trips/${context.id}/context`, {
              method: "PATCH",
              body: {
                transport,
                originLat: lat === "" ? null : Number(lat),
                originLng: lng === "" ? null : Number(lng),
                version: context.version,
              },
            });
            await onChange(null);
          })
        }
      >
        이동수단·출발지 저장
      </Button>
      <hr />
      <Field label="지역 선택 방식">
        <Select
          value={mode}
          onChange={setMode}
          options={[
            { value: "RANDOM", label: "완전 랜덤" },
            { value: "CONDITIONAL", label: "조건 랜덤" },
            { value: "MANUAL", label: "직접 선택" },
          ]}
        />
      </Field>
      {mode === "CONDITIONAL" &&
        ["MY_TASTE", "DISTANCE"].map((c) => (
          <label className="check" key={c}>
            <input
              type="checkbox"
              checked={conditions.includes(c)}
              onChange={(e) =>
                setConditions(
                  e.target.checked
                    ? [...conditions, c]
                    : conditions.filter((x) => x !== c),
                )
              }
            />
            {c === "MY_TASTE" ? "내 취향" : "출발지 거리"}
          </label>
        ))}
      {mode === "MANUAL" && (
        <Field label="지역">
          <Select
            value={sig}
            onChange={setSig}
            options={[
              { value: "", label: "선택하세요" },
              ...rs
                .filter((r) => r.drawEligible)
                .map((r) => ({
                  value: r.sigCd,
                  label: `${r.province} ${r.city}`,
                })),
            ]}
          />
        </Field>
      )}
      <Button
        disabled={
          busy ||
          (mode === "MANUAL" && !sig) ||
          (mode === "CONDITIONAL" && !conditions.length)
        }
        onClick={() => {
          if (
            context.hasCourse &&
            !confirm(
              "코스를 비우고 다시 만들어요. 다른 참여자의 편집과 식당 선택도 사라집니다. 진행할까요?",
            )
          )
            return;
          run(async () =>
            onChange(
              await api(`/trips/${context.id}/region`, {
                method: "POST",
                body: {
                  mode,
                  conditions: mode === "CONDITIONAL" ? conditions : [],
                  ...(mode === "MANUAL" ? { sigCd: sig } : {}),
                  scheduleDensity: density,
                  replaceCourse: context.hasCourse,
                  version: context.version,
                },
              }),
            ),
          );
        }}
      >
        지역 {context.regionSigCd ? "다시 선택" : "선택"}
      </Button>
    </Panel>
  );
}
function TripCollaboration({ id, ended, participants, onError }) {
  const [friends, setFriends] = useState([]),
    [sent, setSent] = useState([]);
  const load = async () => {
    try {
      const [f, s] = await Promise.all([
        api("/friends"),
        api(`/trips/${id}/friend-invites`),
      ]);
      setFriends(f);
      setSent(s);
    } catch (e) {
      onError(e);
    }
  };
  useEffect(() => {
    load();
  }, [id]);
  return (
    <Panel>
      <div className="two-col">
        <Links
          path={`/trips/${id}/invites`}
          kind="invite"
          title="여행 참여 초대"
          disabled={ended}
        />
        <Links
          path={`/courses/${id}/share-links`}
          kind="shared"
          title="코스 읽기 전용 공유"
        />
      </div>
      <h3>친구 바로 초대</h3>
      <div className="row">
        {friends
          .filter((f) => !participants.some((p) => p.userId === f.userId))
          .map((f) => (
            <Button
              disabled={ended}
              variant="secondary"
              key={f.userId}
              onClick={async () => {
                try {
                  await api(`/trips/${id}/friend-invites`, {
                    method: "POST",
                    body: { friendUserId: f.userId },
                  });
                  await load();
                } catch (e) {
                  onError(e);
                }
              }}
            >
              {f.nickname} 초대
            </Button>
          ))}
      </div>
      {sent.map((s) => (
        <div className="list-row" key={s.id}>
          <span>
            {s.invitee?.nickname} · {s.status}
          </span>
          {s.status === "PENDING" && (
            <Button
              variant="ghost"
              onClick={async () => {
                try {
                  await api(`/trips/${id}/friend-invites/${s.id}`, {
                    method: "DELETE",
                  });
                  load();
                } catch (e) {
                  onError(e);
                }
              }}
            >
              취소
            </Button>
          )}
        </div>
      ))}
    </Panel>
  );
}
function CandidatePicker({ tripId, picker, onClose, onSelect }) {
  const meal = picker.item?.type === "MEAL";
  const [data, setData] = useState(null),
    [error, setError] = useState(null),
    [busy, setBusy] = useState(false),
    [q, setQ] = useState(""),
    [category, setCategory] = useState(""),
    [radius, setRadius] = useState(5000),
    [page, setPage] = useState(1),
    [source, setSource] = useState("recommendations");
  const load = async (search = false, p = 1) => {
    setBusy(true);
    setError(null);
    try {
      let r;
      if (meal) {
        r = await api(
          `/courses/${tripId}/restaurants/${search ? "search" : "recommendations"}?` +
            query({
              itemId: picker.item.itemId,
              radius,
              ...(search ? { query: q, page: p } : {}),
            }),
        );
        if (!search && !(r.sections || []).some((s) => s.items.length)) {
          r = await api(
            `/courses/${tripId}/restaurants/search?` +
              query({ itemId: picker.item.itemId, radius, page: 1 }),
          );
          setSource("search");
        } else setSource(search ? "search" : "recommendations");
      } else
        r = await api(
          `/courses/${tripId}/alternatives?` +
            query({ itemId: picker.item?.itemId, q, category, limit: 20 }),
        );
      setData(r);
      setPage(p);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  useEffect(() => {
    load();
  }, []);
  const entries = meal
    ? data?.items || data?.sections?.flatMap((s) => s.items) || []
    : data?.groups?.flatMap((s) => s.items) || [];
  return (
    <Modal
      title={
        meal
          ? "이번 식사는 어디에서?"
          : picker.item
            ? "다른 장소 찾아보기"
            : "일정에 장소 더하기"
      }
      onClose={onClose}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          load(true);
        }}
      >
        <div className="row">
          <Field
            label={meal ? "식당 검색" : "관광지 이름 검색"}
            value={q}
            maxLength={50}
            onChange={(e) => setQ(e.target.value)}
            placeholder={meal ? "음식 또는 식당 이름" : "같은 지역 안에서 검색"}
          />
          <Button type="submit" disabled={busy}>
            <Search size={16} />
            검색
          </Button>
        </div>
        {meal ? (
          <Field label="검색 반경 (m)">
            <input
              type="number"
              min={1}
              max={20000}
              value={radius}
              onChange={(e) => setRadius(Number(e.target.value))}
            />
          </Field>
        ) : (
          <Field label="관광지 유형">
            <Select
              value={category}
              onChange={setCategory}
              options={[{ value: "", label: "모든 유형" }, ...categories]}
            />
          </Field>
        )}
      </form>
      {meal && (
        <div className="row">
          <Button variant="ghost" disabled={busy} onClick={() => load(false)}>
            공공데이터 추천
          </Button>
          <Badge>
            {source === "search" ? "카카오 검색 결과" : "출처 기반 추천"}
          </Badge>
        </div>
      )}
      <ErrorBox error={error} />
      {busy ? (
        <Loading />
      ) : entries.length ? (
        entries.map((c, i) => (
          <div
            className="candidate"
            key={c.attractionId || `${c.externalId}-${i}`}
          >
            <Photo src={c.thumbnailUrl || c.imageUrl} alt={c.name} />
            <div className="grow">
              <h3>{c.name}</h3>
              <p>{c.address || c.reason}</p>
              {c.representativeMenu && <p>{c.representativeMenu}</p>}
              <small>
                {c.distanceMeters != null ? `${c.distanceMeters}m · ` : ""}
                {c.provider || labels[c.category]}
              </small>
              <Sources items={c.sources} />
            </div>
            <Button
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                setError(null);
                try {
                  await onSelect(c);
                } catch (e) {
                  setError(e);
                } finally {
                  setBusy(false);
                }
              }}
            >
              선택
            </Button>
          </div>
        ))
      ) : (
        <Empty title="조건에 맞는 결과가 없습니다" />
      )}
      {meal && source === "search" && (
        <div className="row">
          <Button
            disabled={busy || page === 1}
            variant="secondary"
            onClick={() => load(true, page - 1)}
          >
            이전
          </Button>
          <span>{page} 페이지</span>
          <Button
            disabled={busy || data?.isEnd !== false}
            variant="secondary"
            onClick={() => load(true, page + 1)}
          >
            다음
          </Button>
        </div>
      )}
      {data && (
        <Json
          data={{
            ...data,
            items: data.items?.map(({ selectionToken, ...c }) => c),
            sections: data.sections?.map((s) => ({
              ...s,
              items: s.items.map(({ selectionToken, ...c }) => c),
            })),
          }}
        />
      )}
    </Modal>
  );
}
