package com.teamfighter.tfm.story;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * 문장 경계. 마침표·물음표·느낌표·줄바꿈에서 자른다.
     *
     * <p>완벽한 문장 분리가 아니다 — 소수점이나 약어에서도 잘린다. 그래도 되는 이유는
     * 이 검사가 <b>더 잘게 자를수록 안전해지기</b> 때문이다. 잘못 잘리면 관계를 놓칠 뿐
     * (미검출), 없는 관계를 만들어내지는 않는다.
     */
    private static final Pattern SENTENCE = Pattern.compile("[.!?\\n]+");

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
        return run(brief, names, allChampions, allTeamNames, Set.of(), article);
    }

    /**
     * 선수 이름까지 대조한다.
     *
     * <p>선수가 들어오면 검사가 하나 더 생긴다 — <b>관계</b>다. 값이 전부 사실인데
     * 연결만 틀린 문장("Faker 가 마법사로 10킬")은 숫자 대조로도 챔피언 대조로도 안 걸린다.
     * 낱말은 다 이 매치의 것이기 때문이다.
     *
     * @param allAthleteNames 커리어의 선수 이름 전체. 비어 있으면 선수 검사를 건너뛴다
     */
    public static FactCheckResult run(MatchBrief brief, NameBook names,
                                      Set<String> allChampions, Set<String> allTeamNames,
                                      Set<String> allAthleteNames,
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

        // --- 4. 선수 이름 ---
        // 이 매치에 나온 선수와, 그 선수가 실제로 한 챔피언들.
        // 관계 검사가 이 표를 기준으로 돈다.
        Map<String, Set<String>> playedBy = playedBy(brief, names);             // 선수 이름 → 그가 한 챔피언들
        for (String athlete : allAthleteNames) {
            if (!playedBy.containsKey(athlete) && mentions(article, athlete)) {
                contradictions.add(new FactCheckResult.Finding(
                        "이 매치에 없는 선수", athlete));
            }
        }

        // --- 5. 관계: 선수 ↔ 챔피언 ---
        if (!playedBy.isEmpty()) {
            checkRelations(article, playedBy, picked, contradictions, unverified);
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

    /**
     * 이 매치에서 <b>누가 무엇을 했나</b>. 관계 검사의 기준표다.
     *
     * <p>세트를 가로질러 모은다 — 한 선수가 세트마다 다른 챔피언을 하므로 값이 집합이다.
     * 이름을 모르는 선수는 넣지 않는다. 기사가 그 선수를 이름으로 부를 수 없으니
     * 검사할 대상도 아니다.
     */
    private static Map<String, Set<String>> playedBy(MatchBrief brief, NameBook names) {
        Map<String, Set<String>> played = new LinkedHashMap<>();
        for (MatchBrief.SetBrief set : brief.sets()) {                          // 1. 세트마다
            for (MatchBrief.PlayerLine line : set.players()) {                  // 2. 선수마다
                String name = names.athleteName(line.athleteId());              // 3. 이름을 아는 선수만
                if (name == null || name.isBlank()) {
                    continue;
                }
                played.computeIfAbsent(name, k -> new LinkedHashSet<>())        // 4. 이름 → 챔피언 집합
                        .add(line.champion());
            }
        }
        return played;
    }

    /**
     * 한 문장 안에서 선수와 챔피언이 <b>잘못 묶였는지</b> 본다.
     *
     * <h2>어떻게 판정하나</h2>
     *
     * 문장 단위로 자른 뒤, 그 문장에 나온 선수와 챔피언을 모은다. 그리고 문장에 나온
     * 챔피언이 <b>같은 문장에 나온 어느 선수의 것도 아니면</b> 관계가 틀린 것이다.
     *
     * <h2>확신하는 것만 모순으로 올린다</h2>
     *
     * <ul>
     *   <li>선수 하나 · 챔피언 하나뿐인 문장이면 연결이 하나로 정해진다 → <b>모순</b></li>
     *   <li>여럿이 섞인 문장은 "상대로", "맞서" 같은 구조일 수 있다 → <b>미확인</b></li>
     * </ul>
     *
     * 이 구분이 없으면 목록이 잡음으로 가득 차고, 잡음이 되면 아무도 안 본다(D66 ③).
     *
     * <p>밴된 챔피언은 여기서 보지 않는다 — 아무도 하지 않았으므로 전부 걸리고,
     * 그 언급은 이미 위에서 미확인으로 올렸다.
     */
    private static void checkRelations(String article,
                                       Map<String, Set<String>> playedBy,
                                       Set<String> picked,
                                       List<FactCheckResult.Finding> contradictions,
                                       List<FactCheckResult.Finding> unverified) {
        for (String sentence : SENTENCE.split(article)) {                       // 1. 문장 단위로 자른다
            List<String> athletesHere = playedBy.keySet().stream()              // 2. 이 문장에 나온 선수
                    .filter(name -> mentions(sentence, name))
                    .toList();
            if (athletesHere.isEmpty()) {
                continue;                                                       //    선수 얘기가 아니면 볼 것이 없다
            }
            List<String> championsHere = picked.stream()                        // 3. 이 문장에 나온 (뽑힌) 챔피언
                    .filter(champion -> mentions(sentence, champion))
                    .toList();

            for (String champion : championsHere) {
                boolean anyoneHere = athletesHere.stream()                      // 4. 이 문장의 선수 중 하나라도 그 챔피언을 했나
                        .anyMatch(name -> playedBy.get(name).contains(champion));
                if (anyoneHere) {
                    continue;
                }
                String what = String.join(", ", athletesHere) + " ↔ " + champion;
                if (athletesHere.size() == 1 && championsHere.size() == 1) {    // 5. 연결이 하나로 정해지면 모순
                    contradictions.add(new FactCheckResult.Finding(
                            "선수와 챔피언을 잘못 묶었다", what));
                } else {                                                        // 6. 섞여 있으면 사람이 볼 목록으로만
                    unverified.add(new FactCheckResult.Finding(
                            "한 문장에 선수와 남의 챔피언이 같이 나왔다 — 관계 확인", what));
                }
            }
        }
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
