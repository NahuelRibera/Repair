package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatTitleGeneratorTest {

    @Test
    void recognizesCommonTopicsRegardlessOfPunctuation() {
        assertThat(ChatTitleGenerator.generate("When should I change the oil?")).isEqualTo("Oil change");
        assertThat(ChatTitleGenerator.generate("How often should I lubricate the chain?")).isEqualTo("Chain maintenance");
        assertThat(ChatTitleGenerator.generate("I feel the bike is hotter than usual")).isEqualTo("Cooling issue");
        assertThat(ChatTitleGenerator.generate("What tire pressure should I use?")).isEqualTo("Tire pressure");
        assertThat(ChatTitleGenerator.generate("My bike won't start")).isEqualTo("Won't start");
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
