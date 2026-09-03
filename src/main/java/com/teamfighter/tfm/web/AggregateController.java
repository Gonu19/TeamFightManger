package com.teamfighter.tfm.web;

import com.teamfighter.tfm.analysis.AggregationService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 집계 다시 돌리기 — {@code POST /aggregate}.
 *
 * <h2>왜 자기 컨트롤러를 갖나</h2>
 *
 * 이 동작은 <b>통계도 창작도 아니다.</b> {@code analysis/} 를 부르는 일이고, 두 화면이
 * 똑같이 필요로 한다 — 연대기에서는 사이클 ② 이고, 티어에서는 "숫자가 낡았다" 를 고치는
 * 버튼이다.
 *
 * <p>전에는 {@code POST /story/aggregate} 였다. 연대기 화면에만 버튼이 있었으니 그 자리가
 * 자연스러웠는데, 티어 화면에도 버튼을 붙이는 순간 <b>통계 화면에 {@code /story} 로 가는
 * 주소가 생긴다.</b> D61 이 물리적으로 끊어 둔 것은 링크만이 아니라 <b>두 세계가 서로를
 * 아는 것</b> 자체다 — 폼의 action 도 그 앎에 든다. 그래서 어느 쪽에도 안 속한 경로로 옮겼다.
 *
 * <h2>요청 안에서 돈다</h2>
 *
 * 실측 <b>1.5초</b>(슬롯 4 · 경기 1,175 · 참가자 9,400). 모델 호출과 달리 바깥으로
 * 나가지 않으므로 {@code StoryJobs} 의 작업 잠금을 쓰지 않는다 (D81).
 *
 * <p><b>잠금이 없다는 것은 동시에 부르면 안 된다는 뜻이기도 하다.</b> 집계는
 * {@code DELETE} 후 {@code INSERT} 로 표를 갈아치우므로 두 번이 겹치면 부분적으로 지워진
 * 표가 남는다. 지금은 사람이 버튼을 눌러야만 돌아서 사실상 직렬이다 — 적재 완료를 신호로
 * 자동화하려면 그때 잠금을 같이 넣어야 한다 ({@code decisions/OPEN.md}).
 */
@Controller
public class AggregateController {

    private final AggregationService aggregation;

    public AggregateController(AggregationService aggregation) {
        this.aggregation = aggregation;
    }

    /**
     * 집계를 한 바퀴 돌리고 <b>부른 화면으로 돌려보낸다.</b>
     *
     * <p>돌아갈 곳을 경로 문자열로 받지 않는다. 받으면 {@code ?from=http://…} 로 아무 데나
     * 보낼 수 있는 자리가 되고, 그건 인증이 없는 앱(D41·D59)에서 특히 나쁘다.
     * 허락된 이름만 받아 이 메서드가 경로를 만든다.
     *
     * @param from     {@code tier} 면 티어로, 그 밖의 값은 전부 연대기로
     * @param slot     보고 있던 커리어
     * @param scrim    티어에서만 쓴다 — 커리어를 유지하듯 거르개도 유지한다 (D82 결정 5)
     * @param category 티어의 역할군 탭. 비었으면 안 싣는다
     */
    @PostMapping("/aggregate")
    public String aggregate(@RequestParam(required = false) String from,
                            @RequestParam(required = false) Integer slot,
                            @RequestParam(required = false) Boolean scrim,
                            @RequestParam(required = false) String category,
                            RedirectAttributes redirect) {
        AggregationService.Result result = aggregation.run();

        redirect.addFlashAttribute("notice",
                "집계를 다시 돌렸다 — 카운터 " + result.counterRows() + "행 · 티어 "
                        + result.performanceRows() + "행 · 쌍 효과 " + result.pairRows() + "행.");

        if (slot != null) {
            redirect.addAttribute("slot", slot);
        }

        if (!"tier".equals(from)) {
            return "redirect:/story";
        }

        // 티어는 거르개를 두 개 더 들고 있다. 안 실어 보내면 집계 한 번에 역할군 탭이
        // 조용히 "전체" 로 돌아가고, 사용자는 자기가 누르지 않은 변화를 보게 된다.
        if (scrim != null) {
            redirect.addAttribute("scrim", scrim);
        }
        if (category != null && !category.isBlank()) {
            redirect.addAttribute("category", category);
        }
        return "redirect:/tier";
    }
}
