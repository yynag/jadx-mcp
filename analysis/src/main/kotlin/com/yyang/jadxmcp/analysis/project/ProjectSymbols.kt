package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.analysis.metadata.ClassIndex
import com.yyang.jadxmcp.protocol.*
import jadx.api.*
import jadx.api.data.impl.JadxNodeRef
import jadx.api.metadata.annotations.InsnCodeOffset
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.api.metadata.annotations.VarNode
import jadx.api.utils.CodeUtils
import jadx.core.dex.attributes.AType
import jadx.core.dex.nodes.MethodNode

/** Native resolution helpers. Call only while the workspace/GUI owner holds its project lease.
 * References intentionally use raw native identities; aliases are lookup conveniences, never
 * persisted identity, so validation fails closed when a symbol is missing or ambiguous.
 */
class ProjectSymbols(
    private val decompiler: JadxDecompiler,
    private val methodGroups: MutableMap<NodeReference, Set<NodeReference>> = mutableMapOf()
) {
    // Reuse one lease-local class list across validation and collision checks; alias fields stay live.
    private val classes by lazy { ClassIndex.Snapshot(decompiler.classesWithInners) }

    fun clazz(name: String): JavaClass =
        classes.find(name) ?: missing("Class '$name' not found")

    fun method(symbol: SymbolRef): JavaMethod {
        val signature = symbol.descriptor
        val candidates = clazz(symbol.className).methods.filter { method ->
            val info = method.methodNode.methodInfo
            (method.name == symbol.memberName || info.name == symbol.memberName) &&
                (signature == null || signature == info.shortId || signature == info.shortId.substringAfter(info.name) ||
                    signature == info.makeSignature(true, true))
        }
        if (candidates.size > 1) projectError(ErrorCode.INVALID_ARGUMENT, "Overloaded method requires exact descriptor")
        return candidates.singleOrNull() ?: missing("Method '$symbol' not found")
    }

    fun field(symbol: SymbolRef): JavaField {
        val descriptor = symbol.descriptor
        val candidates = clazz(symbol.className).fields.filter {
            (it.name == symbol.memberName || it.fieldNode.fieldInfo.name == symbol.memberName) &&
                (descriptor == null || descriptor == it.fieldNode.fieldInfo.shortId ||
                    descriptor == it.fieldNode.fieldInfo.shortId.substringAfter(':'))
        }
        if (candidates.size > 1) projectError(ErrorCode.INVALID_ARGUMENT, "Ambiguous field requires descriptor")
        return candidates.singleOrNull() ?: missing("Field '$symbol' not found")
    }

    fun reference(node: JavaNode): NodeReference = ProjectDataCodec.node(
        JadxNodeRef.forJavaNode(node) ?: projectError(ErrorCode.UNSUPPORTED, "Unsupported native node"))

    fun packageReference(name: String): NodeReference {
        val pkg = decompiler.root.resolvePackage(name) ?: decompiler.root.packages.singleOrNull {
            it.aliasPkgInfo.fullName == name
        } ?: missing("Package '$name' not found")
        return ProjectDataCodec.node(JadxNodeRef.forPkg(pkg.pkgInfo.fullName))
    }

    fun variable(method: JavaMethod, name: String, reg: Int?, ssa: Int?): VarNode {
        if ((reg == null) != (ssa == null)) projectError(ErrorCode.INVALID_ARGUMENT, "reg and ssa must be supplied together")
        if (reg != null) ProjectDataCodec.variable(reg, requireNotNull(ssa))
        val matches = variables(method).filter { it.name == name && (reg == null || it.reg == reg && it.ssa == ssa) }
        if (matches.size > 1) projectError(ErrorCode.INVALID_ARGUMENT, "Ambiguous variable requires reg and ssa")
        val result = matches.singleOrNull() ?: missing("Variable '$name' not found")
        if (result.name == "this") projectError(ErrorCode.INVALID_ARGUMENT, "Cannot rename this")
        return result
    }

    fun variables(method: JavaMethod): List<VarNode> = method.topParentClass.codeInfo.codeMetadata.asMap.values
        // AnnotatedCodeWriter stores declarations as NodeDeclareRef(VarNode), not as a bare VarNode.
        // Keep support for direct node annotations too; references need no separate enumeration
        // because every editable source variable must have a declaration in this method.
        .mapNotNull { annotation -> when (annotation) {
            is NodeDeclareRef -> annotation.node as? VarNode
            is VarNode -> annotation
            else -> null
        } }.filter { it.mth == method.methodNode }.distinctBy { it.reg to it.ssa }

    /** Generated line -> exact INSN metadata offset. Never treat displayed line as DEX offset. */
    fun instructionAtLine(cls: JavaClass, method: JavaMethod?, line: Int): AttachmentIdentity {
        val info = cls.topParentClass.codeInfo
        val lines = info.codeStr.split('\n')
        if (line !in 1..lines.size) projectError(ErrorCode.INVALID_ARGUMENT, "Source line is out of range")
        val start = lines.take(line - 1).sumOf { it.length + 1 }
        val end = start + lines[line - 1].length
        val offsets = info.codeMetadata.asMap.filter { (position, annotation) ->
            position in start..end && annotation is InsnCodeOffset
        }
        val targets = offsets.mapNotNull { (position, annotation) ->
            val owner = decompiler.getEnclosingNode(info, position) as? JavaMethod ?: return@mapNotNull null
            if (method != null && owner != method) return@mapNotNull null
            // A class-only line request cannot reach a nested class outside the requested class.
            if (owner.declaringClass != cls) return@mapNotNull null
            AttachmentIdentity(reference(owner), CodeReference(CodeAttachmentKind.INSN, (annotation as InsnCodeOffset).offset))
        }.distinct()
        if (targets.size > 1) projectError(ErrorCode.INVALID_ARGUMENT, "Line has several instruction offsets; use a stable attachment")
        return targets.singleOrNull() ?: missing("Line has no exact instruction attachment in the requested method/class")
    }

    /** Reject nonexistent identities rather than allowing native JADX warning-and-ignore behavior. */
    fun validate(data: CodeData, context: CallContext) {
        for (comment in data.comments) {
            if (comment.nodeRef.type == NodeReferenceKind.PKG ||
                comment.codeRef?.attachType?.let { it != CodeAttachmentKind.INSN } == true)
                projectError(ErrorCode.UNSUPPORTED,
                    "JADX 1.5.6 applies comments only to class/field/method nodes or INSN attachments")
        }
        val entries = data.comments.map { ProjectDataCodec.identity(it) } + data.renames.map { ProjectDataCodec.identity(it) }
        for (identity in entries.distinct()) {
            context.checkActive()
            val node = identity.node
            if (node.type == NodeReferenceKind.PKG) {
                if (decompiler.root.resolvePackage(node.declaringClass) == null) missing("Raw package reference not found: $node")
                continue
            }
            val cls = decompiler.root.resolveRawClass(node.declaringClass) ?: missing("Raw class reference not found: $node")
            if (cls.classInfo.rawName != node.declaringClass) missing("Reference must use a raw class identity")
            when (node.type) {
                NodeReferenceKind.FIELD -> if (cls.searchFieldByShortId(node.shortId) == null) missing("Exact field reference not found: $node")
                NodeReferenceKind.METHOD -> {
                    val mth = cls.searchMethodByShortId(node.shortId) ?: missing("Exact method reference not found: $node")
                    val attachment = identity.code ?: continue
                    val java = clazz(node.declaringClass).methods.single { it.methodNode == mth }
                    when (attachment.attachType) {
                        CodeAttachmentKind.VAR -> if (variables(java).none {
                            it.reg == (attachment.index shr 16) && it.ssa == (attachment.index and 0xffff)
                        }) missing("Native reg/ssa reference not found: $identity")
                        CodeAttachmentKind.MTH_ARG -> if (attachment.index !in java.arguments.indices) missing("Method argument reference out of range")
                        CodeAttachmentKind.INSN -> {
                            val info = java.topParentClass.codeInfo
                            val end = CodeUtils.getMethodEnd(mth, info)
                            if (info.codeMetadata.asMap.none { (pos, annotation) ->
                                pos in (java.defPos + 1)..end && annotation is InsnCodeOffset && annotation.offset == attachment.index
                            }) missing("Instruction offset not present in generated method metadata")
                        }
                        CodeAttachmentKind.CATCH -> projectError(ErrorCode.UNSUPPORTED, "JADX 1.5.6 does not apply CATCH attachments")
                    }
                }
                else -> Unit
            }
        }
        // Native rename helpers warn/overwrite on some collisions; validate both existing and
        // staged symbols before touching native state. Conservative rejection is preferable to
        // silently generating duplicate Java declarations (including two different incoming keys).
        requireConsistentMethodRenames(data) { context.checkActive() }
        val staged = mutableMapOf<List<String>, NodeReference>()
        for (rename in data.renames) {
            val ref = rename.nodeRef
            val attachment = rename.codeRef
            if (attachment == null) requireRenameAvailable(ref, rename.newName)
            val scope = when {
                attachment != null -> listOf("VAR", ref.declaringClass, ref.shortId, rename.newName)
                ref.type == NodeReferenceKind.CLASS -> {
                    val cls = clazz(ref.declaringClass)
                    listOf("CLASS", cls.`package`, if (cls.isInner) reference(requireNotNull(cls.declaringClass)).declaringClass else "", rename.newName)
                }
                ref.type == NodeReferenceKind.METHOD -> listOf("METHOD", ref.declaringClass,
                    ref.shortId.substringAfter('(').substringBefore(')'), rename.newName)
                ref.type == NodeReferenceKind.FIELD -> listOf("FIELD", ref.declaringClass, rename.newName)
                else -> listOf("PKG", rename.newName)
            }
            val previous = staged.putIfAbsent(scope, ref)
            if (previous != null && !(attachment == null && ref.type == NodeReferenceKind.METHOD &&
                    previous.type == NodeReferenceKind.METHOD && nativeMethod(previous) in methodRenameGroup(nativeMethod(ref))))
                projectError(ErrorCode.CONFLICT, "Incoming renames collide at $scope")
        }
    }

    /**
     * Match MethodNode.rename's actual write set, including native bridge/override links. Never
     * infer membership from names/arity: overloads and unrelated declarations are separate groups.
     */
    internal fun methodRenameGroup(method: MethodNode): Set<MethodNode> {
        fun reference(node: MethodNode) = NodeReference(NodeReferenceKind.METHOD,
            node.declaringClass.classInfo.rawName, node.methodInfo.shortId)
        val native = linkedSetOf(method).apply { method.get(AType.METHOD_OVERRIDE)?.relatedMthNodes?.let(::addAll) }
        val refs = native.mapTo(linkedSetOf(), ::reference)
        // Source invalidation deep-reloads MethodNodes and drops override attributes. The owner's
        // immutable inputs keep these captured raw group identities valid across those reloads.
        refs.toList().forEach { methodGroups[it]?.let(refs::addAll) }
        val group = refs.toSet()
        group.forEach { methodGroups[it] = group }
        return group.mapTo(linkedSetOf(), ::nativeMethod)
    }

    internal fun nativeMethod(ref: NodeReference): MethodNode {
        val cls = decompiler.root.resolveRawClass(ref.declaringClass)
            ?: missing("Exact method reference not found: $ref")
        return cls.searchMethodByShortId(ref.shortId) ?: missing("Exact method reference not found: $ref")
    }

    /** All group writes must agree before any class/field/method alias or native codeData changes. */
    internal fun requireConsistentMethodRenames(data: CodeData, checkActive: () -> Unit = {}) {
        val names = mutableMapOf<MethodNode, String>()
        for (rename in data.renames) {
            if (rename.nodeRef.type != NodeReferenceKind.METHOD || rename.codeRef != null) continue
            checkActive()
            for (member in methodRenameGroup(nativeMethod(rename.nodeRef))) {
                val previous = names.putIfAbsent(member, rename.newName)
                if (previous != null && previous != rename.newName)
                    projectError(ErrorCode.CONFLICT, "Override/bridge group has incompatible aliases '$previous' and '${rename.newName}'")
            }
        }
    }

    fun requireRenameAvailable(node: NodeReference, name: String) {
        if (node.type == NodeReferenceKind.PKG) {
            val target = decompiler.root.packages.firstOrNull { it.aliasPkgInfo.fullName == name }
            if (target != null && target.pkgInfo.fullName != node.declaringClass)
                projectError(ErrorCode.CONFLICT, "Target package already exists")
            return
        }
        val cls = clazz(node.declaringClass)
        val collision = when (node.type) {
            NodeReferenceKind.CLASS -> decompiler.classesWithInners.any {
                it != cls && it.name == name && it.`package` == cls.`package` &&
                    (if (cls.isInner) it.isInner && it.declaringClass == cls.declaringClass else !it.isInner)
            }
            NodeReferenceKind.FIELD -> cls.fields.any { it.name == name && reference(it) != node }
            NodeReferenceKind.METHOD -> {
                val method = nativeMethod(node)
                if (method.methodInfo.isConstructor || method.methodInfo.isClassInit)
                    projectError(ErrorCode.INVALID_ARGUMENT, "Rename the class, not its constructor/initializer")
                val group = methodRenameGroup(method)
                group.any { member -> member.declaringClass.methods.any { it !in group && it.alias == name &&
                    it.methodInfo.argumentsTypes == member.methodInfo.argumentsTypes } }
            }
            else -> false
        }
        if (collision) projectError(ErrorCode.CONFLICT, "Rename would collide with an existing symbol")
    }

    private fun missing(message: String): Nothing = projectError(ErrorCode.NOT_FOUND, message)
}
