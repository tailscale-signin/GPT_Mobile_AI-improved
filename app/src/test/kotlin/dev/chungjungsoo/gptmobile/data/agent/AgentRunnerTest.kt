package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerTest {
    @Test
    fun `exhausted anonymous quota disables only the failing tool after one round`() = kotlinx.coroutines.test.runTest {
        var attempts = 0
        var siblingCalls = 0
        val events = AgentRunner().run(
            session { tools, exchanges ->
                flow {
                    if (exchanges.isEmpty()) {
                        emit(toolCall("quota", "ask_pipeworx"))
                    } else if (exchanges.size == 1) {
                        assertFalse(tools.any { it.name == "ask_pipeworx" })
                        assertTrue(tools.any { it.name == "news" })
                        emit(toolCall("fallback", "news"))
                    } else {
                        emit(ProviderEvent.TextDelta("Answer from the available news provider"))
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(
                tool("ask_pipeworx") { id, _ ->
                    attempts++
                    AgentToolResult(id, ToolResultContent.Text("Today's 50 free anonymous calls are used up. Claim a free key for 4x the calls."), true)
                },
                tool("news") { id, _ ->
                    siblingCalls++
                    AgentToolResult(id, ToolResultContent.Text("Available headlines"), false)
                }
            )
        ).toList()
        assertEquals(1, attempts)
        assertEquals(1, siblingCalls)
        assertEquals(AgentRunEvent.Provider(ProviderEvent.Completed), events.last())
        assertFalse(terminalToolFailure("HTTP 429: retry after 2 seconds"))
    }

    @Test
    fun `legacy MCP search translates nullable filters and larger requested source counts`() = kotlinx.coroutines.test.runTest {
        var attempts = 0
        AgentRunner().run(
            session { _, exchanges ->
                flow {
                    if (exchanges.isEmpty()) {
                        emit(
                            toolCall(
                                "legacy",
                                "mcp__usearch__web_search",
                                buildJsonObject {
                                    put("query", "Canadian news")
                                    put("maxResults", 20)
                                    put("recencyDays", kotlinx.serialization.json.JsonNull)
                                    put("includeDomains", kotlinx.serialization.json.JsonNull)
                                }
                            )
                        )
                    } else {
                        emit(ProviderEvent.TextDelta("Answer"))
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(
                tool("web_search") { id, arguments ->
                    attempts++
                    assertEquals(
                        buildJsonObject {
                            put("query", "Canadian news")
                            put("maxResults", 10)
                            put("totalResults", 20)
                        },
                        arguments
                    )
                    AgentToolResult(id, ToolResultContent.Text("Search evidence"), false)
                }
            )
        ).toList()
        assertEquals(1, attempts)
    }

    @Test fun `repeat guard preserves calls admitted earlier in the same batch`() = runBlocking {
        var executed = 0
        var rounds = 0
        val events = AgentRunner(AgentRunLimits(maxToolCalls = 50)).run(
            session { _, _ ->
                flow {
                    if (rounds++ == 0) repeat(26) { emit(toolCall("call-$it", "search")) } else emit(ProviderEvent.TextDelta("Finished"))
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(
                tool("search") { id, _ ->
                    executed++
                    AgentToolResult(id, ToolResultContent.Text("ok"), false)
                }
            )
        ).toList()
        assertEquals(24, executed)
        assertEquals(24, events.filterIsInstance<AgentRunEvent.ToolFinished>().count { !it.result.isError })
    }

    @Test fun `unassigned MCP search routes through the authorized aggregate only`() = kotlinx.coroutines.test.runTest {
        var searches = 0
        val events = AgentRunner().run(
            session { _, exchanges ->
                flow {
                    if (exchanges.isEmpty()) {
                        emit(toolCall("search", "mcp__usearch__web_search", buildJsonObject { put("query", "France timeline") }))
                    } else {
                        assertEquals("web_search", exchanges.single().calls.single().name)
                        emit(ProviderEvent.TextDelta("Grounded answer"))
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(
                tool("web_search") { id, arguments ->
                    searches++
                    assertEquals("France timeline", (arguments["query"] as kotlinx.serialization.json.JsonPrimitive).content)
                    AgentToolResult(id, ToolResultContent.Text("Configured engine evidence"), false)
                }
            )
        ).toList()
        assertEquals(1, searches)
        assertFalse(events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result.isError)
    }

    @Test fun `unassigned connections stay blocked when no aggregate is assigned`() = kotlinx.coroutines.test.runTest {
        val events = AgentRunner().run(
            session { _, exchanges ->
                flow {
                    if (exchanges.isEmpty()) emit(toolCall("search", "mcp__usearch__web_search", buildJsonObject { put("query", "France timeline") })) else emit(ProviderEvent.TextDelta("No search connection assigned"))
                    emit(ProviderEvent.Completed)
                }
            },
            emptyList()
        ).toList()
        assertTrue(events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result.isError)
    }

    @Test fun `malformed tool arguments recover once without executing any incomplete action`() = kotlinx.coroutines.test.runTest {
        var rounds = 0
        var actions = 0
        val events = AgentRunner().run(
            session { tools, _ ->
                flow {
                    rounds++
                    if (rounds == 1) {
                        emit(toolCall("incomplete", "write"))
                        emit(ProviderEvent.Failed("Tool arguments were not valid JSON."))
                        emit(ProviderEvent.Usage(100, 4096, 4196))
                    } else {
                        assertTrue(tools.isEmpty())
                        emit(ProviderEvent.TextDelta("The incomplete action was not performed."))
                        emit(ProviderEvent.Completed)
                    }
                }
            },
            listOf(
                tool("write") { id, _ ->
                    actions++
                    AgentToolResult(id, ToolResultContent.Text("done"), false)
                }
            )
        ).toList()
        assertEquals(2, rounds)
        assertEquals(0, actions)
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertTrue(events.any { it is AgentRunEvent.Provider && (it.event as? ProviderEvent.Usage)?.totalTokens == 4196 })
    }

    @Test fun `connection abort retry preserves completed tools and cannot loop`() = kotlinx.coroutines.test.runTest {
        var rounds = 0
        var actions = 0
        val events = AgentRunner().run(
            session { tools, exchanges ->
                flow {
                    rounds++
                    if (rounds == 1) {
                        emit(toolCall("completed", "read"))
                        emit(ProviderEvent.Completed)
                    } else {
                        assertEquals(1, exchanges.size)
                        if (rounds == 3) assertTrue(tools.isEmpty())
                        emit(ProviderEvent.Failed("Software caused connection abort"))
                    }
                }
            },
            listOf(
                tool("read") { id, _ ->
                    actions++
                    AgentToolResult(id, ToolResultContent.Text("Evidence"), false)
                }
            )
        ).toList()
        assertEquals(3, rounds)
        assertEquals(1, actions)
        assertEquals(1, events.count { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test fun `read url circuit pauses only the failing host`() = runBlocking {
        val executed = mutableListOf<String>()
        val reader = tool("read_url") { id, args ->
            val url = (args.getValue("url") as kotlinx.serialization.json.JsonPrimitive).content
            executed += url
            AgentToolResult(id, ToolResultContent.Text(if (url.contains("blocked")) "HTTP 403" else "Evidence"), url.contains("blocked"))
        }
        val urls = listOf("https://blocked.example/a", "https://blocked.example/b", "https://blocked.example/c", "https://blocked.example/d", "https://healthy.example/a")
        val events = AgentRunner(AgentRunLimits(maxRounds = 8)).run(
            session { tools, exchanges ->
                flow {
                    assertTrue(tools.any { it.name == "read_url" })
                    urls.getOrNull(exchanges.size)?.let { url ->
                        emit(ProviderEvent.ToolCall("read-${exchanges.size}", "read_url", buildJsonObject { put("url", url) }))
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(reader)
        ).toList()
        assertEquals(urls.take(3) + urls.last(), executed)
        assertEquals(3, executed.count { "blocked" in it })
        assertTrue(events.filterIsInstance<AgentRunEvent.ToolFinished>().any { it.result.content.toString().contains("other hosts") })
    }

    @Test
    fun `GitHub resource and permission failures do not disable later valid operations`() = runBlocking {
        var executions = 0
        val github = tool("github__github_api") { id, _ ->
            executions++
            AgentToolResult(
                id,
                ToolResultContent.Text(
                    if (executions <= 3) "GITHUB_NOT_FOUND_OR_HIDDEN: invalid path" else "Found valid resource"
                ),
                executions <= 3
            )
        }
        AgentRunner(AgentRunLimits(maxRounds = 8)).run(
            session { tools, exchanges ->
                flow {
                    assertTrue(tools.any { it.name == "github__github_api" })
                    if (exchanges.size < 4) emit(toolCall("github-${exchanges.size}", "github__github_api"))
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(github)
        ).toList()
        assertEquals(4, executions)
    }

    @Test
    fun `partial delegation allows primary GitHub recovery after helper capability failure`() = runBlocking {
        var writes = 0
        val delegate = tool("delegate_to_model") { id, _ ->
            AgentToolResult(id, ToolResultContent.Text("Helper lacks GitHub access. Use primary GitHub tools."), true)
        }
        val github = tool("github__work") { id, _ ->
            writes++
            AgentToolResult(id, ToolResultContent.Text("Draft PR created"), false)
        }
        val resolved = listOf(delegate, github).map {
            dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool(it, null, null, it.definition.name, it.definition.name)
        }
        val tools = dev.chungjungsoo.gptmobile.data.agent.tool.primaryDelegationTools(resolved, true, 25).map { it.tool }
        val events = AgentRunner().run(
            session { schemas, exchanges ->
                flow {
                    assertTrue(schemas.any { it.name == "github__work" })
                    when (exchanges.size) {
                        0 -> emit(toolCall("delegate", "delegate_to_model"))
                        1 -> {
                            assertTrue(exchanges.single().results.single().isError)
                            emit(toolCall("write", "github__work"))
                        }
                        else -> emit(ProviderEvent.TextDelta("Draft PR created"))
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            tools
        ).toList()
        assertEquals(1, writes)
        assertEquals(2, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
        assertEquals(AgentRunEvent.Provider(ProviderEvent.Completed), events.last())
    }

    @Test
    fun `context guard reserves a final answer and asks to continue`() = runBlocking {
        val exposed = mutableListOf<Int>()
        var rounds = 0
        val session = session { tools, exchanges ->
            exposed += tools.size
            if (rounds++ == 0) {
                flow {
                    emit(toolCall("context-call"))
                    emit(ProviderEvent.Completed)
                }
            } else {
                flow {
                    assertTrue(exchanges.last().results.last().content.toString().contains("ask whether the user wants to continue"))
                    emit(ProviderEvent.TextDelta("Here are my findings. Continue?"))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val tool = tool { id, _ -> AgentToolResult(id, ToolResultContent.Text("source ".repeat(40)), false) }
        val events = AgentRunner(AgentRunLimits(contextTokens = 2048, initialContextTokens = 1600, finalResponseReserveTokens = 256)).run(session, listOf(tool)).toList()
        assertEquals(listOf(1, 0), exposed)
        assertEquals(AgentRunEvent.Provider(ProviderEvent.Completed), events.last())
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test
    fun `exhausted search output closes tool use and requests the final answer`() = runBlocking {
        val limits = AgentRunLimits(maxToolOutputBytes = 32)
        val bound = ToolExecutionBudget(limits).bind(
            tool("web_search") { callId, _ ->
                AgentToolResult(callId, ToolResultContent.Text("Useful source " + "x".repeat(100)), false)
            }
        )
        val events = AgentRunner(limits).run(
            session { tools, exchanges ->
                flow {
                    if (exchanges.isEmpty()) {
                        emit(toolCall("search", "web_search"))
                    } else {
                        assertTrue(tools.isEmpty())
                        val resultText = (exchanges.single().results.single().content as ToolResultContent.Text).text
                        assertTrue(resultText.contains("Useful source"))
                        assertTrue(resultText.contains("tool-result byte budget is exhausted"))
                        assertFalse(resultText.contains("tool-call allowance is exhausted"))
                        emit(ProviderEvent.TextDelta("Answer from the source"))
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(bound)
        ).toList()
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.TextDelta })
        assertTrue(
            events.filterIsInstance<AgentRunEvent.Notice>().any {
                it.message.contains("Tool-result byte budget reached: 32/32 bytes used")
            }
        )
    }

    @Test
    fun `shared child call budget notice uses shared counters rather than top level call count`() = runBlocking {
        val budgeted = object : AgentTool {
            override val definition = AgentToolDefinition("delegate_to_model", "", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("completed delegated evidence"),
                isError = false,
                toolCallBudgetExhausted = true,
                toolCallBudgetUsed = 9,
                toolCallBudgetLimit = 9,
                toolCallBudgetConfigured = 10,
                toolCallBudgetReserved = 1
            )
        }
        val events = AgentRunner(AgentRunLimits(maxToolCalls = 50)).run(
            session { tools, exchanges ->
                flow {
                    if (exchanges.isEmpty()) {
                        emit(toolCall("delegate", "delegate_to_model"))
                    } else {
                        assertTrue(tools.isEmpty())
                        emit(ProviderEvent.TextDelta("Answer from completed evidence"))
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(budgeted)
        ).toList()

        assertTrue(
            events.filterIsInstance<AgentRunEvent.Notice>().any {
                it.message.contains("Shared tool-call limit reached: 9/9") &&
                    it.message.contains("10 configured, 1 reserved")
            }
        )
    }

    @Test
    fun `approval latency is not charged to tool timeout by the runner`() = runBlocking {
        val limits = AgentRunLimits(toolTimeoutMillis = 5)
        val bound = ToolExecutionBudget(limits).bind(
            tool("write") { callId, _ ->
                AgentToolResult(callId, ToolResultContent.Text("saved"), isError = false)
            }
        ) { _, _ ->
            delay(40)
            true
        }
        val events = AgentRunner(limits).run(
            session { _, exchanges ->
                if (exchanges.isEmpty()) {
                    flow {
                        emit(toolCall("call", "write"))
                        emit(ProviderEvent.Completed)
                    }
                } else {
                    flow {
                        emit(ProviderEvent.TextDelta("done"))
                        emit(ProviderEvent.Completed)
                    }
                }
            },
            listOf(bound)
        ).toList()
        assertFalse(events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result.isError)
    }

    @Test
    fun `no tool run calls provider once and preserves text completion`() = runBlocking {
        val calls = AtomicInteger()
        val session = session { tools, exchanges ->
            assertTrue(tools.isEmpty())
            assertTrue(exchanges.isEmpty())
            calls.incrementAndGet()
            flow {
                emit(ProviderEvent.TextDelta("answer"))
                emit(ProviderEvent.Completed)
            }
        }

        val events = AgentRunner().run(session, emptyList()).toList()

        assertEquals(1, calls.get())
        assertEquals(
            listOf(
                AgentRunEvent.Provider(ProviderEvent.TextDelta("answer")),
                AgentRunEvent.Provider(ProviderEvent.Completed)
            ),
            events
        )
    }

    @Test
    fun `independent tool calls execute with at most four concurrent calls`() = runBlocking {
        val providerCalls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val session = session { _, exchanges ->
            when (providerCalls.getAndIncrement()) {
                0 -> flow {
                    repeat(6) { index ->
                        emit(toolCall("call_$index", "lookup_$index", buildJsonObject { put("index", index) }))
                    }
                    emit(ProviderEvent.Completed)
                }

                else -> flow {
                    assertEquals((0 until 6).map { "call_$it" }, exchanges.single().calls.map { it.callId })
                    assertEquals((0 until 6).map { "call_$it" }, exchanges.single().results.map { it.callId })
                    emit(ProviderEvent.TextDelta("done"))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val tools = (0 until 6).map { index ->
            tool("lookup_$index") { callId, _ ->
                val current = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, current) }
                delay(30)
                active.decrementAndGet()
                AgentToolResult(callId, ToolResultContent.Text("ok"), isError = false)
            }
        }

        val events = AgentRunner(
            limits = AgentRunLimits(maxConcurrentTools = 4)
        ).run(session, tools).toList()

        assertEquals(4, maxActive.get())
        assertEquals(6, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
        assertTrue(events.contains(AgentRunEvent.Provider(ProviderEvent.TextDelta("done"))))
    }

    @Test
    fun `same tool queue does not consume concurrency slots needed by other tools`() = runBlocking {
        val independentFinished = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val slow = tool("slow") { id, _ ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            independentFinished.await()
            active.decrementAndGet()
            AgentToolResult(id, ToolResultContent.Text("ok"), false)
        }
        val independent = tool("independent") { id, _ ->
            independentFinished.complete(Unit)
            AgentToolResult(id, ToolResultContent.Text("ok"), false)
        }
        val events = AgentRunner(AgentRunLimits(maxConcurrentTools = 2, toolTimeoutMillis = 1000)).run(
            session { _, exchanges ->
                flow {
                    if (exchanges.isEmpty()) {
                        repeat(4) { emit(toolCall("slow-$it", "slow")) }
                        emit(toolCall("independent", "independent"))
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(slow, independent)
        ).toList()
        assertEquals(1, maximum.get())
        assertEquals(5, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
        assertFalse(events.filterIsInstance<AgentRunEvent.ToolFinished>().any { it.result.isError })
    }

    @Test
    fun `parallel failure burst counts once per round instead of once per invocation`() = runBlocking {
        var executions = 0
        val failing = tool("lookup") { id, _ ->
            executions++
            AgentToolResult(id, ToolResultContent.Text("network unavailable"), true)
        }
        var round = 0
        AgentRunner(AgentRunLimits(maxRounds = 8, maxToolCalls = 20)).run(
            session { tools, _ ->
                flow {
                    if (tools.isNotEmpty()) {
                        repeat(4) { emit(toolCall("$round-$it", "lookup")) }
                        round++
                    }
                    emit(ProviderEvent.Completed)
                }
            },
            listOf(failing)
        ).toList()
        assertEquals(3, round)
        assertEquals(12, executions)
    }

    @Test
    fun `usage snapshots collapse within a round and add across tool rounds`() = runBlocking {
        val providerCalls = AtomicInteger()
        val session = session { _, _ ->
            when (providerCalls.getAndIncrement()) {
                0 -> flow {
                    emit(ProviderEvent.Usage(inputTokens = 100, outputTokens = 10, totalTokens = 110))
                    emit(ProviderEvent.Usage(inputTokens = 100, outputTokens = 20, totalTokens = 120))
                    emit(toolCall("usage_call"))
                    emit(ProviderEvent.Completed)
                }

                else -> flow {
                    emit(ProviderEvent.Usage(inputTokens = 150, outputTokens = 25, totalTokens = 175))
                    emit(ProviderEvent.Usage(inputTokens = 150, outputTokens = 30, totalTokens = 180))
                    emit(ProviderEvent.TextDelta("done"))
                    emit(ProviderEvent.Completed)
                }
            }
        }

        val events = AgentRunner().run(session, listOf(tool())).toList()
        val usage = events
            .filterIsInstance<AgentRunEvent.Provider>()
            .mapNotNull { it.event as? ProviderEvent.Usage }

        assertEquals(
            listOf(
                ProviderEvent.Usage(inputTokens = 100, outputTokens = 20, totalTokens = 120, cumulative = false),
                ProviderEvent.Usage(inputTokens = 150, outputTokens = 30, totalTokens = 180, cumulative = false)
            ),
            usage
        )
        assertEquals(300, usage.sumOf { it.totalTokens ?: 0 })
    }

    @Test
    fun `provider completion is emitted only after the final tool round`() = runBlocking {
        val providerCalls = AtomicInteger()
        val session = session { _, _ ->
            when (providerCalls.getAndIncrement()) {
                0 -> flow {
                    emit(toolCall("call_1"))
                    emit(ProviderEvent.Completed)
                }

                else -> flow {
                    emit(ProviderEvent.TextDelta("final"))
                    emit(ProviderEvent.Completed)
                }
            }
        }

        val events = AgentRunner().run(session, listOf(tool())).toList()

        assertEquals(1, events.count { it == AgentRunEvent.Provider(ProviderEvent.Completed) })
        assertTrue(events.indexOf(AgentRunEvent.Provider(ProviderEvent.TextDelta("final"))) < events.indexOf(AgentRunEvent.Provider(ProviderEvent.Completed)))
    }

    @Test
    fun `provider failure terminates the run without completion or another round`() = runBlocking {
        val providerCalls = AtomicInteger()
        val session = session { _, _ ->
            providerCalls.incrementAndGet()
            flow {
                emit(ProviderEvent.Failed("provider failed"))
                emit(ProviderEvent.Completed)
            }
        }

        val events = AgentRunner().run(session, emptyList()).toList()

        assertEquals(1, providerCalls.get())
        assertEquals(
            listOf(AgentRunEvent.Provider(ProviderEvent.Failed("provider failed"))),
            events
        )
    }

    @Test
    fun `circuit breaker exception in provider stream emits classified error`() = runBlocking {
        val providerCalls = AtomicInteger()
        val session = session { _, _ ->
            providerCalls.incrementAndGet()
            flow {
                throw CircuitBreakerOpenException(3000L, "test-provider")
            }
        }

        val events = AgentRunner().run(session, emptyList()).toList()

        assertEquals(1, providerCalls.get())
        assertEquals(
            listOf(AgentRunEvent.Provider(ProviderEvent.Failed("Service temporarily unavailable due to high error rates. Please wait a moment before trying again."))),
            events
        )
    }

    @Test
    fun `tool call ceiling executes available calls and finalizes gracefully`() = runBlocking {
        val executions = AtomicInteger()
        val providerCalls = AtomicInteger()
        val exposedToolCounts = mutableListOf<Int>()
        val session = session { tools, exchanges ->
            exposedToolCounts += tools.size
            when (providerCalls.getAndIncrement()) {
                0 -> flow {
                    repeat(12) { emit(toolCall("call_$it")) }
                    emit(ProviderEvent.Completed)
                }
                else -> flow {
                    assertEquals(12, exchanges.single().results.size)
                    emit(ProviderEvent.TextDelta("final"))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val tool = tool { callId, _ ->
            executions.incrementAndGet()
            AgentToolResult(callId, ToolResultContent.Text("ok"), isError = false)
        }

        val events = AgentRunner(AgentRunLimits(maxToolCalls = 12)).run(session, listOf(tool)).toList()

        assertEquals(12, executions.get())
        assertEquals(listOf(1, 0), exposedToolCounts)
        assertTrue(events.any { it is AgentRunEvent.Notice })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertEquals(AgentRunEvent.Provider(ProviderEvent.Completed), events.last())
    }

    @Test
    fun `round ceiling requests one final no-tools synthesis round`() = runBlocking {
        val providerCalls = AtomicInteger()
        val executions = AtomicInteger()
        val exposedToolCounts = mutableListOf<Int>()
        val session = session { tools, _ ->
            val round = providerCalls.getAndIncrement()
            exposedToolCounts += tools.size
            flow {
                if (round < 2) {
                    emit(toolCall("call_$round"))
                } else {
                    emit(ProviderEvent.TextDelta("Final answer from available results."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val tool = tool { callId, _ ->
            executions.incrementAndGet()
            AgentToolResult(callId, ToolResultContent.Text("ok"), isError = false)
        }

        val events = AgentRunner(
            limits = AgentRunLimits(maxRounds = 2)
        ).run(session, listOf(tool)).toList()

        assertEquals(3, providerCalls.get())
        assertEquals(2, executions.get())
        assertEquals(listOf(1, 1, 0), exposedToolCounts)
        assertTrue(events.any { it is AgentRunEvent.Notice })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertEquals(AgentRunEvent.Provider(ProviderEvent.Completed), events.last())
    }

    @Test
    fun `single tool is circuit broken after per response repeat limit`() = runBlocking {
        val executions = AtomicInteger()
        val providerCalls = AtomicInteger()
        val repeated = tool("github__github_api") { id, _ ->
            executions.incrementAndGet()
            AgentToolResult(id, ToolResultContent.Text("ok"), false)
        }
        val session = session { tools, _ ->
            val round = providerCalls.getAndIncrement()
            flow {
                if (tools.isNotEmpty()) {
                    emit(toolCall("call-$round", "github__github_api"))
                } else {
                    emit(ProviderEvent.TextDelta("finished"))
                }
                emit(ProviderEvent.Completed)
            }
        }

        val events = AgentRunner(AgentRunLimits(maxRounds = 40, maxToolCalls = 50)).run(session, listOf(repeated)).toList()

        assertEquals(24, executions.get())
        assertTrue(events.filterIsInstance<AgentRunEvent.ToolFinished>().any { it.result.isError })
        assertEquals(AgentRunEvent.Provider(ProviderEvent.Completed), events.last())
    }

    @Test
    fun `tool is circuit broken after three consecutive errors`() = runBlocking {
        val executions = AtomicInteger()
        val failing = tool("github__github_api") { id, _ ->
            executions.incrementAndGet()
            AgentToolResult(id, ToolResultContent.Text("bad request"), true)
        }
        var round = 0
        val session = session { tools, _ ->
            flow {
                if (tools.isNotEmpty()) {
                    emit(toolCall("failure-${round++}", "github__github_api"))
                } else {
                    emit(ProviderEvent.TextDelta("fallback answer"))
                }
                emit(ProviderEvent.Completed)
            }
        }

        val events = AgentRunner(AgentRunLimits(maxRounds = 10)).run(session, listOf(failing)).toList()

        assertEquals(3, executions.get())
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.TextDelta })
        assertEquals(AgentRunEvent.Provider(ProviderEvent.Completed), events.last())
    }

    @Test
    fun `tool output is bounded before persistence and provider replay`() = runBlocking {
        val providerCalls = AtomicInteger()
        var replayedResult: AgentToolResult? = null
        val session = session { _, exchanges ->
            when (providerCalls.getAndIncrement()) {
                0 -> flow {
                    emit(toolCall("bounded_call"))
                    emit(ProviderEvent.Completed)
                }

                else -> flow {
                    replayedResult = exchanges.single().results.single()
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val tool = tool { callId, _ ->
            AgentToolResult(callId, ToolResultContent.Text("abcdefghij"), isError = false)
        }

        val events = AgentRunner(
            limits = AgentRunLimits(maxToolOutputBytes = 5)
        ).run(session, listOf(tool)).toList()

        assertEquals(ToolResultContent.Text("abcde"), replayedResult?.content)
        assertEquals(
            ToolResultContent.Text("abcde"),
            events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result.content
        )
    }

    @Test
    fun `tool definition rejection retries once without tools before execution`() = runBlocking {
        val exposedTools = mutableListOf<List<AgentToolDefinition>>()
        val session = session { tools, _ ->
            exposedTools += tools
            if (tools.isNotEmpty()) {
                throw ToolDefinitionsRejectedException("unsupported")
            }
            flow {
                emit(ProviderEvent.TextDelta("chat fallback"))
                emit(ProviderEvent.Completed)
            }
        }

        val events = AgentRunner().run(session, listOf(tool())).toList()

        assertEquals(listOf(1, 0), exposedTools.map { it.size })
        assertTrue(events.contains(AgentRunEvent.Notice("Tools unavailable for this model.", persistent = true)))
        assertTrue(events.contains(AgentRunEvent.Provider(ProviderEvent.TextDelta("chat fallback"))))
    }

    @Test
    fun `tool definition fallback cannot execute an unexposed tool call`() = runBlocking {
        val providerCalls = AtomicInteger()
        val executions = AtomicInteger()
        val session = session { tools, exchanges ->
            when (providerCalls.getAndIncrement()) {
                0 -> throw ToolDefinitionsRejectedException("unsupported")

                1 -> flow {
                    assertTrue(tools.isEmpty())
                    emit(toolCall("unexpected_call"))
                    emit(ProviderEvent.Completed)
                }

                else -> flow {
                    assertTrue(tools.isEmpty())
                    assertTrue(exchanges.single().results.single().isError)
                    emit(ProviderEvent.TextDelta("chat fallback"))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val tool = tool { callId, _ ->
            executions.incrementAndGet()
            AgentToolResult(callId, ToolResultContent.Text("executed"), isError = false)
        }

        val events = AgentRunner().run(session, listOf(tool)).toList()

        assertEquals(0, executions.get())
        assertTrue(events.contains(AgentRunEvent.Notice("Tools unavailable for this model.", persistent = true)))
        assertTrue(events.contains(AgentRunEvent.Provider(ProviderEvent.TextDelta("chat fallback"))))
    }

    @Test
    fun `tool definition rejection never retries after a tool executes`() = runBlocking {
        val providerCalls = AtomicInteger()
        val executions = AtomicInteger()
        val session = session { _, _ ->
            when (providerCalls.getAndIncrement()) {
                0 -> flow {
                    emit(toolCall("side_effect_call"))
                    emit(ProviderEvent.Completed)
                }

                else -> throw ToolDefinitionsRejectedException("unsupported after call")
            }
        }
        val tool = tool { callId, _ ->
            executions.incrementAndGet()
            AgentToolResult(callId, ToolResultContent.Text("done"), isError = false)
        }

        val events = AgentRunner().run(session, listOf(tool)).toList()

        assertEquals(2, providerCalls.get())
        assertEquals(1, executions.get())
        assertFalse(events.any { it is AgentRunEvent.Notice })
        assertTrue((events.last() as AgentRunEvent.Provider).event is ProviderEvent.Failed)
    }

    @Test
    fun `tool timeout becomes an error result and the model can continue`() = runBlocking {
        val providerCalls = AtomicInteger()
        var replayedResult: AgentToolResult? = null
        val session = session { _, exchanges ->
            when (providerCalls.getAndIncrement()) {
                0 -> flow {
                    emit(toolCall("slow_call"))
                    emit(ProviderEvent.Completed)
                }

                else -> flow {
                    replayedResult = exchanges.single().results.single()
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val tool = tool { _, _ -> awaitCancellation() }

        AgentRunner(
            limits = AgentRunLimits(toolTimeoutMillis = 20)
        ).run(session, listOf(tool)).toList()

        assertEquals(true, replayedResult?.isError)
        assertEquals(
            ToolResultContent.Text("Tool 'lookup' timed out after 20 ms."),
            replayedResult?.content
        )
    }

    @Test
    fun `run timeout message uses configured duration`() = runBlocking {
        val session = session { _, _ ->
            flow { awaitCancellation() }
        }

        val events = AgentRunner(
            limits = AgentRunLimits(runTimeoutMillis = 20)
        ).run(session, emptyList()).toList()

        assertEquals(
            AgentRunEvent.Provider(ProviderEvent.Failed("Agent run timed out after 20 ms.")),
            events.single()
        )
    }

    @Test
    fun `external cancellation keeps already emitted partial text and does not complete`() = runBlocking {
        val partialSeen = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentRunEvent>()
        val session = session { _, _ ->
            flow {
                emit(ProviderEvent.TextDelta("partial"))
                awaitCancellation()
            }
        }

        val job = launch {
            AgentRunner().run(session, emptyList()).collect { event ->
                events += event
                partialSeen.complete(Unit)
            }
        }
        partialSeen.await()
        job.cancelAndJoin()

        assertEquals(
            listOf(AgentRunEvent.Provider(ProviderEvent.TextDelta("partial"))),
            events
        )
    }

    @Test
    fun `engine owned session forwards tool timeline events without another round`() = runBlocking {
        val providerCalls = AtomicInteger()
        val session = object : AgentProviderSession {
            override val handlesToolsInternally: Boolean = true

            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> {
                providerCalls.incrementAndGet()
                assertTrue(exchanges.isEmpty())
                return flow {
                    emit(toolCall("engine_call"))
                    emit(
                        ProviderEvent.ToolResult(
                            toolCall("engine_call"),
                            AgentToolResult("engine_call", ToolResultContent.Text("from-engine"), isError = false)
                        )
                    )
                    emit(ProviderEvent.TextDelta("final"))
                    emit(ProviderEvent.Completed)
                }
            }
        }

        val events = AgentRunner().run(session, listOf(tool())).toList()

        assertEquals(1, providerCalls.get())
        assertEquals(
            listOf(
                AgentRunEvent.Provider(toolCall("engine_call")),
                AgentRunEvent.ToolFinished(
                    toolCall("engine_call"),
                    AgentToolResult("engine_call", ToolResultContent.Text("from-engine"), isError = false)
                ),
                AgentRunEvent.Provider(ProviderEvent.TextDelta("final")),
                AgentRunEvent.Provider(ProviderEvent.Completed)
            ),
            events
        )
    }

    @Test
    fun `external cancellation during a tool is not converted to a tool error`() = runBlocking {
        val toolStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentRunEvent>()
        val session = session { _, _ ->
            flow {
                emit(toolCall("cancel_call"))
                emit(ProviderEvent.Completed)
            }
        }
        val tool = tool { _, _ ->
            toolStarted.complete(Unit)
            awaitCancellation()
        }

        val job = launch {
            AgentRunner().run(session, listOf(tool)).collect { events += it }
        }
        toolStarted.await()
        job.cancelAndJoin()

        assertFalse(events.any { it is AgentRunEvent.ToolFinished })
        assertFalse(events.any { it == AgentRunEvent.Provider(ProviderEvent.Completed) })
    }

    @Test
    fun `backend throughput survives aggregated usage from a buffered response`() = runBlocking {
        val session = session { _, _ ->
            flow {
                emit(ProviderEvent.TextDelta("one buffered answer"))
                emit(ProviderEvent.Usage(inputTokens = 20, outputTokens = 10, decodeTokensPerSecond = 42.5))
                emit(ProviderEvent.Completed)
            }
        }
        val usage = AgentRunner().run(session, emptyList()).toList()
            .filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(42.5, usage.decodeTokensPerSecond!!, .01)
        assertEquals(10, usage.outputTokens)
    }

    @Test
    fun `mixed owner tool calls execute as separate batches and preserve result order`() = runBlocking {
        val executionOrder = mutableListOf<String>()
        val session = session { _, exchanges ->
            flow {
                if (exchanges.isEmpty()) {
                    emit(toolCall("client-1", "client"))
                    emit(toolCall("gateway-1", "gateway"))
                    emit(toolCall("client-2", "client"))
                    emit(toolCall("gateway-2", "gateway"))
                    emit(ProviderEvent.Completed)
                } else {
                    emit(ProviderEvent.TextDelta("done"))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val events = AgentRunner(AgentRunLimits(maxConcurrentTools = 4)).run(
            session,
            listOf(
                tool("client", owner = AgentToolExecutionOwner.CLIENT) { id, _ ->
                    executionOrder += id
                    AgentToolResult(id, ToolResultContent.Text(id), false)
                },
                tool("gateway", owner = AgentToolExecutionOwner.GATEWAY) { id, _ ->
                    executionOrder += id
                    AgentToolResult(id, ToolResultContent.Text(id), false)
                }
            )
        ).toList()

        assertEquals(listOf("client-1", "client-2", "gateway-1", "gateway-2"), executionOrder)
        assertEquals(
            listOf("client-1", "gateway-1", "client-2", "gateway-2"),
            events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.call.callId }
        )
    }

    private fun session(
        stream: (List<AgentToolDefinition>, List<AgentToolExchange>) -> Flow<ProviderEvent>
    ): AgentProviderSession = object : AgentProviderSession {
        override fun streamRound(
            tools: List<AgentToolDefinition>,
            exchanges: List<AgentToolExchange>
        ): Flow<ProviderEvent> = stream(tools, exchanges)
    }

    private fun toolCall(
        callId: String,
        name: String = "lookup",
        arguments: JsonObject = buildJsonObject {}
    ) = ProviderEvent.ToolCall(callId, name, arguments)

    private fun tool(
        name: String = "lookup",
        owner: AgentToolExecutionOwner = AgentToolExecutionOwner.CLIENT,
        execute: suspend (String, JsonObject) -> AgentToolResult = { callId, _ ->
            AgentToolResult(callId, ToolResultContent.Text("ok"), isError = false)
        }
    ): AgentTool = object : OwnedAgentTool {
        override val executionOwner = owner
        override val definition = AgentToolDefinition(
            name = name,
            description = "test tool",
            inputSchema = buildJsonObject { put("type", "object") }
        )

        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = execute(callId, arguments)
    }
}
