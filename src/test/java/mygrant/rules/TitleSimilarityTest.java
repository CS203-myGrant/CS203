package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The documented title fallback: Jaccard similarity of normalised titles, threshold 0.8. */
class TitleSimilarityTest {

    @Test
    void stopwordsAndTheStageSuffixAreIgnored() {
        assertThat(TitleSimilarity.words(RuleFixtures.TITLE)).hasSize(17).doesNotContain("a", "and", "of", "for");
        assertThat(TitleSimilarity.similarity(RuleFixtures.TITLE, RuleFixtures.TITLE + "; Final Rule")).isEqualTo(1.0);
        assertThat(TitleSimilarity.similarity(RuleFixtures.TITLE, RuleFixtures.TITLE + "; Delay of Effective Date"))
                .isEqualTo(1.0);
        // A suffix that is part of the rule's name stays.
        assertThat(TitleSimilarity.words("Visa Fees; Student Exchange")).contains("student", "exchange");
    }

    @Test
    void theThresholdAllowsOneReplacedWordButNotTwo() {
        String oneReplaced = RuleFixtures.TITLE.replace("Media", "Outlets");
        String twoReplaced = oneReplaced.replace("Academic", "Vocational");
        String fourAdded = RuleFixtures.TITLE + " Including Spouses Children Dependents";

        assertThat(TitleSimilarity.similarity(RuleFixtures.TITLE, oneReplaced)).isCloseTo(16.0 / 18, within(1e-9))
                .isGreaterThanOrEqualTo(TitleSimilarity.THRESHOLD);
        assertThat(TitleSimilarity.similarity(RuleFixtures.TITLE, fourAdded)).isCloseTo(17.0 / 21, within(1e-9))
                .isGreaterThanOrEqualTo(TitleSimilarity.THRESHOLD);
        assertThat(TitleSimilarity.similarity(RuleFixtures.TITLE, twoReplaced)).isCloseTo(15.0 / 19, within(1e-9))
                .isLessThan(TitleSimilarity.THRESHOLD);
    }

    @Test
    void newsHeadlinesDoNotReachTheThreshold() {
        assertThat(TitleSimilarity.similarity(RuleFixtures.TITLE,
                "Judge blocks rule ending open-ended stays for F-1 students")).isLessThan(0.2);
    }

    @Test
    void anEmptyTitleMatchesNothing() {
        assertThat(TitleSimilarity.similarity("", RuleFixtures.TITLE)).isZero();
        assertThat(TitleSimilarity.similarity("The and of", "of the and")).isZero();
    }
}
