package com.bot.service;

import com.bot.model.FetchResult;
import com.bot.model.SystemAlert;
import com.bot.model.DailyWord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class WordService {

    private final RestTemplate restTemplate;
    private final Random random = new Random();

    // Fallback word list
    private static final List<DailyWord> FALLBACK = List.of(
            DailyWord.builder().word("resilient").pronunciation("/rɪˈzɪliənt/")
                    .definition("有弹性的；能迅速恢复的").example("She's a resilient girl.").build(),
            DailyWord.builder().word("pragmatic").pronunciation("/præɡˈmætɪk/")
                    .definition("务实的；实用主义的").example("We need a pragmatic approach.").build(),
            DailyWord.builder().word("ambiguous").pronunciation("/æmˈbɪɡjuəs/")
                    .definition("模棱两可的；不明确的").example("The statement was ambiguous.").build(),
            DailyWord.builder().word("inevitable").pronunciation("/ɪnˈevɪtəbl/")
                    .definition("不可避免的").example("Change is inevitable.").build(),
            DailyWord.builder().word("profound").pronunciation("/prəˈfaʊnd/")
                    .definition("深刻的；意义深远的").example("It had a profound impact.").build()
    );

    public DailyWord fetch() {
                return fetchWithAlert().data();
        }

        public FetchResult<DailyWord> fetchWithAlert() {
        try {
            // Try free API: https://v1.hitokoto.cn/ but for English word
            // Use a simple English word API
            String url = "https://random-word-api.herokuapp.com/word?number=1";
            var resp = restTemplate.getForObject(url, List.class);
            if (resp != null && !resp.isEmpty()) {
                String word = (String) resp.get(0);
                                return FetchResult.of(DailyWord.builder()
                        .word(word)
                        .pronunciation("")
                        .definition("（每日一词）")
                        .example("Try using '" + word + "' in a sentence today!")
                                                .build());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch word: {}", e.getMessage());
                        return FetchResult.of(
                                        fallbackWord(),
                                        List.of(SystemAlert.warn("WordService", "WORD_API_FALLBACK",
                                                        e.getClass().getSimpleName() + ": " + e.getMessage())));
        }
                return FetchResult.of(
                                fallbackWord(),
                                List.of(SystemAlert.warn("WordService", "WORD_API_EMPTY_RESPONSE",
                                                "每日单词接口返回空结果，已使用本地词库")));
        }

        private DailyWord fallbackWord() {
                return FALLBACK.get(random.nextInt(FALLBACK.size()));
    }
}
