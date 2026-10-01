import { test, expect } from "@playwright/test";

test("one click shows pending request body, full JSON response, copy, filters and live selection", async ({
  page,
  context,
}) => {
  let release;
  const waiting = new Promise((resolve) => {
    release = resolve;
  });
  const responseBody = {
    code: "TEST_VALIDATION",
    message: "테스트 응답",
    details: {
      nested: {
        count: 2,
        enabled: true,
        items: ["서울", "부산"],
        longText: "본문".repeat(350),
      },
    },
  };
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/auth/refresh")
      return route.fulfill({
        status: 401,
        json: { code: "AUTH_INVALID_REFRESH_TOKEN" },
      });
    if (path === "/api/auth/login") {
      await waiting;
      return route.fulfill({ status: 422, json: responseBody });
    }
    return route.fulfill({
      json: { questionVersion: "demo-mbti-v1", questions: [] },
    });
  });
  await page.goto("/");
  await page.getByLabel("이메일").fill("private@example.com");
  await page.getByLabel("비밀번호").fill("secret-password");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await page.getByRole("button", { name: "API 기록 보기" }).click();
  const inspector = page.getByRole("complementary", {
    name: "API 요청·응답 기록",
  });
  await expect(inspector).toBeVisible();
  const request = inspector.getByRole("region", { name: "요청 본문" }),
    response = inspector.getByRole("region", { name: "응답 본문" });
  await expect(request.locator("pre")).toContainText('"password": "[비공개]"');
  await expect(response).toContainText("응답을 기다리고 있습니다");
  release();
  await expect(response.locator("pre")).toContainText(
    JSON.stringify(responseBody, null, 2),
  );
  await expect(inspector.getByText("HTTP 422", { exact: true })).toBeVisible();
  await expect(inspector).not.toContainText("secret-password");
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await response.getByRole("button", { name: "응답 본문 복사" }).click();
  expect(
    JSON.parse(await page.evaluate(() => navigator.clipboard.readText())),
  ).toEqual(responseBody);
  await expect(response.getByText("복사됨")).toBeVisible();
  await page.screenshot({
    path: "test-results/api-inspector-desktop.png",
    fullPage: false,
  });
  await inspector
    .locator(".request-entry")
    .filter({ hasText: "/api/auth/login" })
    .click();
  await page.getByRole("button", { name: /백엔드 연결됨/ }).click();
  await expect(response.locator("pre")).toContainText("TEST_VALIDATION");
  await inspector.getByRole("button", { name: "선택한 기록 유지" }).click();
  await expect(response.locator("pre")).toContainText("demo-mbti-v1");
  await inspector.getByLabel("API 기록 필터").selectOption("error");
  await expect(response.locator("pre")).toContainText("TEST_VALIDATION");
  await inspector.getByLabel("API 기록 검색").fill("not-found-path");
  await expect(
    inspector.getByText("검색 조건에 맞는 요청이 없습니다."),
  ).toBeVisible();
  await inspector.getByLabel("API 기록 검색").fill("");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() =>
      page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    )
    .toBe(true);
  await expect(request).toBeVisible();
  await expect(response).toBeVisible();
  await page.screenshot({ path: "test-results/api-inspector-mobile.png" });
  await inspector.getByRole("button", { name: "기록 패널 확대" }).click();
  await expect(inspector).toHaveClass(/maximized/);
  await inspector.getByRole("button", { name: "비우기" }).click();
  await expect(inspector.locator(".request-entry")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(inspector).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "API 기록 보기" }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});
