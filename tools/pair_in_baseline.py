"""골든 파일에서 두 챔피언의 대면 수를 센다 — D35 제외 이전의 값이다.

DB 에는 D35(4v4 · 픽 4 · 밴 3)를 통과한 경기만 들어 있다. 그래서 DB 의 표본 수는
`decisions/` 의 옛 측정값보다 작을 수 있고, **얼마나 작은지는 쌍마다 다르다** —
제외된 경기가 특정 커리어에 몰려 있기 때문이다.

문서의 숫자와 DB 가 어긋날 때, 그것이 버그인지 D35 때문인지 여기서 갈린다.
골든 파일은 파서 출력 전체라 제외 이전 값을 담고 있다.

    python tools/pair_in_baseline.py Werewolf Swordman
    python tools/pair_in_baseline.py            # 기본 두 쌍을 비교한다

실제로 이걸로 D49 의 미확인을 풀었다. Werewolf vs Swordman 은 골든 25 · DB 13 인데
25건 중 22건이 제외가 일어난 커리어에 있었고, Fighter vs DarkMage 는 골든 25 · DB 25 로
그 커리어에 0건이었다. 같은 문서, 같은 25, 다른 결과.
"""

import glob
import io
import json
import os
import sys

BASELINE_GLOB = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "tests", "baseline", "slot_*.json")


def _scrim_side(entries):
    """스크림의 한쪽 진영에 나온 챔피언 이름. 표현이 두 가지라 둘 다 받는다."""
    return {e["champion"] if isinstance(e, dict) else e for e in entries or []}


def count_pair(path, a, b):
    """(적팀 대면 수, 같은 팀 수). 한 경기는 둘 중 하나로만 센다."""
    data = json.load(io.open(path, encoding="utf-8"))
    enemy = ally = 0

    for game in data["game_stats"]:
        blue, red = set(game["blue_pick"]), set(game["red_pick"])
        if (a in blue and b in red) or (a in red and b in blue):
            enemy += 1
        elif (a in blue and b in blue) or (a in red and b in red):
            ally += 1

    for scrim in data["scrim_stats"]:
        blue = _scrim_side(scrim.get("blue_stat"))
        red = _scrim_side(scrim.get("red_stat"))
        if (a in blue and b in red) or (a in red and b in blue):
            enemy += 1
        elif (a in blue and b in blue) or (a in red and b in red):
            ally += 1

    return enemy, ally


def report(a, b):
    paths = sorted(glob.glob(BASELINE_GLOB))
    if not paths:
        raise SystemExit("골든 파일이 없다: " + BASELINE_GLOB)

    total_enemy = total_ally = 0
    print("%s vs %s" % (a, b))
    for path in paths:
        enemy, ally = count_pair(path, a, b)
        total_enemy += enemy
        total_ally += ally
        print("  %-32s 적팀 %3d · 같은 팀 %3d" % (os.path.basename(path), enemy, ally))
    print("  %-32s 적팀 %3d · 같은 팀 %3d" % ("합계 (D35 제외 이전)", total_enemy, total_ally))
    print()


if __name__ == "__main__":
    if len(sys.argv) == 3:
        report(sys.argv[1], sys.argv[2])
    elif len(sys.argv) == 1:
        report("Werewolf", "Swordman")
        report("Fighter", "DarkMage")
    else:
        raise SystemExit("사용법: python tools/pair_in_baseline.py [챔피언A 챔피언B]")
