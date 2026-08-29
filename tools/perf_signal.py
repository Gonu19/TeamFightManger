"""승패 대신 **점수차**로 재면 얼마나 예민해지나 — D62 결정 5 의 첫 확인.

D62 는 "스킬 시너지는 승패가 아니라 경기력에서 봐야 한다" 고 정했다. 이유는 통로의 폭이다.
**승패는 경기 하나가 1비트**다. 조합 하나의 값을 그 좁은 통로로 추정하려니 수백 경기가
필요했다.

그런데 새로 파싱할 것도 없다. 세이브의 `GameStat` 에 **세트 스코어**가 이미 있고,
그것은 팀의 킬 합이다(805경기 중 732건에서 정확히 일치). `11 대 13` 은 `졌다` 보다
훨씬 많은 것을 말한다 — **얼마나 졌는지**를 말한다.

그래서 이 스크립트는 `synergy_holdout.py` 와 **똑같은 모형·똑같은 교차검증**을 돌리되
목표만 바꾼다.

    승패   y ∈ {0,1}           로지스틱      ← D60 · D62 가 쓴 것
    점수차 y = 좌점수 − 우점수   최소제곱      ← 이 스크립트

두 결과의 t 를 나란히 놓으면 **통로를 넓히는 것이 표본을 늘리는 것을 얼마나 대신하는지**
가 나온다.

    python tools/perf_signal.py
    python tools/perf_signal.py --seed 3
"""

import argparse
import glob
import io
import json
import math
import os
import random
from collections import defaultdict

from synergy_holdout import MODELS, build_features, mean, paired, project

BASELINE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "tests", "baseline", "slot_*.json")

TEAM_SIZE = 4
BASE_GRID = [1.0, 4.0, 16.0, 64.0]
EXTRA_GRID = [4.0, 16.0, 64.0, 256.0]
SWEEPS = 60


# --------------------------------------------------------------- 자료 만들기

def load_games(include_scrims):
    """4v4 경기. `y` 는 **왼쪽 점수 − 오른쪽 점수** 다.

    좌우 뒤집기는 `synergy_holdout.load_games` 와 **같은 씨앗·같은 순서**를 쓴다.
    두 목표가 정확히 같은 배치를 보아야 t 를 나란히 놓을 수 있다.
    """
    raw = []
    for path in sorted(glob.glob(BASELINE)):
        career = os.path.basename(path).replace("slot_", "").replace(".json", "")[:6]
        data = json.load(io.open(path, encoding="utf-8"))

        for g in data["game_stats"]:
            blue, red = g["blue_pick"], g["red_pick"]
            if len(set(blue)) != TEAM_SIZE or len(set(red)) != TEAM_SIZE:
                continue
            raw.append((list(blue), list(red),
                        (career, g["blue_team_id"]), (career, g["red_team_id"]),
                        g["blue_score"] - g["red_score"]))

        if not include_scrims:
            continue
        for s in data["scrim_stats"]:
            if s.get("team_size") != TEAM_SIZE:
                continue
            blue = [c["champion"] for c in s["blue_stat"]]
            red = [c["champion"] for c in s["red_stat"]]
            if len(set(blue)) != TEAM_SIZE or len(set(red)) != TEAM_SIZE:
                continue
            raw.append((blue, red, None, None, s["blue_score"] - s["red_score"]))

    rnd = random.Random(20260830)
    games = []
    for blue, red, bt, rt, margin in raw:
        if rnd.random() < 0.5:
            games.append({"left": blue, "right": red, "lt": bt, "rt": rt,
                          "y": float(margin)})
        else:
            games.append({"left": red, "right": blue, "lt": rt, "rt": bt,
                          "y": float(-margin)})
    return games


# -------------------------------------------------------------------- 적합

def fit_gaussian(rows, nparam, ridge):
    """능형 최소제곱을 좌표하강으로 푼다.

    로지스틱과 달리 IRLS 바깥 고리가 없다 — 손실이 이미 이차식이다. 그래서 같은 자료에
    대해 **로지스틱보다 싸고 안정적이다.** 통로를 넓히면 계산도 같이 쉬워진다.
    """
    theta = [0.0] * nparam
    resid = [y for _, y in rows]

    by_feat = defaultdict(list)
    for i, (terms, _) in enumerate(rows):
        for j, s in terms:
            by_feat[j].append((i, s))

    denom = {j: float(len(cells)) + ridge[j] for j, cells in by_feat.items()}

    for _ in range(SWEEPS):
        moved = 0.0
        for j, cells in by_feat.items():
            num = 0.0
            for i, s in cells:
                num += s * resid[i]
            delta = (num - ridge[j] * theta[j]) / denom[j]
            if delta == 0.0:
                continue
            theta[j] += delta
            for i, s in cells:
                resid[i] -= s * delta
            moved = max(moved, abs(delta))
        if moved < 1e-9:
            break
    return theta


def predict(rows, theta):
    out = []
    for terms, _ in rows:
        v = 0.0
        for j, s in terms:
            v += s * theta[j]
        out.append(v)
    return out


# ---------------------------------------------------------------------- CV

def cross_validate(games, blocks, folds, base_ridge, extra_ridge, seed=7):
    """경기별 **제곱오차**를 돌려준다. 짝지은 비교는 이 값으로 한다."""
    rnd = random.Random(seed)
    order = list(range(len(games)))
    rnd.shuffle(order)

    err = [0.0] * len(games)
    for f in range(folds):
        test_idx = set(order[f::folds])
        train = [games[i] for i in range(len(games)) if i not in test_idx]
        test_ids = [i for i in range(len(games)) if i in test_idx]
        test = [games[i] for i in test_ids]

        if not blocks:                                    # 상수 모형
            base = mean([g["y"] for g in train])
            for i in test_ids:
                err[i] = (games[i]["y"] - base) ** 2
            continue

        train_rows, block_of, index = build_features(train, blocks)
        ridge = [base_ridge if b in ("champ", "team") else extra_ridge
                 for b in block_of]
        theta = fit_gaussian(train_rows, len(block_of), ridge)

        test_rows = project(test, blocks, index)
        for i, pred in zip(test_ids, predict(test_rows, theta)):
            err[i] = (games[i]["y"] - pred) ** 2
    return err


# -------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--folds", type=int, default=5)
    ap.add_argument("--scrims", action="store_true")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    games = load_games(args.scrims)
    ys = [g["y"] for g in games]
    spread = math.sqrt(mean([(y - mean(ys)) ** 2 for y in ys]))

    print("=" * 78)
    print("점수차로 재면 얼마나 예민해지나 — 승패 1비트 대신 연속값")
    print("=" * 78)
    print(f"  경기 {len(games)}건 · {args.folds}겹 · 씨앗 {args.seed}")
    print(f"  목표: 왼쪽 점수 − 오른쪽 점수. 표준편차 {spread:.2f}킬")
    print("  값은 **held-out 제곱근평균제곱오차(RMSE)**. 작을수록 잘 맞춘 것이다.")
    print()

    results = {}
    for tag, label, blocks in MODELS:
        best = None
        if not blocks:
            grid = [(0.0, 0.0)]
        elif set(blocks) <= {"champ", "team"}:
            grid = [(b, 0.0) for b in BASE_GRID]
        else:
            grid = [(b, e) for b in BASE_GRID for e in EXTRA_GRID]
        for lb, le in grid:
            err = cross_validate(games, blocks, args.folds, lb, le, args.seed)
            if best is None or mean(err) < mean(best[0]):
                best = (err, lb, le)
        results[tag] = best
        err, lb, le = best
        rmse = math.sqrt(mean(err))
        r2 = 1.0 - mean(err) / mean(results["M0"][0])
        cfg = "—" if not blocks else (f"λ기저={lb:.0f}" +
                                      (f" λ조합={le:.0f}" if le else ""))
        print(f"  {tag} {label:<14} RMSE {rmse:5.3f}킬  R² {r2:+.4f}  {cfg}")
    print()

    print("-" * 78)
    print("  무엇이 값어치가 있나 — 짝지은 비교 (제곱오차가 줄었나)")
    print("-" * 78)
    print("  | 성분 | 비교 | 오차 감소 | t |")
    print("  |---|---|---|---|")
    comparisons = [
        ("챔피언 강도", "M0", "M1"),
        ("팀 강도", "M1", "M2"),
        ("2인 시너지", "M2", "M3"),
        ("3인 시너지 (2인을 넣은 뒤)", "M3", "M4"),
        ("카운터", "M2", "M5"),
        ("전부 함께", "M2", "M6"),
    ]
    for label, base, full in comparisons:
        # 오차가 줄면 좋은 것이므로 부호를 뒤집어 "이득" 으로 맞춘다
        gain = [b - f for b, f in zip(results[base][0], results[full][0])]
        m, se, t = paired(gain, [0.0] * len(gain))
        verdict = "**있다**" if t > 2 else ("없다" if t > -2 else "**해롭다**")
        print(f"  | {label} | {base}→{full} | {m:+.4f} | {t:+.2f} | {verdict}")
    print()
    print("  같은 경기·같은 모형·같은 분할에서 목표만 바꾼 것이다.")
    print("  `synergy_holdout.py` 의 t 와 나란히 놓으면 통로를 넓힌 효과가 보인다.")


if __name__ == "__main__":
    main()
