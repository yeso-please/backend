import React, { useEffect, useState } from "react";
import { Copy, Plus, RefreshCw, Users, Upload, ArrowUp } from "lucide-react";
import { api, query, uploadLimitMB } from "./api";
import { navigate } from "./main";
import { Auth } from "./profile";
import { Course } from "./travel";
import {
  Button,
  Panel,
  Field,
  Select,
  Tags,
  ErrorBox,
  Empty,
  Loading,
  Badge,
  Json,
  Notice,
  Photo,
  KoreaMap,
  labels,
} from "./ui";
// 여행기 경험 태그 사전(backend OnboardingQuestionBank.EXPERIENCE_TAGS). AI Hub 설문 전환 후 질문 API에는 없다.
const DIARY_EXPERIENCE_TAGS = [
  "자연",
  "바다",
  "산",
  "산책",
  "골목",
  "역사",
  "시장",
  "로컬 음식",
  "카페",
  "휴식",
  "실내",
  "체험",
];
export function Links({ path, kind, title, disabled = false }) {
  const [links, setLinks] = useState([]),
    [url, setUrl] = useState(""),
    [days, setDays] = useState(7),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(null),
    [copied, setCopied] = useState(false);
  const load = async () => {
    try {
      setLinks(await api(path));
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
  }, [path]);
  return (
    <div className="link-manager">
      <h3>{title}</h3>
      <div className="row">
        <Field
          label="유효기간 (일)"
          type="number"
          min={1}
          max={30}
          value={days}
          onChange={(e) => setDays(Number(e.target.value))}
        />
        <Button
          variant="secondary"
          disabled={disabled || busy || days < 1 || days > 30}
          onClick={async () => {
            setBusy(true);
            setError(null);
            try {
              const r = await api(path, {
                method: "POST",
                body: { expiresInDays: days },
              });
              setUrl(
                `${location.origin}/${kind}/${encodeURIComponent(r.token)}`,
              );
              setCopied(false);
              await load();
            } catch (e) {
              setError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          <Plus size={16} />
          링크 발급
        </Button>
        <Button variant="ghost" aria-label="링크 새로고침" onClick={load}>
          <RefreshCw size={16} />
        </Button>
      </div>
      {url && (
        <div className="copy-row">
          <input aria-label="발급된 링크" readOnly value={url} />
          <Button
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(url);
                setCopied(true);
              } catch {
                setError(new Error("링크 입력란에서 직접 복사해 주세요."));
              }
            }}
          >
            <Copy size={15} />
            {copied ? "복사됨" : "복사"}
          </Button>
        </div>
      )}
      {url && (
        <small>
          링크 원문은 지금만 확인할 수 있습니다. 필요한 경우 복사해 보관하세요.
        </small>
      )}
      <ErrorBox error={error} />
      {links.map((l) => (
        <div key={l.id} className="list-row">
          <span>
            #{l.id} · {l.expiresAt?.replace("T", " ")}
            <Badge tone={l.revoked ? "warn" : ""}>
              {l.revoked
                ? "폐기됨"
                : new Date(l.expiresAt + "+09:00") < new Date()
                  ? "만료됨"
                  : "활성"}
            </Badge>
          </span>
          <Button
            variant="ghost"
            disabled={l.revoked || busy}
            onClick={async () => {
              setBusy(true);
              try {
                await api(`${path}/${l.id}`, { method: "DELETE" });
                await load();
              } catch (e) {
                setError(e);
              } finally {
                setBusy(false);
              }
            }}
          >
            폐기
          </Button>
        </div>
      ))}
    </div>
  );
}
export function Friends() {
  const [friends, setFriends] = useState(null),
    [invites, setInvites] = useState([]),
    [error, setError] = useState(null);
  const load = async () => {
    setError(null);
    try {
      const [f, i] = await Promise.all([
        api("/friends"),
        api("/me/trip-invites"),
      ]);
      setFriends(f);
      setInvites(i);
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
  }, []);
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">BETTER TOGETHER</div>
          <h1>함께 떠나면 더 좋은 여행</h1>
          <p>친구 링크를 나누고 같은 코스를 함께 만들어 보세요.</p>
        </div>
        <Button variant="secondary" onClick={load}>
          <RefreshCw size={16} />
          새로고침
        </Button>
      </div>
      <ErrorBox error={error} onRetry={load} />
      <div className="two-col">
        <Panel>
          <Links title="친구 초대 링크" path="/friend-links" kind="friend" />
        </Panel>
        <Panel>
          <h2>
            받은 여행 초대 <Badge>{invites.length}</Badge>
          </h2>
          {!invites.length ? (
            <Empty title="받은 초대가 없어요" />
          ) : (
            invites.map((i) => (
              <div className="invite-row" key={i.id}>
                <h3>{i.trip?.title}</h3>
                <p>
                  {i.trip?.startDate} ~ {i.trip?.endDate}
                </p>
                <p>
                  {i.inviter?.nickname} ·{" "}
                  {i.dateConflict ? "날짜 중복" : "참여 대기"}
                </p>
                {
                  <div className="row">
                    {["accept", "decline"].map((action) => (
                      <Button
                        disabled={action === "accept" && i.dateConflict}
                        key={action}
                        variant={action === "decline" ? "secondary" : ""}
                        onClick={async () => {
                          try {
                            const r = await api(
                              `/me/trip-invites/${i.id}/${action}`,
                              { method: "POST" },
                            );
                            if (r?.id) navigate(`/course/${r.id}`);
                            else load();
                          } catch (e) {
                            setError(e);
                          }
                        }}
                      >
                        {action === "accept" ? "참여하기" : "거절"}
                      </Button>
                    ))}
                  </div>
                }
              </div>
            ))
          )}
        </Panel>
      </div>
      <h2 className="section-title">나의 여행 친구</h2>
      {friends === null ? (
        !error && <Loading />
      ) : !friends.length ? (
        <Empty title="링크로 첫 친구를 초대해 보세요" />
      ) : (
        <div className="trip-grid">
          {friends.map((f) => (
            <Panel key={f.userId}>
              <div className="row">
                <span className="avatar">{f.nickname?.slice(0, 1)}</span>
                <h3>{f.nickname}</h3>
              </div>
              <p className="muted">{f.since?.slice(0, 10)}부터 친구</p>
              <div className="row">
                <Button
                  variant="secondary"
                  onClick={() => navigate(`/travel-map?friend=${f.userId}`)}
                >
                  친구 여행 지도
                </Button>
                <Button
                  variant="ghost"
                  onClick={async () => {
                    if (!confirm(`${f.nickname}님과 친구 관계를 끊을까요?`))
                      return;
                    try {
                      await api(`/friends/${f.userId}`, { method: "DELETE" });
                      load();
                    } catch (e) {
                      setError(e);
                    }
                  }}
                >
                  친구 끊기
                </Button>
              </div>
            </Panel>
          ))}
        </div>
      )}
    </>
  );
}
export function TravelMap() {
  const [pins, setPins] = useState([]),
    [friends, setFriends] = useState([]),
    [who, setWho] = useState(
      new URLSearchParams(location.search).get("friend") || "",
    ),
    [from, setFrom] = useState(""),
    [to, setTo] = useState(""),
    [error, setError] = useState(null),
    [busy, setBusy] = useState(false);
  const load = async () => {
    setBusy(true);
    setError(null);
    try {
      setPins(
        await api(
          (who ? `/friends/${who}/travel-map` : "/me/travel-map") +
            "?" +
            query({ from, to }),
        ),
      );
    } catch (e) {
      setError(e);
      setPins([]);
    } finally {
      setBusy(false);
    }
  };
  useEffect(() => {
    api("/friends").then(setFriends).catch(setError);
  }, []);
  useEffect(() => {
    load();
  }, [who]);
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">PLACES BECOME MEMORIES</div>
          <h1>지도 위에 남긴 기억</h1>
          <p>여행이 끝나면 내 여행에서 여행기를 작성해 보세요.</p>
        </div>
        <Badge>{pins.length}개의 기록</Badge>
      </div>
      <div className="filter-bar">
        <Field label="누구의 지도">
          <Select
            value={who}
            onChange={setWho}
            options={[
              { value: "", label: "내 여행 지도" },
              ...friends.map((f) => ({ value: f.userId, label: f.nickname })),
            ]}
          />
        </Field>
        <Field
          label="방문 시작일"
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
        />
        <Field
          label="방문 종료일"
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
        />
        <Button onClick={load} disabled={busy}>
          조회
        </Button>
      </div>
      <ErrorBox error={error} />
      <div className="discover-layout">
        <KoreaMap
          pins={pins.map((p) => ({
            ...p,
            onClick: () =>
              navigate(`/diary/${p.diaryId}${who ? "?readonly=1" : ""}`),
          }))}
        />
        <div className="stack">
          {busy ? (
            <Loading />
          ) : !pins.length ? (
            <Empty title="아직 여행 기록이 없습니다" />
          ) : (
            pins.map((p) => (
              <Panel key={p.diaryId} className="memory-card">
                <Photo src={p.coverPhotoUrl} alt={p.title} />
                <Badge>{labels[p.status]}</Badge>
                <h2>{p.title}</h2>
                <p>
                  {p.regionName || "지역 미정"} · {p.visitedAt} ·{" "}
                  {labels[p.visibility]}
                </p>
                <Button
                  variant="secondary"
                  onClick={() =>
                    navigate(`/diary/${p.diaryId}${who ? "?readonly=1" : ""}`)
                  }
                >
                  기록 열기
                </Button>
              </Panel>
            ))
          )}
        </div>
      </div>
    </>
  );
}
export function Diary({ id, sharedDiary }) {
  const [diary, setDiary] = useState(sharedDiary || null),
    [draft, setDraft] = useState(null),
    [error, setError] = useState(null),
    [busy, setBusy] = useState(false),
    [tags] = useState(DIARY_EXPERIENCE_TAGS);
  const readonly =
    !!sharedDiary ||
    new URLSearchParams(location.search).has("readonly") ||
    (diary && diary.includeInTasteProfile == null);
  const apply = (d) => {
    setDiary(d);
    setDraft({
      title: d.title,
      body: d.body || "",
      visibility: d.visibility,
      locationPrecision: d.locationPrecision,
      satisfaction: d.satisfaction ?? "",
      experienceTags: d.experienceTags || [],
      includeInTasteProfile: d.includeInTasteProfile ?? true,
      coverPhotoId: d.coverPhotoId,
      photoOrder: d.photos.map((p) => p.photoId),
    });
  };
  const load = async () => {
    setError(null);
    try {
      apply(await api(`/diaries/${id}`));
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    if (sharedDiary) apply(sharedDiary);
    else {
      load();
    }
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
  const save = async () => {
    const d = await api(`/diaries/${id}`, {
      method: "PATCH",
      body: {
        ...draft,
        satisfaction:
          draft.satisfaction === "" ? null : Number(draft.satisfaction),
        ...(draft.coverPhotoId
          ? { coverPhotoId: Number(draft.coverPhotoId) }
          : {}),
      },
    });
    apply(d);
    return d;
  };
  // Photo mutations must not erase unsaved text or the user's photo order.
  const refreshPhotos = async () => {
    const d = await api(`/diaries/${id}`);
    setDiary(d);
    setDraft((old) => ({
      ...old,
      coverPhotoId: d.photos.some((p) => p.photoId === Number(old.coverPhotoId))
        ? old.coverPhotoId
        : d.coverPhotoId,
      photoOrder: [
        ...old.photoOrder.filter((pid) =>
          d.photos.some((p) => p.photoId === pid),
        ),
        ...d.photos
          .filter((p) => !old.photoOrder.includes(p.photoId))
          .map((p) => p.photoId),
      ],
    }));
  };
  if (!diary || !draft)
    return (
      <>
        <ErrorBox error={error} onRetry={load} />
        {!error && <Loading />}
      </>
    );
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">TRAVEL JOURNAL</div>
          <h1>{diary.title}</h1>
          <p>
            {diary.visitedFrom} — {diary.visitedTo} · {diary.courseTitle}
          </p>
        </div>
        <div className="row">
          <Badge>{labels[diary.status]}</Badge>
          <Badge>{readonly ? "읽기 전용" : labels[diary.visibility]}</Badge>
          {!sharedDiary && (
            <Button variant="secondary" onClick={load}>
              <RefreshCw size={16} />
              다시 불러오기
            </Button>
          )}
        </div>
      </div>
      <ErrorBox error={error} />
      <div className="diary-layout">
        <Panel>
          <div className="photo-grid">
            {(readonly
              ? diary.photos
              : draft.photoOrder.map((pid) =>
                  diary.photos.find((p) => p.photoId === pid),
                )
            ).map((p, index) => (
              <div className="photo-tile" key={p.photoId}>
                <Photo src={p.thumbnailUrl || p.url} />
                {!readonly && (
                  <div className="row">
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() =>
                        setDraft({ ...draft, coverPhotoId: p.photoId })
                      }
                    >
                      {Number(draft.coverPhotoId) === p.photoId
                        ? "대표 사진"
                        : "대표 지정"}
                    </Button>
                    <Button
                      variant="ghost"
                      disabled={busy || index === 0}
                      aria-label="사진 앞으로 이동"
                      onClick={() => {
                        const order = [...draft.photoOrder];
                        [order[index - 1], order[index]] = [
                          order[index],
                          order[index - 1],
                        ];
                        setDraft({ ...draft, photoOrder: order });
                      }}
                    >
                      <ArrowUp size={14} />
                    </Button>
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() => {
                        if (confirm("이 사진을 삭제할까요?"))
                          run(async () => {
                            await api(`/diaries/${id}/photos/${p.photoId}`, {
                              method: "DELETE",
                            });
                            await refreshPhotos();
                          });
                      }}
                    >
                      삭제
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </div>
          {!readonly && (
            <Field
              label={`사진 추가 (${diary.photos.length}/30) · JPEG / PNG / WebP · 장당 10MB${uploadLimitMB ? ` · 이 배포의 요청 합계 ${uploadLimitMB}MB` : ""}`}
            >
              <input
                type="file"
                multiple
                accept="image/jpeg,image/png,image/webp"
                disabled={busy}
                onChange={(e) => {
                  const files = [...e.target.files];
                  e.target.value = "";
                  run(async () => {
                    if (!files.length) return;
                    if (files.length + diary.photos.length > 30)
                      throw new Error("사진은 총 30장까지 올릴 수 있습니다.");
                    if (
                      files.some(
                        (f) =>
                          f.size > 10 * 1024 * 1024 ||
                          !["image/jpeg", "image/png", "image/webp"].includes(
                            f.type,
                          ),
                      )
                    )
                      throw new Error(
                        "JPEG, PNG, WebP 형식의 10MB 이하 파일을 선택하세요.",
                      );
                    const body = new FormData();
                    files.forEach((f) => body.append("files", f));
                    await api(`/diaries/${id}/photos`, {
                      method: "POST",
                      body,
                    });
                    await refreshPhotos();
                  });
                }}
              />
            </Field>
          )}
          <small>
            사진 URL은 5분 후 만료될 수 있습니다. 다시 불러오면 새 URL을
            받습니다.
          </small>
        </Panel>
        <Panel>
          {readonly ? (
            <>
              <h2>{diary.title}</h2>
              <p className="prose">{diary.body}</p>
              <div className="chips">
                {diary.experienceTags?.map((t) => (
                  <Badge key={t}>{t}</Badge>
                ))}
              </div>
            </>
          ) : (
            <>
              <Field
                label="제목"
                value={draft.title}
                maxLength={60}
                onChange={(e) => setDraft({ ...draft, title: e.target.value })}
              />
              <Field label="여행 이야기">
                <textarea
                  rows={8}
                  maxLength={5000}
                  value={draft.body}
                  onChange={(e) => setDraft({ ...draft, body: e.target.value })}
                />
              </Field>
              <div className="two-col">
                <Field label="공개 범위">
                  <Select
                    value={draft.visibility}
                    onChange={(v) => setDraft({ ...draft, visibility: v })}
                    options={["PRIVATE", "FRIENDS", "LINK"]}
                  />
                </Field>
                <Field label="사진 위치 공개">
                  <Select
                    value={draft.locationPrecision}
                    onChange={(v) =>
                      setDraft({ ...draft, locationPrecision: v })
                    }
                    options={["CITY", "EXACT", "HIDDEN"]}
                  />
                </Field>
              </div>
              <Field label="여행 만족도">
                <Select
                  value={draft.satisfaction}
                  onChange={(v) => setDraft({ ...draft, satisfaction: v })}
                  options={[
                    { value: "", label: "선택 안 함" },
                    ...[1, 2, 3, 4, 5].map((x) => ({
                      value: x,
                      label: `${x}점`,
                    })),
                  ]}
                />
              </Field>
              <h3>좋았던 경험 · 최대 5개</h3>
              <Tags
                options={tags}
                value={draft.experienceTags}
                max={5}
                onChange={(v) => setDraft({ ...draft, experienceTags: v })}
              />
              <label className="check">
                <input
                  type="checkbox"
                  checked={draft.includeInTasteProfile}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      includeInTasteProfile: e.target.checked,
                    })
                  }
                />
                이 여행의 지역·태그·만족도를 다음 추천에 반영
              </label>
              <div className="row">
                <Button
                  variant="secondary"
                  disabled={busy || !draft.title.trim()}
                  onClick={() => run(save)}
                >
                  저장
                </Button>
                <Button
                  disabled={busy || !draft.title.trim()}
                  onClick={() =>
                    run(async () => {
                      await save();
                      apply(
                        await api(`/diaries/${id}/publish`, { method: "POST" }),
                      );
                    })
                  }
                >
                  {busy ? "처리 중…" : "저장하고 발행"}
                </Button>
              </div>
              <Notice>
                발행하려면 제목이 필요합니다. 사진은 선택입니다. LINK 공개는
                친구에게 자동 공개되지 않습니다.
              </Notice>
              {diary.visibility === "LINK" && diary.status === "PUBLISHED" && (
                <Links
                  path={`/diaries/${id}/share-links`}
                  kind="shared/diary"
                  title="여행기 읽기 전용 공유"
                />
              )}
              <Button
                variant="danger"
                disabled={busy}
                onClick={() => {
                  if (
                    confirm(
                      "여행기와 사진, 공유 링크, 취향 신호를 모두 삭제할까요?",
                    )
                  )
                    run(async () => {
                      await api(`/diaries/${id}`, { method: "DELETE" });
                      navigate("/travel-map");
                    });
                }}
              >
                여행기 삭제
              </Button>
            </>
          )}
          <Json data={diary} />
        </Panel>
      </div>
    </>
  );
}
export function LinkRoute({ route, user, onAuth }) {
  const [data, setData] = useState(null),
    [error, setError] = useState(null),
    [busy, setBusy] = useState(false);
  const parts = route.split("/").filter(Boolean);
  const diary = parts[0] === "shared" && parts[1] === "diary";
  const shared = parts[0] === "shared";
  const token = decodeURIComponent(parts.at(-1));
  const isSession = token === "view";
  const prefix = shared
    ? diary
      ? "/shared/diaries"
      : "/shared/courses"
    : parts[0] === "friend"
      ? "/friend-links/by-token"
      : "/invites";
  useEffect(() => {
    setData(null);
    setError(null);
    let live = true;
    api(prefix + (isSession ? "" : `/${encodeURIComponent(token)}`))
      .then((r) => {
        if (!live) return;
        setData(r);
        if (shared && !isSession)
          history.replaceState(
            {},
            "",
            diary ? "/shared/diary/view" : "/shared/view",
          );
      })
      .catch((e) => live && setError(e));
    return () => {
      live = false;
    };
  }, [route]);
  if (error)
    return (
      <div className="narrow">
        <h1>링크를 열 수 없습니다</h1>
        <ErrorBox error={error} />
        <Button onClick={() => navigate("/")}>홈으로</Button>
      </div>
    );
  if (!data) return <Loading />;
  if (shared)
    return diary ? (
      <Diary sharedDiary={data} />
    ) : (
      <Course sharedCourse={data} />
    );
  return (
    <>
      <div className="narrow">
        <Panel>
          <Badge>{parts[0] === "friend" ? "친구 초대" : "여행 초대"}</Badge>
          <h1>
            {data.inviterNickname ||
              data.title ||
              data.tripTitle ||
              "여행 친구"}
            의 초대
          </h1>
          <p>{data.title || data.tripTitle}</p>
          {data.startDate && (
            <p>
              {data.startDate} — {data.endDate}
            </p>
          )}
          <Json data={data} />
          {user && (
            <Button
              disabled={
                busy || (parts[0] !== "friend" && !user.onboardingCompleted)
              }
              onClick={async () => {
                setBusy(true);
                try {
                  const r = await api(
                    `${prefix}/${encodeURIComponent(token)}/accept`,
                    { method: "POST" },
                  );
                  navigate(
                    parts[0] === "friend"
                      ? "/friends"
                      : `/course/${r.id || r.tripId}`,
                  );
                } catch (e) {
                  setError(e);
                } finally {
                  setBusy(false);
                }
              }}
            >
              초대 수락
            </Button>
          )}
          {user && !user.onboardingCompleted && parts[0] !== "friend" && (
            <Notice>
              여행 참여 전 취향 설문이 필요합니다.{" "}
              <Button
                onClick={() => {
                  sessionStorage.setItem("pending-invite", route);
                  navigate("/onboarding");
                }}
              >
                설문 작성하기
              </Button>
            </Notice>
          )}
        </Panel>
      </div>
      {!user && (
        <Auth
          onAuth={(r) => {
            sessionStorage.setItem("pending-invite", route);
            onAuth(r);
          }}
        />
      )}
    </>
  );
}
