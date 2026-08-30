package com.teamfighter.tfm.web;

import com.teamfighter.tfm.story.StoryClient;
import com.teamfighter.tfm.story.StoryGenerator;
import com.teamfighter.tfm.story.dao.ArticleCard;
import com.teamfighter.tfm.story.dao.ArticleDao;
import com.teamfighter.tfm.story.dao.ArticleView;
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
import java.util.Optional;

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
    private final Optional<StoryGenerator> generator;

    public StoryController(ArticleDao articles, Optional<StoryGenerator> generator) {
        this.articles = articles;
        this.generator = generator;
    }

    /**
     * 루트. 지금은 화면이 하나뿐이라 연대기로 보낸다.
     *
     * <p>이게 없으면 {@code http://127.0.0.1:8088/} 이 404 — 스프링 기본 오류 화면
     * (Whitelabel Error Page)이다. 앱이 뜬 것도 안 뜬 것도 아닌 상태로 보이므로,
     * 갈 곳이 하나라도 있으면 그리로 보내는 편이 낫다.
     *
     * <p>통계 화면이 생기면 여기는 {@code /tier} 로 바뀐다 — 시작 화면은 "지금 무엇이 센가"
     * 가 맞다. 그때 이 메서드를 지우는 게 아니라 목적지만 바꾼다.
     */
    @GetMapping("/")
    public String home() {
        // redirect: 접두사는 뷰 이름이 아니라 302 응답을 뜻한다. forward 로 하면
        // 주소창이 "/" 로 남아서, 사용자가 새로고침할 때 어디에 있는지 알 수 없다.
        return "redirect:/story";
    }

    /**
     * 기사 목록.
     *
     * @param slot 커리어. 없으면 <b>기사가 있는</b> 첫 슬롯을 고른다. 기사가 한 편도 없으면
     *             {@code null} 로 두고 빈 화면을 보여준다 — 그건 오류가 아니라 아직 안 쓴 상태다
     */
    @GetMapping("/story")
    public String list(@RequestParam(required = false) Integer slot, Model model) {
        // 슬롯 목록은 save_slot 이 아니라 article 에서 뽑는다. 기사가 없는 슬롯을 기본값으로
        // 골라주면 "기본 커리어를 보여준다" 는 목적이 그 자리에서 실패한다.
        List<Integer> slots = articles.slotsWithArticles();

        // 사용자가 고른 값이 1순위, 없으면 첫 슬롯, 그것도 없으면 null.
        // null 을 그대로 뷰까지 보내는 이유는 "고를 것이 없다" 와 "안 골랐다" 가
        // 화면에서 같은 그림이기 때문이다 — 둘을 나누려면 뷰에 분기가 하나 더 생긴다.
        Integer selected = slot != null ? slot : (slots.isEmpty() ? null : slots.get(0));

        List<ArticleCard> cards = selected == null
                ? List.of()
                : articles.recent(selected, PAGE_SIZE);

        model.addAttribute("slots", slots);
        model.addAttribute("selectedSlot", selected);
        model.addAttribute("cards", cards);
        // 생성기가 없으면(=story 가 꺼져 있으면) 버튼을 안 그린다
        model.addAttribute("canGenerate", generator.isPresent());
        return "story/list";
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

    /**
     * <b>수동 트리거.</b> 아직 안 쓴 매치 중 가장 최근 것 한 편을 만든다.
     *
     * <h2>왜 POST 인가</h2>
     *
     * 이 요청은 <b>돈이 나가고 DB 가 바뀐다.</b> GET 이면 브라우저가 미리 가져오거나
     * 사용자가 새로고침만 해도 다시 나간다 — 새로고침 한 번이 모델 호출 두 번이 된다.
     *
     * <h2>왜 끝나고 리다이렉트인가</h2>
     *
     * POST 응답으로 HTML 을 그대로 그리면, 사용자가 새로고침할 때 브라우저가 "양식을 다시
     * 제출할까요" 를 묻고 예를 누르면 기사를 또 만든다. POST → 302 → GET 으로 끊으면
     * 새로고침이 <b>목록 조회</b>가 된다 (Post/Redirect/Get).
     *
     * <h2>실패를 삼키지 않는다</h2>
     *
     * 모델 호출이 실패하면 예외가 그대로 올라가 오류 화면이 뜬다. "조용히 아무 일도 안 일어남"
     * 이 이 프로젝트에서 가장 비싼 실패였다(D31). 다만 <b>쓸 매치가 없는 것은 실패가 아니다</b> —
     * 그건 정상이라 메시지로만 알린다.
     */
    @PostMapping("/story/generate")
    public String generate(@RequestParam int slot, RedirectAttributes redirect) {
        StoryGenerator writer = generator.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "기사 생성이 꺼져 있다 — tfm.story.enabled=true 로 켠다 (D61 결정 4)"));

        try {
            Optional<Long> articleId = writer.writeLatestUnwritten(slot);

            if (articleId.isEmpty()) {
                // 다 썼다. 화면에 한 줄 남기고 목록으로 돌아간다.
                // addFlashAttribute 는 리다이렉트 <b>한 번</b>만 살아남는 모델 값이다 —
                // 세션에 담았다가 다음 요청에서 꺼내고 지운다. 쿼리스트링으로 넘기면
                // 그 주소를 북마크했을 때 메시지가 영원히 따라다닌다.
                redirect.addFlashAttribute("notice", "새로 쓸 매치가 없다. 끝난 매치를 모두 썼다.");
                redirect.addAttribute("slot", slot);
                return "redirect:/story";
            }

            // 방금 쓴 기사를 곧바로 보여준다. 목록으로 보내면 사용자가 그중 어느 것이
            // 새로 생긴 것인지 찾아야 한다.
            return "redirect:/story/" + articleId.get();

        } catch (StoryClient.StoryUnavailableException e) {
            // 켜져는 있는데 키가 없는 상태. 오류 화면보다 목록 위의 한 줄이 낫다 —
            // 사용자가 할 일이 "설정을 고친다" 로 분명하기 때문이다.
            log.warn("기사 생성을 부를 수 없다: {}", e.getMessage());
            redirect.addFlashAttribute("notice", "기사 생성을 부를 수 없다: " + e.getMessage());
            redirect.addAttribute("slot", slot);
            return "redirect:/story";
        }
    }
}
