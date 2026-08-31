package com.teamfighter.tfm.web;

import com.teamfighter.tfm.analysis.pair.PairEffectCalculator;
import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Side;
import com.teamfighter.tfm.web.dao.StatsDao;
import com.teamfighter.tfm.web.view.PairRow;
import com.teamfighter.tfm.web.view.TierRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
     * 챔피언 화면에 그리는 쌍의 수.
     *
     * <p>동료·상대 각각 이만큼이다. 다 보여주면 한 챔피언에 수백 줄이 되고,
     * 그중 대부분은 릿지에 눌려 0 근처다 — 그건 목록이 아니라 잡음이다.
     */
    private static final int PAIRS_SHOWN = 12;

    private final StatsDao stats;

    public StatsController(StatsDao stats) {
        this.stats = stats;
    }

    /**
     * 티어 목록. <b>루트가 여기로 온다</b> — 시작 화면은 "지금 무엇이 센가" 가 맞다.
     *
     * @param scrim 스크림을 섞을 것인가. 두 벌이 미리 만들어져 있다 (D47)
     */
    @GetMapping("/tier")
    public String tier(@RequestParam(required = false) Integer slot,
                       @RequestParam(defaultValue = "false") boolean scrim,
                       Model model) {
        List<Integer> slots = stats.slots();
        Integer selected = slot != null ? slot : (slots.isEmpty() ? null : slots.get(0));

        model.addAttribute("slots", slots);
        model.addAttribute("selectedSlot", selected);
        model.addAttribute("scrim", scrim);
        model.addAttribute("rows", selected == null ? List.of() : stats.tier(selected, scrim));
        model.addAttribute("tab", "tier");
        return "stats/tier";
    }

    /**
     * 챔피언 하나 — <b>시너지와 카운터를 한 화면에</b>.
     *
     * <p>둘을 나누지 않는 이유는 밴픽에서 묻는 것이 "이 챔피언 동료로 뭐가 좋고 누가
     * 무서운가" 한 덩어리이기 때문이다. 화면을 오가야 비교되는 구조는 그 질문에 답하지
     * 못한다.
     *
     * <p>없는 챔피언은 404 다. 빈 화면을 200 으로 돌려주면 주소를 잘못 친 것인지
     * 데이터가 없는 것인지 알 수 없다.
     */
    @GetMapping("/champion/{code}")
    public String champion(@PathVariable String code,
                           @RequestParam(required = false) Integer slot,
                           Model model) {
        TierRow champion = stats.champion(code)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "챔피언 " + code + " 이 없다"));

        List<Integer> slots = stats.slots();
        Integer selected = slot != null ? slot : (slots.isEmpty() ? null : slots.get(0));

        List<PairRow> allies = selected == null ? List.of()
                : stats.pairs(selected, champion.championId(), Side.ALLY);
        List<PairRow> foes = selected == null ? List.of()
                : stats.pairs(selected, champion.championId(), Side.FOE);

        model.addAttribute("champion", champion);
        model.addAttribute("slots", slots);
        model.addAttribute("selectedSlot", selected);
        model.addAttribute("allies", allies.stream().limit(PAIRS_SHOWN).toList());
        model.addAttribute("foes", foes.stream().limit(PAIRS_SHOWN).toList());

        // 역시너지 경고는 1급 기능이다 (D65 결정 2). 좋은 조합은 여럿이지만
        // 지뢰는 밟으면 그 판이 끝나므로, 목록에 섞지 않고 위로 뽑아 올린다.
        model.addAttribute("warnings", allies.stream().filter(PairRow::isWarning).toList());

        model.addAttribute("metrics", PairRow.shown());
        model.addAttribute("minObservations", PairEffectCalculator.MIN_OBSERVATIONS);
        model.addAttribute("tab", "tier");
        return "stats/champion";
    }
}
