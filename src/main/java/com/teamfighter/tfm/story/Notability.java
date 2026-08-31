package com.teamfighter.tfm.story;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 이 매치가 얼마나 볼 만한가 → <b>기사의 길이와 댓글 수</b>를 정한다.
 *
 * <p>모드의 {@code 주목도 n/20} 과 같은 발상이지만, 우리는 글 20개가 아니라
 * 기사 한 편의 분량을 정한다.
 *
 * <p><b>가중치는 측정된 값이 아니다.</b> 이 프로젝트는 근거 없는 상수를 코드에 박지
 * 않기로 했고(D44), 여기도 예외가 아니다. 다만 집계 임계값과 달리 주목도는
 * <b>틀려도 통계를 오염시키지 않는다</b> — 기사가 조금 길거나 짧아질 뿐이다.
 * 그래서 설정 표로 빼지 않고 상수로 두되, <b>측정 전임을 명시</b>하고 뒤집힐 조건을 남긴다.
 *
 * <p>지켜야 하는 것은 절대값이 아니라 <b>순서</b>다 — 플레이어 경기가 남의 경기보다,
 * 접전이 스윕보다, 업셋이 예상대로보다 높아야 한다. 테스트도 그 순서만 고정한다.
 */
public record Notability(double score, int paragraphs, int commentCount, List<String> reasons) {

    // --- 가중치. 전부 미측정이다 (뒤집힐 조건은 클래스 주석 참고) ---

    /** 내 팀 경기. 사용자가 가장 보고 싶어 하는 것이라 가장 크다. */
    private static final double W_PLAYER = 0.35;
    /** 접전. brief 만으로 알 수 있어 항상 쓸 수 있다. */
    private static final double W_CLOSE = 0.20;
    /** 순위 싸움. */
    private static final double W_STANDINGS = 0.20;
    /** 업셋. */
    private static final double W_UPSET = 0.15;
    /** 라이벌. */
    private static final double W_RIVALRY = 0.10;

    /**
     * 접전이라고 <b>말할</b> 최소 점수. 가중치보다 높게 둔다 —
     * 어중간한 값에서 이유를 달면 "3 - 1" 도 접전이라고 적히고, 그것이 곧 창작이다.
     */
    private static final double CLOSE_REASON_FLOOR = 0.7;

    /**
     * 분량의 하한과 상한.
     *
     * <p><b>하한을 올렸다(문단 2→3 · 댓글 3→6).</b> 3-0 스윕은 접전 점수가 0 이라 늘 하한을
     * 받는데, 실물에서 그게 2문단·댓글 3개로 나와 화면이 휑했다. 스윕도 경기고, 커뮤니티는
     * 일방적인 경기에서 오히려 말이 많다.
     *
     * <p>상한은 그대로다. 늘리면 세트 나열이 다시 살아난다 — 모델은 분량을 채우려고
     * 세트를 하나씩 훑는 쪽으로 돌아간다.
     */
    private static final int MIN_PARAGRAPHS = 3;
    private static final int MAX_PARAGRAPHS = 6;
    private static final int MIN_COMMENTS = 6;
    private static final int MAX_COMMENTS = 15;

    /**
     * 주목도를 매긴다.
     *
     * <p><b>모르는 값은 0점이 아니라 "쓰지 않음" 이다.</b> 순위를 모른다고 주목도가
     * 깎이면 새 커리어의 모든 기사가 짧아진다. 그래서 쓸 수 있는 항의 가중치 합으로
     * 정규화한다 — 아는 것만으로 판단하고, 모르는 것은 판단에서 뺀다.
     */
    public static Notability of(MatchBrief brief, NotabilityContext context) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(context, "context");

        double earned = 0.0;
        double available = 0.0;
        List<String> reasons = new ArrayList<>();

        // --- 내 팀인가 (맥락에 플레이어 팀이 있을 때만 판단한다) ---
        if (context.playerTeamId() != null) {
            available += W_PLAYER;
            boolean mine = context.playerTeamId().equals(brief.blueTeamId())
                    || context.playerTeamId().equals(brief.redTeamId());
            if (mine) {
                earned += W_PLAYER;
                reasons.add("내 팀 경기");
            }
        }

        // --- 접전인가 (brief 만으로 안다. 항상 쓸 수 있다) ---
        available += W_CLOSE;
        double closeness = closeness(brief);
        earned += W_CLOSE * closeness;
        if (closeness > CLOSE_REASON_FLOOR) {
            reasons.add(brief.blueScore() + " - " + brief.redScore() + " 접전");
        }

        // --- 순위 싸움인가 ---
        if (context.hasStandings()) {
            available += W_STANDINGS;
            int gap = Math.abs(context.blueRank() - context.redRank());
            double nearness = 1.0 - Math.min(1.0, (double) gap / (context.leagueSize() - 1));
            earned += W_STANDINGS * nearness;
            if (nearness >= 0.7) {
                reasons.add(context.blueRank().equals(context.redRank())
                        ? "순위 싸움 (공동 " + context.blueRank() + "위)"
                        : "순위 싸움 (" + context.blueRank() + "위 대 " + context.redRank() + "위)");
            }
        }

        // --- 업셋인가 ---
        if (context.blueWinProbability() != null && brief.winnerTeamId() != null) {
            available += W_UPSET;
            boolean blueWon = context.blueWinProbability() != null
                    && brief.winnerTeamId().equals(brief.blueTeamId());
            double winnerProbability = blueWon
                    ? context.blueWinProbability()
                    : 1.0 - context.blueWinProbability();
            double surprise = 1.0 - clamp(winnerProbability);
            earned += W_UPSET * surprise;
            if (surprise >= 0.6) {
                reasons.add("업셋 (예상 승률 " + Math.round(winnerProbability * 100) + "%)");
            }
        }

        // --- 라이벌인가 ---
        if (context.rivalry()) {
            available += W_RIVALRY;
            earned += W_RIVALRY;
            reasons.add("라이벌전");
        }

        double score = available <= 0 ? 0.0 : clamp(earned / available);
        return new Notability(score, paragraphs(score), commentCount(score), List.copyOf(reasons));
    }

    /**
     * 얼마나 접전이었나. 0 = 스윕, 1 = 마지막 세트까지 간 풀세트.
     *
     * <p><b>세트 목록이 아니라 스코어로 잰다.</b> 지난 시즌 매치는 게임이 세트 기록을
     * 버려서(D6) 세트가 비어 있는데 스코어는 남아 있다. 세트 수로 재면 그런 매치가
     * 전부 "판단 불가" 가 되는데, 우리는 3 - 1 이었다는 것을 알고 있다.
     */
    private static double closeness(MatchBrief brief) {
        int loserSets = Math.min(brief.blueScore(), brief.redScore());
        int maxLoserSets = Math.max(1, brief.needWin() - 1);
        return clamp((double) loserSets / maxLoserSets);
    }

    private static int paragraphs(double score) {
        return MIN_PARAGRAPHS + (int) Math.round(score * (MAX_PARAGRAPHS - MIN_PARAGRAPHS));
    }

    private static int commentCount(double score) {
        return MIN_COMMENTS + (int) Math.round(score * (MAX_COMMENTS - MIN_COMMENTS));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
