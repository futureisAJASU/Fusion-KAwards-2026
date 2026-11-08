# Fusion — Claim Status Ledger

이 문서는 K-AWARDS 공개 쇼케이스에서 기능을 과장하지 않기 위해 작성한 claim ledger입니다.

기준: private development repository  
branch: `transfer/vq8-build-recovery-20261030`  
snapshot: `08a21622`  
date: 2026-11-08

| 공개 가능한 주장 | 상태 | 코드/보고서 대조 | 공개 시 붙여야 할 제한 |
|---|---|---|---|
| Android에서 로컬 LLM을 실행한다 | **IMPLEMENTED** | `LiteRtLlmEngine.kt` | 모델/기기별 성능과 호환성 차이 |
| LiteRT-LM 기반 runtime ownership과 fallback이 있다 | **IMPLEMENTED** | `LiteRtLlmEngine.kt` | 모든 backend가 항상 사용 가능하다는 뜻 아님 |
| 모델이 검색 계획을 세우는 Agentic Search가 있다 | **IMPLEMENTED** | `AgenticSearchHarness.kt` | bounded rounds/query budget, permission/authorization 적용 |
| 검색어가 그대로 무제한 외부 전송되지 않는다 | **IMPLEMENTED CONTROL** | `ApplicationSearchStrategyExecutor.kt`, `RequestScopedAgenticSearchPrivacy.kt` | 보안의 절대적 보증으로 표현 금지 |
| 최신 검색 신뢰성 host audit이 존재한다 | **HOST VERIFIED** | 2026-11-06 Search Reliability P1 report | 그 감사에서 physical release gate는 pending |
| 로컬 파일 RAG가 있다 | **IMPLEMENTED** | `LocalRagCoordinator.kt`, `LocalAttachmentRag.kt` | 지원하지 않는 형식/읽기 실패는 fail-closed |
| PDF page-aware retrieval을 한다 | **IMPLEMENTED** | `LocalRagCoordinator.kt` | 전체 문서를 항상 모델에 넣는 것은 아님 |
| OCR image 경로가 있다 | **IMPLEMENTED PATH** | `LocalRagCoordinator.kt`, `LocalOcrPipeline.kt` | 모든 이미지/OCR 품질 보장 금지 |
| KoEn semantic retrieval을 사용한다 | **IMPLEMENTED** | `KoEnE5OnnxEmbeddingEngine.kt`, `KoEnE5RetrievalOwner.kt` | lexical retrieval과 함께 bounded하게 사용 |
| Evidence UI에서 사실 확인을 할 수 있다 | **IMPLEMENTED** | `EvidenceUiController`, `EvidenceDialog`, `FINAL_REPORT.md` | 자동으로 모든 답변을 진실 판정한다고 표현 금지 |
| semantic verifier가 3개 label을 출력한다 | **IMPLEMENTED / EXPERIMENTAL** | `FusionVerifierModelPolicy.kt` | `SUPPORTED / CONTRADICTED / INSUFFICIENT` |
| verifier가 Galaxy S24에서 qualification 됐다 | **QUALIFIED SUBSYSTEM** | `V0_9C_S24_FINAL_QUALIFICATION_REPORT.md` | Fusion 전체 production-ready를 뜻하지 않음 |
| verifier 160/160 결과가 있다 | **RUNTIME PARITY** | S24 final qualification | 100% 정확도라고 표현 금지 |
| production distribution ready다 | **NO** | S24 final report에서 `false` | 절대 주장 금지 |
| private local-RAG 내용을 웹 검색에서 제한한다 | **IMPLEMENTED CONTROL** | `PostAnswerSearchPrivacy.kt`, egress policy | 앱 수준 정책/consent gate |
| 답변 버전을 비교할 수 있다 | **IMPLEMENTED** | `ResponseVersionCompareDialog.kt` | line-level diff이며 사실성 비교 아님 |
| 긴 대화를 compaction한다 | **IMPLEMENTED** | `AutoCompaction.kt` | 무제한 기억 아님 |
| stale compaction 결과를 방어한다 | **HARDENED** | 2026-11-08 Phase 4 commit chain / tests | 특정 regression 방어를 전체 무결성 보증으로 확대 금지 |
| Study 기능이 있다 | **IMPLEMENTED SURFACES** | `StudySessionPanel.kt`, `StudyProgressSurface.kt`, review-card classes | “Guided Study”라는 과거 명칭보다 실제 UI 명칭 사용 권장 |
| standalone translation이 있다 | **IMPLEMENTED SURFACES** | `StandaloneTranslationScreen.kt`, `LiteRtLocalTranslator.kt` | 모델/manifest availability에 따라 제한 |
| Saved Knowledge / Project 기능이 있다 | **IMPLEMENTED SURFACES** | `knowledge`, `projects` packages | 모든 workflow가 동일하게 qualification 됐다는 표현 금지 |

## 사용하면 안 되는 표현

- “환각을 제거한다”
- “100% 정확한 사실 검증”
- “완전한 개인정보 보호”
- “모든 데이터가 항상 온디바이스에서만 처리된다”
- “상용 배포 준비 완료”
- “모든 테스트 통과”
- “160/160 정확도”
- “무제한 대화 기억”
- “완전 자율형 AI 에이전트”

## 권장 표현

- “근거 기반 답변과 검증을 지원한다”
- “Local-first이며 필요한 경우 명시적으로 웹/외부 경로를 사용한다”
- “검색과 외부 전송 경로에 bounded authorization 및 privacy gate를 둔다”
- “Verifier subsystem은 Galaxy S24 Android execution qualification을 완료했다”
- “Search subsystem은 최신 host hardening을 완료했으며 해당 감사 시점 physical release gate는 pending이었다”
- “작동형 Android 프로토타입”
