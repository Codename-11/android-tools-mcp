# Android Tools MCP

An Android Studio plugin that exposes Android Studio's built-in Gemini agent tools as an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server. This lets external AI coding tools — Claude Code, OpenCode, KiloCode, GitHub Copilot, and any MCP-compatible client — call Android-specific tools directly from Android Studio.

## How it works

The plugin starts an MCP SSE server on `http://127.0.0.1:24601` when you open a project. It discovers tools from the Gemini plugin's extension point (`com.google.aiplugin.agentToolsProvider`) and exposes them over the wire. Because the tools run inside Android Studio, they have full access to your project's Gradle model, connected devices, and the IDE itself — no separate daemon needed.

## Prerequisites

- **Android Studio Ladybug Feature Drop (2025.3.3)** or newer
- **Gemini plugin** enabled in Android Studio (bundled by default)
- An Android project open in Android Studio

## Install (no build required)

Download the latest ZIP from the [Releases page](https://github.com/anthropics/android-tools-mcp/releases), then install it via **Settings → Plugins → ⚙️ → Install Plugin from Disk…** in Android Studio.

## Build from source

The build compiles against your local Android Studio installation (JetBrains does not host Android Studio on their CDN, so `androidStudio("version")` is not an option).

1. Create `local.properties` in the project root (already gitignored):
   ```properties
   android.studio.path=/Applications/Android Studio.app
   ```
   Or set the environment variable `ANDROID_STUDIO_PATH` instead. The fallback is `/Applications/Android Studio.app` (standard macOS install location).

2. Build:
   ```bash
   git clone https://github.com/anthropics/android-tools-mcp
   cd android-tools-mcp
   ./gradlew buildPlugin
   ```

The plugin ZIP is written to `build/distributions/android-tools-mcp-*.zip`.

## Install the plugin

1. Open Android Studio → **Settings** (or **Preferences** on macOS) → **Plugins**
2. Click the gear icon → **Install Plugin from Disk…**
3. Select the ZIP from `build/distributions/`
4. Restart Android Studio

After restart, the MCP server starts automatically when a project opens. Check **Help → Show Log** and search for `MCP` to confirm it started.

## Verify the server is running

```bash
./scripts/health-check
# MCP server is running on port 24601
```

Or manually:

```bash
curl -sN http://127.0.0.1:24601/sse
```

## Connect from your AI coding tool

### Claude Code

```bash
claude mcp add android-studio -- /path/to/android-tools-mcp/scripts/android-studio-mcp
```

The `scripts/android-studio-mcp` wrapper translates Claude Code's stdio transport to the SSE endpoint. After adding, run `claude mcp list` to confirm it appears.

### OpenCode

Add to your `~/.config/opencode/config.json`:

```json
{
  "mcp": {
    "android-studio": {
      "type": "stdio",
      "command": "/path/to/android-tools-mcp/scripts/android-studio-mcp"
    }
  }
}
```

### Kilo

Add to your global Kilo configuration (`~/.config/kilo/kilo.jsonc`) using the `mcp` object:

```json
{
  "mcp": {
    "android-studio": {
      "type": "local",
      "command": ["/path/to/android-tools-mcp/scripts/android-studio-mcp"],
      "enabled": true
    }
  }
}
```

### GitHub Copilot (VS Code)

Add to your `.vscode/mcp.json` (or user-level `settings.json` under `github.copilot.mcp`):

```json
{
  "servers": {
    "android-studio": {
      "type": "stdio",
      "command": "/path/to/android-tools-mcp/scripts/android-studio-mcp"
    }
  }
}
```

### Any SSE-native MCP client

Connect directly to the SSE endpoint — no wrapper needed:

```
http://127.0.0.1:24601/sse
```

## Available tools

All 20 tools are Android-specific. Generic file/code tools are intentionally excluded.

### Device tools

| Tool                     | Description                                                   |
| ------------------------ | ------------------------------------------------------------- |
| `read_logcat`            | Read logcat output from a connected Android device            |
| `take_screenshot`        | Capture a screenshot from a connected device                  |
| `ui_state`               | Dump the current UI hierarchy from a connected device         |
| `adb_shell_input`        | Send input events to a connected device via `adb shell input` |
| `deploy`                 | Build and deploy the app to a connected device                |
| `render_compose_preview` | Render a Compose preview and return the image                 |

### Gradle tools

| Tool                                 | Description                                       |
| ------------------------------------ | ------------------------------------------------- |
| `gradle_sync`                        | Trigger a Gradle sync in the open project         |
| `gradle_build`                       | Build the project via Gradle                      |
| `get_top_level_sub_projects`         | List top-level subprojects in the Gradle build    |
| `get_build_file_location`            | Get the build file path for a given artifact      |
| `get_gradle_artifact_from_file`      | Identify which Gradle artifact owns a source file |
| `get_assemble_task_for_artifact`     | Get the assemble Gradle task for an artifact      |
| `get_test_task_for_artifact`         | Get the test Gradle task for an artifact          |
| `get_artifact_consumers`             | List artifacts that depend on a given artifact    |
| `get_test_artifacts_for_sub_project` | List test artifacts for a subproject              |
| `get_source_folders_for_artifact`    | List source folders for a Gradle artifact         |

### Documentation and search tools

| Tool                  | Description                                                                                        |
| --------------------- | -------------------------------------------------------------------------------------------------- |
| `search_android_docs` | Search the Android developer documentation                                                         |
| `fetch_android_docs`  | Fetch the content of an Android documentation page                                                 |
| `code_search`         | Search code within the open project                                                                |
| `version_lookup`      | Look up the latest stable and preview versions of a Maven artifact (e.g. `androidx.compose.ui:ui`) |

## Configuration

### Custom port

Set the system property `mcp.bridge.port` to use a different port:

1. Open **Help → Edit Custom VM Options** in Android Studio
2. Add: `-Dmcp.bridge.port=12345`
3. Restart Android Studio

Then pass `--port 12345` to the wrapper:

```bash
./scripts/android-studio-mcp --port 12345
```

## Troubleshooting

**0 tools discovered**

- Make sure a project is open and Gradle sync has completed at least once.
- The Gemini plugin must be enabled (check Settings → Plugins).

**"No running devices found"**

- Device tools require an Android device or emulator connected via ADB. This is expected when no device is attached.

**Connection refused / server not reachable**

- The server only starts when a project is open. Open a project in Android Studio and try again.
- Run `./scripts/health-check` to diagnose.

**Proxy interference**

- The wrapper uses `127.0.0.1` (not `localhost`) to bypass system proxy settings. If you have `no_proxy` configured differently, adjust `BASE` in `scripts/android-studio-mcp`.

## How tools stay up to date

Tools are discovered dynamically at runtime from whatever version of the Gemini plugin is installed. When Android Studio or the Gemini plugin is updated with new tools, they automatically appear — no plugin rebuild needed.

## License

Apache 2.0
