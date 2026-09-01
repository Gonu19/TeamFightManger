package com.teamfighter.tfm.web;

import com.teamfighter.tfm.story.dao.GalleryDao;
import com.teamfighter.tfm.story.dao.GalleryView;
import com.teamfighter.tfm.story.gallery.GalleryComment;
import com.teamfighter.tfm.story.StoryJobs;
import com.teamfighter.tfm.web.dao.SlotDao;
import com.teamfighter.tfm.web.view.SlotOption;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 갤러리 — {@code /gallery}.
 *
 * <h2>화면 하나가 페이지 하나다</h2>
 *
 * 모드의 게시판은 '반응 불러오기' 를 누를 때마다 <b>새 페이지</b>가 쌓이고 아래 번호로
 * 넘긴다. 우리도 같다: 배치 하나가 페이지 하나이고, {@code ?page=0} 이 가장 최근이다.
 * 페이지 넘기기를 서버가 하는 이유는 그것이 <b>다른 데이터</b>이기 때문이다 —
 * 반면 정렬(최신·조회·추천)은 같은 데이터의 다른 순서라 브라우저가 한다.
 *
 * <h2>생성은 요청 밖에서 돈다</h2>
 *
 * 페이지 하나가 모델 호출 다섯이고 분당 토큰 한도 때문에 최소 몇 분이 걸린다. 그것을
 * 요청 안에서 하면 브라우저가 스피너만 돌리다 끝난다 — 첫 실물이 정확히 그랬다.
 * 그래서 {@link GalleryJobs} 에 맡기고, 화면이 {@code /gallery/status} 를 폴링한다.
 *
 * <h2>읽기와 쓰기의 조건이 다르다</h2>
 *
 * 읽기({@link GalleryDao})는 항상 된다. {@code tfm.story.enabled=false} 인 설치에서도
 * 이미 뽑은 갤러리는 보여야 한다. 쓰기({@link GalleryJobs})는 켰을 때만 있고,
 * {@link Optional} 로 주입받아 없으면 버튼을 아예 안 그린다.
 */
@Controller
public class GalleryController {

    private final GalleryDao galleries;
    private final SlotDao slots;
    private final Optional<StoryJobs> jobs;
    private final ObjectMapper mapper;

    public GalleryController(GalleryDao galleries, SlotDao slots,
                             Optional<StoryJobs> jobs, ObjectMapper mapper) {
        this.galleries = galleries;
        this.slots = slots;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    /**
     * 게시판.
     *
     * @param slot 커리어. 없으면 <b>갤러리가 있는</b> 첫 슬롯을 고른다. 하나도 없으면
     *             적재된 커리어로 물러선다 — 그러지 않으면 고를 커리어가 없어
     *             <b>첫 갤러리를 영영 못 뽑는다</b> (기사 목록이 겪었던 순환과 같다)
     * @param page  0 이 가장 최근 <b>경기</b>의 페이지다
     * @param batch 페이지 번호 대신 <b>배치 번호</b>로 여는 길. 방금 만든 갤러리로
     *              곧장 가는 데 쓴다 — 아래 주석이 왜 필요한지 적어 뒀다
     */
    @GetMapping("/gallery")
    public String board(@RequestParam(required = false) Integer slot,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(required = false) Long batch,
                        Model model) {
        // 커리어 목록은 <b>적재된 것 전부</b>다. 전에는 "갤이 있는 슬롯" 만 넣었고
        // 화면에는 고르개도 없었다 — 그러면 슬롯 2의 첫 갤을 뽑으러 갈 길이 없다.
        // 비어 있다는 사실은 목록에서 빼서가 아니라 "(비어 있음)" 이라고 적어서 말한다.
        List<SlotOption> options =
                slots.options(java.util.Set.copyOf(galleries.slotsWithGalleries()));
        Integer selected = slot != null ? slot
                : options.stream().filter(SlotOption::filled).findFirst()
                        .or(() -> options.stream().findFirst())
                        .map(SlotOption::slotId)
                        .orElse(null);

        List<GalleryView> pages = selected == null ? List.of() : galleries.pages(selected);
        int index = indexOf(pages, batch, page);

        // 머리말만 담은 목록으로 페이지 번호를 그리고, 지금 볼 페이지만 통째로 다시 읽는다.
        // 열 페이지의 글을 한꺼번에 끌어오면 화면 하나가 수 MB 를 읽는다.
        Optional<GalleryView> current = pages.isEmpty()
                ? Optional.empty()
                : galleries.find(pages.get(index).batchId());

        model.addAttribute("slots", options);
        model.addAttribute("selectedSlot", selected);

        // 커리어를 바꾸면 페이지는 0(가장 최근)으로 돌아가는 것이 맞고, batch 는 옛
        // 커리어의 배치 번호라 들고 가면 남의 글을 연다. 그래서 실어 보낼 것이 없다.
        model.addAttribute("carry", Map.of());
        model.addAttribute("pageCount", pages.size());
        model.addAttribute("pageIndex", index);
        model.addAttribute("gallery", current.orElse(null));
        model.addAttribute("canGenerate", jobs.isPresent());

        // 게시판 데이터를 통째로 내려보낸다. 정렬·글 열기·이슈 모달이 전부 화면 안에서
        // 끝나야 모드와 같은 손맛이 나오고, 그러려면 브라우저가 데이터를 들고 있어야 한다.
        model.addAttribute("boardJson", current.map(this::toJson).orElse("null"));
        return "story/gallery";
    }

    /**
     * 몇 번째 페이지를 그릴까.
     *
     * <h2>배치 번호로 여는 길이 왜 필요한가</h2>
     *
     * 페이지는 <b>경기 시점 순</b>이다(최근 경기가 첫 페이지). 그런데 생성기는
     * <b>갤러리가 아직 없는</b> 매치 중 최근 것을 고른다 — 그건 이미 갤러리가 있는
     * 매치보다 <b>과거</b>다. 그래서 방금 만든 갤러리는 첫 페이지가 아니라 뒤쪽에 생긴다.
     *
     * <p>이걸 모르고 "다 됐으니 첫 페이지로" 를 보내면 두 가지가 한꺼번에 깨진다:
     * 새로 만든 글이 안 보이고, 화면이 "아직 그 배치가 아니네" 하며 <b>영원히
     * 새로고침</b>한다. 실물에서 정확히 그렇게 돌았다 — 배치는 멀쩡히 저장돼 있었는데
     * 사용자는 끝내 그것을 못 봤다.
     *
     * <p>그래서 끝난 뒤에는 <b>번호가 아니라 신원</b>으로 연다.
     *
     * @param batch 배치 번호. 목록에 없으면 무시하고 {@code page} 를 쓴다 —
     *              지워졌거나 다른 커리어의 것이다
     */
    private static int indexOf(List<GalleryView> pages, Long batch, int page) {
        if (batch != null) {
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i).batchId() == batch) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(page, pages.size() - 1));
    }

    /**
     * <b>생성 트리거.</b> 시작만 하고 곧바로 돌아온다.
     *
     * <p>POST 인 이유는 {@code /story/generate} 와 같다 — 이 요청은 돈이 나가고 DB 를
     * 바꾼다. GET 이면 새로고침 한 번이 모델 호출 다섯 번이 된다.
     *
     * <p>다른 점은 <b>기다리지 않는다</b>는 것이다. 리다이렉트로 게시판에 돌려보내면
     * 그 화면이 상태를 폴링해 진행 막대를 그린다.
     */
    @PostMapping("/gallery/generate")
    public String generate(@RequestParam int slot, RedirectAttributes redirect) {
        StoryJobs runner = jobs.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "갤러리 생성이 꺼져 있다 — tfm.story.enabled=true 로 켠다 (D61 결정 4)"));

        if (!runner.startGalleryNext(slot)) {
            // 두 번 누른 것은 오류가 아니다. 이미 도는 작업을 그대로 두고 알리기만 한다.
            // 무엇이 도는지를 말한다 — 기사가 도는 중일 수도 있다 (D81: 자리는 하나다).
            String what = runner.status(slot).map(x -> x.kind().object()).orElse("다른 작업을");
            redirect.addFlashAttribute("notice", what + " 이미 만드는 중이다. 아래 진행 상황을 본다.");
        }
        redirect.addAttribute("slot", slot);
        return "redirect:/gallery";
    }

    /**
     * 진행 상황. 게시판 화면이 몇 초마다 부른다.
     *
     * <p>JSON 을 돌려주는 유일한 자리다. 화면 갱신만을 위한 것이라 뷰를 만들 이유가 없고,
     * 폴링이 HTML 한 장을 매번 끌어오면 그것이 오히려 느리다.
     *
     * @return 아직 한 번도 안 눌렀으면 {@code {"state":"IDLE"}}
     */
    @GetMapping("/gallery/status")
    @ResponseBody
    public Map<String, Object> status(@RequestParam int slot) {
        return jobs.flatMap(runner -> runner.status(slot))
                .<Map<String, Object>>map(s -> Map.of(
                        "state", s.state().name(),
                        "kind", s.kind().name(),
                        "step", s.step(),
                        "percent", s.percent(),
                        "batchId", s.resultId() == null ? 0L : s.resultId(),
                        "message", s.message() == null ? "" : s.message()))
                .orElse(Map.of("state", "IDLE"));
    }

    /**
     * 게시판 데이터를 페이지에 심을 JSON 으로.
     *
     * <h2>레코드를 그대로 직렬화하지 않는다</h2>
     *
     * {@link GalleryView} 를 통째로 넘기면 필드 이름이 자바 쪽 사정에 묶인다 —
     * 컴포넌트 이름을 바꾸는 순간 화면이 조용히 빈 칸을 그린다. 무엇보다 enum 이
     * 이름({@code "FLAME"})으로만 나가서 화면이 한글 라벨과 배지 색을 다시 알아야 한다.
     * 여기서 <b>화면이 쓸 모양 그대로</b> 만들어 넘기면 그 지식이 한 곳에 남는다.
     *
     * <h2>{@code <} 를 이스케이프한다</h2>
     *
     * 이 문자열은 {@code <script type="application/json">} 안에 그대로 들어가는데,
     * 값 어딘가에 {@code </script>} 가 있으면 브라우저가 <b>거기서 스크립트를 끝낸다</b> —
     * 그 뒤는 문서 본문이 된다. 갤 글은 모델이 쓴 자유 문자열이라 실제로 그럴 수 있고,
     * 기사 화면에서 같은 종류의 실패를 이미 한 번 겪었다(HTML 주석 안의 주석 닫는 기호).
     *
     * <p>JSON 은 그 여섯 글자를 {@code <} 로 되읽으므로 값은 화면에서 그대로 살아난다.
     */
    private String toJson(GalleryView gallery) {
        Map<String, Object> board = Map.of(
                "batchId", gallery.batchId(),
                "posts", gallery.posts().stream().map(GalleryController::postJson).toList());
        return mapper.writeValueAsString(board).replace("<", "\\u003c");
    }

    private static Map<String, Object> postJson(GalleryView.Post post) {
        return mapOf(
                "ordinal", post.ordinal(),
                "kind", post.kind().label(),
                "title", post.title(),
                "author", post.author(),
                "body", post.body(),
                "views", post.views(),
                "likes", post.likes(),
                "concept", post.isConcept(),
                "imageDesc", post.imageDesc(),
                "date", post.postedAt(),
                "commentCount", post.commentCount(),
                "comments", commentsJson(post));
    }

    /**
     * 댓글을 <b>중첩</b>으로 만든다. 저장은 평평하지만(부모 순번을 들고 있다) 화면은
     * 원댓글 아래에 대댓글을 그리므로, 그 변환을 여기서 한 번에 끝낸다 —
     * 브라우저에서 하면 순번 규칙을 화면이 알아야 한다.
     */
    private static List<Map<String, Object>> commentsJson(GalleryView.Post post) {
        List<GalleryComment> roots = post.roots();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            GalleryComment root = roots.get(i);
            out.add(mapOf(
                    "author", root.author(),
                    "body", root.body(),
                    "date", root.postedAt(),
                    "replies", post.repliesTo(i).stream()
                            .map(reply -> mapOf(
                                    "author", reply.author(),
                                    "body", reply.body(),
                                    "date", reply.postedAt()))
                            .toList()));
        }
        return out;
    }

    /**
     * {@link Map#of} 를 못 쓴다 — <b>null 값을 거부</b>하기 때문이다. 조회수·닉네임·
     * 작성 시각은 모델이 안 주면 {@code null} 이고, 그 {@code null} 이 "안 줬다" 라는
     * 뜻을 나른다(D71). 0 이나 빈 문자열로 바꾸면 그 뜻이 사라진다.
     */
    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
