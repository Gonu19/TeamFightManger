package com.teamfighter.tfm.web;

import com.teamfighter.tfm.analysis.AggregationService;
import com.teamfighter.tfm.story.StoryGenerator;
import com.teamfighter.tfm.story.dao.ArticleCard;
import com.teamfighter.tfm.story.dao.ArticleDao;
import com.teamfighter.tfm.story.dao.ArticleView;
import com.teamfighter.tfm.story.StoryJobs;
import com.teamfighter.tfm.web.dao.CycleDao;
import com.teamfighter.tfm.web.dao.SlotDao;
import com.teamfighter.tfm.web.view.SlotOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 연대기 — {@code /story} · {@code /story/{id}} · {@code POST /story/generate}.
 *
 * <h2>통계 화면과 링크로 잇지 않는다 (D61)</h2>
 *
 * 챔피언 이름을 눌러 티어로 가는 길을 만들지 않는다. 두 세계는 상단 탭에서만 만나고,
 * 그 탭은 통계 화면이 생길 때 붙인다 — 지금 넣으면 죽은 링크가 되고, 죽은 링크는
 * "아직 없다" 가 아니라 "고장났다" 로 읽힌다. 이 규칙은 주석이 아니라
 * {@code StoryControllerTest} 가 응답 본문에 {@code /tier} 문자열이 없음을 확인해 지킨다.
 *
 * <h2>읽기와 쓰기의 조건이 다르다</h2>
 *
 * <ul>
 *   <li><b>읽기</b>({@link ArticleDao})는 항상 된다. {@code tfm.story.enabled=false} 인
 *       설치에서도 이미 쓴 기사는 보여야 한다 — 켜야 볼 수 있게 만들면 opt-in(D61 결정 4)이
 *       "쓴 것도 못 본다" 로 번진다</li>
 *   <li><b>쓰기</b>({@link StoryGenerator})는 켰을 때만 있다. 그래서 {@link Optional} 로
 *       주입받는다 — 스프링은 빈이 없으면 {@code Optional.empty()} 를 넣어준다.
 *       필드가 비어 있으면 화면은 생성 버튼을 아예 안 그린다</li>
 * </ul>
 *
 * <h2>화면이 통계를 계산하지 않는다</h2>
 *
 * 목록 카드도 상세도 DB 에 저장된 값을 그대로 그린다. 특히 {@code factStatus} 를 지적
 * 목록에서 다시 세지 않는다 — 저장된 값과 갈리면 저장 경로의 구멍을 화면이 덮어 가린다
 * ({@link ArticleView#factStatusMatchesFindings()} 가 그 대조를 물어볼 수만 있게 남겨 뒀다).
 */
@Controller
public class StoryController {

    private static final Logger log = LoggerFactory.getLogger(StoryController.class);

    /**
     * 한 화면에 올리는 기사 수.
     *
     * <p>커리어 한 벌이 매치 109편 남짓이라 페이지를 나누지 않아도 한 화면에 다 들어간다.
     * 페이지네이션은 <b>목록이 실제로 길어지면</b> 넣는다 — 지금 넣으면 화면 하나에
     * 페이지 이동·현재 페이지 표시·경계 처리가 붙는데, 그 코드는 한 페이지짜리 목록에서
     * 한 번도 실행되지 않아 틀려도 아무도 모른다.
     */
    private static final int PAGE_SIZE = 50;

    private final ArticleDao articles;
    private final SlotDao slots;
    private final CycleDao cycles;
    private final AggregationService aggregation;
    private final Optional<StoryGenerator> generator;
    private final Optional<StoryJobs> jobs;

    public StoryController(ArticleDao articles, SlotDao slots, CycleDao cycles,
                           AggregationService aggregation,
                           Optional<StoryGenerator> generator,
                           Optional<StoryJobs> jobs) {
        this.articles = articles;
        this.slots = slots;
        this.cycles = cycles;
        this.aggregation = aggregation;
        this.generator = generator;
        this.jobs = jobs;
    }

    /**
     * 루트. 지금은 화면이 하나뿐이라 연대기로 보낸다.
     *
     * <p>이게 없으면 {@code http://127.0.0.1:8088/} 이 404 — 스프링 기본 오류 화면
     * (Whitelabel Error Page)이다. 앱이 뜬 것도 안 뜬 것도 아닌 상태로 보이므로,
     * 갈 곳이 하나라도 있으면 그리로 보내는 편이 낫다.
     *
     * <p><b>통계 화면이 생겨서 목적지를 바꿨다.</b> 시작 화면은 "지금 무엇이 센가" 가
     * 맞다 — 예고한 대로 메서드를 지우지 않고 목적지만 옮겼다.
     */
    @GetMapping("/")
    public String home() {
        // redirect: 접두사는 뷰 이름이 아니라 302 응답을 뜻한다. forward 로 하면
        // 주소창이 "/" 로 남아서, 사용자가 새로고침할 때 어디에 있는지 알 수 없다.
        return "redirect:/tier";
    }

    /**
     * 기사 목록.
     *
     * @param slot 커리어. 없으면 <b>기사가 있는</b> 첫 슬롯을 고른다. 기사가 한 편도 없으면
     *             {@code null} 로 두고 빈 화면을 보여준다 — 그건 오류가 아니라 아직 안 쓴 상태다
     */
    @GetMapping("/story")
    public String list(@RequestParam(required = false) Integer slot, Model model) {
        // 커리어 목록은 <b>적재된 것 전부</b>다. 전에는 "기사가 있는 슬롯" 만 넣었는데,
        // 그러면 슬롯 2로 첫 기사를 쓰러 갈 길이 없다 — 기사가 있어야 줄이 보이고
        // 그 줄을 눌러야 기사가 생기는 순환이다. 비어 있다는 사실은 목록에서 빼서가
        // 아니라 "(비어 있음)" 이라고 적어서 말한다 (fragments/filters.html).
        Set<Integer> withArticles = Set.copyOf(articles.slotsWithArticles());
        List<SlotOption> options = slots.options(withArticles);

        // 사용자가 고른 값이 1순위. 없으면 기사가 있는 첫 슬롯, 그것도 없으면 첫 슬롯.
        // null 을 그대로 뷰까지 보내는 이유는 "고를 것이 없다" 와 "안 골랐다" 가
        // 화면에서 같은 그림이기 때문이다.
        Integer selected = slot != null ? slot : firstSlot(options);

        model.addAttribute("slots", options);
        model.addAttribute("selectedSlot", selected);

        // 커리어 말고는 이 화면에 거르개가 없다. 고르개가 같이 실을 인자도 없다.
        model.addAttribute("carry", Map.of());

        // 사이클 목록 — 매치 하나가 한 줄이고, 그 줄이 적재·기사·갤러리를 다 들고 있다.
        model.addAttribute("cycle", selected == null
                ? List.of() : cycles.matches(selected, PAGE_SIZE));

        // 기사 목록은 그대로 남긴다. 사이클이 "무엇을 할 차례인가" 를 말한다면
        // 이쪽은 "무엇을 썼나" 이고, 둘은 다른 질문이다.
        model.addAttribute("cards", selected == null
                ? List.of() : articles.recent(selected, PAGE_SIZE));

        // 생성기가 없으면(=story 가 꺼져 있으면) 버튼을 안 그린다
        model.addAttribute("canGenerate", generator.isPresent());
        model.addAttribute("canGenerateGallery", jobs.isPresent());
        // 도는 중이면 화면이 버튼을 잠그고 진행 막대를 그린다 (D81).
        model.addAttribute("busy", selected != null
                && jobs.map(runner -> runner.isBusy(selected)).orElse(false));
        return "story/list";
    }

    /** 내용이 있는 커리어를 먼저 보여준다. 없으면 첫 커리어, 그것도 없으면 {@code null}. */
    private static Integer firstSlot(List<SlotOption> options) {
        return options.stream().filter(SlotOption::filled).findFirst()
                .or(() -> options.stream().findFirst())
                .map(SlotOption::slotId)
                .orElse(null);
    }

    /**
     * 기사 하나. 본문 · 댓글 · 「이 기사가 쓴 숫자」 · 지적을 한 화면에 놓는다 (D61 결정 2).
     *
     * <p>없는 기사는 404 다. 빈 화면을 200 으로 돌려주면 주소를 잘못 친 것인지 기사가
     * 지워진 것인지 알 수 없다.
     */
    @GetMapping("/story/{id}")
    public String detail(@PathVariable long id, Model model) {
        ArticleView article = articles.find(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "기사 " + id + " 가 없다"));

        model.addAttribute("article", article);
        return "story/detail";
    }

    /*
     * POST /story/generate 를 걷어냈다 (D81).
     *
     * "아직 안 쓴 매치 중 가장 최근 것" 을 동기로 쓰던 입구다. 화면이 매치를 줄로 세운
     * 뒤로(D79) 부르는 곳이 없어졌고, 남겨 두면 주소로는 여전히 닿는 <b>30초짜리 동기
     * 경로</b>가 된다 — 진행 상황도 없고 작업 잠금도 안 거치는, 이번에 고치려던 바로 그 길.
     *
     * 고르는 규칙 자체는 안 사라졌다. StoryGenerator.writeLatestUnwritten 이 그대로 있고
     * StoryGeneratorTest 가 그것을 지킨다. 없어진 것은 <b>그 규칙으로 가는 HTTP 문</b>뿐이다.
     */

    /**
     * <b>사이클 ② — 집계를 다시 돌린다.</b>
     *
     * <p>적재는 워처가 자동으로 한다. 집계는 <b>안 한다</b> — 그래서 경기를 하고 세이브를
     * 저장하면 매치는 목록에 뜨는데 티어와 쌍 효과는 어제 값 그대로다. 그 어긋남은
     * 화면 어디에도 안 보인다(숫자가 여전히 그럴듯하다).
     *
     * <p>적재 완료를 신호로 자동으로 돌리는 것이 자연스러워 보이지만, 그러면 워처가
     * 저장을 감지할 때마다 집계가 돈다. 그 비용을 아직 안 쟀고, 기동 시 두
     * {@code ApplicationRunner} 의 순서 문제도 남아 있다({@code decisions/OPEN.md}).
     *
     * <p>요청 안에서 돌려도 되는 이유는 <b>1초 남짓</b>이기 때문이다 — 모델 호출과 달리
     * 바깥으로 나가지 않는다. 그래서 이것만은 작업 잠금을 안 쓴다.
     */
    @PostMapping("/story/aggregate")
    public String aggregate(@RequestParam(required = false) Integer slot,
                            RedirectAttributes redirect) {
        AggregationService.Result result = aggregation.run();
        redirect.addFlashAttribute("notice",
                "집계를 다시 돌렸다 — 카운터 " + result.counterRows() + "행 · 티어 "
                        + result.performanceRows() + "행 · 쌍 효과 " + result.pairRows() + "행.");
        if (slot != null) {
            redirect.addAttribute("slot", slot);
        }
        return "redirect:/story";
    }

    /**
     * <b>사이클 ③ — 이 매치의 기사를 쓴다.</b> 연대기 목록의 줄마다 붙은 버튼이다.
     *
     * <h2>시작만 하고 곧바로 돌아온다 (D81)</h2>
     *
     * 전에는 여기서 모델을 두 번 부르고 끝날 때까지 기다렸다 — 브라우저가 20~30초 동안
     * 흰 화면을 물고 있었고, 그건 사용자에게 <b>멈춘 것과 구분되지 않는다.</b> 그동안
     * 갤러리 버튼도 멀쩡히 눌렸고, 둘이 동시에 돌면 분당 토큰을 서로 잡아먹었다.
     *
     * <p>이제 작업만 띄우고 연대기로 돌아간다. 그 화면이 상태를 폴링해 단계를 그리고,
     * 끝나면 기사로 데려가며, 실패하면 <b>원인을 그 자리에 남긴다.</b>
     *
     * <h2>왜 "가장 최근" 이 아닌가</h2>
     *
     * 화면이 매치를 줄로 세운 뒤로는 <b>사용자가 이미 골랐다.</b> 생성기가 다시 고르면
     * 3일차 버튼을 눌렀을 때 5일차 기사가 나오고, 그 어긋남은 "기사가 하나 생겼다"
     * 로만 보인다 (D79).
     */
    @PostMapping("/story/generate-match")
    public String generateMatch(@RequestParam int slot,
                                @RequestParam int season, @RequestParam int day,
                                @RequestParam int teamA, @RequestParam int teamB,
                                RedirectAttributes redirect) {
        StoryJobs runner = jobs.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "기사 생성이 꺼져 있다 — tfm.story.enabled=true 로 켠다 (D61 결정 4)"));

        if (!runner.startArticle(slot, season, day, teamA, teamB)) {
            // 종류를 안 가리는 거절이다 — 분당 토큰이 하나라 기사와 갤러리는
            // 서로의 경쟁자다 (D81).
            redirect.addFlashAttribute("notice", busyNotice(runner, slot));
        }
        redirect.addAttribute("slot", slot);
        return "redirect:/story";
    }

    /**
     * <b>사이클 ④ — 이 매치의 갤러리 반응을 뽑는다.</b>
     *
     * <p>기사와 같은 자리를 쓴다. 끝나면 화면이 그 배치로 데려간다.
     */
    @PostMapping("/story/generate-gallery")
    public String generateGallery(@RequestParam int slot,
                                  @RequestParam int season, @RequestParam int day,
                                  @RequestParam int teamA, @RequestParam int teamB,
                                  RedirectAttributes redirect) {
        StoryJobs runner = jobs.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "갤러리 생성이 꺼져 있다 — tfm.story.enabled=true 로 켠다 (D61 결정 4)"));

        if (!runner.startGallery(slot, season, day, teamA, teamB)) {
            redirect.addFlashAttribute("notice", busyNotice(runner, slot));
        }
        // 연대기에 머문다. 진행 막대가 여기 있고, 끝나면 화면이 갤러리로 데려간다.
        redirect.addAttribute("slot", slot);
        return "redirect:/story";
    }

    /**
     * <b>라운드 총평 트리거.</b> 아직 총평이 없는 날 중 가장 최근 하루를 정리한다.
     *
     * <p>매치 기사와 <b>버튼을 나눈 이유</b>는 분당 토큰이다. 한 버튼으로 묶으면 한 번에
     * 모델 호출이 넷이 되어 무료 티어 한도(8,000)에 거의 확실히 걸린다. 나누면 사람이
     * 누르는 사이에 창이 다시 열린다 — 비용의 단위를 사람이 고르게 한다는 수동 트리거의
     * 취지와도 맞는다.
     */
    @PostMapping("/story/generate-round")
    public String generateRound(@RequestParam int slot, RedirectAttributes redirect) {
        StoryJobs runner = jobs.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "기사 생성이 꺼져 있다 — tfm.story.enabled=true 로 켠다 (D61 결정 4)"));

        if (!runner.startRound(slot)) {
            redirect.addFlashAttribute("notice", busyNotice(runner, slot));
        }
        redirect.addAttribute("slot", slot);
        return "redirect:/story";
    }

    /**
     * 지금 무엇이 도는가. 화면이 몇 초마다 물어 진행 막대를 갱신한다 (D81).
     *
     * <p>생성이 꺼져 있으면 <b>빈 상태</b>를 돌려준다 — 404 가 아니다. 화면의 폴링은
     * 기능이 꺼져 있어도 도는데, 404 를 주면 콘솔이 빨갛게 물들고 그건 고장으로 읽힌다.
     */
    @GetMapping("/story/status")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> status(@RequestParam int slot) {
        return jobs.flatMap(runner -> runner.status(slot))
                .<java.util.Map<String, Object>>map(status -> {
                    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                    out.put("state", status.state().name());
                    out.put("kind", status.kind().name());
                    out.put("label", status.kind().label());
                    out.put("object", status.kind().object());
                    out.put("step", status.step());
                    out.put("percent", status.percent());
                    out.put("done", status.done());
                    out.put("total", status.total());
                    out.put("message", status.message());
                    out.put("detail", status.detail());
                    out.put("next", status.destination(slot));
                    return out;
                })
                .orElse(java.util.Map.of("state", "IDLE"));
    }

    /**
     * 이미 도는 중일 때 보여줄 한 줄.
     *
     * <p><b>무엇이 도는지를 말한다.</b> "이미 돌고 있다" 만 쓰면 사용자는 자기가 방금
     * 누른 것이 시작된 줄 안다 — 갤러리가 도는 중에 기사를 누른 경우가 그렇다.
     */
    private static String busyNotice(StoryJobs runner, int slot) {
        String what = runner.status(slot)
                .map(status -> status.kind().object())
                .orElse("다른 작업을");
        return what + " 이미 뽑는 중이다. 분당 토큰이 하나라 한 번에 하나만 돈다 — "
                + "끝나면 다시 누른다.";
    }

    /*
     * backToListWith 도 같이 걷어냈다 (D81).
     *
     * 동기 생성이 던진 예외를 목록의 한 줄로 바꾸던 자리다. 이제 실패는 요청이 아니라
     * <b>작업</b> 안에서 나고, StoryJobs 가 그것을 FAILED 상태와 원인 메시지로 들고 있는다 —
     * 화면은 진행 막대 자리에서 그 메시지를 읽는다. 실패를 사람이 읽게 한다는 목적은
     * 그대로이고, 읽는 자리가 바뀌었다.
     */
}
