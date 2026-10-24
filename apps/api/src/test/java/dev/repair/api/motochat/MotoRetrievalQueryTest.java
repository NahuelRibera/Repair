package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MotoRetrievalQueryTest {

    @Test
    void joinsDistinctTermsWithOr() {
        assertThat(MotoRetrievalService.toOrTsQuery("Chain, chain & Slack?")).isEqualTo("chain | slack");
    }

    @Test
    void neverEmitsTsqueryOperators() {
        assertThat(MotoRetrievalService.toOrTsQuery("a' | !b:* (oil)")).isEqualTo("oil");
        assertThat(MotoRetrievalService.toOrTsQuery("x'; DROP -- oil")).isEqualTo("drop | oil");
    }

    @Test
    void emptyPunctuationOrNullGivesEmptyQuery() {
        assertThat(MotoRetrievalService.toOrTsQuery(null)).isEmpty();
        assertThat(MotoRetrievalService.toOrTsQuery("  ?!. ")).isEmpty();
    }

    @Test
    void capsTermCount() {
        String q = String.join(" ", java.util.Collections.nCopies(1, "aa ab ac ad ae af ag ah ai aj ak al am an ao ap aq ar as at au av aw ax ay az"));
        assertThat(MotoRetrievalService.toOrTsQuery(q).split(" \\| ")).hasSize(24);
    }
}
