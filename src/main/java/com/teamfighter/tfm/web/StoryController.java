package com.teamfighter.tfm.web;

import com.teamfighter.tfm.story.dao.ArticleCard;
import com.teamfighter.tfm.story.dao.ArticleDao;
import com.teamfighter.tfm.story.dao.ArticleView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 연대기 — {@code /story} 와 {@code /story/{id}}.
 *
 * <p><b>통계 화면과 링크로 잇지 않는다 (D61).</b> 여기서 챔피언 이름을 눌러 티어로 가는 길은
 * 만들지 않는다. 상단 탭에서만 만난다 — 그 탭은 통계 화면이 생길 때 붙인다. 지금 넣으면
 * 죽은 링크가 되고, 죽은 링크는 "아직 없다" 가 아니라 "고장났다" 로 읽힌다.
 *
 * <p><b>기사 생성이 꺼져 있어도 이 화면은 돈다.</b> 읽기는 {@link ArticleDao} 만 쓰고
 * {@code StoryClient} 를 모른다. {@code tfm.story.enabled=false} 인 설치에서도 이미 쓴 기사는
 * 보여야 한다 — 켜야 볼 수 있게 만들면 D61 결정 4(opt-in)가 "쓴 것도 못 본다" 로 번진다.
 *
 * <p><b>화면이 통계를 계산하지 않는다.</b> 목록 카드도 상세도 DB 에 저장된 값을 그대로 그린다.
 * 특히 {@code factStatus} 를 지적 목록에서 다시 세지 않는다 — 저장된 값과 갈리면
 * 저장 경로의 구멍을 화면이 덮어 가린다({@link ArticleView#factStatusMatchesFindings()}).
 */
@Controller
public class StoryController {

    /** 한 화면에 올리는 기사 수. 커리어 한 벌이 100편 남짓이라 페이지를 아직 나누지 않는다. */
    private static final int PAGE_SIZE = 50;

    private final ArticleDao articles;

    public StoryController(ArticleDao articles) {
        this.articles = articles;
    }

    /**
     * 기사 목록.
     *
     * @param slot 커리어. 없으면 <b>기사가 있는</b> 첫 슬롯을 고른다. 기사가 하나도 없으면
     *             빈 화면을 보여준다 — 그건 오류가 아니라 아직 안 쓴 상태다
     */
    @GetMapping("/story")
    public String list(@RequestParam(required = false) Integer slot, Model model) {
        List<Integer> slots = articles.slotsWithArticles();
        Integer selected = slot != null ? slot : (slots.isEmpty() ? null : slots.get(0));

        List<ArticleCard> cards = selected == null
                ? List.of()
                : articles.recent(selected, PAGE_SIZE);

        model.addAttribute("slots", slots);
        model.addAttribute("selectedSlot", selected);
        model.addAttribute("cards", cards);
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
}
