package com.teamfighter.tfm.analysis.scrim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스크림 추천의 규칙.
 *
 * <p>DB 를 안 쓴다. 추천이 순수 함수라 그렇고, 그래야 <b>변조로 검증</b>할 수 있다 —
 * 규칙 하나를 일부러 깨뜨렸을 때 실패하는지가 이 파일이 지키는 것이다.
 */
class ScrimSuggesterTest {

    /** 역할군 이름. 실제 값은 {@code ChampionCategory} 지만 여기서는 문자열이면 된다. */
    private static final String MELEE = "MELEE";
    private static final String RANGER = "RANGER";

    private static ScrimCandidate champ(int id, String category, int games) {
        return new ScrimCandidate(id, "C" + id, "챔프" + id, category, games);
    }

    /** 역할군이 번갈아 붙는 후보 {@code n} 명. 출전은 전부 0이다. */
    private static List<ScrimCandidate> pool(int n) {
        List<ScrimCandidate> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(champ(i, i % 2 == 0 ? MELEE : RANGER, 0));
        }
        return out;
    }

    /** 아무것도 안 본 상태. 모든 쌍이 미지다. */
    private static PairCoverage nothingSeen() {
        return PairCoverage.empty();
    }

    @Test
    @DisplayName("덱은 넷이고, 여섯 쌍이 세 갈래로 남김없이 나뉜다")
    void deckIsFourAndPairsSumToSix() {
        List<ScrimDeck> decks = ScrimSuggester.suggest(pool(8), nothingSeen(), 2);

        assertThat(decks).hasSize(2);
        for (ScrimDeck deck : decks) {
            assertThat(deck.champions()).hasSize(ScrimSuggester.DECK_SIZE);
            assertThat(deck.unexploredPairs() + deck.scrimTriedPairs() + deck.knownPairs())
                    .isEqualTo(ScrimSuggester.PAIRS_PER_DECK);
        }
    }

    /**
     * 겹치면 목록이 넷이어도 고를 것은 하나다.
     */
    @Test
    @DisplayName("덱끼리 챔피언이 겹치지 않는다")
    void decksDoNotShareChampions() {
        List<ScrimDeck> decks = ScrimSuggester.suggest(pool(12), nothingSeen(), 3);

        List<Integer> all = decks.stream()
                .flatMap(d -> d.champions().stream())
                .map(ScrimCandidate::championId)
                .toList();

        assertThat(all).hasSize(12).doesNotHaveDuplicates();
    }

    /**
     * 실측 라인업 588개 중 역할군 1종은 <b>0건</b>이었다. 아무도 안 쓰는 덱을
     * 추천하면 첫 줄에서 신뢰를 잃는다.
     */
    @Test
    @DisplayName("한 역할군으로만 채운 덱은 안 만든다")
    void deckNeverHasASingleRole() {
        // 여덟 명 중 다섯이 근접, 셋이 원거리. 탐욕법이 근접만 집어도 마지막 자리에서 막힌다.
        List<ScrimCandidate> pool = List.of(
                champ(1, MELEE, 0), champ(2, MELEE, 0), champ(3, MELEE, 0),
                champ(4, MELEE, 0), champ(5, MELEE, 0),
                champ(6, RANGER, 0), champ(7, RANGER, 0), champ(8, RANGER, 0));

        List<ScrimDeck> decks = ScrimSuggester.suggest(pool, nothingSeen(), 2);

        assertThat(decks).isNotEmpty();
        for (ScrimDeck deck : decks) {
            // 넷을 두 종으로 채우면 중복은 당연히 생긴다. 보는 것은 <b>종의 수</b>다.
            long roles = deck.champions().stream()
                    .map(ScrimCandidate::category).distinct().count();
            assertThat(roles).isGreaterThanOrEqualTo(2);
        }
    }

    /**
     * <b>이 추천의 근거 자체다.</b> 이미 아는 쌍만 있는 덱은 스크림에서 할 일이 없다.
     */
    @Test
    @DisplayName("이미 아는 쌍은 미지로 세지 않는다")
    void knownPairsAreNotCountedAsUnexplored() {
        List<ScrimCandidate> pool = pool(4);
        // 1-2 는 공식전으로 알고, 3-4 는 스크림에서 해 봤다.
        PairCoverage coverage = PairCoverage.of(
                List.of(new long[]{1, 2, 0}),
                List.of(new long[]{3, 4, 5}));

        List<ScrimDeck> decks = ScrimSuggester.suggest(pool, coverage, 1);

        assertThat(decks).hasSize(1);
        ScrimDeck deck = decks.get(0);
        assertThat(deck.knownPairs()).isEqualTo(1);
        assertThat(deck.scrimTriedPairs()).isEqualTo(1);
        assertThat(deck.unexploredPairs()).isEqualTo(4);
    }

    /**
     * 새로고침할 때마다 추천이 바뀌면 사용자는 그것을 추천이 아니라 잡음으로 읽는다.
     */
    @Test
    @DisplayName("같은 입력이면 같은 덱이 나온다 — 결정적이다")
    void suggestionIsDeterministic() {
        List<ScrimCandidate> pool = pool(16);

        List<ScrimDeck> first = ScrimSuggester.suggest(pool, nothingSeen(), 4);
        List<ScrimDeck> again = ScrimSuggester.suggest(pool, nothingSeen(), 4);

        assertThat(first).isEqualTo(again);
    }

    /**
     * 발굴이 목적이므로 안 나온 챔피언이야말로 값이 크다.
     */
    @Test
    @DisplayName("덜 나온 챔피언을 먼저 고른다")
    void leastPlayedFirst() {
        List<ScrimCandidate> pool = List.of(
                champ(1, MELEE, 100), champ(2, RANGER, 100),
                champ(3, MELEE, 100), champ(4, RANGER, 100),
                champ(5, MELEE, 0), champ(6, RANGER, 0),
                champ(7, MELEE, 0), champ(8, RANGER, 0));

        List<ScrimDeck> decks = ScrimSuggester.suggest(pool, nothingSeen(), 1);

        assertThat(decks.get(0).champions()).extracting(ScrimCandidate::championId)
                .containsExactlyInAnyOrder(5, 6, 7, 8);
    }

    @Test
    @DisplayName("후보가 넷에 못 미치면 아무것도 추천하지 않는다")
    void tooFewCandidatesYieldNothing() {
        assertThat(ScrimSuggester.suggest(pool(3), nothingSeen(), 4)).isEmpty();
        assertThat(ScrimSuggester.suggest(List.of(), nothingSeen(), 4)).isEmpty();
    }

    @Test
    @DisplayName("만들 수 있는 것보다 많이 달라고 해도 있는 만큼만 준다")
    void doesNotInventDecksBeyondThePool() {
        assertThat(ScrimSuggester.suggest(pool(9), nothingSeen(), 5)).hasSize(2);
    }

    @Test
    @DisplayName("많이 알려주는 덱이 먼저 온다")
    void mostInformativeDeckComesFirst() {
        List<ScrimCandidate> pool = pool(8);
        // 5·6·7·8 사이를 전부 공식전으로 안다 → 그 덱은 미지가 0이다.
        List<long[]> known = new ArrayList<>();
        for (int a = 5; a <= 8; a++) {
            for (int b = a + 1; b <= 8; b++) {
                known.add(new long[]{a, b, 0});
            }
        }
        PairCoverage coverage = PairCoverage.of(known, List.of());

        List<ScrimDeck> decks = ScrimSuggester.suggest(pool, coverage, 2);

        assertThat(decks).hasSize(2);
        assertThat(decks.get(0).unexploredPairs())
                .isGreaterThan(decks.get(1).unexploredPairs());
    }
}
