package org.jetbrains.influxdb

import org.jetbrains.report.*
import org.jetbrains.report.json.*

data class Commit(val revision: String, val developer: String) {
    override fun toString() = "$revision by $developer"
}

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

    override fun toString(): String =
        commits.toString()
}

data class BuildInfo(val number: String, val startTime: String, val endTime: String, val commitsList: CommitsList,
                     val branch: String)

class GoldenResultMeasurement(name: String, metric: String, score: Double) : Measurement("goldenResults") {
    var benchmarkName by Tag<String>("benchmark.name")
    var benchmarkScore by Field<FieldType.InfluxFloat>("benchmark.score")
    var benchmarkMetric by Tag<String>("benchmark.metric")

    init {
        benchmarkName = name
        benchmarkScore = FieldType.InfluxFloat(score)
        benchmarkMetric = metric
    }
}

class BenchmarkMeasurement : Measurement("benchmarks") {

    fun initBuildInfo(buildInfo: BuildInfo) {
        buildNumber = FieldType.InfluxString(buildInfo.number)
        buildBranch = FieldType.InfluxString(buildInfo.branch)
        buildCommits = FieldType.InfluxString(buildInfo.commitsList)
        buildStartTime = FieldType.InfluxString(buildInfo.startTime)
        buildEndTime = FieldType.InfluxString(buildInfo.endTime)
    }

    companion object: EntityFromJsonFactory<List<BenchmarkMeasurement>> {
        fun fromReport(benchmarksReport: BenchmarksReport, buildInfo: BuildInfo?): List<BenchmarkMeasurement> {
            val points = mutableListOf<BenchmarkMeasurement>()
            benchmarksReport.benchmarks.forEach { (name, results) ->
                results.forEach {
                    val point = BenchmarkMeasurement()
                    point.envMachineCpu = benchmarksReport.env.machine.cpu
                    point.envMachineOs = benchmarksReport.env.machine.os
                    point.envJDKVendor = FieldType.InfluxString(benchmarksReport.env.jdk.vendor)
                    point.envJDKVersion = FieldType.InfluxString(benchmarksReport.env.jdk.version)

                    point.kotlinBackendType = benchmarksReport.compiler.backend.type.toString()
                    point.kotlinBackendVersion = FieldType.InfluxString(benchmarksReport.compiler.backend.version)
                    point.kotlinBackendFlags = benchmarksReport.compiler.backend.flags
                    point.kotlinVersion = FieldType.InfluxString(benchmarksReport.compiler.kotlinVersion)

                    point.benchmarkName = name
                    point.benchmarkStatus = FieldType.InfluxString(it.status)
                    point.benchmarkScore = FieldType.InfluxFloat(it.score)
                    point.benchmarkMetric = it.metric.value
                    point.benchmarkRuntime = FieldType.InfluxFloat(it.runtimeInUs)
                    point.benchmarkRepeat = FieldType.InfluxInt(it.repeat)
                    point.benchmarkWarmup = FieldType.InfluxInt(it.warmup)
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

        fun create(data: JsonElement, buildInfo: BuildInfo? = null): List<BenchmarkMeasurement> {
            val results = create(data)
            buildInfo?.let {
                results.forEach {
                    it.initBuildInfo(buildInfo)
                }
            }
            return results
        }

        override fun create(data: JsonElement): List<BenchmarkMeasurement> {
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
                                point.envJDKVendor = FieldType.InfluxString(jdkVendor)
                                point.envJDKVersion = FieldType.InfluxString(jdkVersion)

                                point.kotlinBackendType = type
                                point.kotlinBackendVersion = FieldType.InfluxString(version)
                                point.kotlinBackendFlags = flags
                                point.kotlinVersion = FieldType.InfluxString(kotlinVersion)

                                point.benchmarkName = name
                                point.benchmarkStatus = FieldType.InfluxString(status)
                                point.benchmarkScore = FieldType.InfluxFloat(score)
                                point.benchmarkMetric = metric
                                point.benchmarkRuntime = FieldType.InfluxFloat(runtimeInUs)
                                point.benchmarkRepeat = FieldType.InfluxInt(repeat)
                                point.benchmarkWarmup = FieldType.InfluxInt(warmup)
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
    var envJDKVersion by Field<FieldType.InfluxString>("environment.jdk.version")
    var envJDKVendor by Field<FieldType.InfluxString>("environment.jdk.vendor")

    // Kotlin information.
    // Backend.
    var kotlinBackendType by Tag<String>("kotlin.backend.type")
    var kotlinBackendVersion by Field<FieldType.InfluxString>("kotlin.backend.version")
    var kotlinBackendFlags by Tag<List<String>>("kotlin.backend.flags")
    var kotlinVersion by Field<FieldType.InfluxString>("kotlin.kotlinVersion")

    // Benchmark data.
    var benchmarkName by Tag<String>("benchmark.name")
    var benchmarkStatus by Field<FieldType.InfluxString>("benchmark.status")
    var benchmarkScore by Field<FieldType.InfluxFloat>("benchmark.score")
    var benchmarkMetric by Tag<String>("benchmark.metric")
    var benchmarkRuntime by Field<FieldType.InfluxFloat>("benchmark.runtimeInUs")
    var benchmarkRepeat by Field<FieldType.InfluxInt>("benchmark.repeat")
    var benchmarkWarmup by Field<FieldType.InfluxInt>("benchmark.warmup")

    // Build information (from CI).
    var buildNumber by Field<FieldType.InfluxString>("build.number")
    var buildStartTime by Field<FieldType.InfluxString>("build.startTime")
    var buildEndTime by Field<FieldType.InfluxString>("build.endTime")
    var buildCommits by Field<FieldType.InfluxString>("build.commits")
    var buildBranch by Field<FieldType.InfluxString>("build.branch")
}