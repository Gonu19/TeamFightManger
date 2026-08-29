"""held-out 예측 비교 — 조합 효과가 진짜 있으면 **못 본 경기를 더 잘 맞춰야 한다.**

`synergy_variance.py` 는 분산을 쪼개서 "성분이 있나" 를 물었다. 그 방법에는 두 약점이
있었고 둘 다 D60 에 남아 있다.

- 적합에 쓴 데이터로 잔차를 재기 때문에 **편향 바닥**이 생긴다. 모수 부트스트랩으로
  보정했지만, 보정량이 성분 자체보다 컸다
- 3인 조합은 **2인 시너지를 상속한다.** 모형에 2인 효과가 없으면 3인 잔차가 그것을
  흡수한다. 그래서 3인의 값은 상한이었다

이 스크립트는 둘 다 우회한다. **경기를 나눠 학습하고, 못 본 경기의 로그가능도를 잰다.**
과적합은 held-out 에서 스스로 벌을 받으므로 보정이 필요 없고, 2인 효과를 모형에 **넣은
채로** 3인을 더해보면 상속분은 이미 설명된 뒤라 남는 것만 측정된다.

**비교하는 모형** (전부 같은 최적화기로 적합한다 — 최적화기 차이가 결과에 섞이지 않게).

    M0  50% 고정
    M1  챔피언 강도
    M2  + 팀 강도
    M3  + 2인 시너지          ← M2 대비 이득이 2인 시너지의 값어치다
    M4  + 3인 시너지          ← M3 대비 이득이 3인 고유의 값어치다
    M5  + 카운터              ← M2 대비 이득이 카운터의 값어치다
    M6  전부

모수는 `P(왼쪽 승) = sigmoid(Σθ(왼쪽 항) − Σθ(오른쪽 항))` 의 로그 승산 항이다.
표본이 적은 항을 0 으로 당기는 능형 λ 는 격자에서 고른다. **λ 를 held-out 에서 고르므로
조합 모형에 유리하게 편향돼 있다** — 그렇게 해주고도 못 이기면 결론이 안전해진다.

DB 가 아니라 골든 파일을 읽는다. 비밀번호가 필요 없다.

    python tools/synergy_holdout.py
    python tools/synergy_holdout.py --folds 5 --scrims
"""

import argparse
import glob
import io
import json
import math
import os
import random
from collections import defaultdict
from itertools import combinations

BASELINE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "tests", "baseline", "slot_*.json")

TEAM_SIZE = 4
BASE_GRID = [1.0, 4.0, 16.0, 64.0]     # 챔피언·팀 항의 능형
EXTRA_GRID = [4.0, 16.0, 64.0, 256.0]  # 조합 항의 능형. 항이 훨씬 많아 더 세게 당긴다
OUTER_ITERS = 12                       # IRLS 바깥 고리
INNER_SWEEPS = 4                       # 좌표하강 안쪽 훑기

BLOCKS = ("champ", "team", "pair2", "triple", "counter")

MODELS = [
    ("M0", "50% 고정", ()),
    ("M1", "챔피언 강도", ("champ",)),
    ("M2", "+ 팀 강도", ("champ", "team")),
    ("M3", "+ 2인 시너지", ("champ", "team", "pair2")),
    ("M4", "+ 3인 시너지", ("champ", "team", "pair2", "triple")),
    ("M5", "+ 카운터", ("champ", "team", "counter")),
    ("M6", "전부", ("champ", "team", "pair2", "triple", "counter")),
]


# --------------------------------------------------------------- 자료 만들기

def load_games(include_scrims):
    """4v4 경기. 좌우는 씨앗을 고정해 무작위로 뒤집는다 — 전부 승팀을 왼쪽에 두면
    y 가 다 1이라 모형이 서지 않는다. 모든 모형이 같은 배치를 본다.
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
                        g["win_team"] == 0))

        if not include_scrims:
            continue
        for s in data["scrim_stats"]:
            if s.get("team_size") != TEAM_SIZE:
                continue
            blue = [c["champion"] for c in s["blue_stat"]]
            red = [c["champion"] for c in s["red_stat"]]
            if len(set(blue)) != TEAM_SIZE or len(set(red)) != TEAM_SIZE:
                continue
            if s["blue_score"] == s["red_score"]:
                continue
            raw.append((blue, red, None, None, s["blue_score"] > s["red_score"]))

    rnd = random.Random(20260830)
    games = []
    for blue, red, bt, rt, blue_won in raw:
        y = 1 if blue_won else 0
        if rnd.random() < 0.5:
            games.append({"left": blue, "right": red, "lt": bt, "rt": rt, "y": y})
        else:
            games.append({"left": red, "right": blue, "lt": rt, "rt": bt, "y": 1 - y})
    return games


# ------------------------------------------------------------------ 설계행렬

def build_features(games, blocks):
    """경기마다 (특성 번호, 부호) 목록을 만든다. 부호는 왼쪽 +1 · 오른쪽 −1.

    카운터 항은 **방향이 있는 쌍이 아니라 부호로** 표현한다. 사전순으로 앞선 챔피언이
    왼쪽에 있으면 +1, 오른쪽에 있으면 −1 이다. `c(A,B) = −c(B,A)` 가 자동으로 성립하고
    모수가 절반으로 준다.
    """
    index = {}
    block_of = []

    def feat(key, block):
        if key not in index:
            index[key] = len(block_of)
            block_of.append(block)
        return index[key]

    rows = []
    for g in games:
        left, right = g["left"], g["right"]
        terms = []
        if "champ" in blocks:
            for c in left:
                terms.append((feat(("c", c), "champ"), 1.0))
            for c in right:
                terms.append((feat(("c", c), "champ"), -1.0))
        if "team" in blocks:
            if g["lt"]:
                terms.append((feat(("t", g["lt"]), "team"), 1.0))
            if g["rt"]:
                terms.append((feat(("t", g["rt"]), "team"), -1.0))
        if "pair2" in blocks:
            for combo in combinations(sorted(left), 2):
                terms.append((feat(("p2", combo), "pair2"), 1.0))
            for combo in combinations(sorted(right), 2):
                terms.append((feat(("p2", combo), "pair2"), -1.0))
        if "triple" in blocks:
            for combo in combinations(sorted(left), 3):
                terms.append((feat(("p3", combo), "triple"), 1.0))
            for combo in combinations(sorted(right), 3):
                terms.append((feat(("p3", combo), "triple"), -1.0))
        if "counter" in blocks:
            for a in left:
                for b in right:
                    key = ("x", tuple(sorted((a, b))))
                    terms.append((feat(key, "counter"), 1.0 if a < b else -1.0))
        rows.append((terms, g["y"]))
    return rows, block_of, index


# -------------------------------------------------------------------- 적합

def fit(rows, nparam, ridge):
    """능형 로지스틱. 바깥은 IRLS, 안쪽은 좌표하강.

    모수가 수천 개라 정규방정식을 직접 풀 수 없다(가우스 소거는 k³ 이다). 좌표하강은
    한 번 훑는 비용이 **0 아닌 성분의 총수**와 같아서 모수가 늘어도 선형으로만 는다.
    """
    theta = [0.0] * nparam

    # 특성 → 그 특성이 나오는 (행, 부호)
    by_feat = defaultdict(list)
    for i, (terms, _) in enumerate(rows):
        for j, s in terms:
            by_feat[j].append((i, s))

    eta = [0.0] * len(rows)
    for _ in range(OUTER_ITERS):
        # IRLS 의 작업 가중치와 반응
        w = [0.0] * len(rows)
        resid = [0.0] * len(rows)
        for i, (_, y) in enumerate(rows):
            p = 1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, eta[i]))))
            wi = max(p * (1.0 - p), 1e-6)
            w[i] = wi
            resid[i] = (y - p) / wi          # z_i − eta_i

        denom = [0.0] * nparam
        for j, cells in by_feat.items():
            denom[j] = sum(w[i] for i, _ in cells) + ridge[j]

        moved = 0.0
        for _ in range(INNER_SWEEPS):
            for j, cells in by_feat.items():
                num = 0.0
                for i, s in cells:
                    num += w[i] * s * resid[i]
                delta = num / denom[j]
                if delta == 0.0:
                    continue
                # 능형은 θ 자체도 당긴다
                delta -= ridge[j] * theta[j] / denom[j]
                theta[j] += delta
                for i, s in cells:
                    resid[i] -= s * delta
                moved = max(moved, abs(delta))

        # eta 를 θ 로 다시 계산한다 (좌표하강이 남긴 누적 오차를 씻는다)
        for i, (terms, _) in enumerate(rows):
            e = 0.0
            for j, s in terms:
                e += s * theta[j]
            eta[i] = e

        if moved < 1e-7:
            break
    return theta


def loglik(rows, theta):
    """held-out 로그가능도의 **경기당 평균.** 크면 잘 맞춘 것이다."""
    total = 0.0
    for terms, y in rows:
        eta = 0.0
        for j, s in terms:
            eta += s * theta[j]
        p = 1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, eta))))
        total += math.log(max(p if y else 1.0 - p, 1e-12))
    return total / len(rows)


def accuracy(rows, theta):
    hit = 0
    for terms, y in rows:
        eta = 0.0
        for j, s in terms:
            eta += s * theta[j]
        if (eta > 0) == (y == 1):
            hit += 1
    return hit / len(rows)


# ---------------------------------------------------------------------- CV

def cross_validate(games, blocks, folds, base_ridge, extra_ridge, seed=7):
    """k겹 교차검증. 학습 폴드에서만 특성을 만들고 적합한다.

    **검증 폴드에만 나오는 조합은 특성이 없다** — 그건 정직한 처리다. 처음 보는 조합에
    대해 모형이 할 말이 없는 것이 맞고, 그 경우 남은 항으로만 예측한다.
    """
    rnd = random.Random(seed)
    order = list(range(len(games)))
    rnd.shuffle(order)

    per_game = [0.0] * len(games)          # 경기별 held-out 로그가능도
    hit = [0] * len(games)
    for f in range(folds):
        test_idx = set(order[f::folds])
        train = [games[i] for i in range(len(games)) if i not in test_idx]
        test = [games[i] for i in range(len(games)) if i in test_idx]

        test_ids = [i for i in range(len(games)) if i in test_idx]

        if not blocks:                                   # M0
            for i in test_ids:
                per_game[i] = math.log(0.5)
                hit[i] = -1          # M0 은 적중률을 정의하지 않는다
            continue

        train_rows, block_of, index = build_features(train, blocks)
        ridge = [base_ridge if b in ("champ", "team") else extra_ridge
                 for b in block_of]
        theta = fit(train_rows, len(block_of), ridge)

        # 검증 폴드를 학습 폴드의 특성 번호로 옮긴다 (없는 특성은 버린다)
        test_rows = project(test, blocks, index)
        for i, (terms, y) in zip(test_ids, test_rows):
            eta = 0.0
            for j, sg in terms:
                eta += sg * theta[j]
            p = 1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, eta))))
            per_game[i] = math.log(max(p if y else 1.0 - p, 1e-12))
            hit[i] = 1 if (eta > 0) == (y == 1) else 0
    return per_game, hit


def project(games, blocks, index):
    """검증 폴드를 학습 폴드의 특성 번호로 옮긴다. 처음 보는 키는 버린다."""
    out = []
    for g in games:
        left, right = g["left"], g["right"]
        terms = []

        def add(key, sign):
            j = index.get(key)
            if j is not None:
                terms.append((j, sign))

        if "champ" in blocks:
            for c in left:
                add(("c", c), 1.0)
            for c in right:
                add(("c", c), -1.0)
        if "team" in blocks:
            if g["lt"]:
                add(("t", g["lt"]), 1.0)
            if g["rt"]:
                add(("t", g["rt"]), -1.0)
        if "pair2" in blocks:
            for combo in combinations(sorted(left), 2):
                add(("p2", combo), 1.0)
            for combo in combinations(sorted(right), 2):
                add(("p2", combo), -1.0)
        if "triple" in blocks:
            for combo in combinations(sorted(left), 3):
                add(("p3", combo), 1.0)
            for combo in combinations(sorted(right), 3):
                add(("p3", combo), -1.0)
        if "counter" in blocks:
            for a in left:
                for b in right:
                    add(("x", tuple(sorted((a, b)))), 1.0 if a < b else -1.0)
        out.append((terms, g["y"]))
    return out


# -------------------------------------------------------------------- main

def mean(xs):
    return sum(xs) / len(xs)


def paired(full, base):
    """짝지은 비교 — 같은 경기에서 두 모형의 로그가능도 차이.

    경기마다 난이도가 다르므로 평균끼리 빼는 것보다 **경기별 차이의 흩어짐**을 보는 것이
    훨씬 예민하다. 차이의 표준오차로 t 를 낸다. |t| 가 2 를 넘지 못하면 그 이득은
    표본 흔들림과 구분되지 않는다.
    """
    d = [f - b for f, b in zip(full, base)]
    m = mean(d)
    if len(d) < 2:
        return m, 0.0, 0.0
    var = sum((x - m) ** 2 for x in d) / (len(d) - 1)
    se = math.sqrt(var / len(d))
    return m, se, (m / se if se > 0 else 0.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--folds", type=int, default=5)
    ap.add_argument("--scrims", action="store_true")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    games = load_games(args.scrims)
    print("=" * 78)
    print("held-out 예측 비교 — 조합 효과가 못 본 경기를 더 잘 맞추나")
    print("=" * 78)
    print(f"  경기 {len(games)}건 · {args.folds}겹 교차검증 · 씨앗 {args.seed}")
    print("  값은 **경기당 평균 로그가능도**. 0 에 가까울수록 잘 맞춘 것이다.")
    print("  50% 고정 모형이 -0.6931 이다.")
    print()

    results = {}
    for tag, label, blocks in MODELS:
        best = None
        if not blocks:
            bases, extras = [0.0], [0.0]
        elif set(blocks) <= {"champ", "team"}:
            bases, extras = BASE_GRID, [0.0]
        else:
            bases, extras = BASE_GRID, EXTRA_GRID
        for lb in bases:
            for le in extras:
                ll, hit = cross_validate(games, blocks, args.folds, lb, le, args.seed)
                if best is None or mean(ll) > mean(best[0]):
                    best = (ll, hit, lb, le)
        results[tag] = best
        ll, hit, lb, le = best
        cfg = "—" if not blocks else (f"λ기저={lb:.0f}" +
                                      (f" λ조합={le:.0f}" if le else ""))
        acc = "  —  " if not blocks else f"{mean(hit)*100:5.1f}%"
        print(f"  {tag} {label:<14} {mean(ll):+.4f}  적중 {acc}  {cfg}")
    print()

    print("-" * 78)
    print("  무엇이 값어치가 있나 — 짝지은 비교 (경기당 로그가능도 이득)")
    print("-" * 78)
    print("  | 성분 | 비교 | 이득 | 표준오차 | t |")
    print("  |---|---|---|---|---|")
    comparisons = [
        ("챔피언 강도", "M0", "M1"),
        ("팀 강도", "M1", "M2"),
        ("2인 시너지", "M2", "M3"),
        ("3인 시너지 (2인을 넣은 뒤)", "M3", "M4"),
        ("카운터", "M2", "M5"),
        ("전부 함께", "M2", "M6"),
    ]
    for label, base, full in comparisons:
        m, se, t = paired(results[full][0], results[base][0])
        verdict = "**있다**" if t > 2 else ("없다" if t > -2 else "**해롭다**")
        print(f"  | {label} | {base}→{full} | {m:+.4f} | {se:.4f} | {t:+.2f} | {verdict}")
    print()
    print("  |t| ≤ 2 는 표본 흔들림과 구분되지 않는다는 뜻이다.")
    print("  λ 를 held-out 에서 골랐으므로 조합 모형에 **유리한 쪽으로 기운** 비교다.")
    print("  그렇게 해주고도 t 가 2 를 못 넘으면 그 성분은 예측에 쓸 것이 없다.")


if __name__ == "__main__":
    main()
