# OreshnikAgent

**A native Android AI agent, coding workspace, and device-automation toolkit.**

OreshnikAgent brings AI model providers, agent orchestration, developer tools, memory, browser access, voice interaction, and Android computer-use capabilities into one Android application. It is designed to run as a standalone app: its Python environment and optional tool runtimes are managed by the app rather than requiring Termux.

> **Project status:** Active development. The repository contains a broad feature set, but some advanced tools depend on device permissions, root access, compatible runtime packs, external services, or hardware support. A registered tool does not guarantee that every underlying executable is already installed or available on every device.

## Contents

- [Highlights](#highlights)
- [AI providers and models](#ai-providers-and-models)
- [Agent engine and tool system](#agent-engine-and-tool-system)
- [Developer workspace](#developer-workspace)
- [Android computer use and device automation](#android-computer-use-and-device-automation)
- [Reverse engineering and security research](#reverse-engineering-and-security-research)
- [Voice, vision, and browser](#voice-vision-and-browser)
- [Memory, skills, and sessions](#memory-skills-and-sessions)
- [Runtime and standalone architecture](#runtime-and-standalone-architecture)
- [Permissions and security](#permissions-and-security)
- [Compatibility](#compatibility)
- [Build from source](#build-from-source)
- [Optional runtime packs](#optional-runtime-packs)
- [Roadmap and suggested improvements](#roadmap-and-suggested-improvements)
- [Contributing](#contributing)
- [Disclaimer](#disclaimer)

## Highlights

- **Multiple AI backends:** NVIDIA, ChatGPT plan integration, OpenRouter, BlueSmind, NaraRouter, Mistral, and configurable OpenAI-compatible endpoints.
- **Online and local inference:** switch between remote providers and an on-device GGUF model runtime.
- **Tool-using agent:** structured tool definitions, a registry, per-tool default/ask/allow/deny policies, hard capability gates, task execution, progress events, and tool collaboration.
- **Coding tools:** workspace file operations, Python execution, interactive line-oriented shell sessions, Git inspection and HTTPS remote operations, offline project templates, and Android/Gradle build integration.
- **Android computer use:** screen inspection, accessibility-driven interactions, bounded repeatable UI-test sequences, and app-private UI regression snapshots with pixel-difference comparison.
- **Voice interaction:** speech UI and an optional always-on wake-word service.
- **Persistent context:** session memory, searchable saved memory, and reusable agent skills.
- **Encrypted backups:** passphrase-protected settings/provider/chat/memory/skills exports using PBKDF2-HMAC-SHA256 and AES-256-GCM.
- **Redacted audit trail:** view and clear app-private JSONL records for tool decisions, outcomes, and durations, with credential redaction.
- **Workspace recovery:** bounded app-private snapshots, snapshot listing, and rollback with an automatic pre-restore safety snapshot.
- **Reusable workflows:** saved read-only tool workflows with per-step retries, failure checkpoints, resume, and persistent WorkManager schedules.
- **Provider telemetry:** request counts, token usage, latency, and cost estimates using user-configured per-model rates (no vendor prices are assumed).
- **Extensible runtimes:** app-managed Python and a verified-download mechanism for optional runtime packs.
- **Advanced technical tooling:** database utilities, network inspection, APK/ELF/IL2CPP analysis, Ghidra/IDA-oriented workflows, and authorized security testing tools.

## AI providers and models

The provider layer is managed through a common provider registry.

| Provider / mode | Implementation in this repository |
|---|---|
| NVIDIA | NVIDIA provider and model repository |
| ChatGPT plan | ChatGPT plan authentication and provider integration |
| OpenRouter | OpenRouter provider |
| BlueSmind | BlueSmind provider |
| NaraRouter | NaraRouter provider |
| Mistral | Mistral provider |
| OpenAI-compatible | User-configurable provider profiles with a base URL and API key |
| Local / offline | Offline provider backed by the on-device GGUF runtime |

### Provider management

- Select an active provider and model.
- Store and manage provider credentials in the app's key-management/settings screens.
- Fetch model lists from providers which expose model discovery.
- Validate configured provider keys.
- Add and remove OpenAI-compatible API profiles.
- Keep online-provider selection separate from offline execution mode.

Provider availability, model discovery, authentication requirements, rate limits, and supported request features are controlled by each upstream service. Users must supply their own credentials where required.

### Local models

The app includes an Android llama runtime dependency for local GGUF inference. Model weights are **not bundled** in the APK; users must import compatible model files. Local performance depends on device RAM, CPU, model size, quantization, and supported ABI.

## Agent engine and tool system

OreshnikAgent uses a structured agent engine rather than treating every request as a plain chat completion.

The source includes:

- Per-tool policies: use global defaults, ask every time, always allow, or always deny, configurable from the existing expanded tool cards. Explicit deny and disabled top-level capabilities cannot be overridden by an approval dialog; the global ask-before-write safeguard remains active.
- Redacted app-private audit records for tool permission decisions and execution outcomes. API keys, passwords, bearer tokens, and common inline credentials are redacted before storage.
- Agent API client, configuration, task manager, and execution service.
- Tool definitions with names, descriptions, parameters, categories, and permission requirements.
- Tool registry for exposing available capabilities to the model.
- Structured tool results and execution progress.
- Tool collaboration and collaborative analysis helpers.
- Event bus for agent/task events.
- Permission approval, per-tool approval policies, hard capability gates, and workspace-ownership components.
- App-private redacted tool audit records (tool, session, permission outcome, execution result, duration, timestamp).
- Session learning and context retrieval.
- Workspace snapshots: app-private ZIP snapshots exclude build/dependency caches, cap file count and archive size, validate every restore path, and automatically create a safety snapshot before rollback.
- Git operations: local branch list/create/switch/delete, explicit path staging, staged-only commits, patch export, plus HTTPS clone/fetch/pull/push. Persistent shell sessions support start/write/read/stop with bounded output; they are pipe-based rather than a full PTY and require process, workspace-write, and root capability gates because arbitrary commands can modify files or invoke privileged tools. Credentials are supplied per call, never persisted, and force-push is not exposed.
- Offline project templates: Android/Kotlin, Kotlin CLI, Python, Node.js, and PHP API starters; creation is workspace-scoped and never overwrites an existing target.
- Accessibility sequence playback: bounded, step-reported tap, long-press, swipe, text click/input, scroll, focus, global navigation, and wait actions. UI snapshots can be captured, listed, and compared with a bounded pixel-difference report.
- Saved workflow builder: create/list/delete/run read-only inspection workflows, with up to 12 steps, bounded retries, and saved failure checkpoints for resume.
- Persistent workflow scheduling: WorkManager schedules read-only workflows from 15 minutes upward. Background execution requires every tool permission to be pre-approved; network-dependent workflows automatically require a network connection.

### Tool categories represented in the code

The registered tool surface includes the following areas. Availability of individual operations depends on runtime provisioning, Android version, permissions, root status, and the target environment.

| Area | Representative capabilities |
|---|---|
| Files and workspace | List, read, write, search, patch, delete, snapshots, listing, and rollback |
| Python | Execute code and files; manage packages through the app-owned Python runtime |
| Build and languages | Node.js/npm, Java/javac, Gradle, Kotlin compiler, PHP/Composer, Ruby, Clang, Make, and offline project templates |
| Git | Status, diffs, history, branch operations, explicit staging, staged commits, patch export, and HTTPS remotes |
| Android build | Build Android projects using an app-managed Android SDK/JDK/Gradle toolchain |
| Databases | SQLite, JDBC, Microsoft Access/Jet, MDBTools, and Python ODBC workflows |
| System | Process listing/management, installed-app inventory, and process termination |
| Android accessibility | Read screens, click, scroll, focus, invoke global actions, play bounded UI-test sequences, capture/compare UI snapshots |
| Web | Search, crawl, and browser interaction |
| Memory and skills | Search/save/delete memory, inspect memory statistics, list/activate skills, build and schedule read-only workflows |
| Network and traffic | Port scanning, HTTP capture/history/replay, packet capture, and traffic inspection |
| Reverse engineering | APK inspection/patch pipeline, ELF utilities, IL2CPP metadata, Ghidra/IDA-oriented analysis |
| Security research | SQL injection/XSS checks, directory discovery, header checks, sqlmap integration, and exploit-source research |

Some entries are adapters which require a separately provisioned executable or compatible native binary. Do not assume that a command-line tool is present simply because its agent tool is registered.

## Developer workspace

The project includes tools intended to help an agent work with code and project files on the device.

- Workspace file listing, reading, writing, searching, patching, and deletion.
- Python code and script execution through the bundled Python environment.
- Git status, diff, and log operations using JGit.
- Android project build integration with managed build tools.
- Language/runtime adapters for Node.js, npm, Java, javac, Gradle, Kotlin, PHP, Composer, Ruby, Clang, and Make.
- GGUF tensor inspection/editing and quantization tool integrations.
- Database inspection and export utilities for supported formats.

Build and language adapters may require their corresponding runtime pack. See [Optional runtime packs](#optional-runtime-packs).

## Android computer use and device automation

The Android integration includes accessibility and device-action tools.

- Capture or inspect the current screen.
- Tap coordinates or visible text/elements.
- Type text, scroll, swipe, and long-press.
- Navigate back or home and open installed apps.
- Focus UI elements and invoke global accessibility actions.
- Use screen-capture and vision-analysis components as part of agent workflows.
- Send SMS or open application settings through dedicated actions where permissions and platform policy allow.
- Inspect installed apps and process state.
- Optional root-backed device operations on rooted devices.

**Setup required:** Android computer-use actions require the user to enable the app's Accessibility Service. Screen capture, microphone, camera, contacts, notifications, SMS, and other operations require their respective Android permissions or system-granted access. Root-only functions require a rooted device and explicit superuser approval.

### MIUI / HyperOS resilience

The app contains additional components intended to improve long-running accessibility and wake-word service reliability on Xiaomi MIUI/HyperOS devices:

- Accessibility watchdog and keep-alive service.
- Boot-completed receiver.
- Root-assisted battery/PowerKeeper remediation helpers.
- Wake-word diagnostics.

These mechanisms cannot guarantee that MIUI/HyperOS or another Android vendor will keep a background service alive. Autostart, background-start, battery, and microphone restrictions may still need manual configuration.

## Reverse engineering and security research

The codebase contains advanced analysis and testing tools, including:

- APK archive, manifest, resource, DEX, native-library, and signature inspection.
- APK decode/build/sign/install/patch workflow adapters.
- ELF and binary identification/analysis helpers.
- IL2CPP metadata parsing and runtime-resolution helpers.
- Ghidra headless analysis, decompilation, cross-reference, project, annotation, and script adapters.
- IDA-oriented capabilities, analysis, offsets, cross-references, and memory-correlation tools.
- Unity runtime dump helpers.
- HTTP capture, modification, replay, and history tools.
- TCP dump/packet-capture adapters and port scanning.
- Web vulnerability checks and exploit research/source review helpers.

Availability of full Ghidra, IDA, radare2, apktool, sqlmap, packet-capture, and other external tool functionality depends on the relevant runtime pack, license, native compatibility, and device permissions. The presence of an integration does not mean proprietary software is included.

**Use these capabilities only on applications, devices, networks, and systems you own or are explicitly authorized to assess.** Do not use them to bypass access controls, compromise third-party systems, or access data without permission.

## Voice, vision, and browser

### Voice

- Dedicated voice activity and wake-word service.
- Microphone-based interaction where permission is granted.
- Background wake-word diagnostics.
- Foreground-service declarations for long-running microphone use.

Always-on listening can affect battery life and is subject to Android background microphone restrictions. Review the app's microphone permission and wake-word settings before enabling it.

### Vision and screen understanding

- Vision analyzer component for image/screen interpretation.
- Screen-capture manager for supported capture workflows.
- Visual analysis tools integrated with Android computer use.

Vision requests may require a compatible provider/model and network access. Local model vision support depends on the imported model and runtime.

### Browser

- Live browser component and overlay/cursor UI.
- Agent tools for web search, crawling, and browser interaction.
- HTTP networking and page parsing support.

Web access depends on connectivity, target website behavior, and provider/runtime configuration.

## Memory, skills, and sessions

### Memory

- Persistent app database and memory store.
- Search, save, delete, and statistics tools.
- Session learning/context helpers.
- Memory screen for inspecting and managing stored context.

Memory is intended to support continuity across tasks. Users should avoid storing passwords, private keys, or other secrets as general memory.

### Skills

- Skill repository and database-backed skill management.
- Seeded skill catalogue assets.
- Skill listing and activation through agent tools.
- Request-time retrieval of relevant skill instructions.

Skills guide the agent's workflow; they do not grant permissions or override tool security checks.

### Sessions and chat

The application includes chat/session models, session adapters, message rendering, attachment request building, and notification helpers. Markdown rendering supports common formatting, tables, links, and strikethrough.

## Runtime and standalone architecture

OreshnikAgent is designed as a **standalone Android application**. It does not require Termux to be installed.

### App-managed components

- Chaquopy-managed CPython 3.12 runtime.
- Python packages declared for the build, including Paramiko, AsyncSSH, Fabric, and smbprotocol.
- Runtime manager and Python runtime integration.
- Runtime catalogue repository and tool-pack installer.
- App-managed Android build toolchain support.
- App-private storage for imported model files and runtime data.

### Optional runtime downloads

The app fetches a remote runtime catalogue and installs optional packs on demand. Downloads are HTTPS-only and verified using SHA-256 before installation, with manifest and archive-path validation. The repository now includes an automated ARM64 pack builder that queries the official Termux main APT repository, verifies its signed InRelease metadata and package-index hash, resolves package dependencies, verifies each downloaded .deb checksum, and records package versions and source checksums in the runtime manifest. The pack is relocated into the app-owned runtime directory and its Termux interpreter prefix is rewritten at install time.

The first published Termux pack is intended to provide Bash, Git, OpenSSH, curl-compatible CLI, jq, ripgrep, SQLite, rsync, networking utilities and core tools. Availability is not assumed until the Termux pack workflow succeeds and publishes its release asset and catalogue checksum. Tool adapters that need unavailable binaries continue to report the runtime as missing rather than pretending they are installed.

Runtime archives must contain Android/Bionic-compatible binaries for the target ABI. A normal Linux/glibc executable cannot simply be copied onto Android and expected to run.

## Permissions and security

The manifest declares permissions for capabilities such as internet access, microphone, camera, notifications, wake locks, foreground services, boot completion, contacts, SMS, and package inventory. Android may require runtime prompts, special settings pages, or explicit user action for these features.

Security-related implementation points include:

- Tool permission categories and a permission approval coordinator.
- Workspace ownership guard.
- Root operations routed through a root manager.
- App-private runtime installation and validation.
- HTTPS runtime catalogue and checksum verification.
- Android FileProvider for controlled file sharing.

### Recommended operating practices

1. Grant only the permissions needed for the task.
2. Review tool permission prompts before approving actions.
3. Use root access only when a task genuinely requires it.
4. Keep API keys in the app's credential management rather than source files.
5. Only install runtime packs from trusted release sources.
6. Avoid running untrusted scripts or binaries.
7. Back up important workspace files before automated edits or APK patching.
8. Use network and reverse-engineering tools only with explicit authorization.

## Compatibility

| Requirement | Current project configuration |
|---|---|
| Android | Minimum API 26 (Android 8.0) |
| Compile SDK | Android API 36 |
| Target SDK | Android API 36 |
| Primary device ABI | arm64-v8a |
| Emulator ABI | x86_64 |
| Java bytecode target | Java 17 |
| Chaquopy Python | Python 3.12 |
| CI JDK | JDK 21 |
| Android build toolchain | SDK, NDK and CMake versions declared in the Gradle project |

The APK is configured for arm64-v8a and x86_64. Other ABIs are not currently declared in the app's ABI filters.

## Build from source

### Prerequisites

- JDK 21 for the current CI build setup.
- Android SDK Platform 36 and matching Build Tools.
- Android NDK version 30.0.16248370.
- CMake version 4.1.2.
- Python 3.12 for Chaquopy.
- Network access to download Gradle and Maven/Android dependencies.

### Build commands

On Linux or macOS:

~~~bash
chmod +x gradlew
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
~~~

On Windows:

~~~powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
~~~

Generated APKs are normally written to:

~~~text
app/build/outputs/apk/debug/
app/build/outputs/apk/release/
~~~

The CI release workflow also publishes the installable APK in the repository root as `OreshnikAgent-release.apk`, alongside `OreshnikAgent-release.apk.sha256`. The root APK is updated only after unit tests, release lint and release packaging succeed.

### Release signing

If no release keystore properties are supplied, the release variant uses the standard debug certificate so that CI can produce an installable test APK. **This is not production signing.**

For a production release, provide the following Gradle properties securely through your local Gradle properties file or CI secrets:

~~~properties
storeFile=/secure/path/to/release-keystore.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
~~~

Never commit keystore files or signing passwords to the repository.

## Optional runtime packs

To publish a runtime pack:

1. Build binaries for Android/Bionic and the intended ABI(s).
2. Package the runtime files in a ZIP archive.
3. Put runtime.json at the root of the ZIP and describe the pack ID and entrypoint files using the manifest format expected by the installer.
4. Publish the ZIP as a GitHub Release asset over HTTPS.
5. Calculate the archive's SHA-256 checksum.
6. Add a catalogue entry to runtime-catalog.json.

Catalogue entry shape:

~~~json
{
  "id": "example-runtime",
  "name": "Example runtime",
  "version": "1.0.0",
  "description": "Description of the optional Android runtime",
  "url": "https://github.com/OWNER/REPOSITORY/releases/download/TAG/example-runtime.zip",
  "sha256": "REPLACE_WITH_64_CHARACTER_SHA256"
}
~~~

The URL and checksum above are examples, not a currently published pack. Keep the catalogue schemaVersion at 1 unless the app's parser is updated to support a newer schema.

## Roadmap and suggested improvements

The following are **proposed enhancements**, not claims about features already shipped. They are prioritized to make the app safer, easier to install, and more dependable.

### P0 — Reliability and release readiness

- Publish and test signed runtime packs for ARM64, starting with the most frequently used developer tools.
- Add automated unit tests for tool argument validation, permission checks, runtime installation, archive traversal protection, and provider error handling.
- Add instrumentation tests for first launch, provider setup, chat, memory, and permission flows.
- Add automated release notes and signed provenance metadata to the CI build (the pipeline now runs unit tests/lint and uploads an APK checksum plus dependency report).
- Provide a clear in-app runtime compatibility matrix and per-tool readiness status.
- Add a safe recovery flow for interrupted downloads, failed runtime upgrades, and corrupted tool installations.

### P1 — Agent quality and control

- Add a visual task-plan panel with cancellation and pause/resume controls (workflow step retries and resume checkpoints are available through agent tools).
- Add provider capability detection for vision, tool calling, streaming, context limits, and structured output.

### P2 — Runtime and developer experience

- Add a managed runtime marketplace with versions, ABI metadata, signatures, checksums, dependency declarations, and rollback.
- Add runtime health checks and self-repair for Python, Java, Node.js, Gradle, Git, database, and reverse-engineering toolchains.
- Add a terminal-style interactive task console with live output and process controls.
- Add import/export for skills, memory, settings, and workspaces.

### P3 — Advanced capabilities

- Add a multi-agent task board with specialist roles, shared artifacts, and review gates.
- Extend encrypted configuration backups to include workspace snapshots and optional encrypted memory-at-rest.
- Add local embedding/index management and configurable retrieval policies.
- Add a visual workflow editor (the agent-driven read-only workflow builder, retries, resume checkpoints, and persistent schedules are implemented).

- Add a plugin/extension API with signed packages and strict capability permissions.

Community feedback and contributions can help prioritize this roadmap.

## Contributing

Contributions are welcome. Before submitting a change:

1. Preserve existing package names, public interfaces, constructor signatures, tool registration IDs, and parameter names unless a migration is intentional and documented.
2. Keep runtime-specific binaries out of the source tree unless their licensing and distribution terms explicitly permit it.
3. Add tests for new tools, permission paths, and runtime installation behavior.
4. Document required Android permissions, root requirements, external binaries, and supported ABIs.
5. Never include API keys, private certificates, signing keystores, or personal data in commits.

Please open an issue describing the device/Android version, app version or commit, steps to reproduce, expected behavior, and relevant redacted logs.

## Disclaimer

OreshnikAgent is an actively developed software project. Features may be experimental, hardware-dependent, or unavailable until their external runtime is installed. No guarantee is made that every tool works on every Android device or Android vendor build.

The user is responsible for complying with applicable laws, service terms, software licenses, and authorization requirements. Security testing, reverse engineering, root operations, memory access, packet capture, and device automation must be performed only in environments where the user has permission.

---

**Repository:** https://github.com/casndrih/OreshnikAgent  
**Source branch:** main
