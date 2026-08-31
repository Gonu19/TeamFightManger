package com.teamfighter.tfm.web;

import com.teamfighter.tfm.analysis.pair.PairEffectCalculator;
import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Side;
import com.teamfighter.tfm.ingest.entity.ChampionCategory;
import com.teamfighter.tfm.web.dao.StatsDao;
import com.teamfighter.tfm.web.view.PairBucket;
import com.teamfighter.tfm.web.view.PairRow;
import com.teamfighter.tfm.web.view.TierRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 통계 — {@code /tier} · {@code /champion/{code}}.
 *
 * <h2>기사·갤러리와 링크로 잇지 않는다 (D61)</h2>
 *
 * 여기서 챔피언 이름을 눌러 갤러리로 가는 길을 만들지 않는다. 두 세계는 <b>상단 탭에서만</b>
 * 만난다. 창작층이 만든 값이 통계 화면으로 새어 들어오는 경로를 물리적으로 끊어 두는 것이
 * D61 의 전부이고, 링크 하나가 그 선을 다시 잇는다.
 *
 * <h2>화면이 계산하지 않는다</h2>
 *
 * 추정 승률도 쌍 효과도 집계가 만들어 둔 값을 그대로 그린다. 화면이 다시 계산하면
 * 집계와 화면이 다른 답을 할 수 있고, 그때 <b>틀린 쪽이 안 보인다</b> — 둘 다
 * 그럴듯한 숫자이기 때문이다.
 *
 * <h2>표본을 숨기지 않는다</h2>
 *
 * 티어의 출전 수도 쌍의 관측 수도 값 옆에 붙는다. 교차검증은 "효과가 있다" 까지만
 * 말했고 개별 값의 크기는 표본이 얇으면 그만큼 흔들린다 — D13·D60 의 "판정 불가"
 * 규칙이 이 화면 전체에 걸린다.
 */
@Controller
public class StatsController {

    /**
     * 한 묶음에 그리는 쌍의 수.
     *
     * <p>다 보여주면 한 챔피언에 수백 줄이 되고, 그중 대부분은 릿지에 눌려 0 근처다 —
     * 그건 목록이 아니라 잡음이다. 묶음이 셋으로 늘면서 12 에서 10 으로 줄였다:
     * 화면 하나에 30줄이면 세 묶음을 나란히 놓은 이점이 사라진다.
     */
    private static final int PAIRS_SHOWN = 10;

    private final StatsDao stats;

    public StatsController(StatsDao stats) {
        this.stats = stats;
    }

    /**
     * 티어 목록. <b>루트가 여기로 온다</b> — 시작 화면은 "지금 무엇이 센가" 가 맞다.
     *
     * @param scrim    스크림을 섞을 것인가. 두 벌이 미리 만들어져 있다 (D47)
     * @param category 역할군 탭. {@code null} 이면 전체다
     */
    @GetMapping("/tier")
    public String tier(@RequestParam(required = false) Integer slot,
                       @RequestParam(defaultValue = "false") boolean scrim,
                       @RequestParam(required = false) String category,
                       Model model) {
        List<Integer> slots = stats.slots();
        Integer selected = slot != null ? slot : (slots.isEmpty() ? null : slots.get(0));
        String tab = normalize(category);

        model.addAttribute("slots", slots);
        model.addAttribute("selectedSlot", selected);
        model.addAttribute("scrim", scrim);
        model.addAttribute("categories", ChampionCategory.values());
        model.addAttribute("selectedCategory", tab);
        model.addAttribute("rows", selected == null ? List.of()
                : stats.tier(selected, scrim, tab));
        model.addAttribute("tab", "tier");
        return "stats/tier";
    }

    /**
     * 모르는 역할군은 전체로 떨어뜨린다.
     *
     * <p>주소창에 손으로 친 {@code ?category=TOP} 이 500 이 되면 안 된다 — 탭 이름
     * 하나 때문에 티어 화면을 통째로 못 보는 것은 손해가 이득보다 크다.
     */
    private static String normalize(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return Stream.of(ChampionCategory.values())
                .map(Enum::name)
                .filter(name -> name.equals(category))
                .findFirst()
                .orElse(null);
    }

    /**
     * 챔피언 하나 — <b>세 묶음</b>으로 보여준다.
     *
     * <pre>
     *   상대하기 어려움   맞은편에 있으면 내가 더 죽는다
     *   상대하기 쉬움     맞은편에 있으면 내가 덜 죽는다
     *   듀오 시너지       같은 팀이면 덜 죽거나 더 때린다
     * </pre>
     *
     * <p>가르는 축은 <b>{@code DEATH}</b> 다 (D64 결정 3). 동료·상대 두 표에 지표
     * 벡터를 그대로 늘어놓던 것을 바꾼 이유는 그 표가 "그래서 이 챔피언 뽑아도 되나"
     * 라는 질문에 답을 안 해 주기 때문이다. 벡터는 사라지지 않는다 — 줄을 펼치면 나온다.
     *
     * <p>역시너지는 묶음에 섞지 않고 위로 뽑는다 (D65 결정 2). 좋은 조합은 여럿이지만
     * 지뢰는 밟으면 그 판이 끝난다.
     *
     * <p>없는 챔피언은 404 다. 빈 화면을 200 으로 돌려주면 주소를 잘못 친 것인지
     * 데이터가 없는 것인지 알 수 없다.
     */
    @GetMapping("/champion/{code}")
    public String champion(@PathVariable String code,
                           @RequestParam(required = false) Integer slot,
                           Model model) {
        List<Integer> slots = stats.slots();
        Integer selected = slot != null ? slot : (slots.isEmpty() ? null : slots.get(0));

        TierRow champion = stats.champion(code, selected)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "챔피언 " + code + " 이 없다"));

        List<PairRow> allies = selected == null ? List.of()
                : stats.pairs(selected, champion.championId(), Side.ALLY);
        List<PairRow> foes = selected == null ? List.of()
                : stats.pairs(selected, champion.championId(), Side.FOE);

        model.addAttribute("champion", champion);
        model.addAttribute("slots", slots);
        model.addAttribute("selectedSlot", selected);

        // 접힌 표가 쓰는 원본. 묶음은 여기서 잘라낸 것이고, 여섯 지표는 안 사라진다
        // (D65 결정 1) — 첫눈에 보이는 것만 줄었다.
        model.addAttribute("allies", allies);
        model.addAttribute("foes", foes);

        model.addAttribute("buckets", List.of(
                PairBucket.of("상대하기 어려움", "맞은편에 있으면 더 죽는다", "hard",
                        PairRow.Bucket.HARD_FOE, foes, PAIRS_SHOWN),
                PairBucket.of("상대하기 쉬움", "맞은편에 있으면 덜 죽는다", "easy",
                        PairRow.Bucket.EASY_FOE, foes, PAIRS_SHOWN),
                PairBucket.of("듀오 시너지", "같은 팀이면 덜 죽거나 더 때린다", "duo",
                        PairRow.Bucket.DUO, allies, PAIRS_SHOWN)));

        // 경고는 잘라내지 않는다. 열 개를 넘는 지뢰밭이면 그 사실 자체가 정보다.
        model.addAttribute("warnings",
                allies.stream().filter(PairRow::isWarning)
                        .sorted(Comparator.comparingDouble(PairRow::rankValue).reversed())
                        .toList());

        model.addAttribute("metrics", PairRow.shown());
        model.addAttribute("minObservations", PairEffectCalculator.MIN_OBSERVATIONS);
        model.addAttribute("tab", "tier");
        return "stats/champion";
    }
}
