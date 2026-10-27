"""개발용 순위 모델(LightGBM JSON)을 비공개 S3에서 받아 SHA256을 확인하고 compose 기본 경로에 둔다.

  python scripts/fetch_ranker_model.py                 # AWS CLI 자격 증명(버킷 읽기 권한)으로 받기
  python scripts/fetch_ranker_model.py --url "<링크>"   # 팀에서 받은 기간 한정 다운로드 링크로 받기

기본 대상: ../ai/data/interim/models/lgbm-ranker-v1/ranker.json (TRIPIN_RANKER_MODEL_DIR 기본값과 같다).
없어도 백엔드는 뜨고 취향 점수만 E5 코사인으로 돌아간다. AI Hub 학습 파생물이라 팀 밖으로 전달하지 않는다.
"""
import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

VERSION = "lgbm-ranker-v1"
S3_URI = f"s3://tripin-dev-models-545769669608/ranker/{VERSION}.json"
SHA256 = "8f2a049219850831341a99317b1f3893a804b6fde6a3e65213619c2568439287"
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
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST, help="ranker.json을 둘 폴더")
    args = parser.parse_args()

    target = args.dest / "ranker.json"
    if target.exists():
        if sha256(target) == SHA256:
            print(f"이미 준비됨: {target}")
            return 0
        print(f"{target}이 있지만 내용이 다릅니다. 지우고 다시 받으세요.", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        download = Path(tmp) / "ranker.json"
        if args.url:
            with urllib.request.urlopen(args.url, timeout=60) as response, download.open("wb") as out:
                shutil.copyfileobj(response, out, 1 << 20)
        else:
            subprocess.run(["aws", "s3", "cp", S3_URI, str(download), "--only-show-errors"], check=True)
        actual = sha256(download)
        if actual != SHA256:
            print(f"SHA256 불일치: {actual}", file=sys.stderr)
            return 1
        args.dest.mkdir(parents=True, exist_ok=True)
        shutil.move(download, target)
    print(f"준비 완료: {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
