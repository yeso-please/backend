"""관광지 이미지 URL 검증. DB에 직접 쓰지 않고 CSV만 읽고 쓴다.

입력: `id,image_url` 헤더가 있는 CSV(개발 RDS에서 PENDING 이미지를 읽기 전용으로 export).
출력: `id,result,detail,size` 결과 CSV와 VALID id 목록(헤더 없음, DB 반영용).

판정
  VALID   HTTP 200, Content-Type image/*, 앞부분(128KB)으로 Pillow가 형식·크기를 읽음, 가로·세로 >= 100px
  INVALID 403/404/410, 이미지가 아님, 판독 불가, 너무 작음
  ERROR   타임아웃·5xx 등 일시 오류. 판정 보류(PENDING 유지), 다음 실행에서 다시 본다

원본 서버(tong.visitkorea.or.kr) 부담을 줄이려고 동시 요청을 제한한다. 절차는 docs/runbooks/attraction-image-validation.md.

    pip install requests Pillow
    python scripts/validate_attraction_images.py images.csv results.csv valid_ids.csv
"""
import argparse
import csv
import io
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from PIL import Image

HEAD_BYTES = 128 * 1024
MIN_SIDE = 100
RETRIES = 2
_local = threading.local()


def _session() -> requests.Session:
    if not hasattr(_local, "session"):
        session = requests.Session()
        session.headers["User-Agent"] = "TriPin-image-validator/1.0 (dev data quality check)"
        _local.session = session
    return _local.session


def check(url: str) -> tuple[str, str, str]:
    for attempt in range(RETRIES + 1):
        try:
            return _check_once(url.strip())
        except Exception as e:  # 일시 오류만 여기로 온다
            if attempt == RETRIES:
                return "ERROR", str(e) if isinstance(e, RuntimeError) else type(e).__name__, ""
            time.sleep(1.5 * (attempt + 1))
    raise AssertionError("unreachable")


def _check_once(url: str) -> tuple[str, str, str]:
    with _session().get(url, stream=True, timeout=(5, 15), allow_redirects=True) as response:
        if response.status_code in (403, 404, 410):
            return "INVALID", f"HTTP_{response.status_code}", ""
        if response.status_code != 200:
            raise RuntimeError(f"HTTP_{response.status_code}")
        content_type = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
        head = b""
        for chunk in response.iter_content(16384):
            head += chunk
            if len(head) >= HEAD_BYTES:
                break
    if not content_type.startswith("image/"):
        return "INVALID", f"CONTENT_TYPE:{content_type or 'none'}", ""
    try:
        image = Image.open(io.BytesIO(head))
        width, height = image.size
    except Exception:
        return "INVALID", "UNREADABLE_IMAGE", ""
    if min(width, height) < MIN_SIDE:
        return "INVALID", "TOO_SMALL", f"{width}x{height}"
    return "VALID", image.format or "", f"{width}x{height}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("images_csv")
    parser.add_argument("results_csv")
    parser.add_argument("valid_ids_csv")
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--limit", type=int, help="앞에서 N건만 (샘플 확인용)")
    args = parser.parse_args()

    with open(args.images_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if args.limit:
        rows = rows[:args.limit]

    start = time.time()
    results: list[tuple[str, str, str, str]] = []
    with ThreadPoolExecutor(args.workers) as executor:
        futures = {executor.submit(check, row["image_url"]): row["id"] for row in rows}
        for done, future in enumerate(as_completed(futures), 1):
            results.append((futures[future], *future.result()))
            if done % 1000 == 0:
                print(f"{done}/{len(rows)} {time.time() - start:.0f}s", flush=True)
    results.sort(key=lambda r: int(r[0]))

    with open(args.results_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "result", "detail", "size"])
        writer.writerows(results)
    with open(args.valid_ids_csv, "w", newline="", encoding="utf-8") as f:
        f.writelines(f"{r[0]}\n" for r in results if r[1] == "VALID")

    print(f"done {len(rows)} in {time.time() - start:.0f}s", dict(Counter(r[1] for r in results)))
    for (result, detail), count in Counter((r[1], r[2]) for r in results if r[1] != "VALID").most_common(10):
        print(f"  {result} {detail}: {count}")


if __name__ == "__main__":
    main()
