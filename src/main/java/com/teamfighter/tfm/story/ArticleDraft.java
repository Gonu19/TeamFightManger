package com.teamfighter.tfm.story;

import java.util.List;
import java.util.Objects;

/**
 * 저장 직전의 기사 한 편. <b>DB 도 LLM 도 모른다.</b>
 *
 * <p><b>{@code factStatus} 를 인자로 받지 않는다.</b> V8 주석이 "모순이 하나라도 있으면
 * {@code CONTRADICTED}" 를 저장하는 쪽의 책임으로 남겼는데, 그것을 DAO 코드로 지키면
 * DB 없이는 검증할 수 없고 경로가 하나 늘 때마다 다시 틀릴 수 있다.
 * 여기서 <b>대조 결과로부터 계산</b>하면 그 값을 틀리게 넣을 방법 자체가 없어진다.
 * D35 가 트리거 대신 고른 "적재에서 막고 테스트로 고정한다" 의 더 강한 형태다.
 *
 * <p><b>{@code briefText} 는 참조가 아니라 텍스트다 (D61 결정 2).</b> 나중에 집계가
 * 갱신돼도 바뀌면 안 된다 — 기사가 <i>그때</i> 무엇을 보고 썼는지가 남아야 검증이 된다.
 *
 * @param blueTeamId {@code team.team_id} 다. 세이브의 {@code game_team_id} 가 아니다
 * @param comments   창작층 안에서만 산다. 집계로 올라가지 않는다
 * @param findings   대조에서 나온 지적. 이 목록이 {@link #factStatus()} 를 정한다
 */
public record ArticleDraft(
        int slotId,
        Integer scheduleId,
        Integer competitionId,
        String competitionKey,
        int season,
        int day,
        Integer round,
        Integer blueTeamId,
        Integer redTeamId,
        Integer blueScore,
        Integer redScore,
        Integer blueKill,
        Integer redKill,
        double notability,
        List<String> notabilityReasons,
        String headline,
        String body,
        String briefText,
        String model,
        List<CommentLine> comments,
        List<Finding> findings) {

    /**
     * 댓글 하나.
     *
     * <p><b>평평하다.</b> 대댓글은 자기 안에 자식을 담는 대신 부모의 순번을 들고 있다 —
     * DB 의 {@code article_comment} 가 행 하나에 {@code parent_ordinal} 하나를 갖는 꼴과
     * 같다. 중첩으로 만들면 저장 직전에 다시 펴야 하고, 그 변환이 한 겹 더 생긴다.
     *
     * @param author        유동닉. 모델이 안 주면 {@code null} 이고 화면이 익명으로 그린다.
     *                      지어내지 않는 이유는 D57 과 같다 — 빈 칸이 진짜보다 낫다
     * @param parentOrdinal 받아친 원댓글의 순번(1부터). {@code null} 이면 원댓글이다
     */
    public record CommentLine(String author, String body, Integer parentOrdinal) {

        public CommentLine {
            Objects.requireNonNull(body, "body");
            author = author == null || author.isBlank() ? null : author.strip();
        }

        /** 닉네임 없는 원댓글. JSON 파싱이 실패했을 때의 폴백이 이걸 쓴다. */
        public static CommentLine of(String body) {
            return new CommentLine(null, body, null);
        }

        public boolean isReply() {
            return parentOrdinal != null;
        }
    }

    /** 지적 하나. {@link FactCheckResult.Finding} 을 저장 가능한 꼴로 옮긴 것이다. */
    public record Finding(Severity severity, String what, String evidence) {
        public Finding {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(what, "what");
            evidence = evidence == null ? "" : evidence;
        }
    }

    public enum Severity { CONTRADICTION, UNVERIFIED }

    public enum FactStatus { CLEAN, CONTRADICTED }

    /**
     * 기사 종류.
     *
     * <p><b>레코드 컴포넌트가 아니라 팀이 있는지로 정한다</b>({@link #kind()}). 종류를 따로
     * 받으면 "종류는 ROUND 인데 팀이 채워진" 상태가 만들어질 수 있고, 그런 상태는 DB 의
     * CHECK 가 잡기 전까지 코드 안을 돌아다닌다. 하나에서 다른 하나가 따라 나오면
     * 어긋날 방법이 없다 — {@code fact_status} 를 findings 에서 계산한 것과 같은 수법이다.
     */
    public enum Kind { MATCH, ROUND }

    /**
     * 제목으로 인정할 최대 길이.
     *
     * <p>실측으로 정했다 — 실제 생성된 제목이 21자였다(`팀 33, 연장전 뒤 2-1 역전 승리`).
     * 두 배쯤 여유를 두되, 66자짜리 산문 첫 줄은 제목이 아니라고 판단할 수 있어야 한다.
     * 처음에 80으로 뒀다가 그 산문이 제목으로 통과해서 내렸다.
     */
    private static final int MAX_HEADLINE = 40;

    public ArticleDraft {
        // 매치 기사면 여섯 값이 다 있어야 하고, 총평이면 팀이 둘 다 없어야 한다.
        // DB 에도 같은 CHECK 가 있지만(V10) 여기서 먼저 막는다 — DB 까지 가면 실패가
        // 저장 시점에 터지고, 그때는 이미 모델 호출 두 번을 쓴 뒤다.
        boolean hasTeams = blueTeamId != null || redTeamId != null;
        if (hasTeams && (blueTeamId == null || redTeamId == null)) {
            throw new IllegalArgumentException("팀이 한쪽만 있다 — 매치 기사는 둘 다 있어야 한다");
        }
        if (hasTeams && (blueScore == null || redScore == null
                || blueKill == null || redKill == null)) {
            throw new IllegalArgumentException("매치 기사인데 스코어나 킬이 비었다");
        }

        Objects.requireNonNull(headline, "headline");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(briefText, "briefText");
        Objects.requireNonNull(model, "model");
        if (headline.isBlank() || body.isBlank()) {
            throw new IllegalArgumentException("빈 기사는 저장하지 않는다 — 왜 비었는지 아무도 모르게 된다");
        }
        if (briefText.isBlank()) {
            throw new IllegalArgumentException(
                    "사실 블록이 비었다. 「이 기사가 쓴 숫자」가 빈 기사는 검증할 수 없다 (D61)");
        }
        if (notability < 0.0 || notability > 1.0) {
            throw new IllegalArgumentException("주목도 범위를 벗어났다: " + notability);
        }
        notabilityReasons = List.copyOf(notabilityReasons);
        comments = List.copyOf(comments);
        findings = List.copyOf(findings);
    }

    /**
     * 대조 결과에서 계산한다. <b>이 값을 밖에서 정할 수 없다.</b>
     *
     * <p>모순이 하나라도 있으면 {@code CONTRADICTED} 다. 화면은 이 값으로 경고를 띄우고,
     * 목록에서도 표시한다 — 숨기면 검증 장치가 죽는다.
     */
    public FactStatus factStatus() {
        boolean contradicted = findings.stream()
                .anyMatch(f -> f.severity() == Severity.CONTRADICTION);
        return contradicted ? FactStatus.CONTRADICTED : FactStatus.CLEAN;
    }

    /**
     * 매치 기사인가 총평인가. <b>팀이 있으면 매치다.</b>
     *
     * <p>V10 의 CHECK 두 개가 DB 쪽에서 같은 규칙을 지킨다.
     */
    public Kind kind() {
        return blueTeamId == null ? Kind.ROUND : Kind.MATCH;
    }

    /**
     * 하루치 총평. 팀도 스코어도 없다 — 매치 하나를 가리키지 않기 때문이다.
     *
     * <p>유일 키는 {@code (slot, ROUND, season, day, NULL, NULL)} 이 된다. NULL 이 서로
     * 같다고 봐야 "하루에 한 편" 이 성립하는데, 그 절({@code NULLS NOT DISTINCT})이
     * V10 에 있다. 없으면 버튼을 누를 때마다 총평이 한 편씩 쌓인다.
     */
    public static ArticleDraft ofRound(int slotId, int season, int day,
                                       double notability, List<String> notabilityReasons,
                                       String headline, String body, String briefText,
                                       String model, List<CommentLine> comments,
                                       FactCheckResult factCheck) {
        Objects.requireNonNull(factCheck, "factCheck");

        List<Finding> findings = new java.util.ArrayList<>();
        factCheck.contradictions().forEach(f ->
                findings.add(new Finding(Severity.CONTRADICTION, f.what(), f.evidence())));
        factCheck.unverified().forEach(f ->
                findings.add(new Finding(Severity.UNVERIFIED, f.what(), f.evidence())));

        return new ArticleDraft(
                slotId, null, null, null, season, day, null,
                null, null, null, null, null, null,
                notability, notabilityReasons,
                headline, body, briefText, model, comments, findings);
    }

    /** 기사를 그대로 실어도 되는가. */
    public boolean isClean() {
        return factStatus() == FactStatus.CLEAN;
    }

    /**
     * 생성 결과를 저장 꼴로 옮긴다.
     *
     * <p>팀 번호를 인자로 받는 이유는 {@code MatchBrief} 가 <b>세이브의</b> 팀 번호를
     * 들고 있기 때문이다. DB 의 {@code team.team_id} 는 다른 값이고, 그 변환은
     * 팀 표를 아는 쪽의 몫이다 — 여기서 추측하지 않는다.
     */
    public static ArticleDraft of(int slotId, MatchBrief brief, Notability notability,
                                  int blueTeamId, int redTeamId,
                                  String headline, String body, String briefText,
                                  String model, List<CommentLine> comments,
                                  FactCheckResult factCheck) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(factCheck, "factCheck");

        List<Finding> findings = new java.util.ArrayList<>();
        factCheck.contradictions().forEach(f ->
                findings.add(new Finding(Severity.CONTRADICTION, f.what(), f.evidence())));
        factCheck.unverified().forEach(f ->
                findings.add(new Finding(Severity.UNVERIFIED, f.what(), f.evidence())));

        return new ArticleDraft(
                slotId, brief.scheduleId(), brief.competitionId(), brief.competitionKey(),
                brief.season(), brief.day(), brief.round(),
                blueTeamId, redTeamId,
                brief.blueScore(), brief.redScore(), brief.blueKill(), brief.redKill(),
                notability.score(), notability.reasons(),
                headline, body, briefText, model, comments, findings);
    }

    /**
     * 본문에서 제목을 떼어낸다. 프롬프트가 "제목 한 줄, 빈 줄, 본문" 을 요구한다.
     *
     * <p>모델이 그 형식을 안 지키면 <b>첫 줄을 제목으로 삼는다.</b> 던지지 않는 이유는
     * 형식 위반이 사실 오류가 아니기 때문이다 — 기사는 멀쩡한데 줄바꿈만 다를 수 있다.
     * 다만 첫 줄이 너무 길면 제목이 아니라 본문이므로, 그때는 제목을 비워 둔다.
     */
    public static String[] splitHeadline(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[]{"", ""};
        }
        String text = raw.strip();
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) {
            return new String[]{"", text};
        }
        String first = text.substring(0, firstBreak).strip();
        String rest = text.substring(firstBreak).strip();
        if (first.length() > MAX_HEADLINE || rest.isBlank()) {
            return new String[]{"", text};
        }
        return new String[]{first, rest};
    }
}
