"""세이브 파일에서 경기 데이터를 꺼낸다 — 레퍼런스 구현.

`nrbf.py` 가 만든 객체 그래프에서 도메인 값을 뽑는다.
Java 의 `parser/save/` 가 이 결과와 일치해야 한다.

지키는 규칙 (savefile.md / decision.md):
  * 진영은 챔피언 이름으로 매칭한다. ChampStat 인덱스 순서를 쓰지 않는다 (D20)
  * 현재 날짜는 TodayData 에서 읽는다. max(GameStat.Day) 로 추정하지 않는다 (D18)
  * 밴 배열 길이를 상수로 가정하지 않는다. 커리어 초반 데이터에 2밴이 있다 (D26)
  * 세이브 파일은 읽기만 한다
"""
from __future__ import annotations

import sys

from nrbf import NrbfObject, parse_file

# List<T> 의 실제 원소는 _items 에 있고 _size 만큼만 유효하다.
# 뒤쪽은 용량 여유분이라 None 이 들어 있다 — 길이로 세면 틀린다.
_LIST_ITEMS = "_items"
_LIST_SIZE = "_size"

# 백킹 필드는 `<Name>k__BackingField` 로 직렬화된다.
_BACKING = "k__BackingField"


def _unwrap_enum(value):
    """.NET enum 은 `value__` 하나짜리 객체로 직렬화된다. 정수로 되돌린다.

    TeamType(0=BLUE, 1=RED), ChampionCategory 등이 전부 이 모양이다.
    풀지 않으면 값 비교도 직렬화도 안 된다.
    """
    if isinstance(value, NrbfObject) and list(value.members) == ["value__"]:
        return value.members["value__"]
    return value


def _member(obj, name):
    """자동 프로퍼티의 백킹 필드까지 찾아보고, enum 은 정수로 푼다."""
    if not isinstance(obj, NrbfObject):
        return None
    if name in obj.members:
        return _unwrap_enum(obj.members[name])
    return _unwrap_enum(obj.members.get("<%s>%s" % (name, _BACKING)))


def _list(obj):
    """List<T> 를 파이썬 리스트로. _size 를 넘는 여유분은 버린다."""
    if obj is None:
        return []
    if isinstance(obj, list):
        return obj
    items = obj.members.get(_LIST_ITEMS)
    size = obj.members.get(_LIST_SIZE)
    if items is None:
        return []
    return items[:size] if isinstance(size, int) else [x for x in items if x is not None]


def _walk(parser, class_name):
    """스트림 안의 특정 클래스 인스턴스를 전부 모은다."""
    return [v for v in parser.objects.values()
            if isinstance(v, NrbfObject) and v.class_name == class_name]


def _stat(obj):
    """MatchStat 하나를 평평한 dict 로."""
    return {
        "champion": _member(obj, "Champion"),
        "kill": _member(obj, "Kill"),
        "death": _member(obj, "Death"),
        "assist": _member(obj, "Assist"),
        "dealing": _member(obj, "Dealing"),
        "tanking": _member(obj, "Tanking"),
        "healing": _member(obj, "Healing"),
        "live_duration": _member(obj, "LiveDuration"),
    }


def _champ_stats(obj):
    """AthleteMatchStat 목록 → (athlete_id, MatchStat) 평평한 dict 목록."""
    out = []
    for a in _list(obj):
        if a is None:
            continue
        inner = _member(a, "Stat")
        row = _stat(inner) if isinstance(inner, NrbfObject) else _stat(a)
        row["athlete_id"] = _member(a, "AthleteID")
        out.append(row)
    return out


def _names(obj):
    """List<string> → 파이썬 문자열 목록. None 은 버린다(밴이 2개인 경기)."""
    return [x for x in _list(obj) if x is not None]


def read_game_stats(parser):
    """GameStat — 공식 경기. 세트 단위다(다전제는 스케줄 1건에 세트 여러 개)."""
    out = []
    for g in _walk(parser, "GameStat"):
        out.append({
            "id": _member(g, "ID"),
            "schedule_id": _member(g, "ScheduleID"),
            "season": _member(g, "Season"),
            "day": _member(g, "Day"),
            "set_no": _member(g, "Set"),
            "blue_team_id": _member(g, "BlueTeamID"),
            "red_team_id": _member(g, "RedTeamID"),
            "blue_score": _member(g, "BlueScore"),
            "red_score": _member(g, "RedScore"),
            "win_team": _member(g, "WinTeam"),          # 0 = BLUE, 1 = RED
            "blue_ban": _names(_member(g, "BlueBan")),
            "blue_pick": _names(_member(g, "BluePick")),
            "red_ban": _names(_member(g, "RedBan")),
            "red_pick": _names(_member(g, "RedPick")),
            "champ_stat": _champ_stats(_member(g, "ChampStat")),
            "is_overtime": _member(g, "IsOvertime"),
            "is_sudden_death": _member(g, "IsSuddenDeath"),
        })
    out.sort(key=lambda r: (r["id"] if r["id"] is not None else -1))
    return out


def read_scrim_stats(parser):
    """ScrimStat — 연습경기. 밴이 없고 Season/Day 도 없다."""
    out = []
    for s in _walk(parser, "ScrimStat"):
        blue = [_stat(x) for x in _list(_member(s, "BlueStat")) if x is not None]
        red = [_stat(x) for x in _list(_member(s, "RedStat")) if x is not None]
        out.append({
            "id": _member(s, "ID"),
            "team_id": _member(s, "TeamID"),
            "blue_score": _member(s, "BlueScore"),
            "red_score": _member(s, "RedScore"),
            "blue_stat": blue,
            "red_stat": red,
            "team_size": max(len(blue), len(red)),
        })
    out.sort(key=lambda r: (r["id"] if r["id"] is not None else -1))
    return out


def read_patches(parser):
    """PatchNews — 패치 이력. 사용자 입력이 필요 없다."""
    out = []
    for n in _walk(parser, "PatchNews"):
        date = _member(n, "Date")
        changes = []
        for p in _list(_member(n, "Patches")):
            if p is None:
                continue
            changes.append({
                "name": _member(p, "Name"),
                "attack": _member(p, "Attack"),
                "magic": _member(p, "Magic"),
                "defence": _member(p, "Defence"),
                "max_hp": _member(p, "MaxHp"),
                "attack_speed": _member(p, "AttackSpeed"),
                "skill_cool": _member(p, "SkillCool"),
                "move_speed": _member(p, "MoveSpeed"),
            })
        if not changes and not _names(_member(n, "NewChamps")):
            continue                                   # 패치가 아닌 일반 뉴스
        out.append({
            "season": _member(date, "Season"),
            "day": _member(date, "Day"),
            "run": _member(date, "Run"),
            "new_champs": _names(_member(n, "NewChamps")),
            "changes": sorted(changes, key=lambda c: c["name"] or ""),
        })
    out.sort(key=lambda r: (r["season"] or 0, r["day"] or 0, r["run"] or 0))
    return out


def read_today(parser):
    """현재 게임 내 날짜. max(GameStat.Day) 로 추정하면 최대 5일 뒤처진다 (D18)."""
    for t in _walk(parser, "TodayData"):
        time = _member(t, "Time")
        if time is None:
            continue
        return {
            "season": _member(time, "Season"),
            "day": _member(time, "Day"),
            "run": _member(time, "Run"),
        }
    return None


def read_save(path):
    """세이브 파일 하나에서 경기 스트림(1번)을 읽어 도메인 값으로."""
    parser = parse_file(path, streams={1})[1]
    return {
        "today": read_today(parser),
        "patches": read_patches(parser),
        "game_stats": read_game_stats(parser),
        "scrim_stats": read_scrim_stats(parser),
    }


def summarize(data):
    """골든 파일에 넣기 전에 눈으로 확인하는 요약."""
    games = data["game_stats"]
    scrims = data["scrim_stats"]
    ban_counts = {}
    for g in games:
        for side in ("blue_ban", "red_ban"):
            n = len(g[side])
            ban_counts[n] = ban_counts.get(n, 0) + 1
    sizes = {}
    for s in scrims:
        sizes[s["team_size"]] = sizes.get(s["team_size"], 0) + 1
    return {
        "today": data["today"],
        "patches": len(data["patches"]),
        "games": len(games),
        "scrims": len(scrims),
        "seasons": sorted({g["season"] for g in games if g["season"] is not None}),
        "ban_count_distribution": dict(sorted(ban_counts.items())),
        "scrim_team_size_distribution": dict(sorted(sizes.items())),
        "champions": len(sorted({c for g in games for c in g["blue_pick"] + g["red_pick"]})),
    }


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    data = read_save(argv[0])
    s = summarize(data)
    print(argv[0])
    for k, v in s.items():
        print("  %-30s %s" % (k, v))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
