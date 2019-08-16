package org.jetbrains.influxdb

import kotlin.reflect.KProperty

class InfluxDBConnector <T>(val host: String, val databaseName: String, val port: Int = 8086,
                        private val user: String? = null, private val password: String? = null,
                        val postRequestSender: (url: String, body: String, user: String?, password: String?) -> T) {
    fun query(query: String) {

    }
    //fun<T : Measurement, U: Any> select(columns: ColumnEntity<*>, where: (ColumnEntity<U>)->Expression<U>): List<T> {}

    fun insert(point: Measurement) {
        val description = point.lineProtocol()
        println(description)
    }

    fun insert(points: Collection<Measurement>): T {
        val description  = with(StringBuilder()) {
            var prefix = ""
            points.forEach {
                append("${prefix}${it.lineProtocol()}")
                prefix = "\n"
            }
            toString()
        }
        val writeUrl = "$host:$port/write?db=$databaseName"
        return postRequestSender(writeUrl, description, user, password)
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
    val fields = mutableListOf<ColumnEntity.FieldEntity<*>>()
    val tags = mutableListOf<ColumnEntity.TagEntity<*>>()

    inner class Field<T: FieldType<*>>(val name: String? = null) {
        operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ColumnEntity.FieldEntity<T> {
            val field =  ColumnEntity.FieldEntity<T>(name ?: prop.name)
            fields.add(field)
            return field
        }
    }

    inner class Tag<T: Any>(val name: String? = null) {
        operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ColumnEntity.TagEntity<T> {
            val tag = ColumnEntity.TagEntity<T>(name ?: prop.name)
            tags.add(tag)
            return tag
        }
    }

    fun lineProtocol() =
        with(StringBuilder("$name,")) {
            var prefix = ""
            tags.forEach {
                it.value?.let {value ->
                    append("${prefix}${it.lineProtocol()}")
                    prefix = ","
                } ?: println("Tag $it.name isn't initialized.")
            }
            prefix = " "
            fields.forEach {
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

    fun insert(): Boolean {
        TODO()
    }
}

sealed class Expression<T: Any>(val name: String, val value: T) {
    //infix fun or(Expression<*>)
    //infix fun and(Expression<*>)
}

sealed class ColumnEntity<T : Any>(val name: String) {
    var value: T? = null
        protected set

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? = value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, definedValue: T?) {
        value = definedValue
    }

    //infix fun eq(value: T)
    class FieldEntity<T : FieldType<*>>(name: String) : ColumnEntity<T>(name) {
         fun lineProtocol() =
                 value?.let {
                     when(it) {
                         is FieldType.InfluxInt -> "$name=${it.value}i"
                         is FieldType.InfluxFloat -> "$name=${it.value}"
                         is FieldType.InfluxBoolean -> "$name=${it.value}"
                         else -> "$name=\"$it\""
                     }
                 }
    }

    class TagEntity<T : Any>(name: String) : ColumnEntity<T>(name) {
        private fun escape() = "$value".replace(" |,|=".toRegex()) { match -> "\\${match.value}" }

        fun lineProtocol() = "$name=${escape()}"
    }
}


