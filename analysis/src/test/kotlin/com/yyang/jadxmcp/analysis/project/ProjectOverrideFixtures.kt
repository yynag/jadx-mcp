package com.yyang.jadxmcp.analysis.project

/** Independent native bytecode inputs; no compiler invocation or cross-package test dependency. */
interface ProjectParentFixture {
    fun ping(value: Int): String
    fun ping(value: String): String
}

class ProjectChildFixture : ProjectParentFixture {
    override fun ping(value: Int): String = "integer-$value"
    override fun ping(value: String): String = "string-$value"
}

interface ProjectGenericParentFixture<T> { fun convert(value: T): T }
class ProjectGenericChildFixture : ProjectGenericParentFixture<String> {
    override fun convert(value: String): String = "converted-$value"
}
