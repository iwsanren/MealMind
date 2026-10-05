package com.mealmind.service.recommend;

import com.mealmind.model.SlotBundle;
import com.mealmind.service.slot.SlotOptionService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleSlotExtractorTest {

    private RuleSlotExtractor extractor() {
        SlotOptionService options = mock(SlotOptionService.class);
        Map<String, List<String>> vocabulary = new LinkedHashMap<>();
        vocabulary.put("mealTime", List.of("Breakfast", "Lunch", "Dinner"));
        vocabulary.put("mood", List.of("Tired", "Want to Treat Myself"));
        vocabulary.put("scene", List.of("Work", "Home"));
        vocabulary.put("healthGoal", List.of("High Protein", "Light", "Fat Loss"));
        vocabulary.put("cuisine", List.of("Western"));
        vocabulary.put("taste", List.of("Savory"));
        vocabulary.put("convenience", List.of("Quick"));
        when(options.findAllOptions()).thenReturn(vocabulary);
        return new RuleSlotExtractor(options);
    }

    @Test
    void findsTagsFromTheVocabularyCaseInsensitively() {
        SlotBundle slots = extractor().extract("I'm tired, want a LIGHT dinner at home, something quick");

        assertThat(slots.mealTime()).containsExactly("Dinner");
        assertThat(slots.mood()).containsExactly("Tired");
        assertThat(slots.healthGoal()).containsExactly("Light");
        assertThat(slots.scene()).containsExactly("Home");
        assertThat(slots.convenience()).containsExactly("Quick");
        assertThat(slots.cuisine()).isEmpty();
    }

    @Test
    void matchesWholeWordsAndPhrasesOnly() {
        assertThat(extractor().extract("highlights of the workshop").healthGoal()).isEmpty();   // "light" inside "highlights"
        assertThat(extractor().extract("homework").scene()).isEmpty();                         // "home" / "work" inside a word
        assertThat(extractor().extract("high protein lunch").healthGoal()).containsExactly("High Protein");
    }

    @Test
    void noMatchOrNoMessageGivesAnEmptyBundle() {
        assertThat(extractor().extract("hello there").isEmpty()).isTrue();
        assertThat(extractor().extract(null).isEmpty()).isTrue();
    }
}
