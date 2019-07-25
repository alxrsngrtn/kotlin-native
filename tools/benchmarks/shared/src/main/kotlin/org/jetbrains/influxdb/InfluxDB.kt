package org.jetbrains.influxdb

import kotlin.reflect.KProperty

class InfluxDBConnector(val host: String, val databaseName: String, val port: Int = 8086, private val user: String? = null, private val password: String? = null) {
    fun query(query: String) {

    }
    //fun<T : Measurement, U: Any> select(columns: ColumnEntity<*>, where: (ColumnEntity<U>)->Expression<U>): List<T> {}

    /*fun insert(point: Measurement) {

    }*/
}

open class Measurement(val name: String) {
    var timestamp: String? = null
        protected set
    val fields = mutableListOf<ColumnEntity.FieldEntity<*>>()
    val tags = mutableListOf<ColumnEntity.TagEntity<*>>()

    inner class Field<T: Any>(val name: String? = null) {
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

    private val description by lazy {
        with(StringBuilder("$name,")) {
            var prefix = ""
            tags.forEach {
                append("${prefix}${it.name}=${it.value}")
                prefix = ","
            }
            prefix = " "
            fields.forEach {
                append("${prefix}${it.name}=${it.value}")
                prefix = ","
            }
            timestamp?.let {
                append(" $timestamp")
            }
            toString()
        }
    }

    fun insert(): Boolean {

    }
}

fun List<Measurement>.insert() {

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
    class FieldEntity<T : Any>(name: String) : ColumnEntity<T>(name)
    class TagEntity<T : Any>(name: String) : ColumnEntity<T>(name)
}


