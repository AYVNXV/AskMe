# Ask Me - Mod Documentation

A **client-side Fabric mod for Minecraft 26.2** that puts Google's
**Gemini 1.5 Flash** AI into your chat, with a full configuration GUI, a fixed
management command set, secure API-key masking and automatic command execution.

---

## 1. What it does

| Feature | Where |
|---|---|
| Ask Gemini anything locally | `/ask <question>` (default trigger; customizable) |
| Open configuration GUI | Press **K** (rebindable) or ModMenu → Ask Me → Configure |
| Manage everything | `/askme help / apikey / prefix / systemprompt / actions / info` |
| Masked API key | GUI hides all but first 2 chars; `/askme info` shows masked form too |
| Show All toggle | Reveal/hide key inside the GUI at any time |
| Copy button | Puts the FULL raw key on your clipboard |
| Async requests | All HTTP runs on dedicated daemon threads; zero game freezes |
| Action execution | Model may emit `ACTION::/shop` lines which your client runs (max 3) |
| Error handling | Invalid keys, timeouts, rate limits → clean colored chat warnings |

All answers are rendered **only in your local chat** - nothing is ever sent to
the server besides commands you approve via Action Execution.

---

## 2. File map

```text
src/main/java/com/ayyan/askme/
├── AskMe.java                    main entrypoint (constants + logger)
├── AskMeClient.java              client entrypoint bootstrap order
├── command/
│   ├── AiCommands.java           customizable /<prefix> tree + live Brigadier rewiring + answer rendering + ACTION runner
│   └── ManageCommands.java       fixed /askme subcommand tree (help/apikey/prefix/systemprompt/actions/info)
├── config/
│   ├── AskMeConfig.java          JSON persistence (config/askme.json) via Gson
│   └── KeyMasker.java            shared masking helper (first 2 chars visible)
├── gemini/
│   └── GeminiService.java        async HttpClient -> v1beta generateContent; friendly error mapping; main-thread callbacks
├── gui/
│   ├── AskMeConfigScreen.java    K-screen: labels, fields, toggles, Save, status line
│   └── MaskedTextFieldWidget.java TextFieldWidget subclass with password-style display
├── integration/
│   └── ModMenuIntegration.java   ModMenu entrypoint -> config screen factory
├── keybind/
│   └── ModKeys.java              registers K + END_CLIENT_TICK opener
└── util/
    └── ChatUtil.java             colored local-chat print helpers (info/warn/error/subtle/raw)
```

Assets:

```text
src/main/resources/
├── fabric.mod.json               id ask-me, entrypoints main/client/modmenu, environment client
└── assets/ask-me/
    ├── icon.png                  mod icon (ModMenu/library pages)
    └── lang/en_us.json           GUI labels, statuses and keybind name translations
```

Config file created on first run:

```text
.minecraft/config/askme.json   { apiKey, commandKeyword, promptPrefix, allowActions }
```

---

## 3. Registration map

| Component | Registered in | Mechanism |
|---|---|---|
| Client feature set | `AskMeClient.onInitializeClient` | Fabric `client` entrypoint |
| Config load | `AskMeClient.onInitializeClient` | `AskMeConfig.load()` before everything |
| Key `K` + tick | `ModKeys.initialize()` | fabric-key-binding-api-v1 + lifecycle events |
| `/<keyword>` (+ `/ask`, `/ai`) | `AiCommands.initialize()` | `ClientCommandRegistrationCallback` + root-node splice |
| `/askme ...` | `ManageCommands.initialize()` | fabric-command-api-v2 client dispatcher |
| Config screen | `AskMeConfigScreen` | plain vanilla `Screen`; opened by keybind or ModMenu |
| ModMenu hook | `ModMenuIntegration` | `modmenu` entrypoint (optional dependency) |

### How live prefix swapping works

Brigadier's client `CommandDispatcher` keeps its root node in a private field.
`AiCommands.resolveRoot()` reflects it once (field name is stable because
Brigadier is an unobfuscated library), then:

1. validates the requested word (`[a-z0-9_-]{1,32}`),
2. rejects words owned by other commands (`RESERVED`),
3. detaches our previous custom literal (never `/ask`),
4. attaches the freshly built literal subtree (`OK`) or falls back to
   *active-after-restart* messaging (`FAILED`).

Both successes persist to `config/askme.json` immediately.

---

## 4. Data flow of one question

```text
/ask why is the sky blue
      │  (main thread)
      ▼
AiCommands.runAsk -> key present? -> ChatUtil "Thinking..."
      │            snapshot apiKey/promptPrefix/allowActions
      ▼
GeminiService.ask  (worker thread #n)
   builds { contents[], system_instruction[], generationConfig }
   POST https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=...
      │  HTTP >=400? -> mapped friendly warning       time-out/net-error -> retry hint
      ▼
parse candidates[0].content.parts[*].text   (or blockReason/safety stop)
      │  result delivered via MinecraftClient.execute() -> MAIN THREAD again
      ▼
AiCommands.presentAnswer
   split ACTION::/cmd lines (cap 3) → print answer body in chat
   allowActions==true ? networkHandler.sendChatCommand(cmd) : skip with notice
```

## 5. Security model

* Key stored only in `config/askme.json` on disk; transmitted solely as the
  `?key=` query parameter over HTTPS to Google.
* Every user-visible surface shows `KeyMasker.mask()` output; the raw key is
  revealed exactly once per deliberate action (Show All) and copied verbatim
  once per Copy press - never logged, never echoed through chat.
* Action Execution defaults ON but can be disabled instantly with
  `/askme actions off` or the GUI toggle; suggestion lines are length-capped,
  counted (max 3) and executed only when connected to a world.

## 6. Building

```bat
gradlew.bat build     :: artifact lands in build/libs/ask-me-<version>.jar
```

Dependencies pinned in `gradle.properties`: Minecraft `1.21.2`,
Yarn `1.21.2+build.1`, Loader `0.19.5`, Loom `1.17-SNAPSHOT`,
Fabric API `0.160.0+26.2`, ModMenu `14.0.2` (compile-only).
