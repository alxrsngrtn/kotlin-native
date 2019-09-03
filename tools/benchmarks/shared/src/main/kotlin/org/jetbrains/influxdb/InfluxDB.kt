package org.jetbrains.influxdb

import kotlin.reflect.KProperty

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

    fun query(query: String) {
        checkConnection()
        val queryUrl = "$host:$port/query?db=$databaseName&q=$query"
        sendRequest("GET", queryUrl, user, password, true)
    }

    private fun checkConnection() = if (!::host.isInitialized || !::databaseName.isInitialized) {
        error("Please, firstly set connection to Influx database to have opportunity to send requests.")
    } else { }

    //fun<T: Any> select(measurement: Measurement, columns: Expression<T>): List<T> {}

    fun insert(point: Measurement) {
        checkConnection()
        val description = point.lineProtocol()
        val writeUrl = "$host:$port/write?db=$databaseName"
        sendRequest("POST", writeUrl, user, password, body = description)
    }

    fun insert(points: Collection<Measurement>) {
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
        sendRequest("POST", writeUrl, user, password, body = description)
    }
}

// Hack for Kotlin/JS.
// Need separate classes to describe types, because Int and Double are same in Kotlin/JS.
sealed class FieldType<T : Any>(val value: T) {
    class InfluxInt(value: Int): FieldType<Int>(value)
    class InfluxFloat(value: Double): FieldType<Double>(value)
    class InfluxString(value: Any): FieldType<String>(value.toString())
    class InfluxBoolean(value: Boolean): FieldType<Boolean>(value)
}

open class Measurement(val name: String) {
    var timestamp: ULong? = null
        protected set
    val fields = mutableMapOf<String, ColumnEntity.FieldEntity<*>>()
    val tags = mutableMapOf<String, ColumnEntity.TagEntity<*>>()

    inner class Field<T: FieldType<*>>(val name: String? = null) {
        operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ColumnEntity.FieldEntity<T> {
            val field = ColumnEntity.FieldEntity<T>(name ?: prop.name)
            if (field.name in fields.keys) {
                error("Field ${field.name} already exists in measurement $name")
            }
            fields[field.name] = field
            return field
        }
    }

    inner class Tag<T: Any>(val name: String? = null) {
        operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ColumnEntity.TagEntity<T> {
            val tag = ColumnEntity.TagEntity<T>(name ?: prop.name)
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

    fun distinct(fieldName: String): InfluxFunction<*> {
        if (fieldName !in fields.keys) {
            error("There is no field with $fieldName in measurement $name.")
        }
        return fields[fieldName]!!.let { DistinctFunction(it) }
    }

    fun <T: Any>select(columns: Expression<*>): List<T> {
        val query = "SELECT ${columns.lineProtocol()} FROM \"$name\""
        InfluxDBConnector.query(query)/*.then { response ->
            // Parse response.
println(response)
        }*/
        return listOf()
    }
}

abstract class Expression<T: Any>() {
    //infix fun or(Expression<*>)
    //infix fun and(Expression<*>)
    abstract fun lineProtocol(): String
}

abstract class InfluxFunction<T : Any>(val entity: ColumnEntity<T>) {
    abstract fun lineProtocol(): String
}

class DistinctFunction<T : FieldType<*>>(field: ColumnEntity.FieldEntity<T>) : InfluxFunction<T>(field) {
    override fun lineProtocol() = "distinct(\"${entity.name}\")"
}

sealed class ColumnEntity<T : Any>(val name: String): Expression<T>() {
    var value: T? = null
        protected set

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? = value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, definedValue: T?) {
        value = definedValue
    }

    //infix fun eq(value: T)
    class FieldEntity<T : FieldType<*>>(name: String) : ColumnEntity<T>(name) {
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

    class TagEntity<T : Any>(name: String) : ColumnEntity<T>(name) {
        private fun escape() = "$value".replace(" |,|=".toRegex()) { match -> "\\${match.value}" }

        override fun lineProtocol() = "$name=${escape()}"
    }
}


