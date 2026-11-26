<!-- IGNORE THIS, CONTEXT.MD TEMPLATE BY @SyanicXD (YouTube) -->

## 🔧 Mod Info

- **Mod Name:** `Ask Me`
- **Mod ID:** `AskMe`
- **Author:** `AYYAN`
- **Package Name:** `com.ayyan.askme`
- **Minecraft Version:** `26.2`
- **Fabric Loader Version:** `0.19.5`
- **Yarn Mappings:** `26.2+build.3`
- **Fabric API Version:** `0.115.4+1.21.1``
- **Java Version (JDK):** `25`
- **Mod Type:** `client`

---

## 🧠 Mod Functionality

Role & Objective: You are an expert Minecraft mod developer (Fabric 1.21+ / Forge). Update the "Ask Me" mod code specification to include secure API key masking in the GUI and command-line handling, featuring a "Show All" toggle and a "Copy" button. Ensure zero crashes, clean separation of concerns, and full production-ready code.

Updated Security & UI Requirements for API Key:

Masked API Key Input / Display:

In the configuration GUI and any status display, the saved Google Gemini API key must be masked for security.

By default, only the first 2 letters of the API key should be visible (e.g., AI...), while the rest are hidden behind masking characters (like *).

Include a "Show All" toggle checkbox or button next to the API key field in the GUI so the user can reveal or hide the full key when needed.

Include a "Copy" button right next to the field to easily copy the full API key to the clipboard.

Dual-Layer Command Structure & /askme Management:

AI Prompt Command (Customizable): Default is /ask (e.g., /ask what is your name), fully customizable via GUI or chat.

Fixed Internal Mod Management Command (/askme): Hardcoded and non-changeable.

Sub-commands:

/askme help: Displays comprehensive usage instructions.

/askme apikey <your_key>: Saves the Gemini API key directly from chat.

/askme prefix <new_prefix>: Updates the AI prompt prefix on the fly.

/askme info: Shows mod details, version, and current configuration status (with masked API key display).

Core Technical Specifications Retained:

Keybind & ModMenu Integration: Default key (K) to open configuration GUI, fully customizable via standard controls, with ModMenu integration.

Asynchronous Execution: Gemini 1.5 Flash API requests must run on separate background threads to prevent main-thread freezing, lag ticks, or game crashes.

Client-Side Secrecy: Responses render exclusively in the local player's chat client-side.

Action/Command Execution: Support automated game command execution (e.g., running /shop if requested by AI).

Error Handling: Gracefully catch invalid API keys, connection failures, or rate limits, outputting clean chat warnings.

Output Requirements:
Provide the complete file structure and full source code implementing these exact specifications for Fabric 1.21.11

---

## 📦 Output Instructions

The AI must:

- ✅ List all **Java source files**  
  - Show full relative paths (e.g., `src/main/java/com/...`)  
  - Give a one-line description of each file's purpose

- ✅ Write **full, working Java code** for every file  
  - No placeholders, stubs, or missing logic  
  - Fully import-ready and compilable

- ✅ Provide a valid, working `fabric.mod.json`

- ✅ Register all components correctly:
  - Items, blocks, screens, overlays, particles, keybinds, etc.  
  - Client or server init logic as appropriate

- ✅ Include all required **import statements** and **annotations**

---

## 🎨 Assets (If Applicable)

If assets are needed, AI must:

- 📂 **List all required assets**, including:
  - Textures (`.png`)
  - Lang files (`lang/en_us.json`)
  - Models and blockstates (`.json`)
  - Sounds or GUI elements

- 📄 **Provide full content** and **exact paths** for each file:
  - Example: `src/main/.../exampleMixin.java`
  - Example: `src/main/resources/assets/[modid]/textures/item/example_item.png`
  - Example: `src/main/resources/assets/[modid]/lang/en_us.json`

---

## 📘 Documentation Output

Also generate a brief documentation file and save it as:

> 📁 `docs/OUTPUT.md`

This file must:

- ✅ Explain what the mod does  
- ✅ Summarize all the components (classes, assets, events)  
- ✅ Describe where everything is registered and how it works  
- ✅ Be written for the user to understand how the mod is structured  
- ✅ Use Markdown formatting (headings, bullets, code blocks)

---

## ⚙️ Optional Features

If applicable, include:

- Mixin setup  
- Config file support (Cloth Config or JSON)  
- Command registration  
- Client/server networking  
- Runtime environment checks  
- Shader or render layer support

---

## ❌ Rules

- ❌ Do not skip any code  
- ❌ Do not use vague notes like “implement this later”  
- ❌ Do not generate placeholder files or comments  
- ✅ Output must be **complete and build-ready** from the start