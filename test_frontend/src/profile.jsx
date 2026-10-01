import React, { useEffect, useState } from "react";
import { Compass, Check, ArrowRight, RefreshCw } from "lucide-react";
import { api } from "./api";
import {
  Button,
  Field,
  Select,
  Panel,
  Tags,
  ErrorBox,
  Loading,
  Badge,
  Json,
  Notice,
  labels,
  KoreaMap,
} from "./ui";
export function Auth({ onAuth }) {
  const [signup, setSignup] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(null);
  return (
    <div className="auth-layout">
      <div className="auth-story">
        <div className="eyebrow">A LITTLE FURTHER, A LITTLE MORE YOU</div>
        <h1>
          다음 여행은
          <br />
          어디로 <em>떠날까요?</em>
        </h1>
        <p>
          나의 취향에서 시작해, 낯선 지역을 발견하고
          <br />
          친구와 함께 나만의 여행을 완성하세요.
        </p>
        <KoreaMap />
        <div className="story-caption">
          <Compass size={19} />
          취향 분석부터 코스, 여행 기록까지.
        </div>
      </div>
      <Panel className="auth-form">
        <Badge>BACKEND CONNECTED EXPERIENCE</Badge>
        <h2>{signup ? "새로운 여행의 시작" : "다시 만나 반가워요"}</h2>
        <p className="muted">실제 백엔드 계정으로 모든 기능을 테스트합니다.</p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError(null);
            const f = new FormData(e.currentTarget);
            try {
              const body = {
                email: f.get("email"),
                password: f.get("password"),
              };
              if (signup) body.nickname = f.get("nickname");
              onAuth(
                await api("/auth/" + (signup ? "signup" : "login"), {
                  method: "POST",
                  body,
                }),
              );
            } catch (err) {
              setError(err);
            } finally {
              setBusy(false);
            }
          }}
        >
          <Field
            label="이메일"
            name="email"
            type="email"
            autoComplete="email"
            maxLength={190}
            placeholder="traveler@example.com"
            required
          />
          {signup && (
            <Field
              label="닉네임"
              name="nickname"
              maxLength={30}
              autoComplete="nickname"
              required
            />
          )}
          <Field
            label="비밀번호"
            name="password"
            type="password"
            minLength={8}
            maxLength={64}
            autoComplete={signup ? "new-password" : "current-password"}
            placeholder="8자 이상"
            required
          />
          <ErrorBox error={error} />
          <Button type="submit" disabled={busy} variant="wide">
            {busy ? "연결 중…" : signup ? "가입하고 취향 찾기" : "로그인"}
            <ArrowRight size={17} />
          </Button>
        </form>
        <p className="auth-switch">
          {signup ? "이미 계정이 있나요?" : "처음 방문했나요?"}{" "}
          <button
            onClick={() => {
              setSignup(!signup);
              setError(null);
            }}
          >
            {signup ? "로그인" : "회원가입"}
          </button>
        </p>
        <div className="mini-guide">
          <b>처음 테스트한다면</b>
          <span>01 계정 만들기</span>
          <span>02 여행 취향 설문</span>
          <span>03 날짜 선택 · 지역 추첨 · 코스 생성</span>
        </div>
      </Panel>
    </div>
  );
}
export function Onboarding({ onComplete }) {
  const [q, setQ] = useState(null),
    [regions, setRegions] = useState([]),
    [answers, setAnswers] = useState({}),
    [density, setDensity] = useState("RELAXED"),
    [tags, setTags] = useState([]),
    [exclude, setExclude] = useState([]),
    [liked, setLiked] = useState([]),
    [sig, setSig] = useState(""),
    [note, setNote] = useState(""),
    [likedTags, setLikedTags] = useState([]),
    [step, setStep] = useState(0),
    [result, setResult] = useState(null),
    [error, setError] = useState(null),
    [busy, setBusy] = useState(false);
  const load = async () => {
    setError(null);
    try {
      const [questions, rs] = await Promise.all([
        api("/onboarding/questions"),
        api("/regions"),
      ]);
      setQ(questions);
      setRegions(rs.regions);
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
  }, []);
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      setResult(
        await api("/onboarding/submissions", {
          method: "POST",
          body: {
            questionVersion: q.questionVersion,
            answers: Object.entries(answers).map(([n, c]) => ({
              questionNumber: Number(n),
              choice: c,
            })),
            scheduleDensity: density,
            experienceTags: tags,
            excludeTags: exclude,
            likedTrips: liked,
          },
        }),
      );
      setStep(2);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="narrow">
      <div className="page-heading">
        <div>
          <div className="eyebrow">YOUR TRAVEL DNA</div>
          <h1>나를 닮은 여행 취향</h1>
          <p>서버에서 받은 질문과 태그로 취향 프로필을 만듭니다.</p>
        </div>
        <Badge>{step + 1} / 3</Badge>
      </div>
      <ErrorBox error={error} onRetry={load} />
      {!q ? (
        !error && <Loading />
      ) : (
        <>
          <div className="stepper">
            {["여행 성향", "취향과 기억", "분석 결과"].map((x, i) => (
              <span key={x} className={i === step ? "active" : ""}>
                {i + 1} {x}
              </span>
            ))}
          </div>
          {step === 0 ? (
            <>
              <div className="question-list">
                {q.questions.map((x, i) => (
                  <Panel key={x.number}>
                    <small>QUESTION {String(i + 1).padStart(2, "0")}</small>
                    <h3>{x.prompt}</h3>
                    <div className="two-col">
                      {[x.choice1, x.choice2].map((c) => (
                        <button
                          className={`choice ${answers[x.number] === c.choice ? "selected" : ""}`}
                          key={c.choice}
                          onClick={() =>
                            setAnswers({ ...answers, [x.number]: c.choice })
                          }
                        >
                          {c.text}
                          {answers[x.number] === c.choice && (
                            <Check size={18} />
                          )}
                        </button>
                      ))}
                    </div>
                  </Panel>
                ))}
              </div>
              <div className="row spread">
                <span>
                  {Object.keys(answers).length} / {q.questions.length} 응답
                </span>
                <Button
                  disabled={Object.keys(answers).length !== q.questions.length}
                  onClick={() => {
                    setStep(1);
                    window.scrollTo(0, 0);
                  }}
                >
                  다음 · 취향 선택
                </Button>
              </div>
            </>
          ) : step === 1 ? (
            <>
              <Panel>
                <h2>어떤 여행이 좋은가요?</h2>
                <Field label="일정 밀도">
                  <Select
                    value={density}
                    onChange={setDensity}
                    options={q.scheduleDensityOptions}
                  />
                </Field>
                <h3>
                  좋아하는 경험 <small>최대 {q.maxExperienceTags}개</small>
                </h3>
                <Tags
                  options={q.experienceTags}
                  value={tags}
                  onChange={setTags}
                  max={q.maxExperienceTags}
                />
                <h3>피하고 싶은 경험</h3>
                <Tags
                  options={q.excludeTags}
                  value={exclude}
                  onChange={setExclude}
                />
                <Notice>
                  현재 코스에서 강제 제외되는 조건은 물놀이입니다. 나머지는
                  저장되지만 데이터 부족 또는 시각 없는 일정 정책으로 적용되지
                  않습니다.
                </Notice>
              </Panel>
              <Panel>
                <h2>좋았던 여행의 기억</h2>
                <p className="muted">
                  선택 사항 · 최대 30곳 · 메모는 취향 분석에 사용됩니다.
                </p>
                <Field label="지역">
                  <Select
                    value={sig}
                    onChange={setSig}
                    options={[
                      { value: "", label: "지역 선택" },
                      ...regions.map((r) => ({
                        value: r.sigCd,
                        label: `${r.province} ${r.city}`,
                      })),
                    ]}
                  />
                </Field>
                <Field label="좋았던 점">
                  <textarea
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    maxLength={500}
                    placeholder="바다를 따라 조용히 산책한 시간이 좋았어요."
                  />
                </Field>
                <Tags
                  options={q.experienceTags}
                  value={likedTags}
                  onChange={setLikedTags}
                />
                <Button
                  variant="secondary"
                  disabled={
                    !sig ||
                    liked.length >= 30 ||
                    liked.some((x) => x.sigCd === sig)
                  }
                  onClick={() => {
                    setLiked([...liked, { sigCd: sig, note, tags: likedTags }]);
                    setSig("");
                    setNote("");
                    setLikedTags([]);
                  }}
                >
                  기억 추가
                </Button>
                {liked.map((x) => (
                  <div className="list-row" key={x.sigCd}>
                    <span>
                      {regions.find((r) => r.sigCd === x.sigCd)?.city} ·{" "}
                      {x.note}
                    </span>
                    <Button
                      variant="ghost"
                      onClick={() => setLiked(liked.filter((r) => r !== x))}
                    >
                      제거
                    </Button>
                  </div>
                ))}
              </Panel>
              <div className="row spread">
                <Button variant="secondary" onClick={() => setStep(0)}>
                  이전
                </Button>
                <Button disabled={busy} onClick={submit}>
                  {busy ? "취향 분석 중…" : "취향 저장 · AI 분석"}
                </Button>
              </div>
            </>
          ) : (
            result && (
              <Panel className="result">
                <div className="eyebrow">YOUR RESULT</div>
                <h1>{result.mbtiCode}</h1>
                <Badge tone={result.tasteStatus === "READY" ? "" : "warn"}>
                  {labels[result.tasteStatus] || result.tasteStatus}
                </Badge>
                <p>{labels[result.scheduleDensity]}</p>
                {result.tasteStatus !== "READY" && (
                  <Notice>
                    설문은 저장되었습니다. 취향 분석을 사용할 수 없는 동안 코스
                    추천은 폴백으로 동작합니다.
                  </Notice>
                )}
                <div className="row">
                  <Button
                    variant="secondary"
                    onClick={async () => {
                      try {
                        const r = await api("/onboarding/me");
                        setResult(r.submission);
                      } catch (e) {
                        setError(e);
                      }
                    }}
                  >
                    <RefreshCw size={16} />
                    분석 상태 새로고침
                  </Button>
                  <Button onClick={onComplete}>여행 발견하기</Button>
                </div>
                <Json data={result} />
              </Panel>
            )
          )}
        </>
      )}
    </div>
  );
}
