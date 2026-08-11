# SyncVerse 🔄 — Lightweight Enterprise File Sync

[![Java](https://img.shields.io/badge/Java-17-%23ED8B00)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

A zero-config file synchronization system for enterprise environments. SyncVerse keeps files in sync across multiple workstations using a **Central Server** and **lightweight CLI daemons** — with zero open ports on client machines.

> **The challenge:** Traditional file sync requires open ports or complex NAT/Firewall configuration. SyncVerse solves this with an HTTP RESTful polling architecture — clients never serve, only request.

## ✨ Features

| Feature | Description |
|---------|-------------|
| **Zero Client Ports** | Clients never open ports — only outbound HTTP requests |
| **Real-Time Sync** | `java.nio.file.WatchService` detects file changes instantly |
| **Heartbeat Monitoring** | Clients send heartbeats every 3-5s for session health |
| **Delta Catch-Up** | Auto-healing on reconnect — requests missed deltas from server |
| **Flat Directory Sync** | Synchronizes a flat workspace folder (no subdirectories) |
| **File Size Cap** | Each file ≤ 1MB for efficient transfer |
| **Dual JAR** | `server.jar` + `client.jar` — independent, modular deployment |

## 🏗 Architecture

```
┌──────────────┐     HELLO / HEARTBEAT      ┌──────────────┐
│  Client      │ ──────────────────────────► │  Central     │
│  Alice_Node  │     FILE_CHANGE / DELTA     │  Server      │
│  (Daemon)    │ ◄────────────────────────── │  (REST API)  │
└──────────────┘                             │              │
                                             │  ┌────────┐ │
┌──────────────┐     HELLO / HEARTBEAT      │  │ File   │ │
│  Client      │ ──────────────────────────► │  │ Store  │ │
│  Bob_Node    │     FILE_CHANGE / DELTA     │  └────────┘ │
│  (Daemon)    │ ◄────────────────────────── └──────────────┘
└──────────────┘
```

### Sync Protocol

| Message Type | Direction | Description |
|:---:|:---:|---|
| **HELLO** | Client → Server | Register client & initialize session |
| **HEARTBEAT** | Client → Server | Periodic ping (every 3-5s) to maintain session |
| **FILE_CHANGE** | Client → Server | Notify of created/modified/deleted files |
| **RECONNECT** | Client → Server | Re-connect after offline period |
| **DELTA_REQUEST** | Client ↔ Server | Fetch missed file changes |

### Auto-Healing Flow

```
Client crashes ──► Reconnects ──► Sends RECONNECT
                                      │
                                      ▼
                              Sends DELTA_REQUEST
                                      │
                                      ▼
                          Server computes diff
                                      │
                                      ▼
                          Client pulls missed files
                                      │
                                      ▼
                          Local folder fully synced
```

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.9+

### Build

```bash
# Full build (parent + all modules)
mvn clean package

# Individual JARs
mvn package -pl syncverse-server -am
mvn package -pl syncverse-client -am
```

### Run

**Start Server:**

```bash
java -jar syncverse-server/target/server.jar AlphaServer
```

**Start Clients (each in its own terminal):**

```bash
# Terminal 1
java -jar syncverse-client/target/client.jar Alice_Node ./workspace_alice

# Terminal 2
java -jar syncverse-client/target/client.jar Bob_Node ./workspace_bob

# Terminal 3
java -jar syncverse-client/target/client.jar Charlie_Node ./workspace_charlie
```

## 📁 Project Structure

```
syncverse-server/        # Central coordination server
  ├── pom.xml
  └── src/main/java/...

syncverse-client/        # CLI daemon client
  ├── pom.xml
  └── src/main/java/...

syncverse-common/        # Shared models & utilities
  ├── pom.xml
  └── src/main/java/...
```

---

*Built as part of a backend engineering internship program — exploring HTTP-based distributed file synchronization.*
