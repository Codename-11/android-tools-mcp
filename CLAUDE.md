# android-tools-mcp

IntelliJ plugin that bridges Android Studio's Gemini agent tools to the Model Context Protocol (MCP).

## Build

```
./gradlew buildPlugin
```

Output ZIP lands in `build/distributions/`.

## Install

Drop the ZIP into Android Studio's plugin directory (Settings > Plugins > Install from Disk).

## Usage

The plugin starts an MCP server on port **24601** when a project opens. It uses SSE transport at `http://localhost:24601/sse`.

## Connect with Claude Code

```
claude mcp add android-studio -- ./scripts/android-studio-mcp
```

## Architecture

`McpBridgeService` discovers tools via IntelliJ extension points and exposes them through the MCP SDK over SSE. The `scripts/android-studio-mcp` wrapper converts stdio to SSE so that Claude Code (and similar tools) can connect without a browser.
