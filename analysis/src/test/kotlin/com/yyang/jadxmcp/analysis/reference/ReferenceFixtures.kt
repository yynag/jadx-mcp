package com.yyang.jadxmcp.analysis.reference

/** Small independently loaded bytecode fixture: no Android toolchain or new dependency. */
interface ParentFixture {
    fun ping(value: Int): String
    fun ping(value: String): String
}
class ChildFixture : ParentFixture {
    @JvmField var counter: Int = 0
    override fun ping(value: Int): String { counter += value; return "integer-call" }
    override fun ping(value: String): String = "string-call"
}
class CallerFixture {
    fun callInt(target: ParentFixture): String = target.ping(17)
    fun callString(target: ParentFixture): String = target.ping("s")
    fun field(target: ChildFixture): Int = target.counter
}
interface GenericParentFixture<T> { fun convert(value: T): T }
class GenericChildFixture : GenericParentFixture<String> {
    override fun convert(value: String): String = "converted-" + value
}
class GenericCallerFixture {
    fun call(target: GenericParentFixture<String>): String = target.convert("bridge-input")
}
