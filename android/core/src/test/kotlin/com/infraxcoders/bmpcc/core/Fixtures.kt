package com.infraxcoders.bmpcc.core

/**
 * Reference values. android_golden.json was produced by running the ORIGINAL Android Kotlin code on the JVM;
 * sun_reference.json comes from the `astral` Python library. Shared with the iPhone app's tests.
 */
object Fixtures {
    @Suppress("UNCHECKED_CAST")
    fun json(name: String): Map<String, Any?> {
        val text = Fixtures::class.java.classLoader!!.getResourceAsStream("fixtures/$name.json")!!.bufferedReader().readText()
        return Json.parse(text) as Map<String, Any?>
    }
    val android by lazy { json("android_golden") }
    val sun by lazy { json("sun_reference") }

    @Suppress("UNCHECKED_CAST")
    fun list(m: Map<String, Any?>, key: String): List<Map<String, Any?>> = m[key] as List<Map<String, Any?>>
}

fun Map<String, Any?>.d(k: String): Double = (this[k] as Number).toDouble()
fun Map<String, Any?>.s(k: String): String = this[k] as String
fun Map<String, Any?>.optS(k: String): String? = this[k] as? String
