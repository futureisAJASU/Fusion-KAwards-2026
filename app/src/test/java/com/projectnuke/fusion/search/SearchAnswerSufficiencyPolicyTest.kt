package com.projectnuke.fusion.search

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAnswerSufficiencyPolicyTest {
    private val today = LocalDate.of(2026, 11, 3)

    @Test
    fun latestPolicyRateWithUndatedHistoricalEvidenceIsInsufficient() {
        val query = "What is the latest policy-rate decision from Bank of Korea 최신"
        val intent = FusionWebSearch.detectIntent(query)
        val results = listOf(
            result(
                "South Korea Interest Rate - TRADING ECONOMICS",
                "tradingeconomics.com",
                "In South Korea interest rate decisions are taken by the Bank of Korea. The official rate was changed from overnight call rate on March 2008.",
            ),
            result(
                "FULL TEXT BOK statement on monetary policy decision in November",
                "koreatimes.co.kr",
                "The Bank of Korea held the key rate steady at 2.5 percent on Thursday, November 27, 2025.",
            ),
            result(
                "Bank of Korea Holds Policy Rate Steady at 2.5% Amid Housing and ...",
                "infomaconnect.com",
                "The Bank of Korea kept its benchmark interest rate unchanged at 2.5% during its monetary policy meeting on Thursday, November 27, 2025.",
            ),
            result(
                "Executive Summary for Monetary Policy Report (September 2025)",
                "bok.or.kr",
                "Monetary Policy Report at a Glance. Monetary Policy Decisions: Further Base Rate cut from 2.75% to 2.5%.",
            ),
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(query, intent, results, today)

        assertEquals(SearchRequestedFact.RATE, assessment.requestedFact)
        assertEquals(SearchEvidenceFreshnessStatus.STALE, assessment.freshness)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
        assertEquals(false, assessment.directFactEvidencePresent)
    }

    @Test
    fun completedArtemisSplashdownDateInUndatedOfficialSnippetIsSufficient() {
        val query = "According to NASA what date did Artemis II splash down"
        val evidence = result(
            "Artemis II Splashdown and Recovery - NASA",
            "nasa.gov",
            "NASA astronauts splashed down at 8:07 p.m. EDT on April 10, 2026, off the coast of San Diego.",
        ).copy(url = "https://www.nasa.gov/gallery/artemis-ii-splashdown-and-recovery/")

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = FusionWebSearch.detectIntent(query),
            results = listOf(evidence),
            today = LocalDate.of(2026, 11, 4),
        )

        assertEquals(SearchIntent.GENERAL, FusionWebSearch.detectIntent(query))
        assertEquals(SearchRequestedFact.DATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, assessment.status)
        assertTrue(assessment.directFactEvidencePresent)
    }

    @Test
    fun conflictingUndatedRatesInPhysicalModelEvidenceAreInsufficient() {
        val query = "What is the latest policy-rate decision from Bank of Korea 최신"
        val intent = FusionWebSearch.detectIntent(query)
        val results = listOf(
            result(
                "★Monetary Policy Decision & Opening Remarks to the Press Conference ...",
                "bok.or.kr",
                "Monetary Policy Decision The Monetary Policy Board of the Bank of Korea decided today to leave the Base Rate unchanged at 2.50% for the intermeeting period.",
            ).copy(url = "https://www.bok.or.kr/eng/bbs/E0000634/view.do?nttId=10093244&menuNo=400423"),
            result(
                "FULL TEXT BOK statement on monetary policy decision in November",
                "koreatimes.co.kr",
                "The Bank of Korea held the key rate steady at 2.5 percent on Thursday, November 27, 2025.",
            ).copy(url = "https://www.koreatimes.co.kr/economy/20251127/full-text-bok-statement-on-monetary-policy-decision-in-november"),
            result(
                "Bank of Korea Holds Policy Rate Steady at 2.5% Amid Housing and ...",
                "informaconnect.com",
                "The Bank of Korea kept its benchmark interest rate unchanged at 2.5% during its monetary policy meeting on Thursday, November 27, 2025.",
            ).copy(url = "https://informaconnect.com/igm/article/bank-of-korea-holds-policy-rate-steady-at-25-amid-housing-and-currency-concerns/"),
            result(
                "Bank of Korea Raises Base Rate to 3.00% - Seoul Economic Daily",
                "en.sedaily.com",
                "The Bank of Korea raised its base rate by a quarter percentage point to 3.00% on the 27th, following a monetary policy board meeting.",
            ).copy(url = "https://en.sedaily.com/finance/bank-of-korea-raises-base-rate-25-basis-points-to-300"),
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(query, intent, results, today)

        assertEquals(SearchIntent.NEWS, intent)
        assertEquals(SearchRequestedFact.RATE, assessment.requestedFact)
        assertEquals(true, assessment.directFactEvidencePresent)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
        assertEquals(true, assessment.reasonCodes.contains("conflicting_requested_values"))
    }

    @Test
    fun currentNewsUsesPublicationDateEmbeddedInResultUrl() {
        val query = "What is the latest policy rate of the Bank of Korea?"
        val result = result(
            "Bank of Korea statement",
            "koreatimes.co.kr",
            "The Bank of Korea held the key rate steady at 2.5 percent.",
        ).copy(url = "https://www.koreatimes.co.kr/economy/20251127/bok-policy-rate")

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(result),
            today = today,
        )

        assertEquals(SearchEvidenceFreshnessStatus.STALE, assessment.freshness)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun latestNewsWithSevenDayOldEvidenceIsInsufficient() {
        val query = "What is the latest news about Samsung Electronics"
        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = SearchIntent.NEWS,
            results = listOf(
                result(
                    "Samsung Electronics Announces Third Quarter 2026 Results",
                    "samsung.com",
                    "Samsung Electronics announced its third-quarter results.",
                    "Thu, 29 Oct 2026 01:08:00 GMT",
                ),
            ),
            today = LocalDate.of(2026, 11, 5),
        )

        assertEquals(SearchEvidenceFreshnessStatus.STALE, assessment.freshness)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun prelaunchTrainingSnippetDoesNotSatisfyCurrentMissionStatus() {
        val query = "What is the current status of NASA Artemis II?"
        val results = listOf(
            result(
                "Artemis II - Wikipedia",
                "en.wikipedia.org",
                "Artemis II (April 1-11, 2026) flew a crew of four on a flyby of the Moon. It was the first crewed flight of the NASA-led Artemis program.",
            ),
            result(
                "NASA's Artemis II Moon Mission Research Continues on Earth",
                "science.nasa.gov",
                "Artemis II astronaut Victor Glover walks on a treadmill while in a space suit harnessed to NASA's Active Response Gravity Offload System. Artemis II astronauts completed this and other suited tasks before their mission launched.",
            ),
            result(
                "Artemis II Live Mission Tracker — NASA Moon Mission 2026",
                "artemistracker.io",
                "Live tracker for NASA's Artemis II. Launched April 1, 2026 from Kennedy Space Center.",
            ),
            result(
                "Track NASA's Artemis II Mission in Real Time - NASA",
                "nasa.gov",
                "Artemis II is a key step in NASA's path toward a long-term presence at the Moon and future lunar surface exploration.",
            ),
            result(
                "Artemis II Mission Tracker",
                "artemis-ii.com",
                "Track NASA's Artemis II lunar mission in real time with a mission timeline and crew information.",
            ),
            result(
                "Artemis II Mission Tracker | Live 3D Real-Time Visualization",
                "artemistracker.live",
                "Track Artemis II crew in real time as they fly around the Moon. Follow the crew and spacecraft position.",
            ),
            result(
                "Artemis II: NASA's First Crewed Lunar Flyby in 50 Years - NASA",
                "nasa.gov",
                "Meet the Artemis II crew and learn how NASA's 10-day lunar flyby mission will test deep space systems.",
            ),
            result(
                "Artemis II Live Mission Tracker — Real-Time NASA Telemetry",
                "artemistracker.online",
                "Track NASA's Artemis II Orion spacecraft live, including crew information and mission timeline.",
            ),
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(results[1]),
            today = LocalDate.of(2026, 11, 4),
        )

        assertEquals(SearchRequestedFact.CURRENT_STATUS, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
        val selected = SearchAnswerSufficiencyPolicy.selectAnswerEvidence(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = results,
            today = LocalDate.of(2026, 11, 4),
        )
        assertTrue(selected.any { it.url == results[0].url })
        assertFalse(selected.any { it.url == results[1].url })
    }

    @Test
    fun latestOfficialMissionStatusDoesNotAcceptGenericOrPrelaunchPagesAsCurrentStatus() {
        val query = "What is the latest official status of NASA Artemis II?"
        val intent = FusionWebSearch.detectIntent(query)
        val results = listOf(
            result(
                "NASA's Artemis II Moon Mission Research Continues on Earth",
                "science.nasa.gov",
                "Artemis II astronaut Victor Glover walks on a treadmill while in a space suit harnessed to NASA's Active Response Gravity Offload System at NASA's Johnson Space Center. Glover is simulating a walk on a planetary surface while in a suit that has been offloaded to lunar gravity. Artemis II astronauts completed this and other suited tasks before their mission launched and within a few days of ...",
            ).copy(
                url = "https://science.nasa.gov/missions/artemis/artemis-2/nasas-artemis-ii-moon-mission-research-continues-on-earth/",
                rank = 1,
            ),
            result(
                "Artemis II News and Updates - NASA",
                "nasa.gov",
                "Stay up-to-date with the latest Artemis II content from NASA as we explore the Moon for scientific discovery and to prepare for human missions to Mars.",
            ).copy(url = "https://www.nasa.gov/artemis-ii-news-and-updates/", rank = 2),
            result(
                "Artemis II: NASA's First Crewed Lunar Flyby in 50 Years - NASA",
                "nasa.gov",
                "Meet the Artemis II crew and learn how NASA's 10-day lunar flyby mission will test deep space systems and pave the way for future Moon landings.",
            ).copy(url = "https://www.nasa.gov/mission/artemis-ii/", rank = 3),
            result(
                "NASA scraps its 2027 moon landing, adds two missions in 2028",
                "sciencenews.org",
                "NASA's Artemis II Space Launch System rocket rolled back into a building at Kennedy Space Center in Florida for repairs on February 25. The rocket is supposed to launch astronauts to the moon ...",
            ).copy(url = "https://www.sciencenews.org/article/nasa-moon-landing-artemis-ii", rank = 4),
        )

        assertEquals(SearchIntent.NEWS, intent)
        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = intent,
            results = results,
            today = LocalDate.of(2026, 11, 4),
        )

        assertEquals(SearchRequestedFact.CURRENT_STATUS, assessment.requestedFact)
        assertEquals(SearchOfficialAuthorityStatus.PRESENT, assessment.officialAuthority)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
        assertEquals(false, assessment.directFactEvidencePresent)
    }

    @Test
    fun undatedOfficialInTransitStatusIsAdmittedForEuropaClipper() {
        val query = "What is the current status of NASA Europa Clipper"
        val nasaEvidence = result(
            "Europa Clipper - NASA Science",
            "science.nasa.gov",
            "The spacecraft is traveling 1.8 billion miles (2.9 billion kilometers) to reach Jupiter in April 2030.",
        ).copy(
            url = "https://science.nasa.gov/mission/europa-clipper/",
            rank = 5,
        )
        val adjacentMarsEvidence = result(
            "NASA Mars Exploration Program",
            "nasa.gov",
            "NASA's Mars spacecraft is traveling through deep space toward the red planet.",
        ).copy(url = "https://www.nasa.gov/mars-exploration/", rank = 6)
        val results = listOf(
            result(
                "Europa Clipper - Space Launches Live",
                "spacelaunchlive.com",
                "Europa Clipper deep space probe by NASA / JPL. Target: Europa. Status: Cruise.",
            ).copy(url = "https://spacelaunchlive.com/probes/europa-clipper/", rank = 1),
            result("Europa Clipper", "en.wikipedia.org", "Europa Clipper is a NASA mission to study Europa.")
                .copy(url = "https://en.wikipedia.org/wiki/Europa_Clipper", rank = 2),
            result("Where Is Europa Clipper Now?", "orbitalradar.com", "Europa Clipper is traveling to Jupiter, due in April 2030.")
                .copy(url = "https://orbitalradar.com/deep-space/europa-clipper", rank = 3),
            result("Europa Clipper Homepage - NASA Science", "science.nasa.gov", "NASA's Europa Clipper has embarked on its long voyage to Jupiter.")
                .copy(url = "https://science.nasa.gov/europa-clipper-homepage/", rank = 4),
            nasaEvidence,
            adjacentMarsEvidence,
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = FusionWebSearch.detectIntent(query),
            results = results,
            today = LocalDate.of(2026, 11, 5),
        )
        val admitted = SearchAnswerSufficiencyPolicy.selectAnswerEvidence(
            query = query,
            intent = FusionWebSearch.detectIntent(query),
            results = results,
            today = LocalDate.of(2026, 11, 5),
        )

        assertEquals(SearchRequestedFact.CURRENT_STATUS, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, assessment.status)
        assertTrue(assessment.directFactEvidencePresent)
        assertTrue(admitted.any { it.url == nasaEvidence.url })
        assertFalse(admitted.any { it.url == adjacentMarsEvidence.url })
    }

    @Test
    fun latestOfficialNamedMissionCompletionCanSupportCurrentStatus() {
        val query = "What is the latest official status of NASA Artemis II?"
        val intent = FusionWebSearch.detectIntent(query)
        val result = result(
            "Artemis II mission update - NASA",
            "nasa.gov",
            "NASA's Artemis II mission sent four astronauts around the Moon and brought them safely back to Earth on April 10, 2026.",
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = intent,
            results = listOf(result),
            today = LocalDate.of(2026, 11, 4),
        )

        assertEquals(SearchIntent.NEWS, intent)
        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, assessment.status)
    }

    @Test
    fun namedAuthorityCurrentRateAdmitsOfficialRecordAheadOfDatedHikeStories() {
        val query = "What is the current Bank of Korea base rate and did it change?"
        val datedHikeStories = listOf(
            result(
                "Bank of Korea Raises Base Rate to 3.00% - Seoul Economic Daily",
                "en.sedaily.com",
                "The Bank of Korea raised its base rate to 3.00% in a back-to-back hike.",
            ).copy(url = "https://en.sedaily.com/finance/2026/08/27/bank-of-korea-raises-base-rate", rank = 1),
            result(
                "Bank of Korea Raises Rate to 3% in First Consecutive Post-Pandemic Hike",
                "chosun.com",
                "The Bank of Korea raised its base interest rate to 3% after a Monetary Policy Board meeting.",
            ).copy(url = "https://www.chosun.com/english/market-money-en/2026/08/27/rate-hike", rank = 2),
            result(
                "Bank of Korea Hikes Rate to 3% Again",
                "political.org",
                "The Bank of Korea delivered its second consecutive rate hike, raising its policy rate to 3.00%.",
            ).copy(url = "https://political.org/2026/08/26/rate-hike", rank = 4),
        )
        val official = result(
            "Bank of Korea - 한국은행",
            "bok.or.kr",
            "The Monetary Policy Board (MPB) of the Bank of Korea decided to leave the Base Rate unchanged at 3.00% for the intermeeting period.",
        ).copy(url = "https://www.bok.or.kr/eng/main/main.do", rank = 6)

        val selected = SearchAnswerSufficiencyPolicy.selectAnswerEvidence(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = datedHikeStories + official,
            today = LocalDate.of(2026, 11, 4),
        )

        assertEquals(listOf(official.url), selected.map { it.url })
    }

    @Test
    fun expiredLaunchScheduleDoesNotSatisfyCurrentMissionStatus() {
        val query = "What is the current status of NASA Artemis II?"
        val result = result(
            "Artemis II launch schedule - NASA",
            "nasa.gov",
            "NASA scheduled Artemis II to launch on April 1, 2026.",
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(result),
            today = LocalDate.of(2026, 11, 4),
        )

        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun undatedLaunchTrackerDoesNotEstablishCurrentMissionStatus() {
        val query = "What is the current status of NASA Artemis II?"
        val result = result(
            "Artemis II Live Mission Tracker",
            "artemistracker.io",
            "Live tracker for NASA's Artemis II. Launched April 1, 2026 from Kennedy Space Center.",
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(result),
            today = LocalDate.of(2026, 11, 4),
        )

        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    private data class Case(
        val name: String,
        val query: String,
        val intent: SearchIntent,
        val results: List<FusionSearchResult>,
        val expected: SearchAnswerSufficiencyStatus,
        val expectedAuthority: SearchOfficialAuthorityStatus = SearchOfficialAuthorityStatus.NOT_REQUESTED,
        val expectedFreshness: SearchEvidenceFreshnessStatus? = null,
    )

    @Test
    fun deterministicCurrentFactAndAuthorityCorpus() {
        val cases = listOf(
            Case(
                "completed named event with concrete date",
                "What is the current target date for NASA Artemis II?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Artemis II", "nasa.gov", "NASA's Artemis II lunar mission launched on April 1, 2026.", "2026-04-01")),
                SearchAnswerSufficiencyStatus.SUFFICIENT,
                expectedFreshness = SearchEvidenceFreshnessStatus.FRESH,
            ),
            Case(
                "adjacent NASA topic does not satisfy event identity",
                "What is the current target date for NASA Artemis II?",
                SearchIntent.CURRENT_INFO,
                listOf(result("NASA Mars mission schedule", "nasa.gov", "The Mars mission is planned for April 1, 2028.", "2026-11-01")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "matching event without requested date is insufficient",
                "What is the current target date for NASA Artemis II?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Artemis II mission", "nasa.gov", "NASA's crewed lunar flyby will carry four astronauts.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "past target without completion evidence is stale",
                "What is the current target date for NASA Artemis II?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Artemis II target", "nasa.gov", "NASA currently targets April 1, 2026 for Artemis II.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "official policy rate with value",
                "What is the current official policy rate of the Bank of Korea?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Bank of Korea Base Rate", "bok.or.kr", "The Bank of Korea left its Base Rate unchanged at 3.00%.", null)),
                SearchAnswerSufficiencyStatus.SUFFICIENT,
                SearchOfficialAuthorityStatus.PRESENT,
                SearchEvidenceFreshnessStatus.UNDATED,
            ),
            Case(
                "official rate page without requested value",
                "What is the current official policy rate of the Bank of Korea?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Bank of Korea Base Rate", "bok.or.kr", "The Base Rate is used in transactions between the Bank and financial institutions.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
                SearchOfficialAuthorityStatus.PRESENT,
            ),
            Case(
                "unrelated percentage is not a rate answer",
                "What is the current policy rate of the Bank of Korea?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Bank of Korea inflation report", "example.test", "Inflation reached 3.00% in the latest report.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "rate value must be near a policy-rate label",
                "What is the current policy rate of the Bank of Korea?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Bank of Korea policy report", "bok.or.kr", "The policy rate framework is under review. Unemployment was 4.2%, while inflation reached 3.00%.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "conflicting current rates without requested authority abstain",
                "What is the current policy rate of the Bank of Korea?",
                SearchIntent.CURRENT_INFO,
                listOf(
                    result("Bank of Korea Base Rate", "rates.example", "The Bank of Korea base rate is 3.00%."),
                    result("Bank of Korea policy rate", "markets.example", "The Bank of Korea policy rate is 2.75%."),
                ),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "official current rate outranks and resolves stale unofficial value",
                "What is the current official policy rate of the Bank of Korea?",
                SearchIntent.CURRENT_INFO,
                listOf(
                    result("Bank of Korea Base Rate", "bok.or.kr", "The Bank of Korea left the Base Rate at 3.00%.", null),
                    result("Bank rate report", "markets.example", "The policy rate was 2.50% on November 27, 2025.", "2025-11-27"),
                ),
                SearchAnswerSufficiencyStatus.SUFFICIENT,
                SearchOfficialAuthorityStatus.PRESENT,
                SearchEvidenceFreshnessStatus.UNDATED,
            ),
            Case(
                "official NASA request cannot use a news-domain date",
                "What is the official current launch date for NASA Artemis II?",
                SearchIntent.CURRENT_INFO,
                listOf(result("NASA Artemis II launch", "abcnews.com", "NASA launched Artemis II on April 1, 2026.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
                SearchOfficialAuthorityStatus.MISSING,
            ),
            Case(
                "official NASA domain with event date is sufficient",
                "What is the official current launch date for NASA Artemis II?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Artemis II launch", "nasa.gov", "NASA launched Artemis II on April 1, 2026.", "2026-04-01")),
                SearchAnswerSufficiencyStatus.SUFFICIENT,
                SearchOfficialAuthorityStatus.PRESENT,
                SearchEvidenceFreshnessStatus.FRESH,
            ),
            Case(
                "current official officeholder listing supports the requested role",
                "Use an official source to identify the current Prime Minister of Japan",
                SearchIntent.CURRENT_INFO,
                listOf(
                    result(
                        "List of Ministers | Cabinet | Prime Minister's Office of Japan",
                        "japan.kantei.go.jp",
                        "As of November 2, 2026. Prime Minister TAKAICHI Sanae. Inaugurated on September 17, 2026.",
                        "2026-11-02",
                    ).copy(url = "https://japan.kantei.go.jp/105/meibo/daijin/index.html"),
                ),
                SearchAnswerSufficiencyStatus.SUFFICIENT,
                SearchOfficialAuthorityStatus.PRESENT,
                SearchEvidenceFreshnessStatus.FRESH,
            ),
            Case(
                "official Seoul population includes requested value",
                "What is the latest official population estimate for Seoul?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Seoul population", "english.seoul.go.kr", "The Seoul population is 9,602,826 residents in 2026.")),
                SearchAnswerSufficiencyStatus.SUFFICIENT,
                SearchOfficialAuthorityStatus.PRESENT,
                SearchEvidenceFreshnessStatus.UNDATED,
            ),
            Case(
                "official Seoul landing page without a population value is insufficient",
                "What is the latest official population estimate for Seoul?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Population of Seoul | Seoul Metropolitan Government", "english.seoul.go.kr", "The official website provides information about Seoul policies, culture, and services.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
                SearchOfficialAuthorityStatus.PRESENT,
            ),
            Case(
                "population year is not mistaken for the population count",
                "What is the latest population of Seoul?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Seoul population", "seoul.go.kr", "The 2026 population report describes recent city trends but gives no resident count.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "United Nations member-state count is directly supported",
                "How many member states does the United Nations currently have?",
                SearchIntent.CURRENT_INFO,
                listOf(result("UN member states", "un.org", "The United Nations currently has 193 member states.")),
                SearchAnswerSufficiencyStatus.SUFFICIENT,
            ),
            Case(
                "Security Council member count cannot answer UN member states",
                "How many member states does the United Nations currently have?",
                SearchIntent.CURRENT_INFO,
                listOf(result("United Nations Security Council", "un.org", "The United Nations Security Council has 15 members.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "conflicting member-state values are insufficient",
                "How many member states does the United Nations currently have?",
                SearchIntent.CURRENT_INFO,
                listOf(
                    result("UN member states", "un.org", "The United Nations has 193 member states."),
                    result("UN membership", "example.net", "The United Nations has 192 member states.", "2026-11-01"),
                ),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
            ),
            Case(
                "official status is not inferred from title text",
                "What is the official current launch date for NASA Artemis II?",
                SearchIntent.CURRENT_INFO,
                listOf(result("NASA Official Artemis II launch", "space-news.example", "Artemis II launched on April 1, 2026.")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
                SearchOfficialAuthorityStatus.MISSING,
            ),
            Case(
                "undated authoritative current rate is allowed",
                "What is the current policy rate of the Bank of Korea?",
                SearchIntent.CURRENT_INFO,
                listOf(result("Bank of Korea Base Rate", "bok.or.kr", "The Bank of Korea Base Rate is 3.00%.", null)),
                SearchAnswerSufficiencyStatus.SUFFICIENT,
                SearchOfficialAuthorityStatus.NOT_REQUESTED,
                SearchEvidenceFreshnessStatus.UNDATED,
            ),
            Case(
                "fresh dated news remains current evidence",
                "latest semiconductor news",
                SearchIntent.NEWS,
                listOf(result("Semiconductor news", "news.example", "A new semiconductor plant opened today.", "2026-11-01")),
                SearchAnswerSufficiencyStatus.NOT_REQUIRED,
                expectedFreshness = SearchEvidenceFreshnessStatus.FRESH,
            ),
            Case(
                "dated topical news cannot support a latest-news answer",
                "What is the latest news about Samsung Electronics",
                SearchIntent.NEWS,
                listOf(
                    result(
                        "MOIN Secures Multiple Payment Patents from Samsung Electronics",
                        "venturesquare.net",
                        "MOIN secured multiple payment patents from Samsung Electronics.",
                        "Fri, 23 Oct 2026 13:44:39 GMT",
                    ),
                ),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
                expectedFreshness = SearchEvidenceFreshnessStatus.STALE,
            ),
            Case(
                "dated topical news remains eligible without a latest-news request",
                "Samsung Electronics news",
                SearchIntent.NEWS,
                listOf(
                    result(
                        "MOIN Secures Multiple Payment Patents from Samsung Electronics",
                        "venturesquare.net",
                        "MOIN secured multiple payment patents from Samsung Electronics.",
                        "Fri, 23 Oct 2026 13:44:39 GMT",
                    ),
                ),
                SearchAnswerSufficiencyStatus.NOT_REQUIRED,
                expectedFreshness = SearchEvidenceFreshnessStatus.FRESH,
            ),
            Case(
                "old dated news is not treated as latest",
                "latest semiconductor news",
                SearchIntent.NEWS,
                listOf(result("Semiconductor news", "news.example", "A new semiconductor plant opened.", "2025-01-01")),
                SearchAnswerSufficiencyStatus.INSUFFICIENT,
                expectedFreshness = SearchEvidenceFreshnessStatus.STALE,
            ),
        )

        assertEquals("corpus size", 25, cases.size)
        cases.forEach { case ->
            val actual = SearchAnswerSufficiencyPolicy.evaluate(case.query, case.intent, case.results, today)
            assertEquals(case.name, case.expected, actual.status)
            assertEquals(case.name, case.expectedAuthority, actual.officialAuthority)
            case.expectedFreshness?.let { assertEquals(case.name, it, actual.freshness) }
        }
    }

    @Test
    fun agentQueryPlanningRetainsNamedMissionIdentity() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput("What is the current target date for NASA Artemis II"),
        )

        assertEquals(SearchIntent.CURRENT_INFO, plan.intent)
        assertEquals(true, plan.primaryQuery.contains("Artemis II", ignoreCase = true))
        assertEquals(true, plan.primaryQuery.contains("NASA", ignoreCase = true))
    }

    @Test
    fun broadNewsIntentRemainsBroadAndNamedTopicRemainsScoped() {
        assertEquals(SearchIntent.NEWS, FusionWebSearch.detectIntent("뉴스 알려줘"))
        assertEquals(SearchIntent.NEWS, FusionWebSearch.detectIntent("삼성전자 뉴스 알려줘"))
        assertEquals(true, FusionWebSearch.isBroadNewsQueryForTest("뉴스 알려줘"))
        assertEquals(false, FusionWebSearch.isBroadNewsQueryForTest("삼성전자 뉴스 알려줘"))
    }

    @Test
    fun newsIntentRequiresCurrentStatusEvidenceOnlyForAnExplicitNamedStatusRequest() {
        listOf("뉴스 알려줘", "삼성전자 뉴스 알려줘").forEach { query ->
            val intent = FusionWebSearch.detectIntent(query)
            assertEquals(SearchIntent.NEWS, intent)
            assertEquals(
                SearchRequestedFact.NONE,
                SearchAnswerSufficiencyPolicy.evaluate(query, intent, emptyList(), today).requestedFact,
            )
        }

        val query = "What is the latest status of NASA Artemis II?"
        val intent = FusionWebSearch.detectIntent(query)
        assertEquals(SearchIntent.NEWS, intent)
        assertEquals(
            SearchRequestedFact.CURRENT_STATUS,
            SearchAnswerSufficiencyPolicy.evaluate(query, intent, emptyList(), today).requestedFact,
        )
    }

    @Test
    fun officeholderListingWithoutNamedHolderCannotSatisfyCurrentStatusRequest() {
        val query = "Who is the current Prime Minister of Japan?"
        val evidence = result(
            title = "Prime Minister of Japan announces new economic policy",
            host = "example.com",
            snippet = "The office of the Prime Minister of Japan said the cabinet was inaugurated in 2024 and will announce details.",
            publishedAt = "2026-11-02",
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = FusionWebSearch.detectIntent(query),
            results = listOf(evidence),
            today = today,
        )

        assertEquals(SearchRequestedFact.CURRENT_STATUS, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    @Test
    fun negatedPolicyRateValueCannotSatisfyRequestedRate() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val evidence = result(
            title = "Bank of Korea policy rate statement",
            host = "example.com",
            snippet = "The Bank of Korea did not cut its policy rate; the rate is not 3.00%.",
            publishedAt = "2026-11-02",
        )

        val assessment = SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = FusionWebSearch.detectIntent(query),
            results = listOf(evidence),
            today = today,
        )

        assertEquals(SearchRequestedFact.RATE, assessment.requestedFact)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, assessment.status)
    }

    private fun result(
        title: String,
        host: String,
        snippet: String,
        publishedAt: String? = null,
    ) = FusionSearchResult(
        title = title,
        source = host,
        url = "https://$host/article",
        snippet = snippet,
        publishedAt = publishedAt,
        queryUsed = "test query",
    )
}
