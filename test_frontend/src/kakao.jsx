import React, { useEffect, useState } from "react";
import { api } from "./api";
import { Button, Panel, ErrorBox, Loading, Notice } from "./ui";

// 카카오 로그인(docs/api/auth.md 1-6). 인가 코드는 프론트가 받고, 교환은 백엔드가 한다.
// client_id는 카카오 REST API 키(공개 값)다. Client Secret은 프론트에 두지 않는다.
const CLIENT_ID = import.meta.env.VITE_KAKAO_LOGIN_CLIENT_ID || "";
const STORAGE_KEY = "kakao-login";
export const CALLBACK_PATH = "/auth/kakao/callback";

// 콘솔과 백엔드 허용 목록에 등록한 주소가 localhost 기준이다. 127.0.0.1로 열면 redirect_uri가 달라진다.
const redirectUri = () => `${location.origin}${CALLBACK_PATH}`;
const isRegisteredOrigin = () => location.hostname === "localhost";

// 로그인 후 돌아갈 곳은 앱 내부 경로만 허용한다(open redirect 방지).
const safeReturnTo = (path) =>
  typeof path === "string" && path.startsWith("/") && !path.startsWith("//")
    ? path
    : "/";

function randomState() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function KakaoLoginButton() {
  if (!CLIENT_ID)
    return (
      <Notice>
        카카오 로그인: <code>VITE_KAKAO_LOGIN_CLIENT_ID</code>가 비어 있습니다.
      </Notice>
    );
  if (!isRegisteredOrigin())
    return (
      <Notice>
        카카오 로그인은{" "}
        <a href={`http://localhost:${location.port}${location.pathname}`}>
          http://localhost:{location.port}
        </a>
        에서 열어야 합니다(등록된 Redirect URI 기준).
      </Notice>
    );
  const start = () => {
    const state = randomState();
    const returnTo = safeReturnTo(location.pathname + location.search);
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ state, returnTo }));
    location.href =
      "https://kauth.kakao.com/oauth/authorize?" +
      new URLSearchParams({
        client_id: CLIENT_ID,
        redirect_uri: redirectUri(),
        response_type: "code",
        state,
      });
  };
  return (
    <button type="button" className="kakao-login" onClick={start}>
      <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
        <path
          fill="currentColor"
          d="M12 3C6.48 3 2 6.48 2 10.78c0 2.78 1.86 5.21 4.66 6.59l-.95 3.47c-.08.3.26.54.52.37l4.13-2.73c.54.07 1.08.11 1.64.11 5.52 0 10-3.48 10-7.81S17.52 3 12 3z"
        />
      </svg>
      카카오로 계속하기
    </button>
  );
}

// 같은 인가 코드를 두 번 보내지 않도록 모듈 단위로 한 번만 처리한다.
let handled = false;

export function KakaoCallback({ onAuth, navigate }) {
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);
  useEffect(() => {
    if (handled) return;
    handled = true;
    const params = new URLSearchParams(location.search);
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || "null");
    sessionStorage.removeItem(STORAGE_KEY);
    // 코드·state를 주소창과 기록에서 지운다(새로고침·뒤로가기로 재전송 방지).
    history.replaceState(null, "", CALLBACK_PATH);

    if (params.get("error")) {
      // 동의 화면에서 취소(access_denied)하면 조용히 로그인 화면으로 돌아간다.
      navigate(saved?.returnTo || "/");
      return;
    }
    if (!saved || !params.get("state") || params.get("state") !== saved.state) {
      setMessage("로그인 요청을 확인할 수 없어요. 다시 시도해 주세요.");
      return;
    }
    api("/auth/kakao", {
      method: "POST",
      body: { code: params.get("code"), redirectUri: redirectUri() },
    })
      .then((r) => {
        const returnTo = safeReturnTo(saved.returnTo);
        if (!r.onboardingCompleted && returnTo !== "/")
          sessionStorage.setItem("pending-invite", returnTo);
        onAuth(r);
        if (r.onboardingCompleted) navigate(returnTo);
      })
      .catch((e) => {
        if (e.code === "AUTH_KAKAO_INVALID_CODE")
          setMessage("카카오 로그인이 만료됐어요. 다시 로그인해 주세요.");
        else if (e.code === "AUTH_KAKAO_UNAVAILABLE")
          setMessage(
            "카카오 로그인이 잠시 안 돼요. 이메일로 로그인할 수 있어요.",
          );
        setError(e);
      });
  }, []);
  if (!error && !message) return <Loading />;
  return (
    <Panel>
      <h2>카카오 로그인</h2>
      {message && <p>{message}</p>}
      <ErrorBox error={error} />
      <Button onClick={() => navigate("/")}>로그인 화면으로</Button>
    </Panel>
  );
}
