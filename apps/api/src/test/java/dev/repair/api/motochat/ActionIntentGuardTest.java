package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The deterministic false-positive safety net, tested in isolation
 * against the exact QA examples from the task spec (section 2/3).
 */
class ActionIntentGuardTest {

    @Test
    void blocksEveryReportedFalsePositiveExample() {
        assertThat(ActionIntentGuard.blocksAction("I don't remember when the last oil change was.")).isTrue();
        assertThat(ActionIntentGuard.blocksAction("I should probably change the oil soon.")).isTrue();
        assertThat(ActionIntentGuard.blocksAction("I might lubricate the chain tomorrow.")).isTrue();
        assertThat(ActionIntentGuard.blocksAction("I think the previous owner changed the oil at 12,000 km, but I'm not sure.")).isTrue();
        assertThat(ActionIntentGuard.blocksAction("If I were at 25,000 km, what maintenance would be due?")).isTrue();
        assertThat(ActionIntentGuard.blocksAction("What would happen if I changed the oil at 20,000 km?")).isTrue();
        assertThat(ActionIntentGuard.blocksAction("Maybe it was changed around 15,000.")).isTrue();
    }

    @Test
    void neverBlocksAnyReportedTruePositiveExample() {
        assertThat(ActionIntentGuard.blocksAction("I changed the oil myself yesterday at 18,450 km.")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("I changed the oil at 19,000 km.")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("I replaced the oil filter too.")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("I lubricated the chain today.")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("I changed the brake fluid last April.")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("I installed a new battery at 28,000 km.")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("last oil change was at 14000")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("I'm at 18,500 km.")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("My current mileage is 23,400 km.")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("The bike has 12,850 km now.")).isFalse();
    }

    @Test
    void blankOrNullTextNeverBlocks() {
        assertThat(ActionIntentGuard.blocksAction(null)).isFalse();
        assertThat(ActionIntentGuard.blocksAction("")).isFalse();
        assertThat(ActionIntentGuard.blocksAction("   ")).isFalse();
    }
}
