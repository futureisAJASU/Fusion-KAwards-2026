package com.projectnuke.fusion.retrieval

/**
 * S1 deterministic ranking fixture (NOT a factual-verdict benchmark).
 *
 * Covers KO→KO, KO→EN, EN→KO, EN→EN positive paraphrases plus hard negatives
 * the deterministic Evidence rules must keep unsafe (entity/value/modality/
 * temporal distinctions). Tests drive these texts through a Fake engine with
 * fixed vectors, so they assert matcher determinism and top-k recall of the
 * ranking layer — never a universal similarity threshold.
 */
object SemanticBenchmarkFixtures {

    data class DirectionCase(
        val direction: String,
        val claim: String,
        val positive: String,
        val hardNegative: String,
    )

    val CASES: List<DirectionCase> = listOf(
        DirectionCase(
            direction = "KO_KO",
            claim = "ProductX는 12GB RAM을 지원한다",
            positive = "ProductX는 최대 12GB 메모리를 지원합니다",
            hardNegative = "ProductX2는 12GB RAM을 지원하지 않는다",
        ),
        DirectionCase(
            direction = "KO_EN",
            claim = "ProductX는 12GB RAM을 지원한다",
            positive = "ProductX supports up to 12GB of memory",
            hardNegative = "ProductX3 supports 12GB of memory",
        ),
        DirectionCase(
            direction = "EN_KO",
            claim = "ProductX supports up to 12GB of memory",
            positive = "ProductX는 12GB RAM을 지원한다",
            hardNegative = "ProductX supports up to 8GB of memory",
        ),
        DirectionCase(
            direction = "EN_EN",
            claim = "Acme released ProductX2 in 2026",
            positive = "Acme launched ProductX2 in 2026",
            hardNegative = "Acme announced ProductX2 in 2026",
        ),
    )

    /**
     * Extra hard negatives that must never become SUPPORT via similarity.
     *
     * rules-v6 fences quantity-attribute compatibility (memory vs storage):
     * "12GB RAM" vs "12GB storage" is INSUFFICIENT by deterministic rule, and
     * similarity must not override the rules in either direction.
     */
    val HARD_NEGATIVES: List<Pair<String, String>> = listOf(
        "ProductX2 supports 12GB." to "ProductX2 does not support 12GB.",
        "ProductX2 has 12GB RAM." to "ProductX2 has 8GB RAM.",
        "ProductX2 has 12GB RAM." to "ProductX2 has 12GB storage.",
        "ProductX2는 12GB 램을 지원한다." to "ProductX2는 12GB 저장공간을 지원한다.",
        "Acme released ProductX2 in 2026." to "Acme released ProductX3 in 2026.",
        "Acme released ProductX2." to "Acme will release ProductX2.",
        "Acme released ProductX2 today." to "Acme released ProductX2 yesterday.",
        "ProductX2 supports 12GB." to "ProductX3 supports 12GB.",
        "ProductX2 supports 12GB." to "오늘 서울에는 비가 내립니다.",
    )
}
