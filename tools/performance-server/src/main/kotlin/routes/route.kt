/*
 * Copyright 2010-2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.w3c.xhr.*
import kotlin.js.json
import kotlin.js.Date
import kotlin.js.Promise
import org.jetbrains.report.json.*
import org.jetbrains.influxdb.*
import org.jetbrains.build.Build

const val teamCityUrl = "https://buildserver.labs.intellij.net/app/rest"
const val downloadBintrayUrl = "https://dl.bintray.com/content/lepilkinaelena/KotlinNativePerformance"
const val uploadBintrayUrl = "https://api.bintray.com/content/lepilkinaelena/KotlinNativePerformance"
const val buildsFileName = "buildsSummary.csv"
const val goldenResultsFileName = "goldenResults.csv"
const val bintrayPackage = "builds"
const val buildsInfoPartsNumber = 11

operator fun <K, V> Map<K, V>?.get(key: K) = this?.get(key)

data class GoldenResult(val benchmarkName: String, val metric: String, val value: Double)
data class GoldenResultsInfo(val goldenResults: Array<GoldenResult>)

// Build information provided from request.
data class TCBuildInfo(val buildNumber: String, val branch: String, val startTime: String,
                       val finishTime: String)

data class BuildRegister(val buildId: String, val teamCityUser: String, val teamCityPassword: String,
                    val bundleSize: String?) {
    companion object {
        fun create(json: String): BuildRegister {
            val requestDetails = JSON.parse<BuildRegister>(json)
            // Parse method doesn't create real instance with all methods. So create it by hands.
            return BuildRegister(requestDetails.buildId, requestDetails.teamCityUser, requestDetails.teamCityPassword,
                    requestDetails.bundleSize)
        }
    }

    private val teamCityBuildUrl: String by lazy { "$teamCityUrl/builds/id:$buildId" }

    val changesListUrl: String by lazy {
        "$teamCityUrl/changes/?locator=build:id:$buildId"
    }

    private val fileWithResults = "nativeReport.json"

    val teamCityArtifactsUrl: String by lazy { "$teamCityUrl/builds/id:$buildId/artifacts/content/$fileWithResults" }

    fun sendTeamCityRequest(url: String, json: Boolean = false) = sendRequest(RequestMethod.GET, url, teamCityUser, teamCityPassword, json)

    private fun format(timeValue: Int): String =
            if (timeValue < 10) "0$timeValue" else "$timeValue"

    fun getBuildInformation(): Promise<TCBuildInfo> {
        return Promise.all(arrayOf(sendTeamCityRequest("$teamCityBuildUrl/number"),
                sendTeamCityRequest("$teamCityBuildUrl/branchName"),
                sendTeamCityRequest("$teamCityBuildUrl/startDate"))).then { results ->
            val (buildNumber, branch, startTime) = results
            val currentTime = Date()
            val timeZone = currentTime.getTimezoneOffset() / -60    // Convert to hours.
            // Get finish time as current time, because buid on TeamCity isn't finished.
            val finishTime = "${format(currentTime.getUTCFullYear())}" +
                    "${format(currentTime.getUTCMonth() + 1)}" +
                    "${format(currentTime.getUTCDate())}" +
                    "T${format(currentTime.getUTCHours())}" +
                    "${format(currentTime.getUTCMinutes())}" +
                    "${format(currentTime.getUTCSeconds())}" +
                    "${if (timeZone > 0) "+" else "-"}${format(timeZone)}${format(0)}"
            TCBuildInfo(buildNumber, branch, startTime, finishTime)
        }
    }
}

fun checkBuildType(currentType: String, targetType: String): Boolean {
    val releasesBuildTypes = listOf("release", "eap", "rc1", "rc2")
    return if (targetType == "release") currentType in releasesBuildTypes else currentType == targetType
}

// Parse  and postprocess result of response with build description.
fun prepareBuildsResponse(builds: Collection<String>, type: String, branch: String, buildNumber: String? = null): List<Build> {
    val buildsObjects = mutableListOf<Build>()
    builds.forEach {
        val tokens = buildDescriptionToTokens(it)
        if ((checkBuildType(tokens[5], type) || type == "day") && (branch == tokens[3] || branch == "all")
                || tokens[0] == buildNumber) {
            buildsObjects.add(Build(tokens[0], tokens[1], tokens[2], tokens[3],
                    tokens[4], tokens[5], tokens[6].toInt(), tokens[7], tokens[8], tokens[9],
                    if (tokens[10] == "-") null else tokens[10]))
        }
    }
    return buildsObjects
}

fun buildDescriptionToTokens(buildDescription: String): List<String> {
    val tokens = buildDescription.split(",").map { it.trim() }
    if (tokens.size != buildsInfoPartsNumber) {
        error("Build description $buildDescription doesn't contain all necessary information. " +
                "File with data could be corrupted.")
    }
    return tokens
}

// Routing of requests to current server.
fun router() {
    val express = require("express")
    val router = express.Router()
    InfluxDBConnector.initConnection("https://biff-9a16f218.influxcloud.net", "kotlin_native",
            user = "elena_lepikina", password = "KMFBsyhrae6gLrCZ4Tmq")

    // Register build on Bintray.
    router.post("/register", { request, response ->
        val register = BuildRegister.create(JSON.stringify(request.body))

        // Get information from TeamCity.
        register.getBuildInformation().then { buildInfo ->
            register.sendTeamCityRequest(register.changesListUrl, true).then { changes ->

                val commitsList = CommitsList(JsonTreeParser.parse(changes))
                // Get artifact.
                register.sendTeamCityRequest(register.teamCityArtifactsUrl).then { resultsContent ->
                    val results = BenchmarkMeasurement.create(JsonTreeParser.parse(resultsContent),
                            BuildInfo(buildInfo.buildNumber, buildInfo.startTime, buildInfo.finishTime,
                                    commitsList, buildInfo.branch))
                    println("Get results")
                    // Save results in database.
                    InfluxDBConnector.insert(results).then { _ ->
                        response.sendStatus(200)
                    }.catch {
                        response.sendStatus(400)
                    }
                }
            }
        }
    })

    // Register golden results to normalize on Bintray.
    router.post("/registerGolden", { request, response ->
        val goldenResultsInfo = JSON.parse<GoldenResultsInfo>(JSON.stringify(request.body))
        val resultPoints = goldenResultsInfo.goldenResults.map {
            GoldenResultMeasurement(it.benchmarkName, it.metric, it.value)
        }
        InfluxDBConnector.insert(resultPoints).then { _ ->
            response.sendStatus(200)
        }.catch {
            response.sendStatus(400)
        }
    })

    // Get list of builds.
    /*router.get("/builds/:target/:type/:branch/:id", { request, response ->
        val builds = LocalCache[request.params.target, request.params.id]
        response.json(prepareBuildsResponse(builds, request.params.type, request.params.branch, request.params.id))
    })

    router.get("/builds/:target/:type/:branch/:id", { request, response ->
        val builds = LocalCache[request.params.target]
        response.json(prepareBuildsResponse(builds, request.params.type, request.params.branch))
    })*/

    router.get("/metricValue/:target/:type/:branch/:metric", { request, response ->
        val measurement = BenchmarkMeasurement()
        val samples = request.query?.samples.toString().split(",").map { it.trim() }
        val agregation = request.query?.agr.toString() ?: "geomean"
        val metric = request.params.metric

        // Get golden results.
        val golden = GoldenResultMeasurement()
        val goldenResults = golden.select(golden.all()).then { results ->
            val parsedNormalizeResults = mutableMapOf<String, MutableMap<String, Double>>()
            (results as List<GoldenResultMeasurement>).forEach {
                parsedNormalizeResults.getOrPut(it.benchmarkName!!, { mutableMapOf<String, Double>() })[it.benchmarkMetric!!] = it.benchmarkScore!!.value
            }
            parsedNormalizeResults
        }

        val buildsNumbers = InfluxDBConnector.select(measurement.distinct("build.number"),
                measurement.field("build.number")
                        .select((measurement.tag("environment.machine.os") eq request.params.target) and
                                /*(measurement.field("build.number") match ".+-${request.params.type}-.+") and*/
                                (measurement.field("build.branch") eq FieldType.InfluxString("${request.params.branch}")))).then { dbResponse ->
            dbResponse.toString().replace("\\[|\\]".toRegex(), "").split(",")
        }

        Promise.all(arrayOf(buildsNumbers, goldenResults)).then { results ->
            val (buildsNumbers, goldenResults) = results
            (buildsNumbers as List<String>).forEach {
                if (it.contains("${request.params.type}")) {
                    // Get points for this build.
                    measurement.select(measurement.all(), (measurement.tag("environment.machine.os") eq request.params.target) and
                            (measurement.field("build.number") eq FieldType.InfluxString(it))).then { dbResponse ->
                        val report = (dbResponse as List<BenchmarkMeasurement>).toReport()
                        SummaryBenchmarksReport(report).getResultsByMetric(metric, agregation == "geomean", samples, goldenResults)
                    }
                }
            }
        }
    })

    router.get("/branches/:target", { request, response ->
        val measurement = BenchmarkMeasurement()

        InfluxDBConnector.select(measurement.distinct("build.branch"),
                measurement.field("build.branch")
                        .select(measurement.tag("environment.machine.os") eq request.params.target)).then { dbResponse ->
            response.json(dbResponse)
        }
    })

    router.get("/buildsNumbers/:target", { request, response ->
        val measurement = BenchmarkMeasurement()

        InfluxDBConnector.select(measurement.distinct("build.number"),
                measurement.field("build.number")
                        .select(measurement.tag("environment.machine.os") eq request.params.target)).then { dbResponse ->
            response.json(dbResponse)
        }
    })

    /*router.get("/clean", { _, response ->
        LocalCache.clean()
        response.sendStatus(200)
    })*/

    /*router.get("/delete/:target", { request, response ->
        val buildsToDelete: List<String> = request.query.builds.toString().split(",").map { it.trim() }
        val result = LocalCache.delete(request.params.target, buildsToDelete, request.query.user, request.query.key)
        if (result) {
            response.sendStatus(200)
        } else {
            response.sendStatus(404)
        }
    })*/

    // Main page.
    router.get("/", { _, response ->
        response.render("index")
    })

    return router
}

fun sendUploadRequest(url: String, fileContent: String, user: String? = null, password: String? = null) {
    val request = require("sync-request")
    val headers = mutableListOf<Pair<String, String>>("Content-type" to "text/plain")
    if (user != null && password != null) {
        headers.add("Authorization" to getAuth(user, password))
    }
    val response = request("PUT", url,
            json(
                    "headers" to json(*(headers.toTypedArray())),
                    "body" to fileContent
            )
    )
    if (response.statusCode != 201) {
        error("Error during uploading to $url\n" +
                "${response}")
    }
}