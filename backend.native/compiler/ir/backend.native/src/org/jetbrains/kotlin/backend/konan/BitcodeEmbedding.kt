package org.jetbrains.kotlin.backend.konan


import org.jetbrains.kotlin.konan.target.CompilerOutputKind

object BitcodeEmbedding {

    enum class Mode {
        NONE, MARKER, FULL
    }

    internal fun getLinkerOptions(config: KonanConfig): List<String> = when (config.bitcodeEmbeddingMode) {
        Mode.NONE -> emptyList()
        Mode.MARKER -> listOf("-bitcode_bundle", "-bitcode_process_mode", "marker")
        Mode.FULL -> listOf("-bitcode_bundle")
    }

    internal fun getClangOptions(config: KonanConfig): List<String> = when (config.bitcodeEmbeddingMode) {
        Mode.NONE -> listOf("-fembed-bitcode=off")
        Mode.MARKER -> listOf("-fembed-bitcode=marker")
        Mode.FULL -> listOf("-fembed-bitcode=all")
    }

    private val KonanConfig.bitcodeEmbeddingMode: Mode
        get() = configuration.get(KonanConfigKeys.BITCODE_EMBEDDING_MODE)!!.also {
            // TODO: We cannot produce programs due to `-alias` linked flag.
            require(it == Mode.NONE || this.produce == CompilerOutputKind.FRAMEWORK) {
                "${it.name.toLowerCase()} bitcode embedding mode is not supported when producing ${this.produce.name.toLowerCase()}"
            }
        }
}