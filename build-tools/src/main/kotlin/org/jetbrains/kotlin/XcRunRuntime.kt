package org.jetbrains.kotlin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.Xcode

fun Xcode.getRuntimeDescriptors(): List<SimulatorRuntimeDescriptor> =
     Json.Companion.nonstrict.parse(ListRuntimesReport.serializer(), this.runtimes).runtimes

fun Xcode.getLatestSimulatorRuntimeFor(target: KonanTarget, version: String): SimulatorRuntimeDescriptor? {
    val runtimes = getRuntimeDescriptors()
    val osName = when (target) {
        KonanTarget.IOS_X64 -> "iOS"
        KonanTarget.WATCHOS_X64 -> "watchOS"
        KonanTarget.TVOS_X64 -> "tvOS"
        else -> error("Unexpected simulator target: $target")
    }
    return runtimes.firstOrNull { it.isAvailable && it.name == "$osName $version"}
}

// Result of `xcrun simctl list runtimes -j`
@Serializable
data class ListRuntimesReport(
        val runtimes: List<SimulatorRuntimeDescriptor>
)

@Serializable
data class SimulatorRuntimeDescriptor(
        val version: String,
        val bundlePath: String,
        val isAvailable: Boolean,
        val name: String,
        val identifier: String,
        val buildversion: String
)
