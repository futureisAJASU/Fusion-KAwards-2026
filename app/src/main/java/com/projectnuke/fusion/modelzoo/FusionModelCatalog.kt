package com.projectnuke.fusion.modelzoo

import android.content.Context
import android.util.AtomicFile
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.LocalGenerationSettingsPolicy
import com.projectnuke.fusion.util.DebtQuarantine
import com.projectnuke.fusion.util.DebtQuarantineOutcome
import com.projectnuke.fusion.util.exactLongOrNull
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ModelFamily { GEMMA, QWEN, KANANA, LLAMA, PHI, KIMI, DEEPSEEK, MISTRAL, CUSTOM }
enum class ModelRuntimeFormat { LITERT_LM, MEDIAPIPE_LLM, GGUF, ONNX, NEEDS_CONVERSION, EXYNOS_AI_STUDIO, REMOTE_API, UNKNOWN }
enum class ModelAvailability { READY, NEEDS_CONVERSION, NEEDS_DOWNLOAD, REMOTE_ONLY, UNSUPPORTED_ON_DEVICE, CUSTOM_IMPORTED }
enum class ModelMemoryClass { LOW, MEDIUM, HIGH, SERVER }
enum class ModelRecommendedDeviceClass { RAM_8GB_SAFE, RAM_12GB_RECOMMENDED, RAM_16GB_RECOMMENDED, SERVER_ONLY }

data class FusionModelSpec(
    val id: String,
    val displayName: String,
    val family: ModelFamily,
    val parameterLabel: String,
    val runtimeFormat: ModelRuntimeFormat,
    val availability: ModelAvailability,
    val memoryClass: ModelMemoryClass,
    val recommendedDeviceClass: ModelRecommendedDeviceClass,
    val recommendedMaxTokens8Gb: Int,
    val recommendedMaxTokens12Gb: Int,
    val recommendedMtpEnabled: Boolean,
    val recommendedReasoningEnabled: Boolean,
    val supportsVision: Boolean,
    val supportsReasoning: Boolean,
    val supportsNpuCandidate: Boolean,
    val notes: String,
    val fileName: String? = null,
    val downloadUrl: String? = null,
    val modelPageUrl: String? = null,
    val directDownloadUrl: String? = null,
    val directDownloadFileName: String? = null,
    val directDownloadFormat: String? = null,
    val directDownloadSizeGb: Float? = null,
    val directDownloadSha256: String? = null,
    val requiresExternalBrowser: Boolean = true,
    val localPath: String? = null,
    val officialUrl: String? = null,
    val huggingFaceModelId: String? = null,
    val modelSizeEstimateGb: Float? = null,
    val minRecommendedRamGb: Int? = null,
    val recommendedRamGb: Int? = null,
    val localExecutionWarning: String? = null,
    val licenseLabel: String? = null,
    val sourceLabel: String? = null,
    val originalFileName: String? = null,
    val uriString: String? = null,
    val fileSizeBytes: Long? = null,
    val addedAt: Long? = null,
    val lastCheckedAt: Long? = null,
    val externallyReferenced: Boolean = false,
    val copiedInternally: Boolean = localPath != null
) {
    val isRemoteOnly: Boolean get() = availability == ModelAvailability.REMOTE_ONLY
    val canRunLocalRuntime: Boolean get() = runtimeFormat == ModelRuntimeFormat.LITERT_LM
}

object FusionModelCatalog {
    /**
     * The only reasoning-capability authority.  A capability must be carried
     * by an explicit catalog spec; family names and unknown/custom fallbacks
     * are never sufficient evidence.
     */
    internal fun reasoningCapabilityAvailable(spec: FusionModelSpec?): Boolean =
        spec?.supportsReasoning == true

    internal fun effectiveReasoningEnabled(
        spec: FusionModelSpec?,
        requested: Boolean,
    ): Boolean = requested && reasoningCapabilityAvailable(spec)

    internal fun effectiveReasoningEnabled(
        capabilityAvailable: Boolean,
        requested: Boolean,
    ): Boolean = requested && capabilityAvailable

    internal fun resolveImportedFamily(
        fileName: String,
        sizeBytes: Long,
        fallback: ModelFamily,
    ): ModelFamily = when {
        KananaPromptTemplateCompatibility.isK0ImportFileIdentity(fileName, sizeBytes) -> ModelFamily.KANANA
        fileName.contains("kanana", ignoreCase = true) -> ModelFamily.KANANA
        else -> fallback
    }

    val builtIn: List<FusionModelSpec> = listOf(
        FusionModelSpec(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B-it",
            family = ModelFamily.GEMMA,
            parameterLabel = "E2B",
            runtimeFormat = ModelRuntimeFormat.LITERT_LM,
            availability = ModelAvailability.NEEDS_DOWNLOAD,
            memoryClass = ModelMemoryClass.MEDIUM,
            recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
            recommendedMaxTokens8Gb = 2048,
            recommendedMaxTokens12Gb = 4096,
            recommendedMtpEnabled = false,
            recommendedReasoningEnabled = false,
            supportsVision = true,
            supportsReasoning = false,
            supportsNpuCandidate = true,
            notes = "기존 Gemma 실행 경로를 사용하는 로컬 모델입니다.",
            fileName = "gemma-4-E2B-it.litertlm",
            modelPageUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            directDownloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
            directDownloadFileName = "gemma-4-E2B-it.litertlm",
            directDownloadFormat = "litertlm",
            directDownloadSizeGb = 2.588147712f,
            directDownloadSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            officialUrl = "https://ai.google.dev/gemma",
            huggingFaceModelId = "litert-community/gemma-4-E2B-it-litert-lm",
            modelSizeEstimateGb = 2.588147712f,
            minRecommendedRamGb = 8,
            recommendedRamGb = 8,
            localExecutionWarning = "8GB 기기에서 로컬 실행 후보로 사용할 수 있습니다.",
            sourceLabel = "Google Gemma"
        ),
        FusionModelSpec(
            id = "gemma-4-e4b-it",
            displayName = "Gemma 4 E4B-it",
            family = ModelFamily.GEMMA,
            parameterLabel = "E4B",
            runtimeFormat = ModelRuntimeFormat.LITERT_LM,
            availability = ModelAvailability.NEEDS_DOWNLOAD,
            memoryClass = ModelMemoryClass.HIGH,
            recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_12GB_RECOMMENDED,
            recommendedMaxTokens8Gb = 1024,
            recommendedMaxTokens12Gb = 4096,
            recommendedMtpEnabled = false,
            recommendedReasoningEnabled = false,
            supportsVision = true,
            supportsReasoning = false,
            supportsNpuCandidate = true,
            notes = "8GB 기기에서는 실험용이며, 12GB 이상 기기를 권장합니다.",
            fileName = "gemma-4-E4B-it.litertlm",
            modelPageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            directDownloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm",
            directDownloadFileName = "gemma-4-E4B-it.litertlm",
            directDownloadFormat = "litertlm",
            directDownloadSizeGb = 3.65953024f,
            directDownloadSha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            officialUrl = "https://ai.google.dev/gemma",
            huggingFaceModelId = "litert-community/gemma-4-E4B-it-litert-lm",
            modelSizeEstimateGb = 3.65953024f,
            minRecommendedRamGb = 8,
            recommendedRamGb = 12,
            localExecutionWarning = "8GB 기기에서는 메모리 압박이 발생할 수 있습니다.",
            sourceLabel = "Google Gemma"
        ),
        FusionModelSpec("qwen3-0_6b-instruct", "Qwen3 0.6B Instruct", ModelFamily.QWEN, "0.6B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.LOW, ModelRecommendedDeviceClass.RAM_8GB_SAFE, 2048, 4096, false, false, false, true, true, "저메모리 기기에서 빠른 응답용으로 적합합니다.", downloadUrl = "https://huggingface.co/Qwen/Qwen3-0.6B", officialUrl = "https://github.com/QwenLM/Qwen3", huggingFaceModelId = "Qwen/Qwen3-0.6B", modelSizeEstimateGb = 1.2f, minRecommendedRamGb = 8, recommendedRamGb = 8, localExecutionWarning = "저메모리 기기에서도 실험하기 적합한 소형 모델입니다.", sourceLabel = "Qwen"),
        FusionModelSpec("qwen3-1_7b-instruct", "Qwen3 1.7B Instruct", ModelFamily.QWEN, "1.7B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.MEDIUM, ModelRecommendedDeviceClass.RAM_8GB_SAFE, 2048, 4096, false, false, false, true, true, "Fusion 기본 대체 모델 후보입니다.", downloadUrl = "https://huggingface.co/Qwen/Qwen3-1.7B", officialUrl = "https://github.com/QwenLM/Qwen3", huggingFaceModelId = "Qwen/Qwen3-1.7B", modelSizeEstimateGb = 3.4f, minRecommendedRamGb = 8, recommendedRamGb = 8, localExecutionWarning = "8GB 기기에서 실험할 수 있지만 maxTokens를 낮게 설정하는 것이 좋습니다.", sourceLabel = "Qwen"),
        FusionModelSpec("qwen3-4b-instruct", "Qwen3 4B Instruct", ModelFamily.QWEN, "4B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_12GB_RECOMMENDED, 1024, 4096, false, false, false, true, true, "8GB 기기에서는 실험용으로만 권장합니다.", downloadUrl = "https://huggingface.co/Qwen/Qwen3-4B", officialUrl = "https://github.com/QwenLM/Qwen3", huggingFaceModelId = "Qwen/Qwen3-4B", modelSizeEstimateGb = 8.0f, minRecommendedRamGb = 8, recommendedRamGb = 12, localExecutionWarning = "8GB 기기에서는 메모리 압박이 발생할 수 있습니다.", sourceLabel = "Qwen"),
        FusionModelSpec("qwen3_6-35b-a3b", "Qwen3.6 35B A3B", ModelFamily.QWEN, "35B A3B", ModelRuntimeFormat.REMOTE_API, ModelAvailability.REMOTE_ONLY, ModelMemoryClass.SERVER, ModelRecommendedDeviceClass.SERVER_ONLY, 0, 0, false, false, false, true, false, "서버 또는 원격 실행용 후보입니다.", downloadUrl = "https://huggingface.co/Qwen/Qwen3.6-35B-A3B", officialUrl = "https://github.com/QwenLM/Qwen3.6", huggingFaceModelId = "Qwen/Qwen3.6-35B-A3B", modelSizeEstimateGb = 70.0f, minRecommendedRamGb = 32, recommendedRamGb = 64, localExecutionWarning = "이 모델은 모바일 로컬 실행용이 아닙니다. 서버 또는 원격 실행을 권장합니다.", sourceLabel = "Qwen"),
        FusionModelSpec("llama-3_2-1b-instruct", "Llama 3.2 1B Instruct", ModelFamily.LLAMA, "1B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.LOW, ModelRecommendedDeviceClass.RAM_8GB_SAFE, 2048, 4096, false, false, false, false, true, "생태계와 호환성이 좋은 기준 모델입니다.", downloadUrl = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct", officialUrl = "https://huggingface.co/meta-llama", huggingFaceModelId = "meta-llama/Llama-3.2-1B-Instruct", modelSizeEstimateGb = 2.0f, minRecommendedRamGb = 8, recommendedRamGb = 8, localExecutionWarning = "8GB 기기에서 기준 모델로 실험하기 적합합니다.", sourceLabel = "Meta Llama"),
        FusionModelSpec("llama-3_2-3b-instruct", "Llama 3.2 3B Instruct", ModelFamily.LLAMA, "3B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_12GB_RECOMMENDED, 1024, 4096, false, false, false, false, true, "품질 비교용 모델입니다.", downloadUrl = "https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct", officialUrl = "https://huggingface.co/meta-llama", huggingFaceModelId = "meta-llama/Llama-3.2-3B-Instruct", modelSizeEstimateGb = 6.0f, minRecommendedRamGb = 8, recommendedRamGb = 12, localExecutionWarning = "8GB 기기에서는 maxTokens 1024~2048을 권장합니다.", sourceLabel = "Meta Llama"),
        FusionModelSpec("phi-4-mini-instruct", "Phi-4-mini-instruct", ModelFamily.PHI, "mini", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_12GB_RECOMMENDED, 1024, 4096, false, false, false, true, true, "정확한 답변, 코딩, 논리 설명용 후보입니다.", downloadUrl = "https://huggingface.co/microsoft/Phi-4-mini-instruct", officialUrl = "https://azure.microsoft.com/en-us/products/phi", huggingFaceModelId = "microsoft/Phi-4-mini-instruct", modelSizeEstimateGb = 7.0f, minRecommendedRamGb = 8, recommendedRamGb = 12, localExecutionWarning = "8GB 기기에서는 메모리 압박이 발생할 수 있습니다.", sourceLabel = "Microsoft Phi"),
        FusionModelSpec("phi-4-mini-instruct-litert", "Phi-4-mini-instruct LiteRT", ModelFamily.PHI, "mini", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_12GB_RECOMMENDED, 1024, 4096, false, false, false, true, true, "LiteRT 실행 후보입니다.", downloadUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct", officialUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct", huggingFaceModelId = "litert-community/Phi-4-mini-instruct", modelSizeEstimateGb = 7.0f, minRecommendedRamGb = 8, recommendedRamGb = 12, localExecutionWarning = "LiteRT 실행 후보입니다. 실제 호환성은 기기에서 확인해야 합니다.", sourceLabel = "LiteRT Community"),
        FusionModelSpec("phi-4-mini-reasoning", "Phi-4-mini-reasoning", ModelFamily.PHI, "mini", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_12GB_RECOMMENDED, 1024, 4096, false, true, false, true, true, "추론 실험용입니다. 8GB 기기에서는 권장하지 않습니다.", downloadUrl = "https://huggingface.co/microsoft/Phi-4-mini-reasoning", officialUrl = "https://azure.microsoft.com/en-us/products/phi", huggingFaceModelId = "microsoft/Phi-4-mini-reasoning", modelSizeEstimateGb = 7.0f, minRecommendedRamGb = 8, recommendedRamGb = 12, localExecutionWarning = "추론 출력이 길어질 수 있어 8GB 기기에서는 권장하지 않습니다.", sourceLabel = "Microsoft Phi"),
        FusionModelSpec("phi-4-multimodal-instruct", "Phi-4-multimodal-instruct", ModelFamily.PHI, "multimodal", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_16GB_RECOMMENDED, 1024, 4096, false, false, true, true, true, "멀티모달 실험용입니다.", officialUrl = "https://azure.microsoft.com/en-us/products/phi", modelSizeEstimateGb = 8.0f, minRecommendedRamGb = 12, recommendedRamGb = 16, localExecutionWarning = "멀티모달 모델입니다. 16GB 이상 기기에서 확인하는 것을 권장합니다.", sourceLabel = "Microsoft Phi"),
        FusionModelSpec("deepseek-r1-distill-qwen-1_5b", "DeepSeek-R1-Distill-Qwen-1.5B", ModelFamily.DEEPSEEK, "1.5B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.MEDIUM, ModelRecommendedDeviceClass.RAM_8GB_SAFE, 1024, 2048, false, true, false, true, true, "소형 추론 모델 실험 후보입니다.", downloadUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B", officialUrl = "https://huggingface.co/deepseek-ai", huggingFaceModelId = "deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B", modelSizeEstimateGb = 3.0f, minRecommendedRamGb = 8, recommendedRamGb = 8, localExecutionWarning = "소형 추론 모델 실험 후보입니다. 출력이 길어질 수 있어 maxTokens를 낮게 설정하는 것이 좋습니다.", sourceLabel = "DeepSeek"),
        FusionModelSpec("deepseek-r1-distill-qwen-7b", "DeepSeek-R1-Distill-Qwen-7B", ModelFamily.DEEPSEEK, "7B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_16GB_RECOMMENDED, 1024, 2048, false, true, false, true, true, "8GB 기기에서는 권장하지 않습니다.", downloadUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-7B", officialUrl = "https://huggingface.co/deepseek-ai", huggingFaceModelId = "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B", modelSizeEstimateGb = 14.0f, minRecommendedRamGb = 12, recommendedRamGb = 16, localExecutionWarning = "8GB 기기에서는 권장하지 않습니다.", sourceLabel = "DeepSeek"),
        FusionModelSpec("deepseek-v3-v4-server", "DeepSeek V3/V4 Server Models", ModelFamily.DEEPSEEK, "Server", ModelRuntimeFormat.REMOTE_API, ModelAvailability.REMOTE_ONLY, ModelMemoryClass.SERVER, ModelRecommendedDeviceClass.SERVER_ONLY, 0, 0, false, true, false, true, false, "원격 API 실행 후보입니다.", officialUrl = "https://api-docs.deepseek.com/", modelSizeEstimateGb = 100.0f, minRecommendedRamGb = 32, recommendedRamGb = 64, localExecutionWarning = "모바일 로컬 실행용이 아닙니다. 원격 API 실행을 권장합니다.", sourceLabel = "DeepSeek"),
        FusionModelSpec("ministral-3b", "Ministral 3B", ModelFamily.MISTRAL, "3B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_12GB_RECOMMENDED, 1024, 4096, false, false, false, false, true, "Mistral 소형 모델 후보입니다.", downloadUrl = "https://docs.mistral.ai/models/overview", officialUrl = "https://docs.mistral.ai/models/overview", modelSizeEstimateGb = 6.0f, minRecommendedRamGb = 8, recommendedRamGb = 12, localExecutionWarning = "8GB 기기에서 실험할 수 있지만 메모리 압박이 발생할 수 있습니다.", sourceLabel = "Mistral AI"),
        FusionModelSpec("ministral-8b", "Ministral 8B", ModelFamily.MISTRAL, "8B", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_16GB_RECOMMENDED, 1024, 4096, false, false, false, false, true, "고메모리 기기 실험 후보입니다.", downloadUrl = "https://docs.mistral.ai/models/overview", officialUrl = "https://docs.mistral.ai/models/overview", modelSizeEstimateGb = 16.0f, minRecommendedRamGb = 12, recommendedRamGb = 16, localExecutionWarning = "12GB 이상 기기에서 실험하는 것을 권장합니다.", sourceLabel = "Mistral AI"),
        FusionModelSpec("devstral-small", "Devstral Small", ModelFamily.MISTRAL, "Small", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_16GB_RECOMMENDED, 1024, 4096, false, false, false, false, true, "코딩 에이전트용 모델 후보입니다.", downloadUrl = "https://mistral.ai/models", officialUrl = "https://mistral.ai/models", modelSizeEstimateGb = 24.0f, minRecommendedRamGb = 12, recommendedRamGb = 16, localExecutionWarning = "코딩 에이전트용 모델입니다. 모바일 로컬 실행은 변환과 추가 검증이 필요합니다.", sourceLabel = "Mistral AI"),
        FusionModelSpec("magistral-small", "Magistral Small", ModelFamily.MISTRAL, "Small", ModelRuntimeFormat.LITERT_LM, ModelAvailability.NEEDS_CONVERSION, ModelMemoryClass.HIGH, ModelRecommendedDeviceClass.RAM_16GB_RECOMMENDED, 1024, 4096, false, true, false, true, true, "추론 특화 모델 후보입니다.", downloadUrl = "https://mistral.ai/models", officialUrl = "https://mistral.ai/models", modelSizeEstimateGb = 24.0f, minRecommendedRamGb = 12, recommendedRamGb = 16, localExecutionWarning = "추론 특화 모델입니다. 8GB 기기에서는 권장하지 않습니다.", sourceLabel = "Mistral AI"),
        FusionModelSpec("mistral-small-4", "Mistral Small 4", ModelFamily.MISTRAL, "Small 4", ModelRuntimeFormat.REMOTE_API, ModelAvailability.REMOTE_ONLY, ModelMemoryClass.SERVER, ModelRecommendedDeviceClass.SERVER_ONLY, 0, 0, false, false, false, false, false, "서버 또는 고메모리 실행 후보입니다.", downloadUrl = "https://mistral.ai/models", officialUrl = "https://mistral.ai/news/mistral-small-4", modelSizeEstimateGb = 48.0f, minRecommendedRamGb = 16, recommendedRamGb = 24, localExecutionWarning = "모바일 로컬 실행보다는 서버 또는 고메모리 기기를 권장합니다.", sourceLabel = "Mistral AI"),
        FusionModelSpec("le-chat", "Le Chat", ModelFamily.MISTRAL, "Service", ModelRuntimeFormat.REMOTE_API, ModelAvailability.REMOTE_ONLY, ModelMemoryClass.SERVER, ModelRecommendedDeviceClass.SERVER_ONLY, 0, 0, false, false, true, true, false, "Mistral의 원격 AI 서비스입니다.", officialUrl = "https://mistral.ai/news/all-new-le-chat", minRecommendedRamGb = 0, recommendedRamGb = 0, localExecutionWarning = "Le Chat은 모델 파일이 아니라 Mistral의 원격 AI 서비스입니다.", sourceLabel = "Mistral AI"),
        FusionModelSpec("kimi-k2-instruct", "Kimi K2 Instruct", ModelFamily.KIMI, "K2", ModelRuntimeFormat.REMOTE_API, ModelAvailability.REMOTE_ONLY, ModelMemoryClass.SERVER, ModelRecommendedDeviceClass.SERVER_ONLY, 0, 0, false, false, false, true, false, "로컬 실행용이 아니라 서버/API 후보입니다.", downloadUrl = "https://huggingface.co/moonshotai/Kimi-K2-Instruct", officialUrl = "https://github.com/MoonshotAI/Kimi-K2", huggingFaceModelId = "moonshotai/Kimi-K2-Instruct", modelSizeEstimateGb = 100.0f, minRecommendedRamGb = 32, recommendedRamGb = 64, localExecutionWarning = "이 모델은 모바일 로컬 실행용이 아닙니다. 서버 또는 원격 실행을 권장합니다.", sourceLabel = "Moonshot AI")
    )

    internal sealed interface ImportedCatalogLoad {
        data object Missing : ImportedCatalogLoad
        data class Valid(
            val models: List<FusionModelSpec>,
            val needsMigration: Boolean = false,
        ) : ImportedCatalogLoad
        data object Corrupt : ImportedCatalogLoad
        data object Oversized : ImportedCatalogLoad
    }

    internal const val MAX_IMPORTED_ENTRIES = 256
    internal const val MAX_IMPORTED_CATALOG_BYTES = 2L * 1024L * 1024L

    /**
     * File IO seam. Production writes stay crash-safe through AtomicFile,
     * while reads use an explicit base/backup pair mirroring AtomicFile's
     * documented `.bak` layout (preferred over `openRead()` so that a
     * corrupt-but-readable base can still fall back to a valid backup, and
     * so backup recovery stays unit-testable on the host without
     * Robolectric). Tests may replace this with an in-memory fake; they must
     * restore the default afterwards.
     */
    internal interface CatalogFileIo {
        fun hasArtifact(file: File): Boolean
        fun readPrimary(file: File): ByteArray?
        fun readBackup(file: File): ByteArray?
        fun writeArtifact(file: File, bytes: ByteArray)
    }

    private object AtomicCatalogFileIo : CatalogFileIo {
        override fun hasArtifact(file: File): Boolean =
            file.isFile || backupFile(file).isFile

        override fun readPrimary(file: File): ByteArray? =
            try {
                file.takeIf { it.isFile && it.length() <= MAX_IMPORTED_CATALOG_BYTES }?.readBytes()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Recoverable IO failure; fatal Error propagates (C16).
                null
            }

        override fun readBackup(file: File): ByteArray? =
            try {
                backupFile(file).takeIf {
                    it.isFile && it.length() <= MAX_IMPORTED_CATALOG_BYTES
                }?.readBytes()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Recoverable IO failure; fatal Error propagates (C16).
                null
            }

        override fun writeArtifact(file: File, bytes: ByteArray) {
            val atomicFile = AtomicFile(file)
            val stream = atomicFile.startWrite()
            try {
                stream.write(bytes)
                stream.flush()
                atomicFile.finishWrite(stream)
            } catch (failure: Throwable) {
                atomicFile.failWrite(stream)
                throw failure
            }
        }
    }

    internal var catalogFileIo: CatalogFileIo = AtomicCatalogFileIo
        private set

    /** Test-only seam replacement. Production code must not call this. */
    internal fun overrideCatalogFileIoForTests(io: CatalogFileIo) {
        catalogFileIo = io
    }

    internal fun resetCatalogFileIoForTests() {
        catalogFileIo = AtomicCatalogFileIo
    }

    private fun backupFile(file: File): File = File(file.parent, "${file.name}.bak")

    private const val CatalogQuarantineSuffix = ".corrupt.bak"
    private val catalogLock = Any()

    fun all(context: Context): List<FusionModelSpec> = builtIn + loadImported(context)

    fun findByNameOrId(context: Context, value: String?): FusionModelSpec? {
        if (value.isNullOrBlank()) return null
        return all(context).firstOrNull { it.id == value || it.displayName == value }
            ?: inferSpec(value)
    }

    fun inferFamily(context: Context, value: String?): ModelFamily =
        findByNameOrId(context, value)?.family ?: inferFamilyFromText(value)

    fun inferFamilyFromText(value: String?): ModelFamily {
        val lower = value.orEmpty().lowercase()
        return when {
            "kanana" in lower -> ModelFamily.KANANA
            "deepseek" in lower -> ModelFamily.DEEPSEEK
            "mistral" in lower || "ministral" in lower || "magistral" in lower || "devstral" in lower || "le chat" in lower -> ModelFamily.MISTRAL
            "qwen" in lower -> ModelFamily.QWEN
            "llama" in lower -> ModelFamily.LLAMA
            "phi" in lower -> ModelFamily.PHI
            "kimi" in lower -> ModelFamily.KIMI
            "gemma" in lower -> ModelFamily.GEMMA
            else -> ModelFamily.CUSTOM
        }
    }

    /**
     * Display read: corruption renders as an empty imported list so the UI
     * stays usable, but mutations never overwrite unreadable evidence without
     * a secured quarantine (see saveImported). Never deletes model binaries.
     */
    fun loadImported(context: Context): List<FusionModelSpec> {
        return when (val load = loadImportedResult(context)) {
            is ImportedCatalogLoad.Valid -> load.models
            is ImportedCatalogLoad.Missing,
            is ImportedCatalogLoad.Corrupt,
            is ImportedCatalogLoad.Oversized -> emptyList()
        }
    }

    internal fun loadImportedResult(context: Context): ImportedCatalogLoad {
        synchronized(catalogLock) {
            val file = importedMetadataFile(context)
            val parsed = loadImportedResultLocked(file)
            val valid = (parsed as? ImportedCatalogLoad.Valid) ?: return parsed
            if (!valid.needsMigration) return valid
            // Deterministic stable-ID migration, serialized with the read so
            // concurrent loads cannot assign divergent identities.
            val migrated = valid.models.mapIndexed { index, model ->
                if (model.id.isNotBlank()) model
                else model.copy(id = deterministicMigratedId(model, index))
            }
            // Invariant: writeModelsLocked success implies the next loader
            // reads those exact bytes as Valid. A migration we cannot durably
            // persist — because the final representation violates catalog
            // invariants (e.g. duplicate paths behind legacy blank IDs) or
            // because the rewrite fails — must fail closed instead of
            // exposing in-memory identities the durable store never adopted.
            if (!isValidImportedCatalog(migrated) || !writeModelsLocked(file, migrated)) {
                return ImportedCatalogLoad.Corrupt
            }
            return ImportedCatalogLoad.Valid(migrated)
        }
    }

    /**
     * Production validation shared by every writer path (migration, save,
     * remove). Enforces exactly the invariants the next loader enforces:
     * stable non-blank unique IDs, unique managed/local path identity (blank
     * paths are external links and exempt), and the bounded entry cap.
     */
    internal fun isValidImportedCatalog(models: List<FusionModelSpec>): Boolean {
        if (models.size > MAX_IMPORTED_ENTRIES) return false
        val seenIds = HashSet<String>(models.size)
        val seenPaths = HashSet<String>(models.size)
        for (model in models) {
            if (model.id.isBlank()) return false
            if (!seenIds.add(model.id)) return false
            model.localPath?.takeIf { it.isNotBlank() }?.let { path ->
                if (!seenPaths.add(path)) return false
            }
        }
        return true
    }

    private fun parseImported(raw: String): ImportedCatalogLoad {
        // Malformed catalog data is corruption evidence; cancellation and
        // fatal Error propagate and never become Corrupt (C16).
        val array = try {
            JSONArray(raw)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ImportedCatalogLoad.Corrupt
        }
        if (array.length() > MAX_IMPORTED_ENTRIES) return ImportedCatalogLoad.Corrupt
        // The writer replaces same-identity entries, so duplicate stable IDs
        // prove an out-of-contract artifact. Blank local paths are shared by
        // external links and are exempt from the path check.
        val seenIds = HashSet<String>()
        val seenPaths = HashSet<String>()
        var needsMigration = false
        val models = ArrayList<FusionModelSpec>(array.length())
        for (index in 0 until array.length()) {
            val item = try {
                array.optJSONObject(index) ?: return ImportedCatalogLoad.Corrupt
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ImportedCatalogLoad.Corrupt
            }
            val model = parseImportedEntry(item) ?: return ImportedCatalogLoad.Corrupt
            if (model.id.isBlank()) {
                needsMigration = true
            } else {
                if (!seenIds.add(model.id)) return ImportedCatalogLoad.Corrupt
                model.localPath?.takeIf { it.isNotBlank() }?.let { path ->
                    if (!seenPaths.add(path)) return ImportedCatalogLoad.Corrupt
                }
            }
            models += model
        }
        return ImportedCatalogLoad.Valid(models, needsMigration)
    }

    private fun parseImportedEntry(item: JSONObject): FusionModelSpec? {
        // A malformed entry is corruption evidence (null); cancellation and
        // fatal Error propagate (C16).
        return try {
            val path = item.strictStringOrNull("path").orEmpty()
            val uriString = item.strictStringOrNull("uri")?.ifBlank { null }
            val originalFileName = item.strictStringOrNull("originalFileName")?.ifBlank { null }
            val storedFamily = item.strictStringOrNull("family")
                ?.takeIf { it.isNotBlank() }
                ?.let(ModelFamily::valueOf)
                ?: ModelFamily.CUSTOM
            val storedFileSizeBytes = item.strictLongOrNull("fileSizeBytes")
            val family = resolveImportedFamily(
                fileName = File(path).name.ifBlank { originalFileName.orEmpty() },
                sizeBytes = File(path).takeIf { it.isFile }?.length() ?: storedFileSizeBytes ?: 0L,
                fallback = storedFamily,
            )
            val externallyReferenced = item.strictBooleanOrNull("externallyReferenced")
                ?: (uriString != null && path.isBlank())
            val copiedInternally = item.strictBooleanOrNull("copiedInternally")
                ?: (path.isNotBlank() && !externallyReferenced)
            val format = item.strictStringOrNull("runtimeFormat")?.let(ModelRuntimeFormat::valueOf)
                ?: runtimeFormatForFile(originalFileName ?: path)
            FusionModelSpec(
                id = item.strictStringOrNull("id").orEmpty(),
                displayName = item.strictStringOrNull("displayName").orEmpty(),
                family = family,
                parameterLabel = item.strictStringOrNull("parameterLabel") ?: "Custom",
                runtimeFormat = format,
                availability = availabilityForImported(format),
                memoryClass = ModelMemoryClass.MEDIUM,
                recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
                recommendedMaxTokens8Gb = 2048,
                recommendedMaxTokens12Gb = 4096,
                recommendedMtpEnabled = family == ModelFamily.GEMMA,
                recommendedReasoningEnabled = false,
                supportsVision = false,
                // Imported metadata has no validated reasoning declaration.
                // Family inference is intentionally not capability evidence.
                supportsReasoning = false,
                supportsNpuCandidate = family != ModelFamily.KIMI,
                notes = importedNotes(format, externallyReferenced),
                fileName = originalFileName ?: File(path).name,
                localPath = path.ifBlank { null },
                minRecommendedRamGb = 8,
                recommendedRamGb = 8,
                localExecutionWarning = if (externallyReferenced) {
                    "이 런타임은 Fusion 관리 저장소 경로가 필요할 수 있습니다."
                } else {
                    "가져온 모델입니다. 실제 호환성은 기기에서 확인해야 합니다."
                },
                sourceLabel = if (externallyReferenced) "외부 파일 연결" else "사용자 가져오기",
                originalFileName = originalFileName,
                uriString = uriString,
                fileSizeBytes = storedFileSizeBytes?.takeIf { it > 0L },
                addedAt = item.strictLongOrNull("addedAt")?.takeIf { it > 0L },
                lastCheckedAt = item.strictLongOrNull("lastCheckedAt")?.takeIf { it > 0L },
                externallyReferenced = externallyReferenced,
                copiedInternally = copiedInternally
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.strictStringOrNull(key: String): String? = if (!has(key) || isNull(key)) {
        null
    } else {
        get(key) as? String ?: throw IllegalArgumentException(key)
    }

    private fun JSONObject.strictLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) {
        null
    } else {
        exactLongOrNull(get(key)) ?: throw IllegalArgumentException(key)
    }

    private fun JSONObject.strictBooleanOrNull(key: String): Boolean? = if (!has(key) || isNull(key)) {
        null
    } else {
        get(key) as? Boolean ?: throw IllegalArgumentException(key)
    }

    /**
     * Deterministic replacement for the legacy random-UUID migration: the
     * same file bytes always yield the same identity, so a failed rewrite
     * simply retries the identical migration on the next load. The index is
     * included so near-identical legacy entries cannot collide into a
     * duplicate-ID corruption of our own making.
     */
    private fun deterministicMigratedId(model: FusionModelSpec, index: Int): String {
        val key = listOf(
            model.localPath.orEmpty(),
            model.uriString.orEmpty(),
            model.displayName,
            index.toString(),
        ).joinToString("|")
        return "custom-${UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))}"
    }

    fun saveImported(context: Context, spec: FusionModelSpec): Boolean {
        synchronized(catalogLock) {
            val file = importedMetadataFile(context)
            val base = when (val load = loadImportedResultLocked(file)) {
                is ImportedCatalogLoad.Valid -> load.models
                is ImportedCatalogLoad.Missing -> emptyList()
                is ImportedCatalogLoad.Corrupt,
                is ImportedCatalogLoad.Oversized -> {
                    if (!quarantineCorruptLocked(file)) return false
                    emptyList()
                }
            }
            if (base.size >= MAX_IMPORTED_ENTRIES &&
                base.none { sameImportedIdentity(it, spec) }
            ) {
                return false
            }
            val existingUriOwner = base.firstOrNull {
                it.externallyReferenced && spec.externallyReferenced &&
                    !spec.uriString.isNullOrBlank() && it.uriString == spec.uriString
            }
            val replacement = spec.copy(id = existingUriOwner?.id ?: spec.id)
            val updated = (base.filterNot { sameImportedIdentity(it, spec) } + replacement)
                .mapIndexed { index, model ->
                    if (model.id.isNotBlank()) model
                    else model.copy(id = deterministicMigratedId(model, index))
                }
            return writeModelsLocked(file, updated)
        }
    }

    private fun sameImportedIdentity(first: FusionModelSpec, second: FusionModelSpec): Boolean =
        first.id == second.id ||
            (!second.localPath.isNullOrBlank() && first.localPath == second.localPath) ||
            (first.externallyReferenced && second.externallyReferenced &&
                !second.uriString.isNullOrBlank() && first.uriString == second.uriString)

    /** Catalog mutations and last-owner URI release share the same lock. */
    internal fun releaseUriPermissionIfUnowned(context: Context, uri: String, release: () -> Unit): Boolean =
        synchronized(catalogLock) {
            when (val load = loadImportedResultLocked(importedMetadataFile(context))) {
                is ImportedCatalogLoad.Valid -> if (load.models.any { it.externallyReferenced && it.uriString == uri }) return@synchronized false
                is ImportedCatalogLoad.Missing -> Unit
                is ImportedCatalogLoad.Corrupt, is ImportedCatalogLoad.Oversized -> return@synchronized false
            }
            release()
            true
        }

    fun runtimeFormatForFile(pathOrName: String): ModelRuntimeFormat {
        val lower = pathOrName.lowercase()
        return when {
            lower.endsWith(".litertlm") -> ModelRuntimeFormat.LITERT_LM
            lower.endsWith(".task") -> ModelRuntimeFormat.MEDIAPIPE_LLM
            lower.endsWith(".gguf") -> ModelRuntimeFormat.GGUF
            lower.endsWith(".onnx") -> ModelRuntimeFormat.ONNX
            lower.endsWith(".safetensors") -> ModelRuntimeFormat.NEEDS_CONVERSION
            lower.endsWith(".bin") -> ModelRuntimeFormat.UNKNOWN
            else -> ModelRuntimeFormat.UNKNOWN
        }
    }

    fun removeImported(context: Context, spec: FusionModelSpec): Boolean {
        synchronized(catalogLock) {
            val file = importedMetadataFile(context)
            val base = when (val load = loadImportedResultLocked(file)) {
                is ImportedCatalogLoad.Valid -> load.models
                is ImportedCatalogLoad.Missing -> return true
                // Fail closed: without readable metadata the wrong entry
                // could be removed and binaries are never touched blindly.
                is ImportedCatalogLoad.Corrupt,
                is ImportedCatalogLoad.Oversized -> return false
            }
            return writeModelsLocked(file, base.filterNot { it.id == spec.id })
        }
    }

    private fun loadImportedResultLocked(file: File): ImportedCatalogLoad {
        if (!catalogFileIo.hasArtifact(file)) return ImportedCatalogLoad.Missing
        var oversizedEvidence = false

        fun readBounded(candidate: File, read: (File) -> ByteArray?): ByteArray? {
            if (!candidate.isFile) return null
            // Recoverable length/read failures fail closed (null);
            // cancellation and fatal Error propagate (C16).
            val length = try {
                candidate.length()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
            if (length > MAX_IMPORTED_CATALOG_BYTES) {
                oversizedEvidence = true
                return null
            }
            val bytes = try {
                read(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            } ?: return null
            if (bytes.size.toLong() > MAX_IMPORTED_CATALOG_BYTES) {
                oversizedEvidence = true
                return null
            }
            return bytes
        }

        // Prefer the primary file, but a corrupt-but-readable primary must
        // still fall back to a valid backup instead of bricking the catalog.
        // No automatic heal-write here: the next successful save rotates both
        // files through AtomicFile anyway.
readBounded(file, catalogFileIo::readPrimary)?.let { primary ->
            // Strict UTF-8: any malformed byte sequence fails the load (H10).
            // A primary with invalid UTF-8 is treated as Corrupt (not silently
            // replaced with U+FFFD), so it falls back to backup or fails closed.
            val decoded = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(primary))
                    .toString()
            } catch (e: java.nio.charset.MalformedInputException) {
                return ImportedCatalogLoad.Corrupt
            } catch (e: java.nio.charset.UnmappableCharacterException) {
                return ImportedCatalogLoad.Corrupt
            }
            when (val parsed = parseImported(decoded)) {
                is ImportedCatalogLoad.Valid -> return parsed
                is ImportedCatalogLoad.Missing -> return parsed
                is ImportedCatalogLoad.Corrupt -> Unit
                is ImportedCatalogLoad.Oversized -> oversizedEvidence = true
            }
        }
readBounded(backupFile(file)) { catalogFileIo.readBackup(file) }?.let { backup ->
            // Strict UTF-8 for backup too (H10).
            val decoded = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(backup))
                    .toString()
            } catch (e: java.nio.charset.MalformedInputException) {
                return ImportedCatalogLoad.Corrupt
            } catch (e: java.nio.charset.UnmappableCharacterException) {
                return ImportedCatalogLoad.Corrupt
            }
            when (val parsed = parseImported(decoded)) {
                is ImportedCatalogLoad.Valid -> return parsed
                is ImportedCatalogLoad.Missing -> return parsed
                is ImportedCatalogLoad.Corrupt -> Unit
                is ImportedCatalogLoad.Oversized -> oversizedEvidence = true
            }
        }
        return if (oversizedEvidence) ImportedCatalogLoad.Oversized
        else ImportedCatalogLoad.Corrupt
    }

    /**
     * Bounded evidence preservation: copies the unreadable raw bytes into a
     * single quarantine file before any replacement write. Returns false when
     * the evidence cannot be secured, leaving the original intact.
     */
    private fun quarantineCorruptLocked(file: File): Boolean {
        // Evidence-preservation failure fails closed (false); cancellation
        // and fatal Error propagate instead of reading as quarantine success
        // or failure states (C16).
        return try {
            // Secure whichever corrupt artifacts actually constitute the
            // catalog evidence. The primary may be absent while a corrupt
            // `.bak` is the only durable evidence: the mutation must still be
            // able to proceed once every present artifact is secured.
            var allSecured = true
            var evidencePresent = false
            if (file.isFile) {
                evidencePresent = true
                allSecured = allSecured && when (DebtQuarantine.quarantine(
                    source = file,
                    suffix = CatalogQuarantineSuffix,
                )) {
                    is DebtQuarantineOutcome.Secured -> true
                    DebtQuarantineOutcome.SourceMissing -> false
                    DebtQuarantineOutcome.Failed -> false
                }
            }
            val backup = backupFile(file)
            if (backup.isFile) {
                evidencePresent = true
                allSecured = allSecured && when (DebtQuarantine.quarantine(
                    source = backup,
                    suffix = CatalogQuarantineSuffix,
                )) {
                    is DebtQuarantineOutcome.Secured -> true
                    DebtQuarantineOutcome.SourceMissing -> false
                    DebtQuarantineOutcome.Failed -> false
                }
            }
            evidencePresent && allSecured
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Exact writer: serializes every field verbatim through the crash-safe
     * file IO and preserves known-good bytes when the mutation fails.
     * Returns false instead of throwing.
     */
    private fun writeModelsLocked(file: File, models: List<FusionModelSpec>): Boolean {
        // Self-validating writer: the written bytes must always be read back
        // as Valid by the next loader, so every mutation validates the FINAL
        // representation with the shared production invariants first.
        if (!isValidImportedCatalog(models)) return false
        val array = JSONArray()
        models.forEach { model ->
            array.put(JSONObject().apply {
                put("id", model.id)
                put("displayName", model.displayName)
                put("family", model.family.name)
                put("parameterLabel", model.parameterLabel)
                put("path", model.localPath.orEmpty())
                put("runtimeFormat", model.runtimeFormat.name)
                put("originalFileName", model.originalFileName ?: model.fileName.orEmpty())
                put("uri", model.uriString.orEmpty())
                put("fileSizeBytes", model.fileSizeBytes ?: 0L)
                put("addedAt", model.addedAt ?: System.currentTimeMillis())
                put("lastCheckedAt", model.lastCheckedAt ?: System.currentTimeMillis())
                put("externallyReferenced", model.externallyReferenced)
                put("copiedInternally", model.copiedInternally)
            })
        }
        val encoded = array.toString(2).toByteArray(Charsets.UTF_8)
        if (encoded.size.toLong() > MAX_IMPORTED_CATALOG_BYTES) return false
        // Recoverable write failure returns false; cancellation and fatal
        // Error propagate (C16).
        return try {
            catalogFileIo.writeArtifact(file, encoded)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    fun importedSpec(displayName: String, path: String, family: ModelFamily): FusionModelSpec {
        val format = runtimeFormatForFile(path)
        val resolvedFamily = resolveImportedFamily(File(path).name, File(path).length(), family)
        return FusionModelSpec(
            id = "custom-${UUID.randomUUID()}",
            displayName = displayName.ifBlank { "Custom Model" },
            family = resolvedFamily,
            parameterLabel = "Custom",
            runtimeFormat = format,
            availability = availabilityForImported(format),
            memoryClass = ModelMemoryClass.MEDIUM,
            recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
            recommendedMaxTokens8Gb = 2048,
            recommendedMaxTokens12Gb = 4096,
            recommendedMtpEnabled = resolvedFamily == ModelFamily.GEMMA,
            recommendedReasoningEnabled = false,
            supportsVision = false,
            supportsReasoning = false,
            supportsNpuCandidate = resolvedFamily != ModelFamily.KIMI,
            notes = importedNotes(format, externallyReferenced = false),
            fileName = File(path).name,
            localPath = path,
            minRecommendedRamGb = 8,
            recommendedRamGb = 8,
            localExecutionWarning = "가져온 모델입니다. 실제 호환성은 기기에서 확인해야 합니다.",
            sourceLabel = "사용자 가져오기",
            originalFileName = File(path).name,
            addedAt = System.currentTimeMillis(),
            lastCheckedAt = System.currentTimeMillis(),
            externallyReferenced = false,
            copiedInternally = true
        )
    }

    fun externalLinkedSpec(displayName: String, originalFileName: String, uriString: String, fileSizeBytes: Long?, family: ModelFamily): FusionModelSpec {
        val format = runtimeFormatForFile(originalFileName)
        val resolvedFamily = if (family == ModelFamily.KANANA || originalFileName.contains("kanana", ignoreCase = true)) {
            ModelFamily.KANANA
        } else {
            family
        }
        return FusionModelSpec(
            id = "external-${UUID.randomUUID()}",
            displayName = displayName.ifBlank { originalFileName.ifBlank { "External Model" } },
            family = resolvedFamily,
            parameterLabel = "Custom",
            runtimeFormat = format,
            availability = availabilityForImported(format),
            memoryClass = ModelMemoryClass.MEDIUM,
            recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
            recommendedMaxTokens8Gb = 2048,
            recommendedMaxTokens12Gb = 4096,
            recommendedMtpEnabled = resolvedFamily == ModelFamily.GEMMA,
            recommendedReasoningEnabled = false,
            supportsVision = false,
            supportsReasoning = false,
            supportsNpuCandidate = resolvedFamily != ModelFamily.KIMI,
            notes = importedNotes(format, externallyReferenced = true),
            fileName = originalFileName,
            minRecommendedRamGb = 8,
            recommendedRamGb = 8,
            localExecutionWarning = "이 런타임은 Fusion 관리 저장소 경로가 필요할 수 있습니다.",
            sourceLabel = "외부 파일 연결",
            originalFileName = originalFileName,
            uriString = uriString,
            fileSizeBytes = fileSizeBytes,
            addedAt = System.currentTimeMillis(),
            lastCheckedAt = System.currentTimeMillis(),
            externallyReferenced = true,
            copiedInternally = false
        )
    }

    internal const val ImportedMetadataFileName = "fusion_imported_models.json"

    private fun importedMetadataFile(context: Context): File = File(context.filesDir, ImportedMetadataFileName)

    private fun inferSpec(value: String): FusionModelSpec? {
        val family = inferFamilyFromText(value)
        if (family == ModelFamily.CUSTOM) return null
        return FusionModelSpec(
            id = value.lowercase().replace(" ", "-"),
            displayName = value,
            family = family,
            parameterLabel = "Custom",
            runtimeFormat = ModelRuntimeFormat.UNKNOWN,
            availability = ModelAvailability.CUSTOM_IMPORTED,
            memoryClass = ModelMemoryClass.MEDIUM,
            recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
            recommendedMaxTokens8Gb = 2048,
            recommendedMaxTokens12Gb = 4096,
            recommendedMtpEnabled = family == ModelFamily.GEMMA,
            recommendedReasoningEnabled = false,
            supportsVision = false,
            supportsReasoning = false,
            supportsNpuCandidate = family != ModelFamily.KIMI,
            notes = "이름에서 모델 패밀리를 추정했습니다.",
            minRecommendedRamGb = 8,
            recommendedRamGb = 8
        )
    }
}

private fun availabilityForImported(format: ModelRuntimeFormat): ModelAvailability = when (format) {
    ModelRuntimeFormat.LITERT_LM -> ModelAvailability.CUSTOM_IMPORTED
    ModelRuntimeFormat.NEEDS_CONVERSION -> ModelAvailability.NEEDS_CONVERSION
    else -> ModelAvailability.UNSUPPORTED_ON_DEVICE
}

private fun importedNotes(format: ModelRuntimeFormat, externallyReferenced: Boolean): String = when (format) {
    ModelRuntimeFormat.LITERT_LM -> if (externallyReferenced) {
        "외부 파일로 연결되었습니다. 로컬 실행 전 Fusion 관리 저장소로 가져와야 할 수 있습니다."
    } else {
        "가져온 모델입니다."
    }
    ModelRuntimeFormat.MEDIAPIPE_LLM -> "현재 빌드에는 MediaPipe LLM 실행 엔진이 없어 .task 파일을 직접 실행할 수 없습니다."
    ModelRuntimeFormat.NEEDS_CONVERSION -> "이 파일은 변환이 필요합니다."
    else -> "이 파일은 현재 직접 실행할 수 없습니다."
}

fun FusionModelSpec.statusLabel(localAvailable: Boolean): String = when {
    localAvailable -> "사용 가능"
    availability == ModelAvailability.NEEDS_CONVERSION -> "변환 필요"
    availability == ModelAvailability.NEEDS_DOWNLOAD -> "다운로드 필요"
    availability == ModelAvailability.REMOTE_ONLY -> "원격 전용"
    availability == ModelAvailability.UNSUPPORTED_ON_DEVICE -> "지원되지 않음"
    availability == ModelAvailability.CUSTOM_IMPORTED -> "사용 가능"
    else -> "사용 가능"
}

fun FusionModelSpec.deviceLabel(): String = when (recommendedDeviceClass) {
    ModelRecommendedDeviceClass.RAM_8GB_SAFE -> "8GB 권장"
    ModelRecommendedDeviceClass.RAM_12GB_RECOMMENDED -> "12GB 권장"
    ModelRecommendedDeviceClass.RAM_16GB_RECOMMENDED -> "16GB 권장"
    ModelRecommendedDeviceClass.SERVER_ONLY -> "서버 전용"
}

fun FusionModelSpec.recommendedMaxTokensFor8GbLabel(): String = if (recommendedMaxTokens8Gb > 0) "maxTokens ${recommendedMaxTokens8Gb}" else "서버 설정 필요"

fun FusionModelSpec.applyRecommendedSettings(editor: android.content.SharedPreferences.Editor) {
    if (recommendedMaxTokens8Gb > 0) editor.putInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, recommendedMaxTokens8Gb)
    editor.putBoolean("speculative_decoding_enabled", recommendedMtpEnabled)
    editor.putBoolean("reasoning_enabled", recommendedReasoningEnabled)
    if (runtimeFormat == ModelRuntimeFormat.EXYNOS_AI_STUDIO) editor.putString("accelerator", AcceleratorMode.AUTO.name)
}
