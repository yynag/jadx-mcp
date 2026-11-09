package com.yyang.jadxmcp.analysis.workspace

import jadx.api.JavaClass

/**
 * JADX 1.5.6 JavaClass.unload is NOT synchronized, unlike reload/load. Its deepUnload
 * rereads mutable Java input data outside ClassNode.unload's short ClassInfo critical section.
 * A borrowed workspace lease excludes our callers, not RenameService's background reload.
 * Follow native reload's lock order: JavaClass -> ClassNode (decompile) -> ClassInfo
 * (ProcessClass). Hold all three for the entire unload, including cache removal and input read.
 * Never wait for EDT/events or acquire another class's monitor while holding these locks.
 */
object NativeClassInvalidation {
    fun unload(cls: JavaClass) {
        synchronized(cls) {
            synchronized(cls.classNode) {
                synchronized(cls.classNode.classInfo) {
                    cls.unload()
                }
            }
        }
    }
}
