package emohce.data.commitmessage

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class CodexAppServerClientIdentity(
    val name: String,
    val title: String,
    val version: String,
)

internal fun interface CodexAppServerConnectionProcessFactory {
    fun start(executable: String, cwd: Path, environment: Map<String, String>): Process

    companion object {
        val SYSTEM = CodexAppServerConnectionProcessFactory { executable, cwd, environment ->
            ProcessBuilder(codexPlatformCommand(executable, listOf("app-server", "--stdio")))
                .directory(cwd.toFile())
                .also { builder -> builder.environment().putAll(environment) }
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
        }
    }
}

internal class CodexServerRequest(
    val method: String,
    val params: JsonObject,
    private val respond: (JsonElement?, JsonObject?) -> Unit,
) {
    private val responded = AtomicBoolean(false)

    fun success(result: JsonElement = JsonObject(emptyMap())) {
        if (responded.compareAndSet(false, true)) respond(result, null)
    }

    fun error(code: Int = -32_601, message: String = "Unsupported client operation") {
        if (!responded.compareAndSet(false, true)) return
        respond(
            null,
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }
}

internal interface CodexAppServerConnectionListener {
    fun onNotification(method: String, params: JsonObject) = Unit
    fun onServerRequest(request: CodexServerRequest) {
        request.error()
    }
    fun onClosed(error: CodexAppServerException) = Unit
}

/**
 * Policy-free, bounded JSONL transport shared by the strict Commit Provider and interactive Chat.
 *
 * It owns process I/O, JSON-RPC correlation, server-request response delivery, cancellation polling,
 * process-tree shutdown, and one initialize/initialized handshake. Callers exclusively own sandbox,
 * approval, tool, account, thread, and event policy.
 */
internal class CodexAppServerConnection private constructor(
    private val process: Process,
    private val listener: CodexAppServerConnectionListener,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, PendingRequest>()
    private val bestEffortRequestIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val messageCount = AtomicLong()
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    init {
        Thread({ readLoop() }, "EzCodeMark Codex App Server connection").apply {
            isDaemon = true
            start()
        }
        Thread({ drainErrors() }, "EzCodeMark Codex App Server stderr").apply {
            isDaemon = true
            start()
        }
    }

    fun initialize(
        identity: CodexAppServerClientIdentity,
        validate: (JsonObject) -> Unit = {},
    ): JsonObject {
        require(identity.name.isNotBlank() && identity.title.isNotBlank() && identity.version.isNotBlank())
        val result = request(
            "initialize",
            buildJsonObject {
                putJsonObject("clientInfo") {
                    put("name", identity.name)
                    put("title", identity.title)
                    put("version", identity.version)
                }
                putJsonObject("capabilities") { put("experimentalApi", true) }
            },
        ) as? JsonObject ?: throw protocolError("Codex App Server returned an invalid initialize response")
        validate(result)
        notify("initialized")
        return result
    }

    fun request(
        method: String,
        params: JsonElement = JsonNull,
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
        indicator: ProgressIndicator? = null,
        onCanceled: () -> Unit = {},
    ): JsonElement {
        checkOpen()
        val id = requestIds.getAndIncrement()
        val future = CompletableFuture<JsonElement>()
        val request = PendingRequest(method, future)
        pending[id] = request
        try {
            write(
                buildJsonObject {
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            )
            return awaitResponse(future, timeoutMillis, indicator)
        } catch (error: ProcessCanceledException) {
            onCanceled()
            throw error
        } finally {
            pending.remove(id, request)
        }
    }

    fun requestBestEffort(method: String, params: JsonElement = JsonNull) {
        if (closed.get() || !process.isAlive) return
        val id = requestIds.getAndIncrement()
        bestEffortRequestIds += id
        runCatching {
            write(
                buildJsonObject {
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            )
        }.onFailure { bestEffortRequestIds -= id }
    }

    fun notify(method: String, params: JsonElement = JsonObject(emptyMap())) {
        checkOpen()
        write(
            buildJsonObject {
                put("method", method)
                put("params", params)
            },
        )
    }

    fun isAlive(): Boolean = !closed.get() && process.isAlive

    override fun close() {
        close(forcibly = false)
    }

    fun close(
        forcibly: Boolean,
        failure: CodexAppServerException = CodexAppServerException(
            CodexAppServerErrorKind.CLOSED,
            "Codex App Server connection is closed",
        ),
    ) {
        if (!closed.compareAndSet(false, true)) return
        completePending(failure)
        val descendants = descendantHandles(process)
        if (forcibly) {
            stopProcess(process, forcibly = true, descendants = descendants)
            runCatching { writer.close() }
        } else {
            runCatching { writer.close() }
            stopProcess(process, forcibly = false, descendants = descendants)
        }
    }

    private fun awaitResponse(
        future: CompletableFuture<JsonElement>,
        timeoutMillis: Long,
        indicator: ProgressIndicator?,
    ): JsonElement {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            indicator?.checkCanceled()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                val error = CodexAppServerException(CodexAppServerErrorKind.REQUEST, "Codex App Server request timed out")
                shutdownFromTransport(error, forcibly = true)
                throw error
            }
            try {
                return future.get(
                    minOf(RESPONSE_POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)),
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: TimeoutException) {
                checkOpen()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CodexAppServerException(
                    CodexAppServerErrorKind.REQUEST,
                    "Codex App Server request was interrupted",
                )
            } catch (error: ExecutionException) {
                throw (error.cause as? RuntimeException)
                    ?: CodexAppServerException(CodexAppServerErrorKind.PROTOCOL, "Codex App Server request failed")
            }
        }
    }

    private fun readLoop() {
        var failure = CodexAppServerException(
            CodexAppServerErrorKind.PROCESS,
            "Codex App Server terminated unexpectedly",
        )
        try {
            InputStreamReader(process.inputStream, StandardCharsets.UTF_8).buffered().use { reader ->
                while (!closed.get()) {
                    val line = readJsonLine(reader) ?: break
                    if (line.isBlank()) continue
                    if (messageCount.incrementAndGet() > MAX_SESSION_MESSAGES) {
                        throw protocolError("Codex App Server emitted too many messages")
                    }
                    val message = try {
                        json.parseToJsonElement(line) as? JsonObject
                    } catch (_: Exception) {
                        null
                    } ?: throw protocolError("Codex App Server returned invalid JSON")
                    route(message)
                }
            }
        } catch (error: CodexAppServerException) {
            failure = error
        } catch (_: Exception) {
            // Transport and stream details stay out of user-visible errors.
        } finally {
            if (!closed.get()) shutdownFromTransport(failure, forcibly = failure.kind == CodexAppServerErrorKind.PROTOCOL)
        }
    }

    private fun route(message: JsonObject) {
        val hasResult = message.containsKey("result")
        val hasError = message.containsKey("error")
        val responseId = (message["id"] as? JsonPrimitive)?.longOrNull
        if (hasResult || hasError) {
            if (hasResult == hasError || responseId == null) throw protocolError("Codex App Server returned an invalid response")
            val request = pending.remove(responseId)
            if (request == null) {
                if (bestEffortRequestIds.remove(responseId)) return
                throw protocolError("Codex App Server returned an unmatched response")
            }
            if (hasError) {
                request.future.completeExceptionally(
                    CodexAppServerException(CodexAppServerErrorKind.REQUEST, "Codex App Server request failed"),
                )
            } else {
                request.future.complete(message["result"] ?: JsonNull)
            }
            return
        }

        val method = (message["method"] as? JsonPrimitive)?.content
            ?: throw protocolError("Codex App Server omitted a method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val serverRequestId = message["id"] as? JsonPrimitive
        if (serverRequestId != null) {
            listener.onServerRequest(
                CodexServerRequest(method, params) { result, error ->
                    val response = buildJsonObject {
                        put("id", serverRequestId)
                        if (error != null) put("error", error) else put("result", result ?: JsonNull)
                    }
                    runCatching { write(response) }
                },
            )
        } else {
            listener.onNotification(method, params)
        }
    }

    private fun write(message: JsonObject) {
        synchronized(writeLock) {
            checkOpen()
            try {
                writer.write(message.toString())
                writer.newLine()
                writer.flush()
            } catch (_: Exception) {
                val error = CodexAppServerException(
                    CodexAppServerErrorKind.PROCESS,
                    "Codex App Server terminated unexpectedly",
                )
                shutdownFromTransport(error, forcibly = false)
                throw error
            }
        }
    }

    private fun readJsonLine(reader: java.io.Reader): String? {
        val line = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= MAX_JSONL_CHARS) throw protocolError("Codex App Server event is too large")
            line.append(next.toChar())
        }
    }

    private fun checkOpen() {
        if (closed.get() || !process.isAlive) {
            throw CodexAppServerException(CodexAppServerErrorKind.CLOSED, "Codex App Server connection is closed")
        }
    }

    private fun shutdownFromTransport(error: CodexAppServerException, forcibly: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        completePending(error)
        val descendants = descendantHandles(process)
        if (forcibly) {
            stopProcess(process, forcibly = true, descendants = descendants)
            runCatching { writer.close() }
        } else {
            runCatching { writer.close() }
            stopProcess(process, forcibly = false, descendants = descendants)
        }
        runCatching { listener.onClosed(error) }
    }

    private fun completePending(error: CodexAppServerException) {
        pending.values.forEach { it.future.completeExceptionally(error) }
        pending.clear()
        bestEffortRequestIds.clear()
    }

    private fun drainErrors() {
        runCatching {
            process.errorStream.use { stream ->
                val buffer = ByteArray(8_192)
                while (true) {
                    if (stream.read(buffer) < 0) break
                }
            }
        }
    }

    private fun descendantHandles(process: Process): List<ProcessHandle> = runCatching {
        process.toHandle().descendants().toList().asReversed()
    }.getOrDefault(emptyList())

    private fun stopProcess(process: Process, forcibly: Boolean, descendants: List<ProcessHandle>) {
        if (forcibly) {
            descendants.forEach { runCatching { it.destroyForcibly() } }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            waitForHandles(descendants, FORCE_CLOSE_MILLIS)
            runCatching { process.waitFor(FORCE_CLOSE_MILLIS, TimeUnit.MILLISECONDS) }
            return
        }

        runCatching { process.waitFor(GRACEFUL_CLOSE_MILLIS, TimeUnit.MILLISECONDS) }
        descendants.forEach { runCatching { it.destroy() } }
        runCatching { if (process.isAlive) process.destroy() }
        waitForHandles(descendants, DESTROY_CLOSE_MILLIS)
        runCatching { process.waitFor(DESTROY_CLOSE_MILLIS, TimeUnit.MILLISECONDS) }
        descendants.forEach { runCatching { it.destroyForcibly() } }
        runCatching { if (process.isAlive) process.destroyForcibly() }
        waitForHandles(descendants, FORCE_CLOSE_MILLIS)
        runCatching { process.waitFor(FORCE_CLOSE_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun waitForHandles(handles: List<ProcessHandle>, timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun protocolError(message: String) =
        CodexAppServerException(CodexAppServerErrorKind.PROTOCOL, message)

    private data class PendingRequest(
        val method: String,
        val future: CompletableFuture<JsonElement>,
    )

    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val RESPONSE_POLL_MILLIS = 50L
        private const val GRACEFUL_CLOSE_MILLIS = 1_000L
        private const val DESTROY_CLOSE_MILLIS = 1_000L
        private const val FORCE_CLOSE_MILLIS = 500L
        private const val MAX_JSONL_CHARS = 4_000_000
        private const val MAX_SESSION_MESSAGES = 100_000L

        fun open(
            executable: String,
            cwd: Path,
            environment: Map<String, String>,
            listener: CodexAppServerConnectionListener,
            processFactory: CodexAppServerConnectionProcessFactory = CodexAppServerConnectionProcessFactory.SYSTEM,
        ): CodexAppServerConnection {
            require(executable.isNotBlank()) { "Codex executable is required" }
            require(cwd.isAbsolute) { "Codex working directory must be absolute" }
            val process = try {
                processFactory.start(executable, cwd, environment)
            } catch (_: Exception) {
                throw CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Codex App Server could not be started")
            }
            return try {
                CodexAppServerConnection(process, listener)
            } catch (_: Exception) {
                runCatching { process.destroyForcibly() }
                throw CodexAppServerException(CodexAppServerErrorKind.PROCESS, "Codex App Server could not be started")
            }
        }

        fun start(
            executable: String,
            cwd: Path,
            environment: Map<String, String>,
            identity: CodexAppServerClientIdentity,
            listener: CodexAppServerConnectionListener,
            processFactory: CodexAppServerConnectionProcessFactory = CodexAppServerConnectionProcessFactory.SYSTEM,
            validateInitialize: (JsonObject) -> Unit = {},
        ): Pair<CodexAppServerConnection, JsonObject> {
            val connection = open(executable, cwd, environment, listener, processFactory)
            return try {
                connection to connection.initialize(identity, validateInitialize)
            } catch (error: RuntimeException) {
                connection.close(forcibly = true)
                throw error
            }
        }
    }
}
