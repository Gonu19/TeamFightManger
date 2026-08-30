package com.teamfighter.tfm.story;

import com.teamfighter.tfm.story.dao.ArticleDao;
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
}
