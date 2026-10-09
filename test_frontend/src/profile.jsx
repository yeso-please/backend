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
    [styles, setStyles] = useState({}),
    [motives, setMotives] = useState([]),
    [density, setDensity] = useState("RELAXED"),
    [exclude, setExclude] = useState([]),
    [likedRegions, setLikedRegions] = useState([]),
    [mbti, setMbti] = useState({}),
    [sig, setSig] = useState(""),
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
      setStyles(
        Object.fromEntries(
          questions.travelStyles.map((x) => [x.number, x.neutralValue]),
        ),
      );
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
  }, []);
  const motiveLabel = (code) =>
    q.travelMotives.find((m) => m.code === code)?.label;
  const regionLabel = (sigCd) => {
    const r = regions.find((x) => x.sigCd === sigCd);
    return r ? `${r.province} ${r.city}` : sigCd;
  };
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      setResult(
        await api("/onboarding/submissions", {
          method: "POST",
          body: {
            questionVersion: q.questionVersion,
            travelStyles: styles,
            travelMotives: motives,
            likedRegions,
            scheduleDensity: density,
            excludeTags: exclude,
            ...(q.mbtiQuestions ? { mbtiAnswers: mbti } : {}),
          },
        }),
      );
      setStep(3);
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
          <p>서버에서 받은 AI Hub 설문 형식 질문으로 취향 프로필을 만듭니다.</p>
        </div>
        <Badge>{step + 1} / 4</Badge>
      </div>
      <ErrorBox error={error} onRetry={load} />
      {!q ? (
        !error && <Loading />
      ) : (
        <>
          <div className="stepper">
            {["여행 스타일", "여행 MBTI", "동기와 지역", "분석 결과"].map((x, i) => (
              <span key={x} className={i === step ? "active" : ""}>
                {i + 1} {x}
              </span>
            ))}
          </div>
          {step === 0 ? (
            <>
              <div className="question-list">
                {q.travelStyles.map((x, i) => (
                  <Panel key={x.number}>
                    <small>
                      STYLE {String(i + 1).padStart(2, "0")}
                      {x.evidence === "INFERRED" && " · 의미 추정 문항"}
                    </small>
                    <div className="row spread">
                      <strong>{x.leftPole}</strong>
                      <strong>{x.rightPole}</strong>
                    </div>
                    <input
                      type="range"
                      min={x.minValue}
                      max={x.maxValue}
                      value={styles[x.number] ?? x.neutralValue}
                      onChange={(e) =>
                        setStyles({
                          ...styles,
                          [x.number]: Number(e.target.value),
                        })
                      }
                    />
                    <p className="muted">
                      {styles[x.number] ?? x.neutralValue} / {x.maxValue}
                      {(styles[x.number] ?? x.neutralValue) === x.neutralValue &&
                        " · 중립"}
                    </p>
                  </Panel>
                ))}
              </div>
              <div className="row spread">
                <span>{q.travelStyles.length}개 스타일</span>
                <Button
                  onClick={() => {
                    setStep(1);
                    window.scrollTo(0, 0);
                  }}
                >
                  다음 · 여행 MBTI
                </Button>
              </div>
            </>
          ) : step === 1 ? (
            <>
              <div className="question-list">
                {(q.mbtiQuestions || []).map((x) => (
                  <Panel key={x.number}>
                    <small>MBTI {String(x.number).padStart(2, "0")}</small>
                    <h3>{x.question}</h3>
                    <div className="row">
                      {x.choices.map((c) => (
                        <Button
                          key={c.choice}
                          variant={mbti[x.number] === c.choice ? "" : "secondary"}
                          onClick={() => setMbti({ ...mbti, [x.number]: c.choice })}
                        >
                          {c.label}
                        </Button>
                      ))}
                    </div>
                  </Panel>
                ))}
              </div>
              <div className="row spread">
                <Button variant="secondary" onClick={() => setStep(0)}>
                  이전
                </Button>
                <span>
                  {Object.keys(mbti).length} / {(q.mbtiQuestions || []).length}
                </span>
                <Button
                  disabled={
                    Object.keys(mbti).length < (q.mbtiQuestions || []).length
                  }
                  onClick={() => {
                    setStep(2);
                    window.scrollTo(0, 0);
                  }}
                >
                  다음 · 동기와 지역
                </Button>
              </div>
            </>
          ) : step === 2 ? (
            <>
              <Panel>
                <h2>왜 여행을 떠나나요?</h2>
                <h3>
                  여행 동기 <small>최대 {q.maxTravelMotives}개</small>
                </h3>
                <Tags
                  options={q.travelMotives.map((m) => m.label)}
                  value={motives.map(motiveLabel)}
                  onChange={(chosen) =>
                    setMotives(
                      q.travelMotives
                        .filter((m) => chosen.includes(m.label))
                        .map((m) => m.code),
                    )
                  }
                  max={q.maxTravelMotives}
                />
                <Field label="일정 밀도">
                  <Select
                    value={density}
                    onChange={setDensity}
                    options={q.scheduleDensityOptions}
                  />
                </Field>
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
                <h2>좋아하는 여행지</h2>
                <p className="muted">
                  선택 사항 · 최대 {q.maxLikedRegions}곳 · 취향 분석에 사용됩니다.
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
                <Button
                  variant="secondary"
                  disabled={
                    !sig ||
                    likedRegions.length >= q.maxLikedRegions ||
                    likedRegions.includes(sig)
                  }
                  onClick={() => {
                    setLikedRegions([...likedRegions, sig]);
                    setSig("");
                  }}
                >
                  지역 추가
                </Button>
                {likedRegions.map((x) => (
                  <div className="list-row" key={x}>
                    <span>{regionLabel(x)}</span>
                    <Button
                      variant="ghost"
                      onClick={() =>
                        setLikedRegions(likedRegions.filter((r) => r !== x))
                      }
                    >
                      제거
                    </Button>
                  </div>
                ))}
              </Panel>
              <div className="row spread">
                <Button variant="secondary" onClick={() => setStep(1)}>
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
                <h2>{result.profileText}</h2>
                <p>
                  여행 MBTI: <strong>{result.mbtiCode ?? "아직 없음"}</strong>
                </p>
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
