# Project Memory: Nexara

## Overview
Nexara is a local-first AI assistant built with React Native (Expo) and Local LLMs. It features a sophisticated RAG system, Knowledge Graph (KG) integration, and a premium "Super Assistant" UI.

## Core Pillars
1. **Local-First**: All sensitive data (chats, vectors, keys) stays on device.
2. **RAG & KG**: Hybrid retrieval using vector embeddings and knowledge graph relationships.
3. **Visual Excellence**: Premium UI with glassmorphism, glowing effects, and smooth animations (Reanimated).

## Recent Milestones (Jan 2026)

### Knowledge Graph 2.0
- **Global & Session Views**: KG visualization now supports both global (all docs) and session-specific (context) views.
- **Extraction Control**: 
  - Settings -> RAG -> Enable Knowledge Graph (Global)
  - Session -> Settings -> Extraction Toggle (Override)
- **Automatic Extraction**: Chat messages are processed in background to build dynamic entity graphs.
- **Interactive Graph**: Nodes and edges are clickable, with support for filtering by scope (Session/Agent).

### Super Assistant UI
- **Visual Polish**: Unified headers, rotational icons, and "Silky Glow" effects.
- **Configurability**: Full control over RAG params, inference settings, and visual appearance per assistant.
- **Stability**: Fixed crashes related to i18n missing keys (Icon Colors) and undefined object access.

### Engineering Improvements
- **Strict Isolation**: GraphStore now strictly respects filtering rules (or explicit global view).
- **Type Safety**: Enhanced TypeScript interfaces for `RagOptions`.
- **I18n**: Completed localization coverage for new implementation.

## Technical Stack
- **Framework**: Expo (React Native)
- **Store**: Zustand (Chat, Settings, RAG, Graph)
- **Database**: SQLite (Vectors, KG Nodes/Edges)
- **UI**: NativeWind (Tailwind), Reanimated, Skia (Gradients)
- **LLM Client**: Custom OpenAI-compatible client (Supports streaming)

## Known Issues / TODOs
- Android Share Intent support pending.
- PPTX import support pending research.
- Optimization of graph layout for >100 nodes.
