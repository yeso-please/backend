"""개발용 E5 체크포인트를 비공개 S3에서 받아 SHA256을 확인하고 compose 기본 경로에 푼다.

  python scripts/fetch_e5_model.py                 # AWS CLI 자격 증명(버킷 읽기 권한)으로 받기
  python scripts/fetch_e5_model.py --url "<링크>"   # 팀에서 받은 기간 한정 다운로드 링크로 받기

기본 대상: ../ai/data/interim/models/e5-base-rating-2022-2023-t4-all-v2/final (TRIPIN_E5_MODEL_DIR 기본값과 같다).
AI Hub 학습 파생물이라 팀 밖으로 전달하지 않는다. 링크·키를 커밋하거나 채팅방에 공개로 올리지 않는다.
"""
import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

VERSION = "e5-base-rating-2022-2023-t4-all-v2"
S3_URI = f"s3://tripin-dev-models-545769669608/e5/{VERSION}.zip"
ZIP_SHA256 = "0bff9eff843ae88ffeecfb2274ba15aaa5acf63c9bb8c0cf7b2da8c1c78b9a39"
WEIGHTS_SHA256 = "d5168644835c8d052efc3a3d37dd4c35a7754b72fa7eb9b2cd447f1318f7c187"
DEFAULT_DEST = Path(__file__).resolve().parents[1].parent / "ai" / "data" / "interim" / "models" / VERSION


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", help="기간 한정 다운로드 링크(presigned URL). 없으면 AWS CLI로 받는다")
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST, help="final 폴더를 둘 상위 폴더")
    parser.add_argument("--force", action="store_true", help="이미 있는 final 폴더를 지우고 다시 푼다")
    args = parser.parse_args()

    final = args.dest / "final"
    if final.exists() and not args.force:
        weights = final / "model.safetensors"
        if weights.exists() and sha256(weights) == WEIGHTS_SHA256:
            print(f"이미 준비됨: {final}")
            return 0
        print(f"{final}이 있지만 가중치가 다릅니다. --force로 다시 받으세요.", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / f"{VERSION}.zip"
        print("내려받는 중(약 800MB)…")
        if args.url:
            with urllib.request.urlopen(args.url, timeout=60) as response, archive.open("wb") as out:
                shutil.copyfileobj(response, out, 1 << 20)
        else:
            subprocess.run(["aws", "s3", "cp", S3_URI, str(archive), "--only-show-errors"], check=True)
        actual = sha256(archive)
        if actual != ZIP_SHA256:
            print(f"SHA256 불일치: {actual}", file=sys.stderr)
            return 1
        if final.exists():
            shutil.rmtree(final)
        args.dest.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                if not name.startswith("final/") or ".." in Path(name).parts:
                    print(f"예상하지 못한 항목: {name}", file=sys.stderr)
                    return 1
            z.extractall(args.dest)
    if sha256(final / "model.safetensors") != WEIGHTS_SHA256:
        print("가중치 SHA256 불일치", file=sys.stderr)
        return 1
    print(f"준비 완료: {final}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
