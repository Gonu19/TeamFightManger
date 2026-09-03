package com.teamfighter.tfm.analysis.scrim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 스크림에서 <b>해 볼 덱</b>을 고른다.
 *
 * <h2>무엇을 추천하는가 — 메타를 잡을 픽이다</h2>
 *
 * 지금 승률이 좋은 챔피언을 <b>누가 잡는가</b>가 첫째 기준이다. 근거는 지어내지 않고
 * 쌍 효과 표에서 가져온다: 그 상대가 맞은편에 있을 때 메타 상위가 얼마나 <b>더 죽는가</b>
 * (σ). 축은 {@code DEATH} 다 — 딜로 가르면 정확히 거꾸로 읽힌다 (D64 결정 3).
 *
 * <h2>가장 센 카운터가 아니라 가장 덜 확인된 카운터를 고른다</h2>
 *
 * 40번 본 카운터는 이미 아는 것이라 스크림에서 확인할 것이 없다 — 그냥 쓰면 된다.
 * 11번 본 카운터는 릿지에 세게 눌린 값이라(관측 10 + 릿지 16) 아직 흔들린다.
 * <b>스크림은 그 흔들림을 줄이는 자리다.</b>
 *
 * <h2>카운터가 모자라면 발굴로 채운다</h2>
 *
 * 문턱을 넘는 카운터가 넷에 못 미칠 수 있다. 그때 남는 자리는 <b>아무 데서도 안 나온
 * 조합</b>을 만드는 픽으로 채운다 — 근거가 있어서가 아니라 <b>근거가 없다는 사실</b>이
 * 이유다 (D62: 없는 게 아니라 안 보이는 것이다).
 *
 * <h2>강함을 말하지 않는다</h2>
 *
 * 덱 <b>전체</b>가 세다고는 말하지 않는다. 4인 조합은 실측 최다 9경기라 통계가 아니고
 * (D11), 시너지가 실재한다고 확인된 것은 2인까지다 (D60). 이 클래스가 말하는 것은
 * <b>자리마다의 주장</b>뿐이고, 그 주장은 전부 쌍 하나에서 나온다.
 */
public final class ScrimSuggester {

    /** 한 덱의 챔피언 수. 이 게임은 팀당 4픽 고정이다 (D35). */
    public static final int DECK_SIZE = 4;

    /** 한 덱이 만드는 듀오 쌍의 수 — {@code C(4,2)}. */
    public static final int PAIRS_PER_DECK = DECK_SIZE * (DECK_SIZE - 1) / 2;

    /**
     * 한 덱에 있어야 하는 최소 역할군 수.
     *
     * <p><b>잰 값이다.</b> 슬롯 1의 공식전 라인업 588개에서 역할군 2종이 13.6%,
     * 3종이 60.4%, 4종이 26.0% 였고 <b>1종은 0건</b>이었다. 한 역할군으로만 채운 덱은
     * 실제로 아무도 안 쓴다 — 그런 덱을 추천하면 첫 줄에서 신뢰를 잃는다.
     *
     * <p>3종을 요구하지 않는 이유는 2종이 실제로 13.6% 나오기 때문이다.
     * 관측된 것을 금지하지 않는다.
     */
    private static final int MIN_ROLES = 2;

    private ScrimSuggester() {
    }

    /**
     * 카운터를 앞세워 덱을 고른다.
     *
     * <p><b>덱끼리 챔피언이 겹치지 않는다.</b> 겹치면 목록이 넷이어도 고를 것은 하나다.
     *
     * <p><b>결정적이다.</b> 같은 입력이면 같은 순서로 같은 덱이 나온다 — 같은 값에서
     * 챔피언 번호로 갈라 준다. 새로고침할 때마다 추천이 바뀌면 사용자는 그것을
     * 추천이 아니라 잡음으로 읽는다.
     *
     * @param counters  메타 상위를 잡는 픽. 한 챔피언이 여러 대상을 잡으면 여러 줄로 온다
     * @param pool      발굴 자리를 채울 후보 (전체 챔피언)
     * @param coverage  무엇을 이미 봤나
     * @param deckCount 만들 덱 수
     */
    public static List<ScrimDeck> suggest(List<CounterPick> counters,
                                          List<ScrimCandidate> pool,
                                          PairCoverage coverage,
                                          int deckCount) {
        Objects.requireNonNull(counters, "counters");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(coverage, "coverage");
        if (deckCount <= 0 || pool.size() < DECK_SIZE) {
            return List.of();
        }

        // 한 챔피언이 여러 메타 상위를 잡을 수 있다. 그중 <b>가장 덜 확인된</b> 줄을
        // 대표로 남긴다 — 스크림에서 확인할 값어치가 그쪽에 있기 때문이다.
        List<CounterPick> best = bestPerChampion(counters);

        // 덜 확인된 것 먼저. 그다음 세게 잡는 것, 마지막으로 번호(결정성).
        best.sort(Comparator.comparingInt(CounterPick::observations)
                .thenComparing(Comparator.comparingDouble(CounterPick::effect).reversed())
                .thenComparingInt(CounterPick::championId));

        // 발굴 자리는 덜 나온 챔피언부터.
        List<ScrimCandidate> fill = new ArrayList<>(pool);
        fill.sort(Comparator.comparingInt(ScrimCandidate::games)
                .thenComparingInt(ScrimCandidate::championId));

        Set<Integer> used = new HashSet<>();
        List<ScrimDeck> decks = new ArrayList<>();

        while (decks.size() < deckCount) {
            List<ScrimDeck.DeckSlot> deck = buildOne(best, fill, coverage, used);
            if (deck == null) {
                break;
            }
            deck.forEach(s -> used.add(s.championId()));
            decks.add(score(deck, coverage));
        }

        // 스크림에서 확인할 자리가 많은 덱이 먼저. 같으면 잡는 대상이 많은 덱,
        // 그것도 같으면 만든 순서 그대로.
        decks.sort(Comparator.comparingLong(ScrimDeck::toTest).reversed()
                .thenComparing(Comparator.comparingInt((ScrimDeck d) -> d.targets().size()).reversed()));
        return List.copyOf(decks);
    }

    /**
     * 챔피언마다 대표 카운터 한 줄.
     *
     * <p>같은 챔피언이 여러 메타 상위를 잡으면 <b>관측이 가장 얇은</b> 줄을 남긴다.
     * 가장 센 줄을 남기면 이미 40번 확인한 주장이 대표가 되어, 정작 스크림에서
     * 확인할 것이 화면에서 사라진다.
     */
    private static List<CounterPick> bestPerChampion(List<CounterPick> counters) {
        java.util.Map<Integer, CounterPick> byChampion = new java.util.LinkedHashMap<>();
        for (CounterPick p : counters) {
            byChampion.merge(p.championId(), p, (a, b) -> {
                if (a.observations() != b.observations()) {
                    return a.observations() < b.observations() ? a : b;
                }
                return a.effect() >= b.effect() ? a : b;
            });
        }
        return new ArrayList<>(byChampion.values());
    }

    /**
     * 덱 하나. <b>카운터로 먼저 채우고 모자라면 발굴로 채운다.</b>
     *
     * <p>카운터를 고를 때 <b>이미 잡기로 한 대상은 건너뛴다</b> — 같은 메타 상위를
     * 셋이 잡는 덱은 하나를 잡는 덱보다 나을 것이 없다. 넷을 다 카운터로 채울 수
     * 있어도 대상이 겹치면 발굴 쪽이 더 알려준다.
     */
    private static List<ScrimDeck.DeckSlot> buildOne(List<CounterPick> counters,
                                                     List<ScrimCandidate> fill,
                                                     PairCoverage coverage,
                                                     Set<Integer> used) {
        List<ScrimDeck.DeckSlot> deck = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();

        for (CounterPick p : counters) {
            if (deck.size() == DECK_SIZE) {
                break;
            }
            if (used.contains(p.championId()) || has(deck, p.championId())) {
                continue;
            }
            if (!targets.add(p.targetNameKo())) {
                continue;                                       // 이미 잡기로 한 대상이다
            }
            deck.add(ScrimDeck.DeckSlot.of(p));
        }

        if (deck.isEmpty()) {
            return null;                                        // 남은 카운터가 없다
        }

        while (deck.size() < DECK_SIZE) {
            ScrimCandidate best = null;
            int bestGain = -1;
            for (ScrimCandidate c : fill) {
                if (used.contains(c.championId()) || has(deck, c.championId())) {
                    continue;
                }
                if (deck.size() == DECK_SIZE - 1 && roles(deck, c) < MIN_ROLES) {
                    continue;
                }
                int gain = 0;
                for (ScrimDeck.DeckSlot in : deck) {
                    if (coverage.unexplored(in.championId(), c.championId())) {
                        gain++;
                    }
                }
                if (gain > bestGain) {                          // fill 이 이미 정렬돼 있어 동점이면 앞이 이긴다
                    bestGain = gain;
                    best = c;
                }
            }
            if (best == null) {
                return null;                                    // 역할군 조건을 채울 후보가 없다
            }
            deck.add(ScrimDeck.DeckSlot.of(best));
        }
        return deck;
    }

    private static boolean has(List<ScrimDeck.DeckSlot> deck, int championId) {
        return deck.stream().anyMatch(s -> s.championId() == championId);
    }

    /** 이 덱에 {@code extra} 를 넣었을 때의 역할군 수. */
    private static int roles(List<ScrimDeck.DeckSlot> deck, ScrimCandidate extra) {
        Set<String> seen = new HashSet<>();
        deck.forEach(s -> seen.add(s.category()));
        seen.add(extra.category());
        return seen.size();
    }

    /** 여섯 쌍을 세 갈래로 나눈다: 모르는 것 · 스크림에서만 해 본 것 · 공식전으로 아는 것. */
    private static ScrimDeck score(List<ScrimDeck.DeckSlot> deck, PairCoverage coverage) {
        int unexplored = 0;
        int scrimTried = 0;
        int known = 0;
        for (int i = 0; i < deck.size(); i++) {
            for (int j = i + 1; j < deck.size(); j++) {
                int a = deck.get(i).championId();
                int b = deck.get(j).championId();
                if (coverage.knownFromOfficial(a, b)) {
                    known++;
                } else if (coverage.scrimTimes(a, b) > 0) {
                    scrimTried++;
                } else {
                    unexplored++;
                }
            }
        }
        return new ScrimDeck(deck, unexplored, scrimTried, known);
    }
}
