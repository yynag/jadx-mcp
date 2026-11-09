package com.yyang.jadxmcp.analysis.reference

import com.yyang.jadxmcp.analysis.search.SearchServiceTest
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/** Optional offline entrypoint for isolated compiler output when concurrent Gradle workers
 * invalidate shared build/classes. Executes the same JUnit assertions, not substitute checks. */
object OfflineP5Checks {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        var failed = 0
        for (type in listOf(SearchServiceTest::class.java, ReferenceServiceTest::class.java)) {
            for (method in type.declaredMethods.sortedBy { it.name }) {
                if (!method.isAnnotationPresent(Test::class.java)) continue
                try {
                    method.invoke(type.getConstructor().newInstance())
                    println("PASS ${type.simpleName}.${method.name}")
                    passed++
                } catch (failure: InvocationTargetException) {
                    println("FAIL ${type.simpleName}.${method.name}")
                    failure.cause!!.printStackTrace()
                    failed++
                }
            }
        }
        println("P5 offline: passed=$passed failed=$failed")
        check(failed == 0) { "$failed tests failed" }
    }
}
