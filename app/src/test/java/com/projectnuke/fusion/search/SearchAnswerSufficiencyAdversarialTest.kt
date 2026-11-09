package com.projectnuke.fusion.search

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchAnswerSufficiencyAdversarialTest {
    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun wrongCountryRateCannotSatisfyRequestedRate() {
        val assessment = assess(
            query = "What is the current policy rate of the Bank of Korea?",
            title = "Bank of Japan policy rate",
            snippet = "The Bank of Japan policy rate is 0.50%.",
        )

        assertEquals(SearchRequestedFact.RATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun nearIdenticalMissionSuffixCannotSatisfyEventIdentity() {
        val assessment = assess(
            query = "When did Artemis II launch?",
            title = "Artemis III launch update",
            snippet = "Artemis III launched on April 10, 2026.",
            publishedAt = "2026-11-02",
        )

        assertEquals(SearchRequestedFact.DATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun formerOfficeholderCannotSatisfyCurrentOfficeholderRequest() {
        val assessment = assess(
            query = "Who is the current Prime Minister of Japan?",
            title = "Former leader profile",
            snippet = "Former Prime Minister Sanae Takaichi was inaugurated in 2021.",
        )

        assertEquals(SearchRequestedFact.CURRENT_STATUS, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun futureInaugurationCannotEstablishCurrentOfficeholder() {
        val assessment = assess(
            query = "Who is the current Prime Minister of Japan?",
            title = "Prime Minister Takaichi Sanae",
            snippet = "Prime Minister Takaichi Sanae will be inaugurated on December 10, 2026.",
            publishedAt = "2026-11-10",
        )

        assertEquals(SearchRequestedFact.CURRENT_STATUS, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun comparativeRateCannotSatisfyExactCurrentRate() {
        val assessment = assess(
            query = "What is the current policy rate of the Bank of Korea?",
            title = "Bank of Korea policy rate",
            snippet = "The Bank of Korea policy rate is below 3.00%.",
        )

        assertEquals(SearchRequestedFact.RATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun forecastRateCannotSatisfyCurrentRate() {
        val assessment = assess(
            query = "What is the current policy rate of the Bank of Korea?",
            title = "Bank of Korea policy outlook",
            snippet = "The Bank of Korea may raise its policy rate to 3.00% next year.",
        )

        assertEquals(SearchRequestedFact.RATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun previousRateCannotSatisfyCurrentRate() {
        val assessment = assess(
            query = "What is the current policy rate of the Bank of Korea?",
            title = "Bank of Korea policy history",
            snippet = "The previous policy rate was 2.75% before the bank changed course.",
        )

        assertEquals(SearchRequestedFact.RATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun futureScheduledLaunchCannotAnswerWhenEventLaunched() {
        val assessment = assess(
            query = "When did Artemis II launch?",
            title = "Artemis II mission",
            snippet = "Artemis II is scheduled to launch on April 10, 2027.",
            publishedAt = "2026-11-02",
        )

        assertEquals(SearchRequestedFact.DATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun lowerBoundedPopulationCannotSatisfyExactPopulation() {
        val assessment = assess(
            query = "What is the current population of Seoul?",
            title = "Seoul population",
            snippet = "Seoul has at least 9 million residents.",
        )

        assertEquals(SearchRequestedFact.POPULATION, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun priceRangeCannotSatisfyExactPrice() {
        val assessment = assess(
            query = "What is the current price of the device?",
            title = "Device pricing",
            snippet = "The device price is between $199 and $299.",
        )

        assertEquals(SearchRequestedFact.PRICE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun lowerBoundedCountCannotSatisfyExactCount() {
        val assessment = assess(
            query = "How many countries are in the group?",
            title = "Group membership",
            snippet = "The group includes at least 10 countries.",
        )

        assertEquals(SearchRequestedFact.COUNT, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun wrongRelatedCompanyPriceCannotSatisfyRequestedEntity() {
        val assessment = assess(
            query = "What is the current price of Samsung SDI?",
            title = "Samsung Electronics price",
            snippet = "The Samsung Electronics price is $199.",
        )

        assertEquals(SearchRequestedFact.PRICE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun missingModelSuffixCannotSatisfyRequestedEntity() {
        val assessment = assess(
            query = "What is the current price of the Galaxy S26 Ultra?",
            title = "Galaxy S26 price",
            snippet = "The Galaxy S26 price is $999.",
        )

        assertEquals(SearchRequestedFact.PRICE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun priceIndexCannotSatisfyRequestedPrice() {
        val assessment = assess(
            query = "What is the current price of the Galaxy S26 Ultra?",
            title = "Galaxy S26 Ultra price index",
            snippet = "The Galaxy S26 Ultra price index rose 2.5% in the latest report.",
        )

        assertEquals(SearchRequestedFact.PRICE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun conflictingSplashdownDatesCannotRemainSufficient() {
        val query = "When did Artemis II splash down?"
        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = FusionWebSearch.detectIntent(query),
            results = listOf(
                result("Artemis II splashdown", "Artemis II splashed down on April 10, 2026."),
                result("Artemis II recovery", "Artemis II splashed down on April 12, 2026."),
            ),
            today = today,
        )

        assertEquals(SearchRequestedFact.DATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun populationDensityCannotSatisfyRequestedPopulation() {
        val assessment = assess(
            query = "What is the current population of Seoul?",
            title = "Seoul population density",
            snippet = "Seoul has a population density of 16,000 people per square kilometer.",
        )

        assertEquals(SearchRequestedFact.POPULATION, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun rateChangeInBasisPointsCannotSatisfyRequestedRate() {
        val assessment = assess(
            query = "What is the current policy rate of the Bank of Korea?",
            title = "Bank of Korea policy decision",
            snippet = "The Bank of Korea policy rate changed by 25 basis points at the latest meeting.",
        )

        assertEquals(SearchRequestedFact.RATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun publicationDateWithoutEventDateCannotAnswerWhenEventHappened() {
        val assessment = assess(
            query = "When did Artemis II splash down?",
            title = "Artemis II splashdown report",
            snippet = "Artemis II completed its mission successfully.",
            publishedAt = "2026-11-10",
        )

        assertEquals(SearchRequestedFact.DATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    private fun assess(
        query: String,
        title: String,
        snippet: String,
        publishedAt: String? = "2026-11-10",
    ): SearchAnswerSufficiencyAssessment = SearchAnswerSufficiencyPolicy.evaluate(
        query = query,
        intent = FusionWebSearch.detectIntent(query),
        results = listOf(result(title, snippet, publishedAt)),
        today = today,
    )

    private fun result(
        title: String,
        snippet: String,
        publishedAt: String? = "2026-11-10",
    ) = FusionSearchResult(
        title = title,
        source = "example.com",
        url = "https://example.com/article",
        snippet = snippet,
        publishedAt = publishedAt,
    )
}
