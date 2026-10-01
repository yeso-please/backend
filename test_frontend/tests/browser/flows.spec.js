import { test, expect } from "@playwright/test";
const user = { id: 1, email: "test@example.com", nickname: "테스터" };
const questions = {
  questionVersion: "demo-mbti-v1",
  questions: Array.from({ length: 12 }, (_, i) => ({
    number: i + 1,
    prompt: `테스트 질문 ${i + 1}`,
    choice1: { choice: 1, text: "여유롭게" },
    choice2: { choice: 2, text: "계획적으로" },
  })),
  experienceTags: ["바다", "산책"],
  excludeTags: ["물놀이"],
  maxExperienceTags: 5,
  scheduleDensityOptions: ["RELAXED", "PACKED"],
};
const region = {
  sigCd: "47130",
  province: "경상북도",
  city: "경주시",
  centerLat: 35.8,
  centerLng: 129.2,
  drawEligible: true,
  ineligibleReasons: [],
};
const context = {
  id: 42,
  startDate: "2027-01-01",
  endDate: "2027-01-02",
  nights: 1,
  transport: "CAR",
  regionSigCd: "47130",
  scheduleDensity: "RELAXED",
  version: 2,
  hasCourse: true,
};
const course = {
  tripId: 42,
  version: 2,
  myRole: "PARTICIPANT",
  regionSigCd: "47130",
  regionName: "경상북도 경주시",
  title: "테스트 경주 여행",
  titleSource: "RULE",
  recommendationMode: "RULE_BASED",
  startDate: context.startDate,
  endDate: context.endDate,
  scheduleDensity: "RELAXED",
  tasteBasis: { userId: 1, nickname: "테스터" },
  warnings: [{ code: "PERSONALIZATION_FALLBACK" }],
  days: [
    {
      dayIndex: 0,
      date: context.startDate,
      items: [
        {
          itemId: "a-1",
          type: "ATTRACTION",
          attractionId: 1,
          name: "테스트 관광지",
          category: "NATURE",
          lat: 35.8,
          lng: 129.2,
          durationMinutes: 90,
          estimated: true,
          reason: "서버의 추천 이유",
        },
        {
          itemId: "m-1",
          type: "MEAL",
          meal: "LUNCH",
          durationMinutes: 60,
          restaurant: null,
        },
      ],
    },
    { dayIndex: 1, date: context.endDate, items: [] },
  ],
};
async function setup(
  page,
  { signedIn = true, onboarding = true, override } = {},
) {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.route("**/api/**", async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      p = url.pathname.replace("/api", "");
    let data;
    const custom = override && (await override(p, req));
    if (custom)
      return route.fulfill({ status: custom.status || 200, json: custom.data });
    if (p === "/auth/refresh")
      return route.fulfill({
        status: signedIn ? 200 : 401,
        json: signedIn
          ? {
              user,
              accessToken: "fixture-token",
              onboardingCompleted: onboarding,
            }
          : { code: "AUTH_INVALID_REFRESH_TOKEN" },
      });
    if (["/auth/login", "/auth/signup"].includes(p))
      data = {
        user,
        accessToken: "fixture-token",
        onboardingCompleted: onboarding,
      };
    else if (p === "/onboarding/questions") data = questions;
    else if (p === "/onboarding/submissions")
      data = {
        submissionId: "fixture",
        mbtiCode: "INFP",
        scheduleDensity: "RELAXED",
        tasteStatus: "PENDING",
        onboardingCompleted: true,
      };
    else if (p === "/onboarding/me")
      data = {
        onboardingCompleted: true,
        submission: { tasteStatus: "READY", mbtiCode: "INFP" },
      };
    else if (p === "/regions") data = { regions: [region], eligibleCount: 1 };
    else if (p === "/regions/47130/card")
      data = {
        ...region,
        title: "천년의 도시",
        introduction: ["테스트 소개"],
        characteristics: [],
      };
    else if (p === "/trips/unavailable-dates") data = [];
    else if (p === "/trips/context/check")
      data = { available: true, eligibleRegionCount: 1, conflicts: [] };
    else if (p === "/trips" && req.method() === "POST")
      data = { ...context, hasCourse: false, regionSigCd: null, version: 0 };
    else if (p === "/trips")
      data = [
        {
          ...context,
          tripId: 42,
          title: "테스트 경주 여행",
          participants: [{ userId: 1, nickname: "테스터" }],
        },
      ];
    else if (p === "/trips/42/context") data = context;
    else if (p === "/trips/42/region")
      data = {
        tripId: 42,
        regionSigCd: "47130",
        version: 1,
        appliedConditions: [],
        ignoredConditions: [],
      };
    else if (p === "/trips/42/participants")
      data = [{ userId: 1, nickname: "테스터", isCreator: true }];
    else if (p === "/courses/42" || p === "/courses/42/generate") data = course;
    else if (p === "/courses/42/restaurants/recommendations")
      data = { sections: [{ source: "TOUR_API", items: [] }] };
    else if (p === "/courses/42/restaurants/search")
      data = {
        items: [
          {
            name: "테스트 식당",
            selectionToken: "rs_fixture",
            externalId: "1",
            provider: "KAKAO",
          },
        ],
        page: 1,
        isEnd: true,
      };
    else if (p === "/courses/42/alternatives")
      data = {
        groups: [
          {
            category: "NATURE",
            items: [{ attractionId: 2, name: "새 관광지", category: "NATURE" }],
          },
        ],
      };
    else if (p === "/courses/42/schedule") data = { ...course, version: 3 };
    else if (p === "/me/preferences") data = { courseTasteMode: "TASTE" };
    else if (p === "/friends") data = [{ userId: 2, nickname: "여행친구" }];
    else data = [];
    return route.fulfill({ json: data });
  });
  return errors;
}
test("offline backend shows a useful error without fake success", async ({
  page,
}) => {
  await page.route("**/api/**", (r) =>
    r.fulfill({
      status: 502,
      json: { code: "UPSTREAM_UNAVAILABLE", message: "백엔드 서버 연결 실패" },
    }),
  );
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "다시 만나 반가워요" }),
  ).toBeVisible();
  await page.getByLabel("이메일").fill("test@example.com");
  await page.getByLabel("비밀번호").fill("password123");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("UPSTREAM_UNAVAILABLE");
  await page.screenshot({
    path: "test-results/login-desktop.png",
    fullPage: true,
  });
});
test("onboarding uses all 12 server questions and reports pending taste", async ({
  page,
}) => {
  const errors = await setup(page, { onboarding: false });
  await page.goto("/onboarding");
  for (const b of await page
    .getByRole("button", { name: "여유롭게", exact: true })
    .all())
    await b.click();
  await page.getByRole("button", { name: "다음 · 취향 선택" }).click();
  await page.getByRole("button", { name: "바다", exact: true }).first().click();
  const request = page.waitForRequest((r) =>
    r.url().endsWith("/onboarding/submissions"),
  );
  await page.getByRole("button", { name: "취향 저장 · AI 분석" }).click();
  expect((await request).postDataJSON().answers).toHaveLength(12);
  await expect(page.getByText("취향 분석 재시도 대기")).toBeVisible();
  expect(errors).toEqual([]);
});
test("trip creation follows check, create, draw and opens course", async ({
  page,
}) => {
  const calls = [];
  const errors = await setup(page, {
    override: (p, req) => {
      if (req.method() === "POST") calls.push(p);
    },
  });
  await page.goto("/");
  await page.getByLabel("출발일").fill("2027-01-01");
  await page.getByRole("button", { name: "여행 만들고 지역 발견하기" }).click();
  await expect(
    page.getByRole("heading", { name: "테스트 경주 여행" }),
  ).toBeVisible();
  expect(calls.filter((p) => !p.startsWith("/auth/")).slice(0, 3)).toEqual([
    "/trips/context/check",
    "/trips",
    "/trips/42/region",
  ]);
  expect(errors).toEqual([]);
});
test("date overlap blocks trip creation", async ({ page }) => {
  let creates = 0;
  await setup(page, {
    override: (p, req) => {
      if (p === "/trips/context/check")
        return {
          data: {
            available: false,
            eligibleRegionCount: 1,
            conflicts: [
              {
                tripId: 1,
                title: "겹치는 여행",
                startDate: "2027-01-01",
                endDate: "2027-01-02",
              },
            ],
          },
        };
      if (p === "/trips" && req.method() === "POST") creates++;
    },
  });
  await page.goto("/");
  await page.getByRole("button", { name: "여행 만들고 지역 발견하기" }).click();
  await expect(
    page.getByText("기존 여행과 겹칩니다.", { exact: false }),
  ).toBeVisible();
  expect(creates).toBe(0);
});
test("course conflict remains visible and is not automatically replayed", async ({
  page,
}) => {
  let writes = 0;
  const errors = await setup(page, {
    override: (p) => {
      if (p === "/courses/42/schedule") {
        writes++;
        return {
          status: 409,
          data: { code: "TRIP_VERSION_CONFLICT", message: "버전 충돌" },
        };
      }
    },
  });
  await page.goto("/course/42");
  await page.getByRole("button", { name: "삭제", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("최신 여행");
  expect(writes).toBe(1);
  expect(errors).toEqual([]);
});
test("empty restaurant recommendations fallback to Kakao and use signed body token", async ({
  page,
}) => {
  const errors = await setup(page);
  await page.goto("/course/42");
  await page.getByRole("button", { name: "식당 선택", exact: true }).click();
  await expect(page.getByText("테스트 식당", { exact: true })).toBeVisible();
  const request = page.waitForRequest((r) => r.url().endsWith("/schedule"));
  await page.getByRole("button", { name: "선택", exact: true }).click();
  expect((await request).postDataJSON()).toEqual({
    version: 2,
    operations: [
      { op: "SET_RESTAURANT", itemId: "m-1", selectionToken: "rs_fixture" },
    ],
  });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(errors).toEqual([]);
});
test("received invite uses nested trip and inviter and blocks date conflict", async ({
  page,
}) => {
  const errors = await setup(page, {
    override: (p) =>
      p === "/me/trip-invites"
        ? {
            data: [
              {
                id: 3,
                trip: {
                  tripId: 42,
                  title: "함께 부산 여행",
                  startDate: "2027-01-01",
                  endDate: "2027-01-02",
                },
                inviter: { nickname: "부산친구" },
                dateConflict: true,
              },
            ],
          }
        : null,
  });
  await page.goto("/friends");
  await expect(page.getByText("함께 부산 여행")).toBeVisible();
  await expect(page.getByRole("button", { name: "참여하기" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "거절" })).toBeEnabled();
  expect(errors).toEqual([]);
});
test("shared course stays read only and never fetches member resources", async ({
  page,
}) => {
  const paths = [];
  const errors = await setup(page, {
    signedIn: false,
    override: (p) => {
      paths.push(p);
      if (p === "/shared/courses/sl_fixture")
        return {
          data: {
            ...course,
            myRole: "VIEWER",
            tasteBasis: null,
            updatedBy: null,
          },
        };
    },
  });
  await page.goto("/shared/sl_fixture");
  await expect(
    page.getByRole("heading", { name: "테스트 경주 여행" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "식당 선택", exact: true }),
  ).toHaveCount(0);
  expect(paths).not.toContain("/trips/42/context");
  expect(page.url()).toContain("/shared/view");
  expect(errors).toEqual([]);
});
test("AI error and vector diagnostics remain distinguishable", async ({
  page,
}) => {
  const errors = await setup(page);
  await page.route("**/ai-api/**", (r) =>
    r.fulfill({
      status: 409,
      json: { detail: { code: "MODEL_VERSION_MISMATCH" } },
    }),
  );
  await page.goto("/lab");
  await page.getByRole("button", { name: "AI 단독 실험", exact: true }).click();
  await page.getByRole("button", { name: "요청 실행", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("MODEL_VERSION_MISMATCH");
  expect(errors).toEqual([]);
});
test("desktop and mobile discovery have no overflow or runtime errors", async ({
  page,
}) => {
  const errors = await setup(page);
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "여행 만들고 지역 발견하기" }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/discover-desktop.png",
    fullPage: true,
  });
  for (const width of [390, 768]) {
    await page.setViewportSize({ width, height: 844 });
    await expect
      .poll(() =>
        page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      )
      .toBe(true);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "test-results/discover-mobile.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("diary multipart upload preserves unsaved draft and publishes with server photos", async ({
  page,
}) => {
  let diary = {
    diaryId: 31,
    tripId: 42,
    title: "여행 기록",
    body: "",
    status: "DRAFT",
    visibility: "PRIVATE",
    locationPrecision: "CITY",
    satisfaction: null,
    experienceTags: [],
    includeInTasteProfile: true,
    photos: [],
    coverPhotoId: null,
    visitedFrom: "2026-01-01",
    visitedTo: "2026-01-02",
  };
  let multipart = false,
    patch;
  const errors = await setup(page, {
    override: (p, req) => {
      if (p === "/diaries/31/photos") {
        multipart = req
          .headers()
          ["content-type"].includes("multipart/form-data");
        diary = {
          ...diary,
          photos: [{ photoId: 1, url: null, thumbnailUrl: null, order: 0 }],
          coverPhotoId: 1,
        };
        return { data: { photos: diary.photos } };
      }
      if (p === "/diaries/31/publish") {
        diary = { ...diary, status: "PUBLISHED" };
        return { data: diary };
      }
      if (p === "/diaries/31") {
        if (req.method() === "PATCH") {
          patch = req.postDataJSON();
          diary = { ...diary, ...patch };
        }
        return { data: diary };
      }
    },
  });
  await page.goto("/diary/31");
  await page.getByLabel("여행 이야기").fill("업로드 중에도 남아야 하는 글");
  await page
    .locator("input[type=file]")
    .setInputFiles({
      name: "fixture.png",
      mimeType: "image/png",
      buffer: Buffer.from("fixture-content"),
    });
  await expect(page.getByText("대표 사진", { exact: true })).toBeVisible();
  await expect(page.getByLabel("여행 이야기")).toHaveValue(
    "업로드 중에도 남아야 하는 글",
  );
  expect(multipart).toBe(true);
  await page.getByRole("button", { name: "저장하고 발행" }).click();
  await expect(page.getByText("발행됨", { exact: true })).toBeVisible();
  expect(patch.body).toBe("업로드 중에도 남아야 하는 글");
  expect(patch.photoOrder).toEqual([1]);
  expect(errors).toEqual([]);
});

test("friend diary with null taste permission remains read only", async ({
  page,
}) => {
  await setup(page, {
    override: (p) =>
      p === "/diaries/31"
        ? {
            data: {
              diaryId: 31,
              title: "친구 기록",
              body: "공개된 글",
              status: "PUBLISHED",
              visibility: "FRIENDS",
              locationPrecision: "CITY",
              includeInTasteProfile: null,
              photos: [],
              experienceTags: [],
            },
          }
        : null,
  });
  await page.goto("/diary/31");
  await expect(page.getByText("공개된 글", {exact:true})).toBeVisible();
  await expect(page.getByRole("button", { name: "저장하고 발행" })).toHaveCount(
    0,
  );
  await expect(page.locator("input[type=file]")).toHaveCount(0);
});

test("ended trips render course as read only", async ({ page }) => {
  await setup(page, {
    override: (p) =>
      p === "/trips/42/context"
        ? { data: { ...context, endDate: "2020-01-02" } }
        : p === "/courses/42"
          ? { data: { ...course, endDate: "2020-01-02" } }
          : null,
  });
  await page.goto("/course/42");
  await expect(
    page.getByText("종료된 여행은 읽기 전용입니다.", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "새로운 코스로 다시 생성" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "식당 선택", exact: true }),
  ).toHaveCount(0);
});
