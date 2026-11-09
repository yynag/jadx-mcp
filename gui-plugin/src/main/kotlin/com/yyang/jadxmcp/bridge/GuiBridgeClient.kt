package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.*

enum class BridgeState { DISABLED, CONNECTING, CONNECTED, RECONNECTING, CLOSED }

/**
 * Transport uses the shared GuiChannelPort contract. An HTTP adapter must
 * bound every I/O call, honor poll context cancellation/deadline, and fence complete/unregister
 * by the supplied lease token. No transport or endpoint call is made on the caller/EDT thread.
 *
 * The endpoint is BORROWED: this client never closes it or the host decompiler. The caller must
 * fence project access using CallContext and updateProject before exposing a replacement project.
 * Runtime ownership, unlike endpoint ownership, is transferred to this client.
 */
class GuiBridgeClient(
    private val transport: GuiChannelPort,
    private val endpoint: BackendEndpoint,
    private val instanceId: String,
    name: String,
    initialProjectEpoch: Long = 0,
    private val runtime: BridgeRuntime = ExecutorBridgeRuntime(),
    private val clock: BridgeClock = BridgeClock(System::currentTimeMillis),
    private val policy: BridgePolicy = BridgePolicy(),
    private val observer: BridgeObserver = object : BridgeObserver {},
    previousRetired: () -> Boolean = { true },
    private val diagnostics: GuiDiagnosticLog? = null,
) : AutoCloseable {
    private class Generation(val epoch: Long, val name: String, val activation: Any, val number: Long) {
        val cancellation = CancellationSource()
        var lease: GuiLease? = null
        val seen = HashSet<String>()
    }

    private val lock = Any()
    private var current: Generation? = null
    private var epoch = initialProjectEpoch
    private var projectName: String? = name
    private var backoff = policy.initialBackoffMillis
    private var executionOutstanding = false
    private var generationNumber = 0L
    private var pendingCleanups = 0
    private var pendingRegistrations = 0
    private var registrationGate: (() -> Boolean)? = previousRetired
    /** Protocol/data failures require a deliberate user action; network outages retry automatically. */
    private var terminalFailure = false
    @Volatile var state: BridgeState = BridgeState.DISABLED
        private set

    private fun transition(value: BridgeState, reason: String) {
        state = value
        log("transition", null, "state" to value)
        observer.transition(value, epoch, generationNumber, reason)
    }

    private fun log(event: String, failure: Throwable? = null, vararg fields: Pair<String, Any?>) {
        diagnostics?.event(event, failure, "instance" to GuiDiagnosticLog.id(instanceId),
            "epoch" to epoch, "generation" to generationNumber, *fields)
    }

    init {
        require(instanceId.isNotBlank())
        require(name.isNotBlank())
        require(initialProjectEpoch >= 0)
        require(endpoint.capabilities.backend == BackendKind.GUI)
    }

    fun enable() = synchronized(lock) {
        check(state != BridgeState.CLOSED) { "Bridge has been unloaded" }
        if (projectName == null) return@synchronized
        if (terminalFailure) return@synchronized
        if (state != BridgeState.DISABLED) return@synchronized
        backoff = policy.initialBackoffMillis
        connect(0, false)
    }

    /** Explicit UI recovery for a terminal protocol error. Never replays work from the old lease. */
    fun reconnect(): Boolean = synchronized(lock) {
        if (state == BridgeState.CLOSED || state != BridgeState.DISABLED || !terminalFailure) return@synchronized false
        // Protocol failure already retired the generation; its unregister is best-effort on cleanup.
        terminalFailure = false
        log("reconnect", null, "source" to "user")
        enable()
        true
    }

    /** Explicit disable is sticky: only enable() may leave DISABLED. */
    fun disable() = synchronized(lock) {
        if (state == BridgeState.CLOSED) return@synchronized
        terminalFailure = false
        retire("explicit_disable")
        transition(BridgeState.DISABLED, "Actively disconnected / project unavailable")
    }

    /** Closing/loading has an epoch but no registerable name until a binding is installed. */
    fun suspendProject(projectEpoch: Long) = synchronized(lock) {
        if (state == BridgeState.CLOSED) return@synchronized
        require(projectEpoch >= epoch) { "Project epoch must not move backwards" }
        epoch = projectEpoch
        projectName = null
        disable()
    }

    fun updateProject(projectEpoch: Long, name: String) = synchronized(lock) {
        require(projectEpoch >= epoch) { "Project epoch must not move backwards" }
        require(name.isNotBlank()) { "Project name must not be blank" }
        check(state != BridgeState.CLOSED) { "Bridge has been unloaded" }
        if (projectEpoch == epoch && name == projectName) return@synchronized
        epoch = projectEpoch
        projectName = name
        retire("project_update")
        if (state != BridgeState.DISABLED) connect(0, false)
        else transition(BridgeState.DISABLED, "Project changed / waiting for project")
    }

    override fun close() = synchronized(lock) {
        if (state == BridgeState.CLOSED) return@synchronized
        retire("bridge_close")
        transition(BridgeState.CLOSED, "Plugin unloaded")
        runtime.close()
    }

    /** Local registration/unregister attempts finished; not proof of remote removal or native drain. */
    fun isRegistrationRetired(): Boolean = synchronized(lock) {
        state == BridgeState.CLOSED && pendingCleanups == 0 && pendingRegistrations == 0 &&
            registrationGate?.invoke() != false
    }

    // All generation transitions are under lock; queued work checks identity, never just epoch.
    private fun connect(delay: Long, retry: Boolean, activation: Any = Any()) {
        // Snapshot both values under the same lock: old I/O must never borrow a later project's name.
        val generation = Generation(epoch, requireNotNull(projectName), activation, ++generationNumber)
        current = generation
        transition(if (retry) BridgeState.RECONNECTING else BridgeState.CONNECTING,
            if (retry) "Retry in ${delay}ms" else "Registering")
        runtime.schedule(BridgeLane.CHANNEL, delay) { register(generation) }
    }

    private fun active(generation: Generation): Boolean = synchronized(lock) {
        current === generation && !generation.cancellation.isCancelled()
    }

    private fun retire(source: String) {
        val old = current ?: return
        current = null
        old.cancellation.cancel()
        log("generation_retired", null, "source" to source, "retiredGeneration" to old.number)
        old.lease?.let { lease -> cleanup(lease, source, old.number) }
    }

    private fun cleanup(lease: GuiLease, source: String, retiredGeneration: Long) {
        pendingCleanups++
        log("unregister_scheduled", null, "source" to source, "session" to GuiDiagnosticLog.id(lease.sessionId),
            "retiredGeneration" to retiredGeneration)
        runtime.schedule(BridgeLane.CLEANUP) {
            try {
                transport.unregister(lease)
                log("unregister_finished", null, "source" to source, "session" to GuiDiagnosticLog.id(lease.sessionId),
                    "retiredGeneration" to retiredGeneration)
            } catch (failure: Exception) {
                log("unregister_failed", failure, "source" to source, "session" to GuiDiagnosticLog.id(lease.sessionId),
                    "retiredGeneration" to retiredGeneration)
            }
            finally { synchronized(lock) { pendingCleanups-- } }
        }
    }

    private fun disconnected(generation: Generation) = synchronized(lock) {
        if (!active(generation)) return@synchronized
        retire("network_reconnect")
        val delay = backoff
        backoff = if (backoff > policy.maxBackoffMillis / 2) policy.maxBackoffMillis else backoff * 2
        // Only registration is retried. An old COMMAND/COMPLETE is never copied to this lease:
        // after a socket failure, whether a native edit or its report completed can be unknowable.
        connect(delay, true, generation.activation)
    }

    private fun serverClosed(generation: Generation) = synchronized(lock) {
        // CLOSED from an in-flight old heartbeat must also cancel its automatic successor.
        // Explicit enable/project changes create a different activation and are not affected.
        if (current?.activation !== generation.activation) return@synchronized
        retire("master_closed")
        terminalFailure = false
        transition(BridgeState.DISABLED, "Actively closed by master")
    }

    private fun transportFailed(generation: Generation, failure: Exception) {
        log("transport_failed", failure, "failedGeneration" to generation.number,
            "session" to generation.lease?.let { GuiDiagnosticLog.id(it.sessionId) })
        val error = (failure as? ProtocolException)?.error
        when {
            error?.code == ErrorCode.CLOSED -> serverClosed(generation)
            // A restarted master has no old session/token. Only an established lease may
            // recover NOT_FOUND; registration/authentication/conflict errors remain terminal.
            error?.code == ErrorCode.NOT_FOUND && generation.lease != null -> disconnected(generation)
            failure is java.io.IOException || error?.code == ErrorCode.DISCONNECTED || error?.code == ErrorCode.DEADLINE_EXCEEDED ->
                disconnected(generation)
            else -> disableGeneration(generation, "Error: ${error?.code?.name ?: "INVALID_DATA"}" +
                (error?.let { " — ${safeGuiErrorSummary(it)}" } ?: ""))
        }
    }

    private fun disableGeneration(generation: Generation, reason: String = "Error: replay protection") = synchronized(lock) {
        // Authentication/lease conflicts, exhausted replay tracking and invalid data are not outages.
        if (active(generation)) {
            log("generation_disabled", null, "source" to if (reason == "Error: replay protection") "replay_protection" else "transport_failure")
            retire("terminal_failure")
            terminalFailure = true
            transition(BridgeState.DISABLED, reason)
        }
    }

    private fun register(generation: Generation) {
        synchronized(lock) {
            if (!active(generation)) return
            // Polling this local fence never waits on EDT or blocks heartbeat/execution workers.
            if (pendingCleanups != 0 || registrationGate?.invoke() == false) {
                runtime.schedule(BridgeLane.CHANNEL, policy.idlePollMillis) { register(generation) }
                return
            }
            registrationGate = null // Do not retain a chain of retired bridges after handoff.
            pendingRegistrations++
        }
        try {
            val lease = transport.register(GuiRegistration(instanceId, generation.name, generation.epoch, endpoint.capabilities))
            require(lease.sessionId.isNotBlank() && lease.token.isNotBlank()) { "Invalid GUI lease" }
            log("lease_registered", null, "registeredGeneration" to generation.number,
                "session" to GuiDiagnosticLog.id(lease.sessionId))
            val accepted = synchronized(lock) {
                if (!active(generation)) false else {
                    generation.lease = lease
                    transition(BridgeState.CONNECTED, "Connected")
                    runtime.schedule(BridgeLane.HEARTBEAT) { heartbeat(generation, lease) }
                    runtime.schedule(BridgeLane.CHANNEL) { poll(generation, lease) }
                    true
                }
            }
            // Registration can finish after unload, when the cleanup executor is already shut down.
            // This is already a transport worker, so release directly without touching the EDT.
            if (!accepted) try {
                log("unregister_started", null, "source" to "late_registration", "session" to GuiDiagnosticLog.id(lease.sessionId))
                transport.unregister(lease)
                log("unregister_finished", null, "source" to "late_registration")
            } catch (failure: Exception) { log("unregister_failed", failure, "source" to "late_registration") }
        } catch (e: Exception) { transportFailed(generation, e) }
        finally { synchronized(lock) { pendingRegistrations-- } }
    }

    private fun heartbeat(generation: Generation, lease: GuiLease) {
        if (!active(generation)) return
        try {
            transport.heartbeat(lease, generation.epoch)
            synchronized(lock) {
                if (!active(generation)) return
                backoff = policy.initialBackoffMillis
                runtime.schedule(BridgeLane.HEARTBEAT, policy.heartbeatMillis) { heartbeat(generation, lease) }
            }
        } catch (e: Exception) { transportFailed(generation, e) }
    }

    private fun poll(generation: Generation, lease: GuiLease) {
        if (!active(generation)) return
        synchronized(lock) {
            if (!active(generation)) return
            if (executionOutstanding) {
                // A cancelled JADX call may ignore interruption. Do not accumulate commands behind it.
                runtime.schedule(BridgeLane.CHANNEL, policy.idlePollMillis) { poll(generation, lease) }
                return
            }
        }
        if (generation.seen.size >= policy.maxCommandsPerLease) {
            disableGeneration(generation) // Bound replay memory without silently forgetting IDs or re-registering.
            return
        }
        try {
            val context = CallContext("poll-${lease.sessionId}", generation.epoch,
                clock.nowMillis() + policy.pollTimeoutMillis, generation.cancellation)
            val response = transport.poll(lease, context)
            if (response.status == PollStatus.CLOSED) {
                serverClosed(generation)
                return
            }
            if (!active(generation)) return
            when (response.status) {
                PollStatus.CLOSED -> Unit // handled before the generation check, including late replies
                PollStatus.IDLE -> runtime.schedule(BridgeLane.CHANNEL, policy.idlePollMillis) { poll(generation, lease) }
                PollStatus.COMMAND -> {
                    val frame = requireNotNull(response.command) { "COMMAND without frame" }
                    // Duplicate IDs must never cause a repeated GUI edit or other side effect.
                    if (!generation.seen.add(frame.context.requestId)) {
                        disableGeneration(generation)
                    } else synchronized(lock) {
                        if (!active(generation)) return
                        executionOutstanding = true
                        observer.received(generation.number)
                        runtime.schedule(BridgeLane.EXECUTION) {
                            try { execute(generation, lease, frame) }
                            finally { synchronized(lock) { executionOutstanding = false } }
                        }
                    }
                }
            }
        } catch (e: Exception) { transportFailed(generation, e) }
    }

    private fun execute(generation: Generation, lease: GuiLease, frame: RequestFrame) {
        if (!active(generation)) return
        // Cooperative cancellation prevents future admission/reporting; it cannot undo a native
        // JADX call that already mutated the borrowed project or ignores thread interruption.
        val context = frame.context.copy(cancellation = CancellationSignal {
            generation.cancellation.isCancelled() || frame.context.cancellation.isCancelled()
        })
        var stage = "execution_validation"
        val completion = try {
            context.checkActive(clock.nowMillis())
            context.requireEpoch(generation.epoch)
            val request = frame.request as? BackendRequest ?: throw ProtocolException(
                ProtocolError(ErrorCode.INVALID_ARGUMENT, "GUI command must be a backend request"))
            if (request.sessionId != lease.sessionId) throw ProtocolException(
                ProtocolError(ErrorCode.CONFLICT, "GUI session mismatch"))
            request.validate()
            if (endpoint.capabilities.operations.none { it.operation == request.operation && it.supported }) {
                throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED, "Operation is not supported by this GUI endpoint"))
            }
            stage = "endpoint_execute"
            val result = endpoint.execute(request, context)
            stage = "result_validation"
            context.checkActive(clock.nowMillis())
            if (!request.operation.resultType.isInstance(result)) throw ProtocolException(
                ProtocolError(ErrorCode.INTERNAL, "Endpoint returned a mismatched result type"))
            CommandCompletion(context.requestId, context.projectEpoch, request.operation, result = result)
        } catch (e: Exception) {
            log("execution_failed", e, "stage" to stage, "request" to GuiDiagnosticLog.id(context.requestId),
                "session" to GuiDiagnosticLog.id(lease.sessionId), "operation" to frame.request.operation,
                "failedGeneration" to generation.number)
            CommandCompletion(context.requestId, context.projectEpoch, frame.request.operation,
                error = (e as? ProtocolException)?.error ?: ProtocolError(ErrorCode.INTERNAL, "GUI execution failed"))
        }
        // Old work can finish after a project swap or reconnect, but must never report to a new lease.
        synchronized(lock) {
            if (!active(generation)) return
            runtime.schedule(BridgeLane.CHANNEL) { report(generation, lease, completion) }
        }
    }

    private fun report(generation: Generation, lease: GuiLease, completion: CommandCompletion) {
        if (!active(generation)) return
        log("completion_sending", null, "reportGeneration" to generation.number,
            "session" to GuiDiagnosticLog.id(lease.sessionId), "request" to GuiDiagnosticLog.id(completion.requestId),
            "operation" to completion.operation, "completionError" to completion.error?.code)
        try {
            transport.complete(lease, completion)
            if (active(generation)) runtime.schedule(BridgeLane.CHANNEL) { poll(generation, lease) }
        } catch (e: Exception) {
            // Delivery is ambiguous: never replay execute/complete locally across a lease boundary.
            log("completion_delivery_failed", e, "request" to GuiDiagnosticLog.id(completion.requestId),
                "session" to GuiDiagnosticLog.id(lease.sessionId), "failedGeneration" to generation.number)
            transportFailed(generation, e)
        }
    }
}
