package com.example.aicode.settings

data class PiModelPreset(
    val id: String,
    val title: String,
    val description: String,
    val providerId: String = "opencode-zen",
    val baseUrl: String = "https://opencode.ai/zen/v1",
    val apiType: String = "openai-completions",
)

/** Curated free coding models exposed by OpenCode Zen (checked 2026-08-09). */
object PiModelCatalog {
    val openCodeZenFree = listOf(
        PiModelPreset("deepseek-v4-flash-free", "DeepSeek V4 Flash Free", "Быстрый дефолт для повседневных правок."),
        PiModelPreset("mimo-v2.5-free", "MiMo V2.5 Free", "Бесплатная универсальная модель."),
        PiModelPreset("laguna-s-2.1-free", "Laguna S 2.1 Free", "Бесплатная модель из каталога Zen."),
        PiModelPreset("ling-3.0-tiny-free", "Ling 3.0 Tiny Free", "Самый лёгкий вариант для коротких задач."),
        PiModelPreset("longcat-2.0-free", "LongCat 2.0 Free", "Бесплатный вариант с длинным контекстом."),
        PiModelPreset("north-mini-code-free", "North Mini Code Free", "Небольшая модель специально для кода."),
        PiModelPreset("nemotron-3-ultra-free", "Nemotron 3 Ultra Free", "Бесплатный trial endpoint NVIDIA."),
    )

    val nvidiaNim = listOf(
        PiModelPreset(
            id = "thinkingmachines/inkling",
            title = "Inkling",
            description = "Thinking Machines reasoning model через NVIDIA NIM.",
            providerId = "nvidia-nim",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            apiType = "openai-completions",
        ),
        PiModelPreset(
            id = "z-ai/glm-5.2",
            title = "GLM 5.2",
            description = "Agentic coding model через NVIDIA NIM.",
            providerId = "nvidia-nim",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            apiType = "openai-completions",
        ),
    )

    val available: List<PiModelPreset> = openCodeZenFree + nvidiaNim

    val default: PiModelPreset = openCodeZenFree.first()
}
