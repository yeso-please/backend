import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  Compass,
  Map,
  BookOpen,
  Users,
  FlaskConical,
  Activity,
  LogOut,
  ChevronRight,
  Terminal,
  RefreshCw,
} from "lucide-react";
import {
  api,
  refresh,
  setToken,
  subscribeLogs,
  getLogs,
  clearLogs,
} from "./api";
import { Button, Panel, Badge, Json, ErrorBox, Loading } from "./ui";
import { Auth, Onboarding } from "./profile";
import { Discover, Trips, Course } from "./travel";
import { Friends, TravelMap, Diary, LinkRoute } from "./social";
import { Lab } from "./lab";
import "./style.css";
import { Diagnostics } from "./diagnostics";
export function useRoute() {
  const [route, setRoute] = useState(location.pathname);
  useEffect(() => {
    const fn = () => setRoute(location.pathname);
    window.addEventListener("popstate", fn);
    return () => window.removeEventListener("popstate", fn);
  }, []);
  return route;
}
export function navigate(path) {
  history.pushState({}, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
  window.scrollTo(0, 0);
}
function App() {
  const route = useRoute(),
    [user, setUser] = useState(null),
    [boot, setBoot] = useState(true),
    [debug, setDebug] = useState(false),
    [connection, setConnection] = useState("확인 전");
  useEffect(() => {
    refresh()
      .then((r) =>
        setUser({ ...r.user, onboardingCompleted: r.onboardingCompleted }),
      )
      .catch(() => {})
      .finally(() => setBoot(false));
    const fn = () => setUser(null);
    window.addEventListener("session-expired", fn);
    return () => window.removeEventListener("session-expired", fn);
  }, []);
  const check = async () => {
    setConnection("확인 중");
    try {
      await api("/onboarding/questions");
      setConnection("연결됨");
    } catch {
      setConnection("연결 실패");
    }
  };
  useEffect(() => {
    check();
  }, []);
  const onAuth = (r) => {
    setUser({ ...r.user, onboardingCompleted: r.onboardingCompleted });
    if (!r.onboardingCompleted) navigate("/onboarding");
    else if (!/^\/(invite|friend|shared)/.test(route)) navigate("/");
  };
  const nav = [
    ["/", "여행 발견", Compass],
    ["/trips", "내 여행", Map],
    ["/travel-map", "여행 기록", BookOpen],
    ["/friends", "친구", Users],
    ["/lab", "API · AI 실험실", FlaskConical],
  ];
  const shared = /^\/(invite|friend\/|shared)/.test(route);
  let page;
  if (boot) page = <Loading />;
  else if (shared)
    page = <LinkRoute route={route} user={user} onAuth={onAuth} />;
  else if (route === "/lab") page = <Lab user={user} />;
  else if (!user) page = <Auth onAuth={onAuth} />;
  else if (route === "/onboarding")
    page = (
      <Onboarding
        onComplete={() => {
          setUser({ ...user, onboardingCompleted: true });
          const pending = sessionStorage.getItem("pending-invite");
          sessionStorage.removeItem("pending-invite");
          navigate(pending || "/");
        }}
      />
    );
  else if (route.startsWith("/course/"))
    page = <Course id={route.split("/")[2]} user={user} />;
  else if (route.startsWith("/diary/"))
    page = <Diary id={route.split("/")[2]} />;
  else if (route === "/trips") page = <Trips />;
  else if (route === "/friends") page = <Friends />;
  else if (route === "/travel-map") page = <TravelMap />;
  else page = <Discover user={user} />;
  return (
    <>
      <header>
        <a
          className="brand"
          href="/"
          onClick={(e) => {
            e.preventDefault();
            navigate("/");
          }}
        >
          <span className="brand-icon">
            <Compass size={25} />
          </span>
          TriPin<span className="brand-sub">TRAVEL STUDIO</span>
        </a>
        <nav aria-label="주 메뉴">
          {nav.map(([url, label, Icon]) => (
            <a
              href={url}
              className={route === url ? "active" : ""}
              key={url}
              onClick={(e) => {
                e.preventDefault();
                navigate(url);
              }}
            >
              <Icon size={16} />
              {label}
            </a>
          ))}
        </nav>
        <div className="account">
          {user ? (
            <>
              <button
                className="avatar"
                title="취향 다시 검사"
                onClick={() => navigate("/onboarding")}
              >
                {user.nickname?.slice(0, 1)}
              </button>
              <span>{user.nickname}</span>
              <Button
                variant="ghost"
                aria-label="로그아웃"
                onClick={async () => {
                  try {
                    await api("/auth/logout", { method: "POST" });
                  } catch {
                    setConnection(
                      "로그아웃 서버 연결 실패 · 쿠키가 남아 있을 수 있음",
                    );
                  } finally {
                    setToken(null);
                    setUser(null);
                    clearLogs();
                    navigate("/");
                  }
                }}
              >
                <LogOut size={17} />
              </Button>
            </>
          ) : (
            <Badge>로그인 전</Badge>
          )}
        </div>
      </header>
      <div className="environment">
        <span>
          <FlaskConical size={14} /> 로컬 테스트 스튜디오 <b>실제 API 모드</b>
        </span>
        <button onClick={check}>
          <Activity size={14} />
          백엔드 {connection}
          <RefreshCw size={12} />
        </button>
      </div>
      <main>{page}</main>
      <footer>
        <span>TriPin · 가보지 않은 곳으로, 나다운 여행.</span>
        <span>Backend contract · 2026.09.30</span>
      </footer>
      {!debug && (
        <button
          className={`debug-toggle ${debug ? "opened" : ""}`}
          aria-expanded={debug}
          aria-controls="api-inspector"
          onClick={() => setDebug(!debug)}
        >
          <Terminal size={17} />
          API 기록 {debug ? "닫기" : "보기"}
        </button>
      )}
      {debug && <Diagnostics onClose={() => setDebug(false)} />}
    </>
  );
}
class Boundary extends React.Component {
  state = { error: null };
  static getDerivedStateFromError(error) {
    return { error };
  }
  render() {
    return this.state.error ? (
      <main>
        <ErrorBox error={this.state.error} />
        <Button onClick={() => location.reload()}>화면 다시 열기</Button>
      </main>
    ) : (
      this.props.children
    );
  }
}
createRoot(document.getElementById("root")).render(
  <Boundary>
    <App />
  </Boundary>,
);
