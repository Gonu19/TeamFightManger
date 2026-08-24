"""골든 파일 생성 — 언어 무관 계약.

Java 파서는 이 파일들을 그대로 통과해야 한다 (project.md 제약).
"그대로"는 **바이트 단위 일치**를 뜻한다. 그래서 JSON 을 정규형으로만 쓴다:
키 정렬, UTF-8 그대로, 구분자 고정, 개행 LF.

사용:
    python tools/make_baseline.py            # fixtures/*.tfm -> tests/baseline/
    python tools/make_baseline.py --check    # 재생성 없이 현재 파일과 대조
"""
from __future__ import annotations

import glob
import hashlib
import json
import os
import sys

from save_model import read_save, summarize

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURES = os.path.join(ROOT, "fixtures")
BASELINE = os.path.join(ROOT, "tests", "baseline")

# 정규형 JSON. Java 의 직렬화도 이 형태를 맞춰야 한다.
_JSON = dict(sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def canonical(data) -> str:
    return json.dumps(data, **_JSON)


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build(path):
    data = read_save(path)
    return canonical(data), summarize(data)


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main(argv):
    check_only = "--check" in argv
    os.makedirs(BASELINE, exist_ok=True)

    fixtures = sorted(glob.glob(os.path.join(FIXTURES, "slot_*.tfm")))
    if not fixtures:
        print("fixtures/slot_*.tfm 이 없다. 세이브 스냅샷을 먼저 뜰 것 (fixtures/README.md)")
        return 1

    manifest = {}
    failed = False

    for src in fixtures:
        slot = os.path.splitext(os.path.basename(src))[0]
        text, summary = build(src)
        digest = sha256(text)
        out = os.path.join(BASELINE, slot + ".json")

        manifest[slot] = {
            "sha256": digest,
            "bytes": len(text.encode("utf-8")),
            "summary": summary,
        }

        if check_only:
            if not os.path.exists(out):
                print("  없음   %s" % os.path.basename(out))
                failed = True
                continue
            with open(out, encoding="utf-8") as f:
                same = sha256(f.read()) == digest
            print("  %s  %s" % ("일치  " if same else "불일치", os.path.basename(out)))
            failed = failed or not same
        else:
            write(out, text)
            print("  %s  %s bytes  %s" % (os.path.basename(out),
                                          format(len(text.encode("utf-8")), ","),
                                          digest[:16]))

    mpath = os.path.join(BASELINE, "manifest.json")
    mtext = json.dumps(manifest, sort_keys=True, ensure_ascii=False, indent=2) + "\n"
    if check_only:
        with open(mpath, encoding="utf-8") as f:
            if f.read() != mtext:
                print("  불일치  manifest.json")
                failed = True
        return 1 if failed else 0

    write(mpath, mtext)
    print("  manifest.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
