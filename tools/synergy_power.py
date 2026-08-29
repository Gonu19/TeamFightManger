"""검정력 — 시너지가 **얼마나 커야** 지금 표본으로 보이나.

D60 은 "2인 시너지가 예측을 유의하게 개선하지 못한다" 고 적었다. 그런데 그것은
**효과가 없다** 는 뜻이 아니라 **이 표본으로는 안 보인다** 는 뜻이다. 둘은 다르다.
어느 쪽인지 가르려면 반대로 물어야 한다 — *효과가 있다면 얼마나 커야 우리가 잡나?*

방법은 심는 것이다. `champ + team` 모형을 적합해 각 경기의 기저 확률을 얻은 뒤,
**크기를 아는 가짜 2인 시너지를 로그 승산에 심고** 승패를 다시 뽑는다. 그리고
`synergy_holdout.py` 와 똑같은 held-out 비교를 돌린다. 효과의 참값을 우리가 정했으므로
"이 크기는 몇 번 중 몇 번 잡히나" 가 그대로 나온다.

읽는 법. `sd` 는 조합 하나가 팀의 로그 승산에 더하는 값의 표준편차다. 승률로 옮기면
`Δp ≈ sd/4` 다 (0.5 근처에서). 팀 하나에 조합이 6개이므로 팀 전체로는 `√6·sd` 가 된다.

    python tools/synergy_power.py
    python tools/synergy_power.py --repeats 20
"""

import argparse
import math
import random
from itertools import combinations

from synergy_holdout import (BASE_GRID, build_features, cross_validate, fit,
                             load_games, mean, paired)

M2 = ("champ", "team")
M3 = ("champ", "team", "pair2")
SD_GRID = [0.0, 0.10, 0.20, 0.30, 0.45, 0.60]
FIT_RIDGE = 16.0          # held-out 이 고른 값. 심기와 검정에 같은 값을 쓴다
EXTRA_RIDGE = 16.0


def base_probs(games):
    """`champ + team` 만으로 각 경기의 '왼쪽 승' 확률을 얻는다. 심기의 바탕이다."""
    rows, block_of, _ = build_features(games, M2)
    theta = fit(rows, len(block_of), [FIT_RIDGE] * len(block_of))
    out = []
    for terms, _ in rows:
        eta = 0.0
        for j, s in terms:
            eta += s * theta[j]
        out.append(eta)
    return out


def inject(games, base_eta, sd, rnd):
    """크기를 아는 가짜 2인 시너지를 심고 승패를 다시 뽑는다."""
    effect = {}

    def eff(combo):
        if combo not in effect:
            effect[combo] = rnd.gauss(0.0, sd) if sd > 0 else 0.0
        return effect[combo]

    out = []
    for g, eta in zip(games, base_eta):
        for combo in combinations(sorted(g["left"]), 2):
            eta += eff(combo)
        for combo in combinations(sorted(g["right"]), 2):
            eta -= eff(combo)
        p = 1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, eta))))
        out.append({**g, "y": 1 if rnd.random() < p else 0})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repeats", type=int, default=10)
    ap.add_argument("--folds", type=int, default=5)
    ap.add_argument("--scrims", action="store_true")
    args = ap.parse_args()

    games = load_games(args.scrims)
    eta = base_probs(games)

    print("=" * 78)
    print("검정력 — 2인 시너지가 얼마나 커야 지금 표본으로 보이나")
    print("=" * 78)
    print(f"  경기 {len(games)}건 · {args.folds}겹 · 크기마다 {args.repeats}회 반복")
    print("  크기를 아는 가짜 시너지를 심고, D60 과 같은 held-out 비교를 돌린다.")
    print("  '잡힘' 은 M2→M3 의 짝지은 t 가 2 를 넘은 비율이다.")
    print()
    print("  | 심은 시너지 sd | 조합당 승률 영향 | 팀 전체 | 잡힘 | 평균 t |")
    print("  |---|---|---|---|---|")

    for sd in SD_GRID:
        hits, ts = 0, []
        for r in range(args.repeats):
            rnd = random.Random(1000 + r)
            sim = inject(games, eta, sd, rnd)
            ll2, _ = cross_validate(sim, M2, args.folds, FIT_RIDGE, 0.0, seed=r + 1)
            ll3, _ = cross_validate(sim, M3, args.folds, FIT_RIDGE, EXTRA_RIDGE,
                                    seed=r + 1)
            _, _, t = paired(ll3, ll2)
            ts.append(t)
            if t > 2:
                hits += 1
        dp = sd / 4 * 100
        team = math.sqrt(6) * sd / 4 * 100
        print(f"  | {sd:.2f} | ±{dp:.1f}%p | ±{team:.1f}%p |"
              f" {hits}/{args.repeats} | {mean(ts):+.2f} |")

    print()
    print("  실제 데이터의 t 는 0.75 ~ 1.65 였다 (D60). 위 표에서 그 t 를 내는 줄이")
    print("  **우리가 배제하지 못한 시너지 크기**다. 그보다 작은 효과는 있어도 안 보인다.")


if __name__ == "__main__":
    main()
