package com.byd.dashcast.clusterlive

/** OEM callbacks are broadcasts without request IDs. Windows constrain evidence, never correlate it. */
internal object ClusterThemeIviExchange {
    interface Transport : AutoCloseable {
        fun verifyIdentity()
        fun alive(): Boolean
        fun register(receiver: (Int, ByteArray) -> Unit): Int
        fun send(bytes: ByteArray): Int
        fun verifyCarrier()
    }
    data class Receipt(val native: Int?, val queryNative: Int?, val responses: List<ByteArray>,
        val postQueryResponses: List<ByteArray>, val attempted: Boolean, val error: String?, val trace: List<String>)

    fun execute(operation: String, name: String, transport: Transport, deadline: Long,
        clock: () -> Long, pause: () -> Unit, log: (String) -> Unit = {}): Receipt {
        require(operation in setOf("query", "load", "restore") && ClusterThemePolicy.ownedName(name))
        val lock = Any()
        val events = ArrayList<ByteArray>()
        val trace = ArrayList<String>()
        var accepting = false
        var mutationWindow = false
        var ended = false
        var invalid = ""
        var native: Int? = null
        var queryNative: Int? = null
        var attempted = false
        var post = emptyList<ByteArray>()
        fun step(message: String) {
            val line = "elapsedMs=${clock()} ${message.take(256)}"
            if (trace.size < 24) trace.add(line)
            log(line)
        }
        fun current() {
            check(clock() < deadline && transport.alive()) { "Pinned Binder/deadline expired; no reconnect/replay" }
            synchronized(lock) { check(invalid.isEmpty()) { invalid } }
        }
        fun collectFrom(floor: Int): List<ByteArray> = synchronized(lock) { events.drop(floor) }
        fun selection(bytes: List<ByteArray>): ClusterThemeProtocol.Response? {
            val choices = bytes.map { ClusterThemeProtocol.decode(it) }.filter { it.cmd == 1001 && it.sub == 4 }
            check(choices.distinct().size <= 1) { "Conflicting broadcast selection responses; uncorrelated evidence" }
            return choices.lastOrNull()
        }
        fun query(): Pair<ClusterThemeProtocol.Response, List<ByteArray>> {
            current()
            val floor = synchronized(lock) { events.size }
            queryNative = transport.send(ClusterThemeProtocol.query())
            step("selection-query native=$queryNative callbackCorrelation=UNAVAILABLE")
            check(queryNative == 0) { "Selection query native=$queryNative" }
            val until = minOf(deadline, clock() + 800)
            while (clock() < until) { current(); pause() }
            current()
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
                            if (decoded == null) invalid = "Malformed broadcast response"
                            else if (decoded.cmd == 1005 && decoded.sub == 0 && decoded.ints[0] == 3) {
                                events.add(bytes.copyOf()); invalid = "OEM resource ACK3 failure; completion ambiguous"
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
            if (operation == "query") {
                native = queryNative; post = baseline.second
            } else {
                check(baseline.first.strings[0] == ClusterThemeProtocol.UI_VERSION) { "UI contract version differs" }
                check(if (operation == "load") baseline.first.isDefault()
                    else baseline.first.isDefault() || baseline.first.selected(name)) { "Baseline is custom/incompatible; no overwrite" }
                transport.verifyIdentity(); current()
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
                    current()
                    if (operation == "load" && collectFrom(mutationFloor).map { ClusterThemeProtocol.decode(it) }
                            .any { ClusterThemePolicy.loadAckMatches(it, name) }) break
                    pause()
                }
                val selected = query()
                post = selected.second
                check(if (operation == "load") selected.first.selected(name) else selected.first.isDefault()) {
                    "Post-query selection does not match requested target"
                }
                val decoded = synchronized(lock) { events.drop(mutationFloor).map { ClusterThemeProtocol.decode(it) } }
                val ownAck = decoded.any { ClusterThemePolicy.loadAckMatches(it, name) }
                step("resource-acks ownAck2Matched=$ownAck ACK4=${decoded.any { it.cmd == 1005 && it.sub == 0 && it.ints[0] == 4 }} expectedHostPath=${ClusterThemePolicy.HOST_DIRECTORY}$name")
                if (operation == "load" && !ownAck) step("load-not-settled: hot reload may omit ACK2; fresh owned selection received; explicit visual observation required")
            }
            transport.verifyIdentity(); current()
            step("identity-rechecked callbackCorrelation=UNAVAILABLE pixels=UNKNOWN")
        } catch (error: Exception) {
            failure = "${error.javaClass.simpleName}: ${error.message}".take(768)
            step("exchange-failed attempted=$attempted $failure")
        } finally {
            synchronized(lock) { accepting = false; ended = true }
            transport.close()
        }
        return Receipt(native, queryNative, synchronized(lock) { events.toList() }, post, attempted, failure, trace)
    }
}
