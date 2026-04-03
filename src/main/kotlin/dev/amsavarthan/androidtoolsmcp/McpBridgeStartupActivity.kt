package dev.amsavarthan.androidtoolsmcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Kicks off the MCP bridge when a project is opened.
 *
 * Registered as a `postStartupActivity` in plugin.xml so that
 * [McpBridgeService.start] is called once the IDE is ready.
 */
class McpBridgeStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(McpBridgeStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.info("Initializing MCP bridge for project: ${project.name}")
        McpBridgeService.getInstance(project).start()
    }
}
