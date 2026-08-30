package com.teamfighter.tfm.story;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 생성된 기사를 {@link MatchBrief} 와 대조한다 — 3층 경계의 마지막 관문.
 *
 * <p>2절의 규칙은 "LLM 출력은 절대 위로 올라가지 않는다" 였다. 그런데 <b>독자에게는</b>
 * 올라간다. 그래서 기사가 사실과 어긋나는지를 코드가 확인하고, 그 결과를 D61 의
 * 「이 기사가 쓴 숫자」 블록에 함께 표시한다.
 *
 * <p><b>거짓 양성이 이 장치를 죽인다.</b> 기사에는 brief 가 모르는 숫자가 얼마든지
 * 나온다 — "20분 만에", "3년 만의 우승". 그것들을 오류라고 부르면 목록이 잡음으로
 * 가득 차고 아무도 보지 않게 된다. 그래서 <b>어긋나는 것</b>과 <b>모르는 것</b>을
 * 나누고, 전자만 기사를 막는다.
 *
 * <p>검사하는 것은 셋뿐이다. 자연어를 이해하려 들지 않는다 —
 * <b>확실히 틀렸다고 말할 수 있는 것만</b> 잡는다.
 *
 * <ol>
 *   <li>스코어 꼴({@code 2 - 0}, {@code 2:0}, {@code 2대 0})이 이 매치의 어떤 스코어와도 안 맞을 때</li>
 *   <li>이 매치에 나오지 않은 챔피언을 말할 때 — 가장 흔한 환각이다.
 *       <b>밴된 챔피언은 예외</b>다. 밴도 이 매치의 사실이라 언급 자체는 틀리지 않다.
 *       다만 "밴된 챔피언이 캐리했다" 는 환각이므로 주의 목록에 올린다</li>
 *   <li>이 매치에 없는 팀 이름을 말할 때</li>
 * </ol>
 */
public final class FactCheck {

    /** 스코어 꼴. 모델이 유니코드 대시(U+2011 등)를 쓰므로 전부 받는다 — 실물에서 겪었다. */
    private static final Pattern SCORE =
            Pattern.compile("(\\d{1,3})\\s*(?:[-‐‑‒–—―−:]|대)\\s*(\\d{1,3})");

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private FactCheck() {
    }

    /** 팀 이름 목록 없이 검사한다. 챔피언과 스코어만 본다. */
    public static FactCheckResult run(MatchBrief brief, NameBook names,
                                      Set<String> allChampions, String article) {
        return run(brief, names, allChampions, Set.of(), article);
    }

    /**
     * 기사를 대조한다.
     *
     * @param allChampions   게임의 챔피언 전체. 이 중 이 매치에 없는 이름이 나오면 모순이다.
     *                       <b>목록에 없는 낱말은 건드리지 않는다</b> — 사람 이름이나
     *                       보통 명사를 챔피언으로 오인하지 않기 위해서다
     * @param allTeamNames   커리어의 팀 이름 전체. 비어 있으면 팀 검사를 건너뛴다
     */
    public static FactCheckResult run(MatchBrief brief, NameBook names,
                                      Set<String> allChampions, Set<String> allTeamNames,
                                      String article) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(allChampions, "allChampions");
        Objects.requireNonNull(allTeamNames, "allTeamNames");
        if (article == null || article.isBlank()) {
            return new FactCheckResult(List.of(), List.of());
        }

        List<FactCheckResult.Finding> contradictions = new ArrayList<>();
        List<FactCheckResult.Finding> unverified = new ArrayList<>();

        Set<String> scorelines = scorelines(brief);
        Set<Integer> knownNumbers = knownNumbers(brief);

        // --- 1. 스코어 꼴 ---
        Set<Integer> inScorelines = new HashSet<>();
        Matcher score = SCORE.matcher(article);
        while (score.find()) {
            int left = Integer.parseInt(score.group(1));
            int right = Integer.parseInt(score.group(2));
            inScorelines.add(left);
            inScorelines.add(right);
            if (!scorelines.contains(key(left, right))) {
                contradictions.add(new FactCheckResult.Finding(
                        "이 매치에 없는 스코어", score.group().trim()));
            }
        }

        // --- 2. 챔피언 ---
        Set<String> picked = championsPicked(brief);
        Set<String> banned = championsBanned(brief);
        for (String champion : allChampions) {
            if (picked.contains(champion) || !mentions(article, champion)) {
                continue;
            }
            if (banned.contains(champion)) {
                // 밴은 이 매치의 사실이므로 언급 자체는 틀리지 않다. 그러나 밴된 챔피언이
                // "캐리했다" 는 환각이고, 그것을 가리려면 문장을 이해해야 한다.
                // 코드가 할 수 있는 데까지만 한다 — 사람이 볼 수 있게 올려둔다.
                unverified.add(new FactCheckResult.Finding(
                        "밴된 챔피언을 언급했다 — 활약했다고 썼는지 확인", champion));
            } else {
                contradictions.add(new FactCheckResult.Finding(
                        "이 매치에 나오지 않은 챔피언", champion));
            }
        }

        // --- 3. 팀 이름 ---
        Set<String> here = new LinkedHashSet<>();
        for (Integer id : List.of(brief.blueTeamId(), brief.redTeamId())) {
            String name = id == null ? null : names.teamName(id);
            if (name != null) {
                here.add(name);
            }
        }
        for (String team : allTeamNames) {
            if (!here.contains(team) && mentions(article, team)) {
                contradictions.add(new FactCheckResult.Finding(
                        "이 매치에 없는 팀", team));
            }
        }

        // --- 그 밖의 숫자는 "모르는 것" 으로만 남긴다 ---
        Matcher number = NUMBER.matcher(article);
        Set<String> seen = new LinkedHashSet<>();
        while (number.find()) {
            int value = Integer.parseInt(number.group());
            if (knownNumbers.contains(value) || inScorelines.contains(value)) {
                continue;
            }
            if (seen.add(number.group())) {
                unverified.add(new FactCheckResult.Finding(
                        "brief 에 없는 숫자", number.group()));
            }
        }

        return new FactCheckResult(contradictions, unverified);
    }

    /**
     * 이 매치에서 말이 되는 스코어 꼴들.
     *
     * <p><b>뒤집힌 것도 받는다.</b> 기사가 진 팀 관점으로 "0 - 2 로 무너졌다" 고 쓸 수
     * 있는데, 그건 틀린 것이 아니라 관점이 다른 것이다. 방향까지 잡으려면 문장의 주어를
     * 이해해야 하고, 그건 이 장치가 할 일이 아니다.
     */
    private static Set<String> scorelines(MatchBrief brief) {
        Set<String> out = new HashSet<>();
        add(out, brief.blueScore(), brief.redScore());
        add(out, brief.blueKill(), brief.redKill());
        brief.sets().forEach(s -> add(out, s.blueKill(), s.redKill()));
        return out;
    }

    private static void add(Set<String> out, int a, int b) {
        out.add(key(a, b));
        out.add(key(b, a));
    }

    private static String key(int a, int b) {
        return a + ":" + b;
    }

    /** brief 가 아는 정수 전부. 여기 있으면 "모르는 숫자" 목록에도 올리지 않는다. */
    private static Set<Integer> knownNumbers(MatchBrief brief) {
        Set<Integer> out = new HashSet<>();
        for (Integer v : List.of(brief.blueScore(), brief.redScore(),
                brief.blueKill(), brief.redKill(), brief.needWin(), brief.setCount())) {
            out.add(v);
        }
        for (Integer v : new Integer[]{brief.season(), brief.day(), brief.round(),
                brief.blueTeamId(), brief.redTeamId()}) {
            if (v != null) {
                out.add(v);
            }
        }
        brief.sets().forEach(s -> {
            out.add(s.setNo());
            out.add(s.blueKill());
            out.add(s.redKill());
        });
        return out;
    }

    private static Set<String> championsPicked(MatchBrief brief) {
        Set<String> out = new HashSet<>();
        brief.sets().forEach(s -> {
            out.addAll(s.bluePick());
            out.addAll(s.redPick());
        });
        return out;
    }

    private static Set<String> championsBanned(MatchBrief brief) {
        Set<String> out = new HashSet<>();
        brief.sets().forEach(s -> {
            out.addAll(s.blueBan());
            out.addAll(s.redBan());
        });
        return out;
    }

    /**
     * 기사가 그 낱말을 말하는가.
     *
     * <p>단순 포함으로 보면 {@code Monk} 가 {@code Monkey} 에 걸린다. 앞뒤가 글자·숫자가
     * 아닌 자리에서만 인정한다. 한글 조사가 바로 붙는 경우({@code Bard가})는 인정해야
     * 하므로 한글은 경계로 친다.
     */
    private static boolean mentions(String article, String word) {
        int from = 0;
        while (true) {
            int at = article.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !isWordChar(article.charAt(at - 1));
            int end = at + word.length();
            boolean rightOk = end >= article.length() || !isWordChar(article.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) && c < 0x1100;   // 한글은 경계로 친다
    }
}
