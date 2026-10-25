package com.byd.dashcast.clusterlive

/** OEM callbacks are broadcasts without request IDs. Windows constrain evidence, never correlate it. */
internal object ClusterThemeIviExchange {
    interface Transport : AutoCloseable {
        fun verifyIdentity()
        fun alive(): Boolean
        fun register(receiver: (Int, ByteArray) -> Unit): Int
        fun send(bytes: ByteArray): Int
        fun verifyCarrier()
        fun verifyLoadCohort(): String
    }
    data class Receipt(val native: Int?, val queryNative: Int?, val responses: List<ByteArray>,
        val postQueryResponses: List<ByteArray>, val attempted: Boolean, val error: String?, val trace: List<String>,
        val retirementEligible: Boolean = false)

    fun execute(operation: String, name: String, transport: Transport, deadline: Long,
        clock: () -> Long, pause: () -> Unit, log: (String) -> Unit = {}): Receipt {
        require(operation in setOf("query", "load", "restore") && ClusterThemePolicy.ownedName(name))
        val lock = Any()
        val events = ArrayList<ByteArray>()
        val callbackReceipts = ArrayList<String>()
        val trace = ArrayList<String>()
        var accepting = false
        var mutationWindow = false
        var ended = false
        var invalid = ""
        var resourceFailure = ""
        var native: Int? = null
        var queryNative: Int? = null
        var attempted = false
        var retirementEligible = false
        var post = emptyList<ByteArray>()
        var lastQueryFloor = 0
        fun step(message: String) {
            val line = "elapsedMs=${clock()} ${message.take(384)}"
            if (trace.size < 64) trace.add(line)
            log(line)
        }
        fun current(readAfterFailure: Boolean = false) {
            check(clock() < deadline && transport.alive()) { "Pinned Binder/deadline expired; no reconnect/replay" }
            synchronized(lock) {
                check(invalid.isEmpty()) { invalid }
                check(readAfterFailure || resourceFailure.isEmpty()) { resourceFailure }
            }
        }
        fun collectFrom(floor: Int): List<ByteArray> = synchronized(lock) { events.drop(floor) }
        fun selection(bytes: List<ByteArray>): ClusterThemeProtocol.Response? {
            val choices = bytes.map { ClusterThemeProtocol.decode(it) }.filter { it.cmd == 1001 && it.sub == 4 }
            check(choices.distinct().size <= 1) { "Conflicting broadcast selection responses; uncorrelated evidence" }
            return choices.lastOrNull()
        }
        fun query(readAfterFailure: Boolean = false): Pair<ClusterThemeProtocol.Response, List<ByteArray>> {
            current(readAfterFailure)
            val floor = synchronized(lock) { events.size }
            lastQueryFloor = floor
            queryNative = transport.send(ClusterThemeProtocol.query())
            step("selection-query native=$queryNative callbackCorrelation=UNAVAILABLE")
            check(queryNative == 0) { "Selection query native=$queryNative" }
            val until = minOf(deadline, clock() + 800)
            while (clock() < until) { current(readAfterFailure); pause() }
            current(readAfterFailure)
            val bytes = collectFrom(floor)
            val selected = selection(bytes) ?: error("No supported fresh selection response")
            step("selection-received UI=${selected.strings[0]} source=${selected.ints[3]} path=${selected.strings[2]} resource=${selected.strings[4]}")
            return selected to bytes
        }
        var failure: String? = null
        try {
            transport.verifyIdentity(); current(); step("installed-Java-identity-verified nativeIdentity=UNKNOWN")
            val registration = transport.register { uid, bytes -> synchronized(lock) {
                if (!ended && accepting && clock() < deadline) {
                    when {
                        uid != 1000 -> invalid = "Callback UID differs from system Java bridge"
                        bytes.size !in 8..4096 -> invalid = "Unsupported callback payload size"
                        events.size >= 32 -> invalid = "Callback bound exceeded; evidence incomplete"
                        else -> {
                            val decoded = runCatching { ClusterThemeProtocol.decode(bytes) }.getOrNull()
                            if (decoded != null && callbackReceipts.size < 32) {
                                val meaning = when {
                                    decoded.cmd == 1005 && decoded.ints[0] == 2 -> "ACK2_REGISTRATION_BROADCAST_UNCORRELATED ownPath=${ClusterThemePolicy.loadAckMatches(decoded, name)}"
                                    decoded.cmd == 1005 && decoded.ints[0] == 4 -> "ACK4_CONFIG_ONLY"
                                    decoded.cmd == 1005 && decoded.ints[0] == 3 -> "ACK3_FAILURE"
                                    decoded.cmd == 1001 && decoded.sub == 4 -> "SELECTION_ONLY"
                                    else -> "BROADCAST"
                                }
                                val path = decoded.strings[if (decoded.cmd == 1001) 2 else 0].replace(Regex("[\\p{Cntrl}]"), " ").take(190)
                                callbackReceipts.add("receivedElapsedMs=${clock()} stage=${if (mutationWindow) "mutation-or-postquery" else "baseline"} $meaning cmd=${decoded.cmd} sub=${decoded.sub} ints=${decoded.ints} path=$path")
                            }
                            if (decoded == null) invalid = "Malformed broadcast response"
                            else if (decoded.cmd == 1005 && decoded.sub == 0 && decoded.ints[0] == 3) {
                                events.add(bytes.copyOf())
                                if (resourceFailure.isEmpty()) resourceFailure = "OEM resource ${decoded.resourceFailureSummary()}; completion ambiguous"
                            }
                            else if (decoded.cmd == 1005 && decoded.sub == 0 && decoded.ints[0] == 2 &&
                                (operation == "load" && (!mutationWindow || !ClusterThemePolicy.loadAckMatches(decoded, name)) ||
                                    operation == "restore" && (!mutationWindow || decoded.strings[0] != "default") || operation == "query"))
                                invalid = "Conflicting or out-of-window resource ACK2"
                            else events.add(bytes.copyOf())
                        }
                    }
                }
            } }
            step("callback-registration native=$registration")
            check(registration == 0) { "Callback registration refused: $registration" }
            synchronized(lock) { accepting = true }
            val baseline = query()
            if (operation == "query" || operation == "restore" && baseline.first.isFactoryConfigured()) {
                native = queryNative; post = baseline.second
                if (operation == "restore") step("factory-already-configured: no mode1 replay; native Qt path property and pixels require verification")
            } else {
                check(baseline.first.strings[0] == ClusterThemeProtocol.UI_VERSION) { "UI contract version differs" }
                check(if (operation == "load") baseline.first.isDefault()
                    else baseline.first.isFactoryConfigured() || baseline.first.selected(name)) { "Baseline is custom/incompatible; no overwrite" }
                transport.verifyIdentity(); current()
                if (operation == "load") {
                    val cohort = transport.verifyLoadCohort(); current()
                    step("installed-artifact-cohort-verified id=$cohort loadedNativeIdentity=UNKNOWN")
                }
                transport.verifyCarrier()
                current()
                // The daemon durably recorded its debt/helper identity before this helper received GO.
                val mutationFloor = synchronized(lock) { mutationWindow = true; events.size }
                attempted = true
                step("mutation-attempt operation=$operation filename=$name")
                native = transport.send(if (operation == "load") ClusterThemeProtocol.load(name) else ClusterThemeProtocol.restoreDefault())
                step("mutation-native native=$native pixels=UNKNOWN")
                check(native == 0) { "Mutation native=$native; remote completion UNKNOWN" }
                val settle = minOf(deadline, clock() + if (operation == "load") 2500 else 500)
                while (clock() < settle) {
                    current(readAfterFailure = true)
                    if (synchronized(lock) { resourceFailure.isNotEmpty() }) break
                    if (operation == "load" && collectFrom(mutationFloor).map { ClusterThemeProtocol.decode(it) }
                            .any { ClusterThemePolicy.loadAckMatches(it, name) }) break
                    pause()
                }
                // ACK3 is a failed mutation, but the same pinned bridge can still report its selection.
                val selected = query(readAfterFailure = true)
                post = selected.second
                if (synchronized(lock) { resourceFailure.isNotEmpty() }) {
                    transport.verifyIdentity(); current(readAfterFailure = true)
                    synchronized(lock) {
                        accepting = false; ended = true
                        check(clock() < deadline && transport.alive()) { "Pinned Binder/deadline expired before negative-response seal" }
                        check(invalid.isEmpty()) { invalid }
                        // Include any selection/ACK that arrived during the final identity check.
                        val finalSelection = selection(events.drop(mutationFloor))
                        retirementEligible = operation == "restore" && ClusterThemeRetirement.eligible(name, finalSelection,
                            events.drop(mutationFloor).map { ClusterThemeProtocol.decode(it) }, native, attempted)
                    }
                    step("negative-response-validated retirementEligible=$retirementEligible nativeIdentity=UNKNOWN")
                }
                synchronized(lock) { check(resourceFailure.isEmpty()) { resourceFailure } }
                check(if (operation == "load") selected.first.selected(name) else selected.first.isFactoryConfigured()) {
                    "Post-query selection does not match requested target"
                }
                val decoded = synchronized(lock) { events.drop(mutationFloor).map { ClusterThemeProtocol.decode(it) } }
                val ownAck = decoded.any { ClusterThemePolicy.loadAckMatches(it, name) }
                step("resource-acks ownAck2Matched=$ownAck ACK4=${decoded.any { it.cmd == 1005 && it.sub == 0 && it.ints[0] == 4 }} expectedHostPath=${ClusterThemePolicy.HOST_DIRECTORY}$name")
                if (operation == "load" && !ownAck) step("load-not-settled: ACK2 absent; Qt registration and QML creation not established; fresh owned selection only")
            }
            transport.verifyIdentity(); current()
            step("identity-rechecked callbackCorrelation=UNAVAILABLE pixels=UNKNOWN")
            synchronized(lock) {
                accepting = false; ended = true
                check(clock() < deadline && transport.alive()) { "Pinned Binder/deadline expired before success seal" }
                check(invalid.isEmpty()) { invalid }
                check(resourceFailure.isEmpty()) { resourceFailure }
                val finalBytes = events.drop(lastQueryFloor)
                val finalSelection = selection(finalBytes) ?: error("Fresh selection missing at success seal")
                check(operation == "query" || if (operation == "load") finalSelection.selected(name) else finalSelection.isFactoryConfigured()) {
                    "Final broadcast selection conflicts with requested target"
                }
                post = finalBytes
            }
        } catch (error: Exception) {
            val negative = synchronized(lock) { resourceFailure }
            val message = "${error.javaClass.simpleName}: ${error.message}"
            failure = (if (negative.isNotEmpty() && !message.contains(negative)) "$negative; $message" else message).take(768)
            step("exchange-failed attempted=$attempted $failure")
        } finally {
            synchronized(lock) { accepting = false; ended = true }
            // Emit only after sealing: logging must never block the Binder callback while holding its lock.
            synchronized(lock) { callbackReceipts.toList() }.forEach { step("callback-receipt $it") }
            synchronized(lock) { events.map { ClusterThemeProtocol.decode(it) }
                .filter { it.cmd == 1005 && it.sub == 0 && it.ints[0] == 3 }.take(4) }
                .forEach { step("resource-response ${it.resourceFailureSummary()}") }
            transport.close()
        }
        return Receipt(native, queryNative, synchronized(lock) { events.toList() }, post, attempted, failure, trace, retirementEligible)
    }
}
