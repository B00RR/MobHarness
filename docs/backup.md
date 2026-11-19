# MobHarn Backup & Data Extraction Policy

This document defines the data classification, backup rules, device-to-device migration policy, and disaster recovery guarantees for MobHarn on Android.

---

## 1. Privacy Principles: Local Inference vs. Local Storage

MobHarn is designed around on-device intelligence:
- **Local Inference:** AI models execute locally on the device (NPU / GPU / CPU via LiteRT-LM) without routing prompts or responses to external cloud model servers.
- **Defensive Local Storage:** Local inference alone does not guarantee privacy if the operating system automatically packages and uploads application databases, user notes, tool history, and code workspaces to remote OS cloud backups (e.g., Google Drive / Android Cloud Backup).

To fulfill the defensive privacy guarantee, MobHarn enforces a strict policy that prevents sensitive user data from leaving the physical device via OS cloud backups, while supporting intentional, direct device-to-device migration when users switch phones.

---

## 2. Data Inventory and Classification

| Path / Location | Domain | Description | Sensitivity | Cloud Backup | Device Transfer |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `mobharn.db*` | `database` | Chat sessions, messages, memory entities, agent skills, tool logs, custom JS tools, automations | **Confidential** | **EXCLUDED** | **INCLUDED** |
| `mobharn_vault/` | `file` | Plaintext Markdown vault (`SOUL.md`, `USER.md`, `MEMORY.md`, skill definitions, session digests) | **Confidential** | **EXCLUDED** | **INCLUDED** |
| `workspace/` | `file` | Coding Workspace projects, user code (HTML/JS/CSS), and local database files | **Confidential** | **EXCLUDED** | **INCLUDED** |
| `litertlm_models/` | `file` | Downloaded LiteRT-LM / GGUF model weights (multi-GB binaries) | **Replaceable** | **EXCLUDED** | **EXCLUDED** |
| `datastore/` | `file` | UI preferences (theme mode, dynamic color, eco thermal mode toggle, enabled tool groups) | **Non-Sensitive** | **INCLUDED** | **INCLUDED** |
| `cache/` | `cache` | Transient caches and temporary images | **Ephemeral** | **EXCLUDED** | **EXCLUDED** |

---

## 3. Policy Specification

### Cloud Backup (`res/xml/data_extraction_rules.xml` & `res/xml/backup_rules.xml`)
Cloud backup targets remote OS cloud storage (Google Drive / Android Backup Service).
- **Rule:** All confidential user data (`database`, `mobharn_vault`, `workspace`) and large model binaries (`litertlm_models`) are explicitly **excluded**.
- **Result:** No personal prompts, private notes, conversation history, tool traces, or user-written scripts leave the phone over cloud backup.
- **Legacy Compatibility:** Android versions prior to API 31 follow identical exclusions via `res/xml/backup_rules.xml` (`<full-backup-content>`).

### Device-to-Device Transfer (`res/xml/data_extraction_rules.xml <device-transfer>`)
Device transfer targets user-initiated local transfers (USB-C cable or local Wi-Fi direct connection during initial device setup).
- **Rule:** User databases, Markdown vault files, and Coding Workspace projects are **included** so the user can seamlessly transition their local AI companion to their new phone.
- **Model Exclusion:** Replaceable model weights (`litertlm_models`) are explicitly **excluded**. Multi-gigabyte model binaries would severely delay phone transfer setup, risk transfer timeouts, and consume local storage unnecessarily. They can be re-downloaded on demand from the Model Hub.

---

## 4. Restore & Recovery Expectations

### Scenario A: Reinstallation or Cloud Restore
When reinstalling MobHarn or restoring a device from Google Cloud Backup:
- **What is recovered:** Application configuration and preferences (dark/light theme, dynamic colors, eco thermal settings).
- **What is NOT recovered:** Chat history, memory vault, tool logs, coding projects, and model files.
- **Why:** By design, to guarantee that no private conversational or memory artifacts were ever uploaded to third-party cloud infrastructure.

### Scenario B: Device-to-Device Migration (New Phone Setup)
When migrating to a new phone via direct cable or Wi-Fi transfer:
- **What is recovered:**
  - Complete chat session history and messages.
  - Personal memories, profile notes, and agent skills (`mobharn_vault/` and database).
  - Coding Studio projects and files (`workspace/`).
  - Scheduled background automations and custom tools.
- **What is NOT recovered:** Downloaded model binaries (`litertlm_models/`).
- **Post-migration step:** Navigate to **Studio → Model Hub** and download the desired on-device model weights.

### Scenario C: Manual User-Controlled Export
Users seeking off-device backups of their data can use built-in export features:
- **Vault Export:** Export the entire markdown memory vault as a zip archive from the Memory screen.
- **Workspace Export:** Export individual coding projects as `.zip` files from Coding Studio.
