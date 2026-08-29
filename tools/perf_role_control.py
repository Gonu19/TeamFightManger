"""역할군 통제 — "이 조합이 좋다" 인가 "이런 구성 균형이 좋다" 인가.

D63 이 남긴 주의다. 챔피언별 출력에서 동료·상대 효과가 크게 나왔는데(t 15~19),
그중 얼마가 **챔피언 고유**이고 얼마가 **역할군 구성**인지 나누지 못했다.

    상대가 탱커를 많이 넣으면 **모두의** 딜이 오른다.
    그것은 진짜 구성 효과이지만 "이 챔피언이 저 챔피언에게 강하다" 와는 다르다.

이 구분이 화면 문구를 가른다.

    역할군이 대부분을 설명하면 → "전사2 + 마법사1 + 보조1 이 좋다" (D11 이 4인에서 낸 결론)
    챔피언 쌍이 살아남으면   → "Chef 옆의 Werewolf 가 좋다"        (사용자의 가설)

**방법.** D63 과 같은 모형·같은 교차검증에 역할군 블록을 더한다. 역할군은 챔피언의
고정 속성이므로(D05) 쌍 특성을 역할군 쌍으로 뭉갠 것이 된다 — 5×5 = 25개.

    mrole  (A의 역할군, 동료 B의 역할군)   25개
    frole  (A의 역할군, 상대 C의 역할군)   25개
    mate   (A, 동료 B)                    1,560개
    foe    (A, 상대 C)                    1,560개

**핵심 비교는 하나다.** 역할군을 **먼저 넣은 뒤에** 챔피언 쌍이 무엇을 더 하는가.
그것이 남으면 챔피언 고유 효과가 실재한다.

역할군 매핑의 원본은 `V3__seed_champions.sql` 하나다(D05). 여기에 복제하지 않는다.

    python tools/perf_role_control.py
    python tools/perf_role_control.py --metric tanking
"""

import argparse

import perf_champion as pc

MODELS = [
    ("R0", "챔피언 평균만", ()),
    ("R1", "+ 팀 강도", ("team",)),
    ("R2", "+ 역할군 구성", ("team", "mrole", "frole")),
    ("R3", "+ 챔피언 쌍", ("team", "mate", "foe")),
    ("R4", "+ 역할군 + 챔피언 쌍", ("team", "mrole", "frole", "mate", "foe")),
]

COMPARISONS = [
    ("팀 강도", "R0", "R1"),
    ("역할군 구성 (단독)", "R1", "R2"),
    ("챔피언 쌍 (단독)", "R1", "R3"),
    ("**챔피언 쌍 — 역할군 통제 후**", "R2", "R4"),
    ("역할군 — 챔피언 쌍 통제 후", "R3", "R4"),
]


def show_top(rows, args):
    """역할군을 통제한 채 전체로 적합해 방향 있는 쌍을 뽑는다.

    **이 값은 검증된 추정치가 아니다.** 교차검증은 "효과가 있다" 까지만 말했고,
    개별 쌍의 크기는 표본이 얇으면 그만큼 흔들린다. 관측 수를 같이 찍는 이유다.
    화면에 낼 때는 D13·D60 의 "판정 불가" 규칙을 그대로 적용해야 한다.
    """
    blocks = ("team", "mrole", "frole", "mate", "foe")
    tr_z, _ = pc.standardize(rows, rows[:1])
    built, index = pc.build(tr_z, blocks)
    kinds = pc.block_of_key(index)
    ridge = [64.0 if k == "team" else 16.0 for k in kinds]
    theta = pc.fit(built, len(index), ridge)

    counts = {}
    for r in rows:
        for b in r["mates"]:
            counts[("m", r["champ"], b)] = counts.get(("m", r["champ"], b), 0) + 1
        for c in r["foes"]:
            counts[("f", r["champ"], c)] = counts.get(("f", r["champ"], c), 0) + 1

    # 부호의 뜻은 지표마다 다르다. `dealing` 에서 상대 효과가 양수인 것은
    # "그 상대가 내 딜을 흡수해 준다" 는 뜻이지 "내가 강하다" 가 아니다.
    # `death` 에서 양수는 "그 상대를 만나면 더 죽는다" — 이쪽이 카운터의 뜻에 맞는다.
    for kind, title in (("m", "동료 — 같은 팀에 있을 때의 변화"),
                        ("f", "상대 — 맞은편에 있을 때의 변화")):
        picked = [(theta[j], key, counts.get(key, 0))
                  for key, j in index.items()
                  if key[0] == kind and counts.get(key, 0) >= 20]
        picked.sort(key=lambda t: -abs(t[0]))
        print()
        print(f"  {title} — 관측 20경기 이상, 상위 {args.top} (지표 `{args.metric}` 의 σ)")
        print(f"  | 대상 챔피언 | 상대 | 효과(σ) | 관측 |")
        print(f"  |---|---|---|---|")
        for val, key, n in picked[:args.top]:
            print(f"  | {key[1]} | {key[2]} | {val:+.3f} | {n} |")
    print()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--metric", choices=pc.METRICS, default="dealing")
    ap.add_argument("--folds", type=int, default=5)
    ap.add_argument("--scrims", action="store_true")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--top", type=int, default=0,
                    help="역할군을 통제한 채 전체로 적합해 상위 쌍을 뽑는다")
    args = ap.parse_args()

    pc.ROLES = pc.load_roles()
    rows = pc.load_rows(args.metric, args.scrims)
    for i, r in enumerate(rows):
        r["match"] = i // (pc.TEAM_SIZE * 2)
    if args.limit:
        rows = [r for r in rows if r["match"] < args.limit]

    print("=" * 78)
    print(f"역할군 통제 — 챔피언 고유인가 구성 균형인가 · 지표 `{args.metric}`")
    print("=" * 78)
    print(f"  관측 {len(rows)}행 = 경기 {len(rows)//8}건 × 8명"
          f" · {args.folds}겹 · 씨앗 {args.seed}")
    print("  역할군 쌍은 25개, 챔피언 쌍은 1,560개다. 역할군이 훨씬 적은 모수로")
    print("  같은 일을 해내면 그쪽이 맞는 설명이다.")
    print()

    results = {}
    for tag, label, blocks in MODELS:
        best = None
        if not blocks:
            grid = [(0.0, 0.0)]
        elif set(blocks) <= {"team"}:
            grid = [(b, 0.0) for b in pc.BASE_GRID]
        else:
            grid = [(b, e) for b in pc.BASE_GRID for e in pc.EXTRA_GRID]
        for lb, le in grid:
            err = pc.cross_validate(rows, blocks, args.folds, lb, le, args.seed)
            if best is None or pc.mean(err) < pc.mean(best[0]):
                best = (err, lb, le)
        results[tag] = best
        err, lb, le = best
        r2 = 1.0 - pc.mean(err) / pc.mean(results["R0"][0])
        cfg = "—" if not blocks else (f"λ팀={lb:.0f}" +
                                      (f" λ조합={le:.0f}" if le else ""))
        print(f"  {tag} {label:<20} 오차 {pc.mean(err):.4f}  R² {r2:+.4f}  {cfg}")
    print()

    print("-" * 78)
    print("  짝지은 비교")
    print("-" * 78)
    print("  | 성분 | 비교 | 오차 감소 | t |")
    print("  |---|---|---|---|")
    for label, base, full in COMPARISONS:
        gain = [b - f for b, f in zip(results[base][0], results[full][0])]
        m, t = pc.paired_t(gain)
        verdict = "**있다**" if t > 2 else ("없다" if t > -2 else "**해롭다**")
        print(f"  | {label} | {base}→{full} | {m:+.5f} | {t:+.2f} | {verdict}")
    print()
    if args.top:
        show_top(rows, args)
    print("  R2→R4 가 핵심이다. 이 값이 0 에 가까우면 챔피언 쌍은 역할군의 다른 이름이고,")
    print("  화면은 '구성 균형' 을 말해야 한다 (D11 이 4인 조합에서 내린 결론과 같아진다).")


if __name__ == "__main__":
    main()
