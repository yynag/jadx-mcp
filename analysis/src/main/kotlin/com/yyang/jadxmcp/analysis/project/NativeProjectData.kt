package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler
import jadx.core.dex.visitors.rename.UserRenames

/** In-memory native application only; callers provide the exclusive project lease. */
internal class NativeProjectData(private val decompiler: JadxDecompiler) {
    private val baselines = mutableMapOf<NodeReference, () -> Unit>()
    private val methodGroups = mutableMapOf<NodeReference, Set<NodeReference>>()

    fun symbols() = ProjectSymbols(decompiler, methodGroups)

    fun read(): CodeData = ProjectDataCodec.fromNative(decompiler.args.codeData)

    fun apply(before: CodeData, after: CodeData) {
        val symbols = symbols()
        // Guard the native boundary too: UserRenames applies sequentially, so incompatible group
        // entries would otherwise succeed with the last alias while codeData retained both names.
        symbols.requireConsistentMethodRenames(after)
        captureAliasBaselines(before, after, symbols)
        baselines.values.forEach { restore -> restore() }
        decompiler.args.codeData = ProjectDataCodec.toNative(after)
        UserRenames.apply(decompiler.root)
        decompiler.root.runPackagesUpdate()
        decompiler.reloadCodeData() // listeners update comment/variable-rename maps; this does NOT apply symbol aliases
        decompiler.classes.forEach { it.unload() }
    }

    /**
     * Capture once so deletion and transaction rollback can undo our edits. Renames already
     * present when attaching have no observable pre-edit alias; their baseline is raw identity.
     */
    private fun captureAliasBaselines(before: CodeData, after: CodeData, symbols: ProjectSymbols) {
        for (rename in before.renames + after.renames) {
            if (rename.codeRef != null || baselines.containsKey(rename.nodeRef)) continue
            val ref = rename.nodeRef
            if (ref.type == NodeReferenceKind.METHOD) {
                val group = symbols.methodRenameGroup(symbols.nativeMethod(ref))
                val alreadyRenamed = before.renames.any { old -> old.codeRef == null &&
                    old.nodeRef.type == NodeReferenceKind.METHOD && symbols.nativeMethod(old.nodeRef) in group }
                for (member in group) {
                    val memberRef = NodeReference(NodeReferenceKind.METHOD, member.declaringClass.classInfo.rawName,
                        member.methodInfo.shortId)
                    if (!baselines.containsKey(memberRef)) {
                        val alias = if (alreadyRenamed) member.methodInfo.name else member.alias
                        // Restore each exact alias without MethodNode.rename's group propagation:
                        // iteration order must not overwrite another member's captured baseline.
                        baselines[memberRef] = {
                            // Deep source invalidation replaces MethodNodes; restore by stable raw identity.
                            requireNotNull(decompiler.root.resolveRawClass(memberRef.declaringClass)
                                ?.searchMethodByShortId(memberRef.shortId)).methodInfo.setAlias(alias)
                        }
                    }
                }
                continue
            }
            val wasAlreadyUserRenamed = before.renames.any { it.nodeRef == ref && it.codeRef == null }
            baselines[ref] = when (ref.type) {
                NodeReferenceKind.PKG -> {
                    val pkg = requireNotNull(decompiler.root.resolvePackage(ref.declaringClass))
                    val alias = if (wasAlreadyUserRenamed) pkg.pkgInfo.fullName else pkg.aliasPkgInfo.fullName
                    { pkg.rename(alias) }
                }
                NodeReferenceKind.CLASS -> {
                    val cls = requireNotNull(decompiler.root.resolveRawClass(ref.declaringClass))
                    val alias = if (wasAlreadyUserRenamed) cls.classInfo.shortName else cls.classInfo.aliasShortName
                    { cls.rename(alias) }
                }
                NodeReferenceKind.METHOD -> error("Method groups are captured above")
                NodeReferenceKind.FIELD -> {
                    val field = requireNotNull(decompiler.root.resolveRawClass(ref.declaringClass)?.searchFieldByShortId(ref.shortId))
                    val alias = if (wasAlreadyUserRenamed) field.fieldInfo.name else field.alias
                    { field.rename(alias) }
                }
            }
        }
    }
}
