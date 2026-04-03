package dev.amsavarthan.androidtoolsmcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool as McpTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Discovers Android Studio's Gemini agent tools via extension points
 * and exposes them as an MCP server over SSE.
 *
 * API chain (from javap decompilation):
 *   ToolsProvider.getToolSets(Project) → List<ToolSet>
 *   ToolSet.getTools(Project) → List<Tool<Args>>
 *   Tool.getName(), Tool.getToolDescription(), Tool.getToolArguments()
 *   Tool.createToolHandler(ToolContext, FunctionCall) → ToolHandler
 *   ToolHandler.handle() → Response (suspend)
 *   Response.text() → String
 */
class McpBridgeService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(McpBridgeService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ktorEngine: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    @Volatile private var cachedTools: List<DiscoveredTool> = emptyList()
    private var mcpServer: Server? = null

    fun start() {
        scope.launch {
            try {
                doStart()
            } catch (e: Exception) {
                log.error("MCP bridge failed to start", e)
            }
        }
    }

    override fun dispose() {
        log.info("Shutting down MCP bridge server")
        runCatching { ktorEngine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000) }
        scope.cancel("McpBridgeService disposed")
    }

    // ---- startup -----------------------------------------------------------

    private suspend fun doStart() {
        val port = System.getProperty("android.tools.mcp.port", "24601").toIntOrNull() ?: 24601

        refreshTools()

        val server = buildMcpServer()
        mcpServer = server
        startSseTransport(server, port)
        log.info("MCP bridge server listening on http://localhost:$port/sse (${cachedTools.size} tools)")

        // Safety net: re-discover after 30s in case providers loaded late
        scope.launch {
            delay(30_000)
            val before = cachedTools.size
            refreshTools()
            if (cachedTools.size != before) {
                log.info("Late discovery found ${cachedTools.size - before} new tool(s), rebuilding")
                mcpServer?.let { rebuildTools(it) }
            }
        }
    }

    private fun refreshTools() {
        cachedTools = discoverTools()
        log.info("Discovered ${cachedTools.size} tool(s): ${cachedTools.map { it.name }}")
    }

    // ---- tool discovery ----------------------------------------------------

    private data class DiscoveredTool(
        val name: String,
        val description: String,
        val arguments: Map<String, String>,
        val rawTool: Any,
    )

    private fun discoverTools(): List<DiscoveredTool> {
        val result = mutableListOf<DiscoveredTool>()
        try {
            val ep = ExtensionPointName.create<Any>("com.google.aiplugin.agentToolsProvider")
            for (provider in ep.extensionList) {
                result += extractFromProvider(provider)
            }
        } catch (e: Exception) {
            log.warn("Failed to enumerate tool providers", e)
        }
        return result
    }

    private fun extractFromProvider(provider: Any): List<DiscoveredTool> {
        val result = mutableListOf<DiscoveredTool>()
        try {
            val toolSets = invoke(provider, "getToolSets", project) as? List<*> ?: return result
            for (toolSet in toolSets) {
                if (toolSet == null) continue
                val tools = invoke(toolSet, "getTools", project) as? List<*> ?: continue
                for (tool in tools) {
                    if (tool == null) continue
                    extractTool(tool)?.let { result += it }
                }
            }
        } catch (e: Exception) {
            log.warn("Error extracting tools from ${provider.javaClass.name}", e)
        }
        return result
    }

    private fun extractTool(tool: Any): DiscoveredTool? {
        val name = invoke(tool, "getName") as? String ?: return null

        var desc = ""
        runCatching {
            val annotation = invoke(tool, "getToolDescription")
            if (annotation != null) {
                desc = (invoke(annotation, "summary") as? String)
                    ?: (invoke(annotation, "description") as? String) ?: ""
            }
        }

        val args = mutableMapOf<String, String>()
        runCatching {
            val toolArgs = invoke(tool, "getToolArguments") as? Map<*, *>
            toolArgs?.forEach { (param, annotation) ->
                val paramName = invoke(param!!, "getName") as? String ?: return@forEach
                val argDesc = invoke(annotation!!, "description") as? String ?: ""
                args[paramName] = argDesc
            }
        }

        return DiscoveredTool(name = name, description = desc, arguments = args, rawTool = tool)
    }

    // ---- MCP server --------------------------------------------------------

    private fun buildMcpServer(): Server {
        val server = Server(
            Implementation(name = "android-tools-mcp", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
        )

        server.addTool(
            name = "_refresh_tools",
            description = "Re-discovers tools from Android Studio. Call this if tools seem missing.",
            inputSchema = McpTool.Input(properties = buildJsonObject {}, required = emptyList()),
        ) { _ ->
            refreshTools()
            rebuildTools(server)
            CallToolResult(content = listOf(TextContent("Refreshed. Now serving ${cachedTools.size} tools: ${cachedTools.map { it.name }}")))
        }

        rebuildTools(server)
        return server
    }

    private fun rebuildTools(server: Server) {
        for (tool in cachedTools) {
            val props = buildJsonObject {
                for ((argName, argDesc) in tool.arguments) {
                    put(argName, buildJsonObject {
                        put("type", "string")
                        if (argDesc.isNotEmpty()) put("description", argDesc)
                    })
                }
            }
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = McpTool.Input(properties = props, required = tool.arguments.keys.toList()),
            ) { request -> handleToolCall(tool, request.arguments) }
        }
    }

    private suspend fun handleToolCall(tool: DiscoveredTool, arguments: JsonObject): CallToolResult {
        return try {
            CallToolResult(content = listOf(TextContent(invokeTool(tool, arguments))))
        } catch (e: Exception) {
            log.warn("Tool '${tool.name}' failed", e)
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    private suspend fun invokeTool(tool: DiscoveredTool, arguments: JsonObject): String {
        val raw = tool.rawTool

        // Create a minimal InvocationContext via dynamic proxy
        val invCtxClass = Class.forName("com.google.aiplugin.agents.InvocationContext")
        val invocationContext = java.lang.reflect.Proxy.newProxyInstance(
            invCtxClass.classLoader,
            arrayOf(invCtxClass),
        ) { _, method, _ ->
            when (method.name) {
                "getProject" -> project
                "getSessionId" -> "mcp-bridge-${System.currentTimeMillis()}"
                "isSubAgent" -> false
                "isAgentStopped" -> false
                "getChanges" -> emptyList<Any>()
                "getImageAttachments" -> emptyList<Any>()
                "getAgentTaskId" -> "mcp"
                "stopAgent" -> Unit
                "toString" -> "McpBridgeInvocationContext"
                else -> null
            }
        }

        // ToolContext(project, invocationContext, userApprovalProvider=null)
        val toolContextClass = Class.forName("com.google.aiplugin.agents.tools.ToolContext")
        val toolContext = toolContextClass.declaredConstructors
            .firstOrNull { it.parameterCount >= 4 } // default constructor
            ?.also { it.isAccessible = true }
            ?.newInstance(project, invocationContext, null, 0b100, null) // mask bit 2 = UserApprovalProvider default
            ?: toolContextClass.declaredConstructors.first()
                .also { it.isAccessible = true }
                .newInstance(project, invocationContext, null)

        // FunctionCall(name, args, tool=null, toolHandler=null, metadata=null, thoughtSignature=null)
        val functionCallClass = Class.forName("com.android.tools.idea.studiobot.Content\$FunctionCall")
        val argsMap: Map<String, Any> = arguments.entries.associate { (k, v) ->
            k to ((v as? JsonPrimitive)?.content ?: v.toString())
        }
        val functionCall = functionCallClass.declaredConstructors
            .first { it.parameterCount == 8 } // default constructor
            .also { it.isAccessible = true }
            .newInstance(tool.name, argsMap, null, null, null, null, 0b111100, null)

        // Tool.createToolHandler(ToolContext, FunctionCall) → ToolHandler
        val handler = invoke(raw, "createToolHandler", toolContext, functionCall)
            ?: return "Failed to create handler for ${tool.name}"

        // ToolHandler.handle() → Response (suspend)
        val response = withContext(Dispatchers.IO) { callSuspend(handler, "handle") }
            ?: return "Handler returned null"

        // Response.text() → String
        return invoke(response, "text") as? String
            ?: invoke(response, "getStatus") as? String
            ?: response.toString()
    }

    // ---- reflection helpers ------------------------------------------------

    private fun invoke(obj: Any, methodName: String, vararg args: Any?): Any? {
        val allMethods = mutableListOf<java.lang.reflect.Method>()
        allMethods += obj.javaClass.methods
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            allMethods += cls.declaredMethods
            cls = cls.superclass
        }
        for (method in allMethods) {
            if (method.name != methodName) continue
            val params = method.parameterTypes.filter { it.name != "kotlin.coroutines.Continuation" }
            if (params.size != args.size) continue
            val compatible = params.zip(args).all { (type, arg) ->
                arg == null || type.isAssignableFrom(arg.javaClass) || type.isPrimitive
            }
            if (!compatible) continue
            runCatching {
                method.isAccessible = true
                return method.invoke(obj, *args)
            }
        }
        return null
    }

    private suspend fun callSuspend(obj: Any, methodName: String): Any? {
        val method = obj.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
        } ?: return invoke(obj, methodName)

        method.isAccessible = true
        return suspendCancellableCoroutine<Any?> { cont ->
            try {
                val result = method.invoke(obj, cont)
                if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                    cont.resumeWith(Result.success(result))
                }
            } catch (e: Exception) {
                cont.resumeWith(Result.failure(e))
            }
        }
    }

    // ---- SSE transport -----------------------------------------------------

    private suspend fun startSseTransport(mcpServer: Server, port: Int) {
        val engine = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, port = port) {
            val ktorServerClass = Class.forName("io.modelcontextprotocol.kotlin.sdk.server.KtorServerKt")
            val mcpMethod = ktorServerClass.methods.first { m ->
                m.name == "mcp" && m.parameterTypes.firstOrNull()?.name?.contains("Application") == true
            }
            mcpMethod.invoke(null, this, { _: Any? -> mcpServer } as (Any?) -> Server)
        }
        ktorEngine = engine
        withContext(Dispatchers.IO) { engine.start(wait = false) }
    }

    companion object {
        fun getInstance(project: Project): McpBridgeService =
            project.getService(McpBridgeService::class.java)
    }
}
