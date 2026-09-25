package com.yeso.backend.support;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.trip.infrastructure.ShareSessionCookieFactory;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 통합 테스트의 공용 준비 동작. 실제 API를 호출해 상태를 만든다(저장소를 직접 조작하지 않는다).
 * "준비"에 쓰는 helper라 성공을 전제로 하고, 실패하면 곧바로 단정이 깨진다. 검증 대상 요청은
 * 테스트가 직접 {@code mockMvc.perform}으로 보낸다. 새 공용 helper는 테스트 클래스가 아니라 여기에 둔다.
 */
public class ApiFixtures {

    public static final String PASSWORD = "password123";

    private static final AtomicLong SEQ = new AtomicLong();

    private final MockMvc mockMvc;

    public ApiFixtures(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** 가입한 회원. {@code accessToken}은 Authorization 헤더에 {@link #bearer()}로 싣는다. */
    public record Member(Long userId, String email, String nickname, String accessToken) {
        public String bearer() {
            return ApiFixtures.bearer(accessToken);
        }
    }

    public static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    /** 테스트끼리 절대 겹치지 않는 이메일. */
    public static String uniqueEmail() {
        return "user" + SEQ.incrementAndGet() + "@example.com";
    }

    // ---------- auth ----------

    /** 회원가입 요청을 그대로 보내고 결과를 돌려준다(상태 검증은 호출한 쪽이 한다). */
    public MvcResult signupResult(String email, String password, String nickname) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","nickname":"%s"}
                                """.formatted(email, password, nickname)))
                .andReturn();
    }

    /** 가입만 한 회원(최초 설문 전). */
    public Member signup(String nickname) throws Exception {
        String email = uniqueEmail();
        MvcResult result = signupResult(email, PASSWORD, nickname);
        assertThat(result.getResponse().getStatus()).as("signup status").isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        Long userId = ((Number) JsonPath.read(body, "$.user.id")).longValue();
        return new Member(userId, email, nickname, JsonPath.read(body, "$.accessToken"));
    }

    public Member signup() throws Exception {
        return signup("tester");
    }

    // ---------- onboarding ----------

    /** 12문항 모두 choice=1, RELAXED인 최소 유효 설문. */
    public static String onboardingBody() {
        String answers = IntStream.rangeClosed(1, 12)
                .mapToObj(n -> "{\"questionNumber\":%d,\"choice\":1}".formatted(n))
                .collect(Collectors.joining(",", "[", "]"));
        return """
                {"questionVersion":"demo-mbti-v1","answers":%s,"scheduleDensity":"RELAXED"}
                """.formatted(answers);
    }

    public void submitOnboarding(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/onboarding/submissions")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("onboarding status").isEqualTo(201);
    }

    /** 가입과 최초 설문을 마친 회원 — 여행 생성·초대 수락의 전제 조건이다. */
    public Member onboardedMember(String nickname) throws Exception {
        Member member = signup(nickname);
        submitOnboarding(member.accessToken());
        return member;
    }

    public Member onboardedMember() throws Exception {
        return onboardedMember("tester");
    }

    // ---------- trip ----------

    public MvcResult createTripResult(String accessToken, LocalDate startDate, int nights, String transport)
            throws Exception {
        return mockMvc.perform(post("/api/trips")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"%s","nights":%d,"transport":"%s"}
                                """.formatted(startDate, nights, transport)))
                .andReturn();
    }

    /** 여행을 만들고 id를 돌려준다. 201이 아니면 실패한다. */
    public Long createTrip(String accessToken, LocalDate startDate, int nights) throws Exception {
        MvcResult result = createTripResult(accessToken, startDate, nights, "WALK");
        assertThat(result.getResponse().getStatus()).as("create trip status").isEqualTo(201);
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    // ---------- invite ----------

    public MvcResult createInviteResult(String accessToken, Long tripId, String body) throws Exception {
        return mockMvc.perform(post("/api/trips/{tripId}/invites", tripId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** 초대 링크를 발급하고 token 원문을 돌려준다. {@code body} 예: {@code "{}"}, {@code {"expiresInDays":30}}. */
    public String inviteToken(String accessToken, Long tripId, String body) throws Exception {
        MvcResult result = createInviteResult(accessToken, tripId, body);
        assertThat(result.getResponse().getStatus()).as("create invite status").isEqualTo(201);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    public String inviteToken(String accessToken, Long tripId) throws Exception {
        return inviteToken(accessToken, tripId, "{}");
    }

    public MvcResult acceptResult(String accessToken, String inviteToken) throws Exception {
        return mockMvc.perform(post("/api/invites/{token}/accept", inviteToken)
                        .header("Authorization", bearer(accessToken)))
                .andReturn();
    }

    /** 초대를 발급받아 수락까지 마친다 — {@code invitee}가 여행 참여자가 된다. */
    public void joinByInvite(String inviterToken, Long tripId, String inviteeToken) throws Exception {
        MvcResult result = acceptResult(inviteeToken, inviteToken(inviterToken, tripId));
        assertThat(result.getResponse().getStatus()).as("accept invite status").isEqualTo(201);
    }

    // ---------- friend ----------

    /** 친구 초대 링크를 발급하고 token 원문을 돌려준다. */
    public String friendLinkToken(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/friend-links")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("create friend link status").isEqualTo(201);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    public MvcResult acceptFriendLinkResult(String accessToken, String friendLinkToken) throws Exception {
        return mockMvc.perform(post("/api/friend-links/by-token/{token}/accept", friendLinkToken)
                        .header("Authorization", bearer(accessToken)))
                .andReturn();
    }

    /** 두 회원을 친구로 만든다({@code a}가 링크를 만들고 {@code b}가 수락). */
    public void makeFriends(Member a, Member b) throws Exception {
        MvcResult result = acceptFriendLinkResult(b.accessToken(), friendLinkToken(a.accessToken()));
        assertThat(result.getResponse().getStatus()).as("accept friend link status").isEqualTo(201);
    }

    // ---------- share link ----------

    public String shareLinkToken(String accessToken, Long tripId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/courses/{tripId}/share-links", tripId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("create share link status").isEqualTo(201);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    public String shareLinkToken(String accessToken, Long tripId) throws Exception {
        return shareLinkToken(accessToken, tripId, "{}");
    }

    /** 공유 token을 open해 받은 HttpOnly share_session cookie를 돌려준다(다음 요청에 그대로 싣는다). */
    public Cookie openShareSession(String shareToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/shared/courses/{token}", shareToken)).andReturn();
        assertThat(result.getResponse().getStatus()).as("open share link status").isEqualTo(303);
        Cookie cookie = result.getResponse().getCookie(ShareSessionCookieFactory.COOKIE_NAME);
        assertThat(cookie).as("share session cookie").isNotNull();
        return cookie;
    }
}
