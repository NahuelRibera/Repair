package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChatTitleGeneratorTest {

    @Test
    void recognizesCommonTopicsRegardlessOfPunctuation() {
        assertThat(ChatTitleGenerator.generate("When should I change the oil?")).isEqualTo("Oil change");
        assertThat(ChatTitleGenerator.generate("How often should I lubricate the chain?")).isEqualTo("Chain maintenance");
        assertThat(ChatTitleGenerator.generate("I feel the bike is hotter than usual")).isEqualTo("Cooling issue");
        assertThat(ChatTitleGenerator.generate("What tire pressure should I use?")).isEqualTo("Tire pressure");
        assertThat(ChatTitleGenerator.generate("My bike won't start")).isEqualTo("Won't start");
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "How do I replace the oil filter?|Oil filter",
            "When should I change the oil?|Oil change",
            "Which brake fluid should I use?|Brake fluid",
            "My brake feels spongy|Brakes",
            "What tire pressure should I use?|Tire pressure",
            "My front tire looks worn|Tires",
            "What tyre pressure should I use?|Tire pressure",
            "My rear tyre looks worn|Tires"
    })
    void moreSpecificKeywordWinsOverGenericOne(String message, String expectedTitle) {
        assertThat(ChatTitleGenerator.generate(message)).isEqualTo(expectedTitle);
    }

    @Test
    void fallsBackToATrimmedExcerptForUnrecognizedTopics() {
        String result = ChatTitleGenerator.generate("Something totally unrelated to any known keyword whatsoever here");
        assertThat(result).isNotNull();
        assertThat(result.length()).isLessThanOrEqualTo(44); // 42 + ellipsis allowance
    }

    @Test
    void blankMessageProducesNoTitle() {
        assertThat(ChatTitleGenerator.generate(null)).isNull();
        assertThat(ChatTitleGenerator.generate("   ")).isNull();
    }
}
