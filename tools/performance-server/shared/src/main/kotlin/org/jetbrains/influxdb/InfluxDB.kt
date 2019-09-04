package org.jetbrains.influxdb

import kotlin.reflect.KProperty
import kotlin.js.Promise
import kotlin.js.json
import org.jetbrains.report.json.*

object InfluxDBConnector {
    private lateinit var host: String
    private lateinit var databaseName: String
    private var port: Int = 8086
    private var user: String? = null
    private var password: String? = null

    fun initConnection(host: String, databaseName: String, port: Int = 8086, user: String? = null,
                       password: String? = null) {
        this.host = host
        this.databaseName = databaseName
        this.port = port
        this.user = user
        this.password = password
    }

    fun query(query: String): Promise<String> {
        checkConnection()

        val queryUrl = "$host:$port/query?db=$databaseName&q=$query"
        println("In query $queryUrl")
        return sendRequest(RequestMethod.GET, queryUrl, user, password, true)
    }

    private fun checkConnection() = if (!::host.isInitialized || !::databaseName.isInitialized) {
        error("Please, firstly set connection to Influx database to have opportunity to send requests.")
    } else { }

    //fun<T: Any> select(measurement: Measurement, columns: Expression<T>): List<T> {}

    fun insert(point: Measurement): Promise<String> {
        checkConnection()
        val description = point.lineProtocol()
        val writeUrl = "$host:$port/write?db=$databaseName"
        return sendRequest(RequestMethod.POST, writeUrl, user, password, body = description)
    }

    inline fun <reified T: Any>selectQuery(query: String, measurement: Measurement? = null): Promise<List<T>> {
        return query(query).then { response ->
            println(response)
            // Parse response.
            if (measurement is T) {
                // Request objects.
                measurement.fromInfluxJson(JsonTreeParser.parse(response)) as List<T>
            } else if (T::class == String::class){
                // Request separate fields.
                fieldsFromInfluxJson(JsonTreeParser.parse(response)) as List<T>
            } else {
                error("Wrong type")
            }
        }
    }

    fun select(columns: Expression<String>, from: Expression<String>, where: WhereExpression? = null): Promise<List<String>> {
        val query = "SELECT ${columns.lineProtocol()} FROM (${from.lineProtocol()}) ${where?.lineProtocol() ?: ""}"
        println(query)
        return selectQuery<String>(query)
    }

    fun fieldsFromInfluxJson(data: JsonElement): List<String> {
        val points = mutableListOf<String>()
        var columnsIndexes: Map<String, Int>
        if (data is JsonObject) {
            val results = data.getRequiredField("results") as JsonArray
            results.map {
                if (it is JsonObject) {
                    val series = it.getRequiredField("series") as JsonArray
                    series.map {
                        if (it is JsonObject) {
                            val values = it.getRequiredField("values") as JsonArray
                            values.forEach {
                                points.add(((it as JsonArray)[1] as JsonLiteral).unquoted())
                            }
                        }
                    }
                }
            }
        }
        return points
    }

    fun insert(points: Collection<Measurement>): Promise<String> {
        checkConnection()
        val description  = with(StringBuilder()) {
            var prefix = ""
            points.forEach {
                append("${prefix}${it.lineProtocol()}")
                prefix = "\n"
            }
            toString()
        }
        val writeUrl = "$host:$port/write?db=$databaseName"
        return sendRequest(RequestMethod.POST, writeUrl, user, password, body = description)
    }
}

// Hack for Kotlin/JS.
// Need separate classes to describe types, because Int and Double are same in Kotlin/JS.
sealed class FieldType<T : Any>(val value: T) {
    override fun toString(): String = value.toString()
    class InfluxInt(value: Int): FieldType<Int>(value)
    class InfluxFloat(value: Double): FieldType<Double>(value)
    class InfluxString(value: Any): FieldType<String>(value.toString())
    class InfluxBoolean(value: Boolean): FieldType<Boolean>(value)
}

abstract class Measurement(val name: String) {
    var timestamp: ULong? = null
        protected set
    val fields = mutableMapOf<String, ColumnEntity.FieldEntity<FieldType<*>>>()
    val tags = mutableMapOf<String, ColumnEntity.TagEntity<*>>()

    fun field(fieldName: String) = fields[fieldName] ?: error ("No field $fieldName in measurement $name")
    fun tag(tagName: String) = tags[tagName] ?: error ("No tag $tagName in measurement $name")

    inner class Field<T: FieldType<*>>(val fieldName: String? = null) {
        operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ColumnEntity.FieldEntity<T> {
            val field = ColumnEntity.FieldEntity<T>(fieldName ?: prop.name, name)
            if (field.name in fields.keys) {
                error("Field ${field.name} already exists in measurement $name")
            }
            fields[field.name] = field as ColumnEntity.FieldEntity<FieldType<*>>
            return field
        }
    }

    inner class Tag<T: Any>(val tagName: String? = null) {
        operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ColumnEntity.TagEntity<T> {
            val tag = ColumnEntity.TagEntity<T>(tagName ?: prop.name, name)
            if (tag.name in tags.keys) {
                error("Field ${tag.name} already exists in measurement $name")
            }
            tags[tag.name] = tag
            return tag
        }
    }

    fun lineProtocol() =
        with(StringBuilder("$name,")) {
            var prefix = ""
            tags.values.forEach {
                it.value?.let {value ->
                    append("${prefix}${it.lineProtocol()}")
                    prefix = ","
                } ?: println("Tag $it.name isn't initialized.")
            }
            prefix = " "
            fields.values.forEach {
                it.value?.let { value ->
                    append("${prefix}${it.lineProtocol()}")
                    prefix = ","
                } ?: println("Field $it.name isn't initialized.")
            }
            timestamp?.let {
                append(" $timestamp")
            }
            toString()
        }

    fun distinct(fieldName: String): DistinctFunction {
        if (fieldName !in fields.keys) {
            error("There is no field with $fieldName in measurement $name.")
        }
        return fields[fieldName]!!.let { DistinctFunction(it) }
    }

    inline fun <reified T: Any>select(columns: Expression<T>, where: WhereExpression? = null): Promise<List<T>> {
        val query = "SELECT ${columns.lineProtocol()} FROM \"$name\" ${where?.lineProtocol() ?: ""}"
        println(query)
        return InfluxDBConnector.selectQuery<T>(query, this)
    }

    fun all() = object : Expression<Measurement>() {
        override fun lineProtocol(): String = "*"
    }

    abstract fun fromInfluxJson(data: JsonElement): List<Measurement>


}

abstract class Expression<T: Any>() {
    //infix fun or(Expression<*>)
    //infix fun and(Expression<*>)
    abstract fun lineProtocol(): String
}
abstract class WhereExpression: Expression<String>() {
    infix fun and(other: WhereExpression) = object : WhereExpression() {
        override fun lineProtocol(): String =
                "WHERE ${this@WhereExpression.lineProtocol().replace("WHERE", "")} AND ${other.lineProtocol().replace("WHERE", "")}"
    }
}
abstract class InfluxFunction<T : Any>(val entity: ColumnEntity<*>): Expression<T>()

class DistinctFunction(field: ColumnEntity.FieldEntity<*>) : InfluxFunction<String>(field) {
    override fun lineProtocol() = "DISTINCT(\"${entity.name}\")"
}

sealed class ColumnEntity<T : Any>(val name: String, val measurement: String): Expression<T>() {
    var value: T? = null
        protected set

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? = value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, definedValue: T?) {
        value = definedValue
    }

    infix fun eq(value: T) = object : WhereExpression() {
        override fun lineProtocol(): String = "WHERE \"$name\"='$value'"
    }

    infix fun match(value: String) = object : WhereExpression() {
        override fun lineProtocol(): String = "WHERE \"$name\"=~/$value/"
    }

    infix fun select(where: WhereExpression) = object : Expression<String>() {
        override fun lineProtocol(): String = "SELECT \"$name\" FROM $measurement ${where.lineProtocol()}"
    }

    class FieldEntity<T : FieldType<*>>(name: String, measurement: String) : ColumnEntity<T>(name, measurement) {
         override fun lineProtocol() =
                 value?.let {
                     when(it) {
                         is FieldType.InfluxInt -> "$name=${it.value}i"
                         is FieldType.InfluxFloat -> "$name=${it.value}"
                         is FieldType.InfluxBoolean -> "$name=${it.value}"
                         else -> "$name=\"$it\""
                     }
                 } ?: ""
    }

    class TagEntity<T : Any>(name: String, measurement: String) : ColumnEntity<T>(name, measurement) {
        private fun escape() = "$value".replace(" |,|=".toRegex()) { match -> "\\${match.value}" }

        override fun lineProtocol() = "$name=${escape()}"
    }
}

// Now implemenation for network connection only for Node.js. TODO - multiplatform.
external fun require(module: String): dynamic

fun getAuth(user: String, password: String): String {
    val buffer = js("Buffer").from(user + ":" + password)
    val based64String = buffer.toString("base64")
    return "Basic " + based64String
}

enum class RequestMethod {
    POST, GET, PUT
}

fun sendRequest(method: RequestMethod, url: String, user: String? = null, password: String? = null,
                acceptJsonContentType: Boolean = false, body: String? = null): Promise<String> {
    val request = require("node-fetch")
    val headers = mutableListOf<Pair<String, String>>()
    if (user != null && password != null) {
        headers.add("Authorization" to getAuth(user, password))
    }
    if (acceptJsonContentType) {
        headers.add("Accept" to "application/json")
    }
    return request(url,
            json(
                    "method" to method.toString(),
                    "headers" to json(*(headers.toTypedArray())),
                    "body" to body
            )
    ).then { response ->
        if (!response.ok)
            error("Error during getting response from $url\n" +
                    "${response}")
        else
            response.text()
    }
}