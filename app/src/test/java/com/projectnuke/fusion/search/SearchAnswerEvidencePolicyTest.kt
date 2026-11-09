package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.SearchStrategy
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAnswerEvidencePolicyTest {
    @Test
    fun latestSamsungNewsDoesNotAdmitStaleMarchStoryAlongsideFreshRecentResults() {
        val query = "What is the latest news about Samsung Electronics"
        val freshPublishedDate = LocalDate.now().minusDays(1)
        val fresh = FusionSearchResult(
            title = "Samsung Electronics Posts Recent Results",
            source = "samsung.com",
            url = "https://news.samsung.com/global/third-quarter-2026-results",
            snippet = "Samsung Electronics announced its results on $freshPublishedDate.",
            publishedAt = freshPublishedDate.toString(),
            queryUsed = "Samsung Electronics latest news",
            rank = 1,
        )
        val stale = FusionSearchResult(
            title = "Samsung Electronics Pyeongtaek P5 contract",
            source = "venturesquare.net",
            url = "https://www.venturesquare.net/2026/03/13/samsung-p5-contract",
            snippet = "A Samsung Electronics construction contract was reported on March 13, 2026.",
            publishedAt = "2026-03-13",
            queryUsed = "Samsung Electronics latest news",
            rank = 2,
        )
        val results = listOf(fresh, stale)
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = "Samsung Electronics latest news",
            intent = SearchIntent.NEWS,
            results = results,
            debugMessage = null,
            sources = results.mapIndexed { index, result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = index + 1,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        assertTrue(admitted.sources.any { it.title == fresh.title })
        assertFalse(admitted.sources.any { it.title == stale.title })
        assertFalse(admitted.structuredContext.orEmpty().contains(stale.title))

        val staleOnly = route(
            SearchRouteOutcome.COMPLETED,
            response.copy(
                results = listOf(stale),
                sources = response.sources.filter { it.title == stale.title },
            ),
        ).answerEvidence()
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, staleOnly.answerSufficiency)
        assertNull(staleOnly.structuredContext)
        assertTrue(staleOnly.sources.isEmpty())
    }

    @Test
    fun completedArtemisSplashdownDateRemainsAdmittedThroughAnswerEvidencePipeline() {
        val query = "According to NASA what date did Artemis II splash down"
        val results = listOf(
            FusionSearchResult(
                title = "Artemis II Splashdown and Recovery - NASA",
                source = "nasa.gov",
                url = "https://www.nasa.gov/gallery/artemis-ii-splashdown-and-recovery/",
                snippet = "NASA astronauts Reid Wiseman, Victor Glover, Christina Koch, and Jeremy Hansen splashed down at 8:07 p.m. EDT on April 10, 2026, off the coast of San Diego, completing a nearly 10-day journey.",
                publishedAt = null,
                queryUsed = query,
                rank = 1,
            ),
            FusionSearchResult(
                title = "NASA Answers Your Most Pressing Artemis II Questions",
                source = "nasa.gov",
                url = "https://www.nasa.gov/missions/nasa-answers-your-most-pressing-artemis-ii-questions/",
                snippet = "NASA's Artemis II mission is scheduled to splash down off the coast of San Diego at approximately 8:07 p.m. EDT on Friday, April 10.",
                publishedAt = null,
                queryUsed = query,
                rank = 2,
            ),
            FusionSearchResult(
                title = "Artemis II splashdown as historic NASA moon mission ends",
                source = "usatoday.com",
                url = "https://www.usatoday.com/story/news/nation/2026/04/10/artemis-ii-splashdown-updates--live/89523233007/",
                snippet = "Crew members have left their Orion capsule after splashing down into the Pacific, ending Artemis II's historic 10-day mission to the moon and back.",
                publishedAt = null,
                queryUsed = query,
                rank = 3,
            ),
            FusionSearchResult(
                title = "ESA - Splashdown for Artemis II",
                source = "esa.int",
                url = "https://www.esa.int/Newsroom/Press_Releases/Splashdown_for_Artemis_II",
                snippet = "Today at 17:07 local time on 10 April, 01:07 BST/02:07 CEST on 11 April, NASA's Orion spacecraft and its crew splashed down safely in the Pacific Ocean, marking the end of the Artemis II mission.",
                publishedAt = null,
                queryUsed = query,
                rank = 4,
            ),
        )
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = FusionWebSearch.detectIntent(query),
            results = results,
            debugMessage = null,
            sources = results.map { result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = result.rank,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.FALLBACK_COMPLETED, response).answerEvidence()

        assertEquals(
            SearchAnswerSufficiencyStatus.SUFFICIENT,
            FusionWebSearch.evaluateResultQualityForTest(results, query, response.intent, requestedQuery = query).answerSufficiency,
        )
        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, admitted.answerSufficiency)
        assertTrue(admitted.structuredContext.orEmpty().contains("April 10, 2026"))
        assertEquals(4, admitted.sources.size)
    }

    @Test
    fun artemisLatestStatusDoesNotAdmitImageAndPrelaunchOnlyEvidence() {
        val userQuery = "What is the latest official status of NASA Artemis II"
        val providerQuery = "$userQuery 최신"
        val results = listOf(
            FusionSearchResult(
                title = "NASA to Discuss Artemis Acceleration, Status of Upcoming Moon Missions",
                source = "nasa.gov",
                url = "https://www.nasa.gov/news-release/nasa-to-discuss-artemis-acceleration-status-of-upcoming-moon-missions/",
                snippet = "As the Artemis II crew members came close to passing behind the Moon and experiencing a planned loss of signal, they captured this image of a crescent Earth setting on the Moon's limb on April 6, 2026. The edge of the visible surface of the Moon is called the lunar limb.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 1,
            ),
            FusionSearchResult(
                title = "NASA's Artemis II Moon Mission Research Continues on Earth",
                source = "science.nasa.gov",
                url = "https://science.nasa.gov/missions/artemis/artemis-2/nasas-artemis-ii-moon-mission-research-continues-on-earth/",
                snippet = "Artemis II astronaut Victor Glover walks on a treadmill while in a space suit harnessed to NASA's Active Response Gravity Offload System at NASA's Johnson Space Center. Glover is simulating a walk on a planetary surface while in a suit that has been offloaded to lunar gravity. Artemis II astronauts completed this and other suited tasks before their mission launched and within a few days of ...",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 2,
            ),
        )
        val response = FusionSearchResponse(
            query = providerQuery,
            normalizedQuery = providerQuery,
            intent = FusionWebSearch.detectIntent(providerQuery),
            results = results,
            debugMessage = null,
            sources = results.map { result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = result.rank,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertEquals(SearchEvidenceStatus.ANSWER_INSUFFICIENT, admitted.evidenceStatus)
        assertEquals(2, admitted.retrievedSources.size)
        assertNull(admitted.structuredContext)
        assertTrue(admitted.sources.isEmpty())
        assertTrue(admitted.qualityStatusByModelResultCount.values.all { it == SearchEvidenceStatus.ANSWER_INSUFFICIENT })
    }

    @Test
    fun datedCurrentRateEvidenceDisplacesConflictingUndatedAndStaleResults() {
        val today = LocalDate.now()
        val freshDatePath = today.minusDays(5).toString().replace('-', '/')
        val staleDatePath = today.minusDays(90).toString().replace('-', '/')
        val staleCompactDate = today.minusDays(90).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val staleDashedDate = today.minusDays(90).toString()
        val query = "What is the latest policy-rate decision from the Bank of Korea?"
        val results = listOf(
            FusionSearchResult(
                title = "Monetary Policy Decision & Opening Remarks to the Press Conference",
                source = "Bank of Korea",
                url = "https://www.bok.or.kr/eng/bbs/E0000634/view.do?nttId=10093244&menuNo=400423",
                snippet = "The Bank of Korea decided today to leave the Base Rate unchanged at 2.50%.",
                publishedAt = null,
                rank = 1,
            ),
            FusionSearchResult(
                title = "FULL TEXT BOK statement on monetary policy decision in November",
                source = "Korea Times",
                url = "https://www.koreatimes.co.kr/economy/$staleCompactDate/full-text-bok-statement",
                snippet = "The Bank of Korea held the key rate steady at 2.5 percent on November 27, 2025.",
                publishedAt = null,
                rank = 2,
            ),
            FusionSearchResult(
                title = "Bank of Korea Holds Policy Rate Steady at 2.5%",
                source = "Informa Connect",
                url = "https://informaconnect.com/igm/article/bank-of-korea-holds-policy-rate-steady-at-25/",
                snippet = "The Bank of Korea kept its benchmark rate at 2.5% during its November 2025 meeting.",
                publishedAt = null,
                rank = 3,
            ),
            FusionSearchResult(
                title = "Bank of Korea Raises Base Rate to 3.00%",
                source = "Seoul Economic Daily",
                url = "https://en.sedaily.com/finance/$staleDatePath/bank-of-korea-raises-base-rate",
                snippet = "The Bank of Korea raised its base rate to 3.00%.",
                publishedAt = null,
                rank = 4,
            ),
            FusionSearchResult(
                title = "Bank of Korea - 한국은행",
                source = "bok.or.kr",
                url = "https://www.bok.or.kr/eng/main",
                snippet = "The Bank of Korea left the Base Rate unchanged at 3.00%.",
                publishedAt = null,
                rank = 5,
            ),
            FusionSearchResult(
                title = "Bank of Korea Holds Rate at 3% as Won Stabilizes",
                source = "Chosun",
                url = "https://www.chosun.com/economy/$freshDatePath/bok-holds-rate-at-3-percent/",
                snippet = "The Bank of Korea held its benchmark rate at 3%.",
                publishedAt = null,
                rank = 6,
            ),
            FusionSearchResult(
                title = "Bank of Korea holds base rate at 3%",
                source = "Reuters",
                url = "https://www.reuters.com/world/asia-pacific/bok-rate-$staleDashedDate/",
                snippet = "The Bank of Korea held the base rate at 3%.",
                publishedAt = null,
                rank = 7,
            ),
            FusionSearchResult(
                title = "South Korea Interest Rate",
                source = "Trading Economics",
                url = "https://tradingeconomics.com/south-korea/interest-rate",
                snippet = "The Bank of Korea sets South Korea's policy rate.",
                publishedAt = null,
                rank = 8,
            ),
        )
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.NEWS,
            results = results,
            debugMessage = null,
            sources = results.map { result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = query,
                    rank = result.rank,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        val selected = SearchAnswerSufficiencyPolicy.selectAnswerEvidence(
            query = query,
            intent = SearchIntent.NEWS,
            results = results,
            today = today,
        )
        assertTrue(
            "selected=${selected.map { it.url }} bok=${SearchAnswerSufficiencyPolicy.relevanceSignals(query, SearchIntent.NEWS, results[4], today)}",
            selected.any { it.url == results[4].url },
        )
        assertEquals(8, admitted.retrievedSources.size)
        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, admitted.answerSufficiency)
        assertTrue(
            "admitted=${admitted.sources.map { it.url }} context=${admitted.structuredContext}",
            admitted.structuredContext.orEmpty().contains("3.00%"),
        )
        assertFalse(admitted.structuredContext.orEmpty().contains("2.50%"))
        assertFalse(admitted.structuredContext.orEmpty().contains("2.5%"))
        assertFalse(admitted.structuredContext.orEmpty().contains("November 27, 2025"))
        assertEquals(
            listOf(results[5].url, results[4].url),
            admitted.sources.take(2).map { it.url },
        )
        assertEquals(
            selected.take(SearchAnswerEvidenceLimits.MODEL_MAX_RESULTS).map { it.url },
            admitted.sources.map { it.url },
        )
    }

    @Test
    fun currentStatusAdmissionKeepsPastEventEvidenceAndDropsPrelaunchTraining() {
        val query = "What is the current status of NASA Artemis II?"
        val results = listOf(
            FusionSearchResult(
                title = "Artemis II - Wikipedia",
                source = "en.wikipedia.org",
                url = "https://en.wikipedia.org/wiki/Artemis_II",
                snippet = "Artemis II (April 1-11, 2026) flew a crew of four on a flyby of the Moon.",
                publishedAt = null,
                queryUsed = "NASA Artemis II status",
                rank = 1,
            ),
            FusionSearchResult(
                title = "NASA's Artemis II Moon Mission Research Continues on Earth",
                source = "science.nasa.gov",
                url = "https://science.nasa.gov/missions/artemis/artemis-2/nasas-artemis-ii-moon-mission-research-continues-on-earth/",
                snippet = "Artemis II astronaut Victor Glover is simulating a walk on a planetary surface. Artemis II astronauts completed suited tasks before their mission launched.",
                publishedAt = null,
                queryUsed = "NASA Artemis II status",
                rank = 2,
            ),
            FusionSearchResult(
                title = "Artemis II Live Mission Tracker",
                source = "artemistracker.io",
                url = "https://artemistracker.io/",
                snippet = "Live tracker for NASA's Artemis II. Launched April 1, 2026 from Kennedy Space Center.",
                publishedAt = null,
                queryUsed = "NASA Artemis II status",
                rank = 3,
            ),
            FusionSearchResult(
                title = "Track NASA's Artemis II Mission in Real Time - NASA",
                source = "nasa.gov",
                url = "https://www.nasa.gov/missions/artemis/artemis-2/track-nasas-artemis-ii-mission-in-real-time/",
                snippet = "Artemis II is a key step in NASA's path toward future lunar surface exploration.",
                publishedAt = null,
                queryUsed = "NASA Artemis II status",
                rank = 4,
            ),
        )
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = results,
            debugMessage = null,
            sources = results.map { result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = result.rank,
                )
            },
            quality = WebSearchQuality(isUsable = true, score = 0.80, reasons = emptyList(), shouldTryFallback = false),
        )

        val admission = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, admission.answerSufficiency)
        assertEquals(listOf(results[0].url), admission.sources.map { it.url })
        assertTrue(admission.structuredContext.orEmpty().contains("flew a crew of four"))
        assertFalse(admission.structuredContext.orEmpty().contains("simulating a walk"))
        assertFalse(admission.structuredContext.orEmpty().contains("suited tasks before"))
    }

    @Test
    fun explicitOfficialRateRequestAdmitsOfficialEvidenceAheadOfHighOverlapResults() {
        val query = "What is the current official policy rate of the Bank of Korea"
        val results = listOf(
            FusionSearchResult(
                title = "South Korea Interest Rate - TRADING ECONOMICS",
                source = "tradingeconomics.com",
                url = "https://tradingeconomics.com/south-korea/interest-rate",
                snippet = "The official interest rate is the Bank of Korea Base Rate.",
                publishedAt = null,
                queryUsed = "Bank of Korea official policy rate",
                rank = 1,
            ),
            FusionSearchResult(
                title = "The Bank of Korea Base Rate | Objectives & Framework",
                source = "bok.or.kr",
                url = "https://www.bok.or.kr/eng/singl/baseRate/progress.do?menuNo=400016",
                snippet = "The Bank of Korea Base Rate is the reference policy rate applied in transactions between the Bank and financial institutions.",
                publishedAt = null,
                queryUsed = "Bank of Korea official policy rate",
                rank = 2,
            ),
            FusionSearchResult(
                title = "Bank of Korea base rate | Policy rate South Korean Central Bank",
                source = "global-rates.com",
                url = "https://www.global-rates.com/en/interest-rates/central-banks/15/south-korean-bank-of-korea-base-rate/",
                snippet = "The Bank of Korea base rate influences interest rates for mortgages, credit and savings accounts.",
                publishedAt = null,
                queryUsed = "Bank of Korea official policy rate",
                rank = 3,
            ),
            FusionSearchResult(
                title = "South Korea Policy Rate, 1999-2026 | CEIC Data",
                source = "ceicdata.com",
                url = "https://www.ceicdata.com/en/indicator/korea/policy-rate",
                snippet = "The Bank of Korea policy rate was set at 3.00% in October 2026.",
                publishedAt = null,
                queryUsed = "Bank of Korea official policy rate",
                rank = 4,
            ),
            FusionSearchResult(
                title = "Bank of Korea - 한국은행",
                source = "bok.or.kr",
                url = "https://www.bok.or.kr/eng/main/main.do",
                snippet = "The Monetary Policy Board of the Bank of Korea decided to leave the Base Rate unchanged at 3.00% for the intermeeting period.",
                publishedAt = null,
                queryUsed = "Bank of Korea official policy rate",
                rank = 5,
            ),
            FusionSearchResult(
                title = "Interest rates in South Korea: 3.00% (2026-10-08)",
                source = "Calculator Lib",
                url = "https://calculatorlib.com/data/interest-rates/kr",
                snippet = "Interest rates in South Korea were 3.00% on 2026-10-08.",
                publishedAt = null,
                queryUsed = "Bank of Korea official policy rate",
                rank = 6,
            ),
            FusionSearchResult(
                title = "Bank of Korea delivers back-to-back rate hikes",
                source = "CNBC",
                url = "https://www.cnbc.com/2026/08/27/korea-bok-rate-hike-inflation.html",
                snippet = "The Bank of Korea hiked rates by 25 basis points to 3%.",
                publishedAt = null,
                queryUsed = "Bank of Korea official policy rate",
                rank = 7,
            ),
            FusionSearchResult(
                title = "Bank of Korea raises rates to 2.75%",
                source = "CNBC",
                url = "https://www.cnbc.com/2026/07/16/bok-interest-rate-monetary-policy-markets-kospi-skhynix.html",
                snippet = "The Bank of Korea raised its rate by 25 basis points.",
                publishedAt = null,
                queryUsed = "Bank of Korea official policy rate",
                rank = 8,
            ),
        )
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = results,
            debugMessage = null,
            sources = results.map { result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = result.rank,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, admitted.answerSufficiency)
        assertTrue(admitted.structuredContext.orEmpty().contains("3.00%"))
        assertTrue(admitted.sources.any { it.url?.contains("bok.or.kr") == true })
        assertTrue(admitted.sources.all { it.url?.contains("bok.or.kr") == true })
        assertFalse(admitted.structuredContext.orEmpty().contains("tradingeconomics.com"))
    }

    @Test
    fun officialSeoulRequestDoesNotAdmitUnofficialPopulationWhenOfficialResultsLackValue() {
        val query = "What the latest official population estimate for Seoul South Korea"
        val results = listOf(
            FusionSearchResult(
                title = "Seoul Population 2026",
                source = "World Population Review",
                url = "https://worldpopulationreview.com/cities/south-korea/seoul",
                snippet = "Seoul's 2026 population is estimated at 9,217,913.",
                publishedAt = null,
                queryUsed = "Seoul population estimate",
                rank = 1,
            ),
            FusionSearchResult(
                title = "Population of Seoul | Seoul Metropolitan Government",
                source = "english.seoul.go.kr",
                url = "https://english.seoul.go.kr/seoul-views/meaning-of-seoul/4-population/",
                snippet = "The official website provides information about Seoul policies, history, culture, and services.",
                publishedAt = null,
                queryUsed = "Seoul population estimate",
                rank = 2,
            ),
            FusionSearchResult(
                title = "Seoul Population Estimate 2026",
                source = "GeoStats Live",
                url = "https://geostats.live/korea-south/seoul/",
                snippet = "As of October 2026, Seoul has an estimated population of 23,013,454.",
                publishedAt = null,
                queryUsed = "Seoul population estimate",
                rank = 3,
            ),
            FusionSearchResult(
                title = "Population - Seoul",
                source = "world.seoul.go.kr",
                url = "https://world.seoul.go.kr/seoul-views/meaning-of-seoul/4-population/",
                snippet = "The official website provides an overall introduction to Seoul.",
                publishedAt = null,
                queryUsed = "Seoul population estimate",
                rank = 4,
            ),
        )
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = results,
            debugMessage = null,
            sources = results.map { result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = result.rank,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        assertEquals(4, admitted.retrievedSources.size)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertNull(admitted.structuredContext)
        assertTrue(admitted.sources.isEmpty())
    }

    @Test
    fun directArtemisEvidenceSurvivesAdjacentMarsResultsInEitherMergeOrder() {
        val query = "When did NASA Artemis II launch?"
        val direct = FusionSearchResult(
            title = "Artemis II lunar mission launch date",
            source = "NASA",
            url = "https://nasa.gov/news/2026/04/01/artemis-ii-launch",
            snippet = "NASA launched Artemis II on April 1, 2026 for its lunar flyby.",
            publishedAt = "2026-11-02",
            queryUsed = query,
        )
        val adjacent = (1..5).map { index ->
            FusionSearchResult(
                title = "NASA Mars exploration schedule $index",
                source = "NASA",
                url = "https://nasa.gov/mars/schedule-$index",
                snippet = "NASA's Mars mission schedule includes future launch planning for 2028.",
                publishedAt = "2026-11-02",
                queryUsed = "NASA Mars exploration schedule",
            )
        }

        fun admitted(results: List<FusionSearchResult>) = route(
            SearchRouteOutcome.COMPLETED,
            FusionSearchResponse(
                query = query,
                normalizedQuery = query,
                intent = SearchIntent.CURRENT_INFO,
                results = results,
                debugMessage = null,
                sources = results.map { result ->
                    WebSearchSource(
                        title = result.title,
                        source = result.source,
                        url = result.url,
                        snippet = result.snippet,
                        publishedAt = result.publishedAt,
                        providerType = result.providerType,
                        providerDisplayName = result.providerDisplayName,
                        searchCategory = result.searchCategory,
                        queryUsed = result.queryUsed,
                        rank = result.rank,
                    )
                },
            ),
        ).answerEvidence()

        val forward = admitted(adjacent + direct)
        val reverse = admitted(listOf(direct) + adjacent)

        assertEquals(
            "sufficiency=${forward.answerSufficiency} evidence=${forward.evidenceStatus} sources=${forward.sources.map { it.url }}",
            SearchAnswerSufficiencyStatus.SUFFICIENT,
            forward.answerSufficiency,
        )
        assertEquals(direct.url, forward.sources.firstOrNull()?.url)
        assertEquals(forward.sources.map { it.url }, reverse.sources.map { it.url })
        assertTrue(forward.structuredContext.orEmpty().contains("April 1, 2026"))
        assertFalse(forward.structuredContext.orEmpty().contains("Mars"))
    }

    @Test
    fun staleArtemisTargetEvidenceIsNotAdmittedAsCurrentAnswer() {
        val query = "What is the current target date for NASA Artemis II"
        val providerQuery = "$query 최신"
        val results = listOf(
            FusionSearchResult(
                title = "What is Artemis II?",
                source = "National Air and Space Museum",
                url = "https://airandspace.si.edu/stories/editorial/what-artemis-ii",
                snippet = "NASA is currently targeting Wednesday, April 1, 2026 for the launch. The launch window opens at 6:24 pm.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 1,
            ),
            FusionSearchResult(
                title = "First Crewed Moon Mission Since 1973 Is 'Go' For 2026",
                source = "Forbes",
                url = "https://www.forbes.com/sites/example/2025/12/24/artemis-ii/",
                snippet = "Artemis II will send astronauts around the moon in February-April 2026.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 2,
            ),
            FusionSearchResult(
                title = "Artemis II: NASA's First Crewed Lunar Flyby in 50 Years",
                source = "NASA",
                url = "https://www.nasa.gov/mission/artemis-ii/",
                snippet = "Meet the Artemis II crew and learn how NASA's 10-day lunar flyby mission will test deep space systems.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 3,
            ),
            FusionSearchResult(
                title = "NASA Progresses Toward Artemis II Moon Mission",
                source = "NASA",
                url = "https://www.nasa.gov/missions/artemis/artemis-2/nasa-progresses-toward-artemis-ii-moon-mission/",
                snippet = "Set to launch in 2026, the spacecraft will carry NASA astronauts Reid Wiseman, Victor Glover, and Christina Koch.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 4,
            ),
            FusionSearchResult(
                title = "Artemis Program Timeline: Missions, Dates & Latest News",
                source = "Space Launch Schedule",
                url = "https://www.spacelaunchschedule.com/news/artemis-program-timeline/",
                snippet = "The crewed Artemis II mission is planned for 2026.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 5,
            ),
            FusionSearchResult(
                title = "Artemis II Launch Date 2026: NASA's Historic Lunar Return",
                source = "Ongoing Now",
                url = "https://ongoingnow.com/space/artemis-ii-launch-date-2026/",
                snippet = "NASA schedules the first crewed lunar flyby with the Artemis II mission set to depart on April 1, 2026.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 6,
            ),
            FusionSearchResult(
                title = "Artemis Program Timeline 2026-2028: Moon Landing Schedule",
                source = "Black Night Space Labs",
                url = "https://blacknightspacelabs.com/blog/artemis-program-2026-2027-moon-landing-timeline",
                snippet = "NASA moved the first lunar landing from Artemis III to Artemis IV.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 7,
            ),
            FusionSearchResult(
                title = "Artemis Program Timeline: Every Mission, 2022 to 2030",
                source = "New Space Tracker",
                url = "https://newspacetracker.com/articles/artemis-program-complete-timeline/",
                snippet = "The target shifted to April 2026.",
                publishedAt = null,
                queryUsed = providerQuery,
                rank = 8,
            ),
        )
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = results,
            debugMessage = null,
            sources = results.map { result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = result.rank,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        assertEquals(8, admitted.retrievedSources.size)
        assertTrue(admitted.retrievalUsable)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertEquals(SearchEvidenceStatus.ANSWER_INSUFFICIENT, admitted.evidenceStatus)
        assertNull(admitted.structuredContext)
        assertTrue(admitted.sources.isEmpty())
    }

    @Test
    fun nonEmptyUnusableFallbackIsNotAdmittedToGenerationOrPersistence() {
        val source = WebSearchSource(
            title = "A title without usable evidence",
            source = "Example",
            url = null,
            snippet = null,
            publishedAt = null,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = "Free",
            searchCategory = null,
            queryUsed = "public query",
            rank = 1,
        )
        val lowQuality = FusionSearchResponse(
            query = "public query",
            normalizedQuery = "public query",
            intent = SearchIntent.GENERAL,
            results = listOf(
                FusionSearchResult(
                    title = "A title without usable evidence",
                    source = "Example",
                    url = null,
                    snippet = null,
                    publishedAt = null,
                    queryUsed = "public query",
                ),
            ),
            debugMessage = null,
            sources = listOf(source),
        )
        val route = route(SearchRouteOutcome.FALLBACK_FAILED, lowQuality)

        val admitted = route.answerEvidence()

        assertFalse(route.response.hasSemanticSuccess())
        assertFalse(admitted.actualWebSearchUsed)
        assertNull(admitted.structuredContext)
        assertTrue(admitted.sources.isEmpty())
    }

    @Test
    fun recoverableAgentFailureAndTimeoutRetainOnlySemanticEvidence() {
        val evidence = usableEvidence()

        val agentFailure = route(SearchRouteOutcome.AGENT_FAILED, evidence).answerEvidence()
        val timeout = route(SearchRouteOutcome.TIMEOUT, evidence).answerEvidence()

        assertTrue(agentFailure.actualWebSearchUsed)
        assertTrue(agentFailure.structuredContext.orEmpty().contains("usable public snippet"))
        assertEquals(evidence.sources, agentFailure.sources)
        assertTrue(timeout.actualWebSearchUsed)
        assertEquals(agentFailure.structuredContext, timeout.structuredContext)
    }

    @Test
    fun staleOrDeniedRouteCannotAdmitEvenUsableLookingResponse() {
        val response = usableEvidence()
        val invalidated = listOf(
            SearchRouteOutcome.PRIVACY_DENIED,
            SearchRouteOutcome.PERMISSION_REVOKED,
            SearchRouteOutcome.OWNERSHIP_LOST,
            SearchRouteOutcome.SEARCH_DISABLED,
            SearchRouteOutcome.CANCELLED,
            SearchRouteOutcome.MANUAL_QUERY_INVALID,
        )

        invalidated.forEach { outcome ->
            val admitted = route(outcome, response).answerEvidence()
            assertFalse("$outcome", admitted.actualWebSearchUsed)
            assertNull("$outcome", admitted.structuredContext)
            assertTrue("$outcome", admitted.sources.isEmpty())
        }
    }

    @Test
    fun admissionMatrixKeepsGenerationUsageAndProvenanceInAgreement() {
        val usable = usableEvidence()
        val unusable = usable.copy(
            results = listOf(usable.results.single().copy(url = null, snippet = null)),
            sources = usable.sources,
        )
        val cases = listOf(
            SearchRouteOutcome.COMPLETED to usable,
            SearchRouteOutcome.COMPLETED to null,
            SearchRouteOutcome.AGENT_FAILED to usable,
            SearchRouteOutcome.AGENT_FAILED to unusable,
            SearchRouteOutcome.FALLBACK_COMPLETED to usable,
            SearchRouteOutcome.FALLBACK_FAILED to unusable,
            SearchRouteOutcome.TIMEOUT to usable,
            SearchRouteOutcome.TIMEOUT to null,
            SearchRouteOutcome.PRIVACY_DENIED to usable,
            SearchRouteOutcome.PERMISSION_REVOKED to usable,
            SearchRouteOutcome.OWNERSHIP_LOST to usable,
            SearchRouteOutcome.SEARCH_DISABLED to usable,
            SearchRouteOutcome.CANCELLED to usable,
            SearchRouteOutcome.MANUAL_QUERY_MISSING to usable,
            SearchRouteOutcome.MANUAL_QUERY_INVALID to usable,
        )

        cases.forEach { (outcome, response) ->
            val admitted = route(outcome, response).answerEvidence()
            val expected = outcome !in setOf(
                SearchRouteOutcome.PRIVACY_DENIED,
                SearchRouteOutcome.PERMISSION_REVOKED,
                SearchRouteOutcome.OWNERSHIP_LOST,
                SearchRouteOutcome.SEARCH_DISABLED,
                SearchRouteOutcome.CANCELLED,
                SearchRouteOutcome.MANUAL_QUERY_MISSING,
                SearchRouteOutcome.MANUAL_QUERY_INVALID,
            ) && response.hasSemanticSuccess()
            assertEquals("$outcome / hasResponse=${response != null}", expected, admitted.actualWebSearchUsed)
            assertEquals(expected, admitted.structuredContext != null)
            assertEquals(expected, admitted.sources.isNotEmpty())
        }
        assertFalse((null as SearchStrategyRouteResult?).answerEvidence().actualWebSearchUsed)
    }

    @Test
    fun answerAttributionContainsOnlySourcesActuallyPresentedToTheModel() {
        val evidence = eightResultEvidence()

        val admitted = route(SearchRouteOutcome.COMPLETED, evidence).answerEvidence()

        assertTrue(admitted.actualWebSearchUsed)
        assertEquals(4, Regex("UNTRUSTED_RESULT_\\d+_BEGIN").findAll(admitted.structuredContext.orEmpty()).count())
        assertEquals(
            evidence.results.take(4).map { it.url },
            admitted.sources.map { it.url },
        )
    }

    @Test
    fun duplicateEvidenceIdentityIsPresentedAndAttributedOnlyOnce() {
        val evidence = eightResultEvidence()
        val duplicate = evidence.results.first().copy(title = "Duplicate title for same URL")
        val response = evidence.copy(results = listOf(evidence.results.first(), duplicate))

        val admitted = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        assertEquals(1, Regex("UNTRUSTED_RESULT_\\d+_BEGIN").findAll(admitted.structuredContext.orEmpty()).count())
        assertEquals(1, admitted.sources.size)
    }

    @Test
    fun retrievalUsabilityDoesNotImplyRequestedRateIsPresent() {
        val evidence = FusionSearchResponse(
            query = "What is the current policy rate of the Bank of Korea?",
            normalizedQuery = "Bank of Korea policy rate",
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                FusionSearchResult("Bank of Korea rate overview", "Example", "https://example.test/one", "The Bank of Korea sets its policy rate.", null),
                FusionSearchResult("Bank policy discussion", "Example", "https://example.test/two", "The current policy rate is discussed.", null),
            ),
            debugMessage = null,
            sources = emptyList(),
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, evidence).answerEvidence()

        assertTrue(admitted.actualWebSearchUsed)
        assertTrue(admitted.retrievalUsable)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertEquals(SearchEvidenceStatus.ANSWER_INSUFFICIENT, admitted.evidenceStatus)
        assertNull(admitted.structuredContext)
        assertTrue(admitted.sources.isEmpty())
    }

    @Test
    fun insufficientStaleRateEvidenceIsNotAdmittedForAnswerGeneration() {
        val query = "What is the latest policy-rate decision from the Bank of Korea?"
        val results = listOf(
            FusionSearchResult(
                title = "BOK holds rate at 2.5%",
                source = "The Korea Times",
                url = "https://koreatimes.co.kr/economy/2025/11/27/bok-rate",
                snippet = "The Bank of Korea held the key rate steady at 2.5 percent on November 27, 2025.",
                publishedAt = "2025-11-27",
                queryUsed = query,
            ),
            FusionSearchResult(
                title = "Bank of Korea Holds Policy Rate Steady at 2.5%",
                source = "InfoMac Connect",
                url = "https://infomaconnect.com/bok-policy-rate",
                snippet = "Unchanged at 2.5% during the meeting on November 27, 2025.",
                publishedAt = "2025-11-27",
                queryUsed = query,
            ),
        )
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = results,
            debugMessage = null,
            sources = results.mapIndexed { index, result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = query,
                    rank = index + 1,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, response).answerEvidence()

        assertTrue(admitted.retrievalUsable)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertNull(admitted.structuredContext)
        assertTrue(admitted.sources.isEmpty())
        assertEquals(2, admitted.retrievedSources.size)
    }

    @Test
    fun answerSufficiencyUsesTheSanitizedSnippetActuallyPresentedToTheModel() {
        val snippet = "The Bank of Korea policy rate framework is described. " + "Context. ".repeat(40) + "The policy rate is 3.00%."
        val evidence = FusionSearchResponse(
            query = "What is the current policy rate of the Bank of Korea?",
            normalizedQuery = "Bank of Korea policy rate",
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                FusionSearchResult("Bank of Korea policy rate", "Bank of Korea", "https://www.bok.or.kr/eng/main", snippet, null),
                FusionSearchResult("BOK policy information", "Bank of Korea", "https://www.bok.or.kr/eng/info", "Background on the policy rate.", null),
            ),
            debugMessage = null,
            sources = emptyList(),
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, evidence).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertFalse(admitted.structuredContext.orEmpty().contains("3.00%"))
    }

    @Test
    fun explicitOfficialRequestAdmitsOnlyAuthorityEvidenceAndDropsStaleThirdPartyDates() {
        val query = "What is the current official policy rate of the Bank of Korea?"
        val staleThirdParty = FusionSearchResult(
            title = "Bank of Korea Base Rate — 3.00% | Cash Rate",
            source = "cashrate.youniivi.com",
            url = "https://cashrate.youniivi.com/bank-of-korea",
            snippet = "Current: 3.00% on Sep 20, 2026.",
            publishedAt = null,
            rank = 1,
        )
        val officialDecision = FusionSearchResult(
            title = "Bank of Korea Monetary Policy Decision",
            source = "bok.or.kr",
            url = "https://www.bok.or.kr/eng/bbs/decision",
            snippet = "The Monetary Policy Board decided to leave the Base Rate unchanged at 3.00%.",
            publishedAt = null,
            rank = 2,
        )
        val officialOverview = FusionSearchResult(
            title = "The Bank of Korea Base Rate",
            source = "bok.or.kr",
            url = "https://www.bok.or.kr/eng/info/rate",
            snippet = "The Bank of Korea Base Rate is the reference policy rate.",
            publishedAt = null,
            rank = 3,
        )
        val results = listOf(staleThirdParty, officialDecision, officialOverview)
        val evidence = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = results,
            debugMessage = null,
            sources = results.mapIndexed { index, result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = query,
                    rank = index + 1,
                    score = result.score,
                    qualityHints = result.qualityHints,
                )
            },
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, evidence).answerEvidence()

        assertEquals(3, admitted.retrievedSources.size)
        assertEquals(listOf(officialDecision.url, officialOverview.url), admitted.sources.map { it.url })
        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, admitted.answerSufficiency)
        assertTrue(admitted.structuredContext.orEmpty().contains("Base Rate unchanged at 3.00%"))
        assertFalse(admitted.structuredContext.orEmpty().contains("cashrate.youniivi.com"))
        assertFalse(admitted.structuredContext.orEmpty().contains("Sep 20, 2026"))
    }

    @Test
    fun explicitOfficialRequestDoesNotAdmitNonofficialResultsWhenAuthorityIsMissing() {
        val query = "What is the current official policy rate of the Bank of Korea?"
        val thirdParty = FusionSearchResult(
            title = "Bank of Korea Base Rate — 3.00%",
            source = "cashrate.youniivi.com",
            url = "https://cashrate.youniivi.com/bank-of-korea",
            snippet = "Current: 3.00%.",
            publishedAt = null,
        )
        val evidence = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(thirdParty),
            debugMessage = null,
            sources = listOf(
                WebSearchSource(
                    title = thirdParty.title,
                    source = thirdParty.source,
                    url = thirdParty.url,
                    snippet = thirdParty.snippet,
                    publishedAt = thirdParty.publishedAt,
                    providerType = thirdParty.providerType,
                    providerDisplayName = thirdParty.providerDisplayName,
                    searchCategory = thirdParty.searchCategory,
                    queryUsed = query,
                    rank = 1,
                ),
            ),
        )

        val admitted = route(SearchRouteOutcome.COMPLETED, evidence).answerEvidence()

        assertTrue(admitted.actualWebSearchUsed)
        assertEquals(1, admitted.retrievedSources.size)
        assertTrue(admitted.sources.isEmpty())
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertFalse(admitted.structuredContext.orEmpty().contains("3.00%"))
    }

    private fun route(outcome: SearchRouteOutcome, response: FusionSearchResponse?) = SearchStrategyRouteResult(
        outcome = outcome,
        response = response,
        selectedStrategy = SearchStrategy.AGENT_HARNESS,
        executedStrategy = SearchStrategy.AGENT_HARNESS,
    )

    private fun usableEvidence() = FusionSearchResponse(
        query = "public query",
        normalizedQuery = "public query",
        intent = SearchIntent.GENERAL,
        results = listOf(
            FusionSearchResult(
                title = "Usable result",
                source = "Example",
                url = "https://example.test/usable",
                snippet = "A usable public snippet.",
                publishedAt = null,
                providerDisplayName = "Free",
                rank = 1,
                queryUsed = "public query",
            ),
        ),
        debugMessage = null,
        sources = listOf(
            WebSearchSource(
                title = "Usable result",
                source = "Example",
                url = "https://example.test/usable",
                snippet = "A usable public snippet.",
                publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "Free",
                searchCategory = null,
                queryUsed = "public query",
                rank = 1,
            ),
        ),
    )

    private fun eightResultEvidence(): FusionSearchResponse {
        val results = (1..8).map { index ->
            FusionSearchResult(
                title = "Usable result $index",
                source = "Example $index",
                url = "https://example.test/$index",
                snippet = "Usable public snippet $index",
                publishedAt = null,
                queryUsed = "public query",
            )
        }
        val sources = results.mapIndexed { index, result ->
            WebSearchSource(
                title = result.title,
                source = result.source,
                url = result.url,
                snippet = result.snippet,
                publishedAt = result.publishedAt,
                providerType = result.providerType,
                providerDisplayName = result.providerDisplayName,
                searchCategory = result.searchCategory,
                queryUsed = result.queryUsed,
                rank = index + 1,
            )
        }
        return FusionSearchResponse(
            query = "public query",
            normalizedQuery = "public query",
            intent = SearchIntent.GENERAL,
            results = results,
            debugMessage = null,
            sources = sources,
            quality = WebSearchQuality(isUsable = true, score = 0.9, reasons = emptyList(), shouldTryFallback = false),
        )
    }
}
