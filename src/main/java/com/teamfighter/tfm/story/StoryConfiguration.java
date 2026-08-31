package com.teamfighter.tfm.story;

import com.teamfighter.tfm.ingest.watcher.TfmProperties;
import com.teamfighter.tfm.story.dao.ArticleDao;
import com.teamfighter.tfm.story.dao.GalleryDao;
import com.teamfighter.tfm.story.gallery.GalleryGenerator;
import com.teamfighter.tfm.story.gallery.GalleryJobs;
import com.teamfighter.tfm.story.gallery.GalleryWriter;
import com.teamfighter.tfm.story.dao.StoryReferenceDao;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code story/} 를 애플리케이션에 거는 유일한 자리.
 *
 * <p><b>플래그를 켜야 빈이 생긴다 (D61 결정 4).</b> {@code AnalysisConfiguration} 과 같은
 * 모양이지만 이유가 하나 더 있다 — 저쪽은 안 켜면 <i>안 도는</i> 것이고, 여기는 안 켜면
 * <b>바깥으로 요청이 안 나가는</b> 것이다. 켜지 않은 설치에서 기사 생성기가 우연히 불릴
 * 경로가 아예 없어야 한다.
 *
 * <p>{@link StoryProperties} 만은 항상 등록한다. 화면이 "이 기능은 꺼져 있다" 를 말하려면
 * 꺼져 있다는 사실 자체는 읽을 수 있어야 하기 때문이다.
 *
 * <p>{@link ArticleWriter} 에 {@code @Service} 를 붙이지 않은 것도 같은 이유다. 붙이면
 * 플래그와 무관하게 빈이 생기고, 그러면 {@link StoryClient} 빈이 없는 설치에서 컨텍스트가
 * 아예 안 뜬다 — 꺼두려던 기능이 앱 전체를 못 뜨게 만든다.
 */
@Configuration
@EnableConfigurationProperties(StoryProperties.class)
public class StoryConfiguration {

    @ConditionalOnProperty(prefix = "tfm.story", name = "enabled", havingValue = "true")
    @Bean
    public StoryClient storyClient(StoryProperties properties, ObjectMapper mapper) {
        return new HttpStoryClient(properties, mapper);
    }

    @ConditionalOnProperty(prefix = "tfm.story", name = "enabled", havingValue = "true")
    @Bean
    public ArticleWriter articleWriter(StoryClient client, ArticleDao articles,
                                       StoryReferenceDao references, StoryProperties properties) {
        return new ArticleWriter(client, articles, references, properties);
    }

    /**
     * 갤러리를 만드는 쪽. {@link ArticleWriter} 와 같은 조건이다 —
     * 꺼진 설치에는 바깥으로 나가는 호출이 아예 없어야 한다.
     */
    @ConditionalOnProperty(prefix = "tfm.story", name = "enabled", havingValue = "true")
    @Bean
    public GalleryWriter galleryWriter(StoryClient client, GalleryDao galleries,
                                       StoryProperties properties) {
        return new GalleryWriter(client, galleries, properties);
    }

    /** 갤러리 트리거의 알맹이. 매치를 고르고 세이브를 다시 읽는다 (D73). */
    @ConditionalOnProperty(prefix = "tfm.story", name = "enabled", havingValue = "true")
    @Bean
    public GalleryGenerator galleryGenerator(GalleryWriter writer, GalleryDao galleries,
                                             ArticleDao articles, StoryReferenceDao references,
                                             TfmProperties properties) {
        return new GalleryGenerator(writer, galleries, articles, references, properties);
    }

    /**
     * 갤러리 생성을 요청 밖에서 돌린다.
     *
     * <p>이것도 플래그 뒤에 둔다. 꺼진 설치에 작업 큐만 떠 있으면 화면이 "시작할 수 있다"
     * 고 믿게 되고, 눌러야 비로소 "생성이 꺼져 있다" 를 알게 된다 — 눌러야 알 수 있는
     * 버튼은 버튼이 아니다.
     */
    @ConditionalOnProperty(prefix = "tfm.story", name = "enabled", havingValue = "true")
    @Bean
    public GalleryJobs galleryJobs(GalleryGenerator generator) {
        return new GalleryJobs(generator);
    }

    /**
     * 수동 트리거의 알맹이. 화면의 버튼이 이걸 부른다.
     *
     * <p>{@link ArticleWriter} 와 같은 조건이다 — 꺼진 설치에는 생성기 자체가 없고,
     * 그래서 {@code StoryController} 는 이 빈을 <b>{@link java.util.Optional} 로 받는다.</b>
     * 없으면 버튼을 안 그린다. 눌러야 "꺼져 있습니다" 를 알려주는 버튼은 버튼이 아니다.
     */
    @ConditionalOnProperty(prefix = "tfm.story", name = "enabled", havingValue = "true")
    @Bean
    public StoryGenerator storyGenerator(ArticleWriter writer, ArticleDao articles,
                                         StoryReferenceDao references, TfmProperties properties) {
        return new StoryGenerator(writer, articles, references, properties);
    }
}
