package org.jetbrains.influxdb

import org.jetbrains.report.*
import org.jetbrains.report.json.*

data class Commit(val revision: String, val developer: String)

// List of commits.
class CommitsList(data: JsonElement): ConvertedFromJson {

    val commits: List<Commit>

    init {
        if (data !is JsonObject) {
            error("Commits description is expected to be a json object!")
        }
        val changesElement = data.getOptionalField("change")
        commits = changesElement?.let {
            if (changesElement !is JsonArray) {
                error("Change field is expected to be an array. Please, check source.")
            }
            changesElement.jsonArray.map {
                with(it as JsonObject) {
                    Commit(elementToString(getRequiredField("version"), "version"),
                            elementToString(getRequiredField("username"), "username")
                    )
                }
            }
        } ?: listOf<Commit>()
    }
}

data class BuildInfo(val number: String, val startTime: String, val endTime: String, val commitsList: CommitsList,
                     val branch: String)

class BenchmarkMeasurement : Measurement("benchmarks") {

    fun initBuildInfo(buildInfo: BuildInfo) {
        buildNumber = buildInfo.number
        buildBranch = buildInfo.branch
        buildCommits = buildInfo.commitsList
        buildStartTime = buildInfo.startTime
        buildEndTime = buildInfo.endTime
    }

    companion object: EntityFromJsonFactory<List<BenchmarkMeasurement>> {
        fun fromReport(benchmarksReport: BenchmarksReport, buildInfo: BuildInfo?): List<BenchmarkMeasurement> {
            val points = mutableListOf<BenchmarkMeasurement>()
            benchmarksReport.benchmarks.forEach { (name, results) ->
                results.forEach {
                    val point = BenchmarkMeasurement()
                    point.envMachineCpu = benchmarksReport.env.machine.cpu
                    point.envMachineOs = benchmarksReport.env.machine.os
                    point.envJDKVendor = benchmarksReport.env.jdk.vendor
                    point.envJDKVersion = benchmarksReport.env.jdk.version

                    point.kotlinBackendType = benchmarksReport.compiler.backend.type.toString()
                    point.kotlinBackendVersion = benchmarksReport.compiler.backend.version
                    point.kotlinBackendFlags = benchmarksReport.compiler.backend.flags
                    point.kotlinVersion = benchmarksReport.compiler.kotlinVersion

                    point.benchmarkName = name
                    point.benchmarkStatus = it.status.toString()
                    point.benchmarkScore = it.score
                    point.benchmarkMetric = it.metric.value
                    point.benchmarkRuntime = it.runtimeInUs
                    point.benchmarkRepeat = it.repeat
                    point.benchmarkWarmup = it.warmup
                    buildInfo?.let {
                        point.initBuildInfo(buildInfo)
                    }
                    points.add(point)
                }
            }
            return points
        }

        fun List<BenchmarkMeasurement>.toReport(): BenchmarksReport {
            TODO()
        }

        override fun create(data: JsonElement, buildInfo: BuildInfo? = null): List<BenchmarkMeasurement> {
            val points = mutableListOf<BenchmarkMeasurement>()
            if (data is JsonObject) {
                val env = data.getRequiredField("env") as JsonObject
                val machine = env.getRequiredField("machine") as JsonObject
                val cpu = elementToString(machine.getRequiredField("cpu"), "cpu")
                val os = elementToString(machine.getRequiredField("os"), "os")

                val jdk = env.getRequiredField("jdk") as JsonObject
                val jdkVersion = elementToString(jdk.getRequiredField("version"), "version")
                val jdkVendor = elementToString(jdk.getRequiredField("vendor"), "vendor")
                val benchmarksObj = data.getRequiredField("benchmarks")
                val compiler = data.getRequiredField("kotlin") as JsonObject
                val backend = compiler.getRequiredField("backend") as JsonObject
                val typeElement = backend.getRequiredField("type") as JsonLiteral
                val type = typeElement.unquoted()
                val version = elementToString(backend.getRequiredField("version"), "version")
                val flagsArray = backend.getOptionalField("flags")
                var flags: List<String> = emptyList()
                if (flagsArray != null && flagsArray is JsonArray) {
                    flags = flagsArray.jsonArray.map { it.toString() }
                }
                val kotlinVersion = elementToString(compiler.getRequiredField("kotlinVersion"), "kotlinVersion")
                if (benchmarksObj is JsonArray) {
                    benchmarksObj.jsonArray.map {
                        if (it is JsonObject) {
                            val name = elementToString(it.getRequiredField("name"), "name")
                            val metricElement = it.getOptionalField("metric")
                            val metric = if (metricElement != null && metricElement is JsonLiteral)
                                metricElement.unquoted()
                            else "EXECUTION_TIME"
                            val statusElement = it.getRequiredField("status")
                            if (statusElement is JsonLiteral) {
                                val status = statusElement.unquoted()
                                val score = elementToDouble(it.getRequiredField("score"), "score")
                                val runtimeInUs = elementToDouble(it.getRequiredField("runtimeInUs"), "runtimeInUs")
                                val repeat = elementToInt(it.getRequiredField("repeat"), "repeat")
                                val warmup = elementToInt(it.getRequiredField("warmup"), "warmup")

                                val point = BenchmarkMeasurement()
                                point.envMachineCpu = cpu
                                point.envMachineOs = os
                                point.envJDKVendor = jdkVendor
                                point.envJDKVersion = jdkVersion

                                point.kotlinBackendType = type
                                point.kotlinBackendVersion = version
                                point.kotlinBackendFlags = flags
                                point.kotlinVersion = kotlinVersion

                                point.benchmarkName = name
                                point.benchmarkStatus = status
                                point.benchmarkScore = score
                                point.benchmarkMetric = metric
                                point.benchmarkRuntime = runtimeInUs
                                point.benchmarkRepeat = repeat
                                point.benchmarkWarmup = warmup
                                buildInfo?.let {
                                    point.initBuildInfo(buildInfo)
                                }
                                points.add(point)
                            } else {
                                error("Status should be string literal.")
                            }
                        } else {
                            error("Benchmark entity is expected to be an object. Please, check origin files.")
                        }
                    }
                } else {
                    error("Benchmarks field is expected to be an array. Please, check origin files.")
                }
            } else {
                error("Top level entity is expected to be an object. Please, check origin files.")
            }
            return points
        }
    }
    // Environment.
    // Machine.
    var envMachineCpu by Tag<String>("environment.machine.cpu")
    var envMachineOs by Tag<String>("environment.machine.os")
    // JDK.
    var envJDKVersion by Field<String>("environment.jdk.version")
    var envJDKVendor by Field<String>("environment.jdk.vendor")

    // Kotlin information.
    // Backend.
    var kotlinBackendType by Tag<String>("kotlin.backend.type")
    var kotlinBackendVersion by Field<String>("kotlin.backend.version")
    var kotlinBackendFlags by Tag<List<String>>("kotlin.backend.flags")
    var kotlinVersion by Field<String>("kotlin.kotlinVersion")

    // Benchmark data.
    var benchmarkName by Tag<String>("benchmark.name")
    var benchmarkStatus by Field<String>("benchmark.status")
    var benchmarkScore by Field<Double>("benchmark.score")
    var benchmarkMetric by Tag<String>("benchmark.metric")
    var benchmarkRuntime by Field<Double>("benchmark.runtimeInUs")
    var benchmarkRepeat by Field<Int>("benchmark.repeat")
    var benchmarkWarmup by Field<Int>("benchmark.warmup")

    // Build information (from CI).
    var buildNumber by Field<String>("build.number")
    var buildStartTime by Field<String>("build.startTime")
    var buildEndTime by Field<String>("build.endTime")
    var buildCommits by Field<CommitsList>("build.commits")
    var buildBranch by Field<String>("build.branch")
}