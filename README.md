# MealMind

MealMind is a personal meal decision agent that helps users decide what to eat based on meal time, budget, location, preferences, recent history, and simple dietary goals.

## Project Goal

The goal is not to build a generic nutrition chatbot. MealMind focuses on a practical daily decision:

> Given my current situation and my available meal options, what should I eat next, and why?

The system should be able to ask clarifying questions when information is missing, retrieve candidate meals from a personal or public menu, rank them, generate concise recommendation reasons, and keep an observable trace of the AI workflow.

## MVP Scope

The first version should be small but complete:

- Manage a small personal meal menu
- Start a chat session
- Detect user intent for meal recommendation or adjustment
- Extract basic slots such as meal time, budget, taste, location, and dietary goal
- Ask a clarifying question when required information is missing
- Retrieve and rank meal candidates
- Generate a short explanation for each recommendation
- Save conversation history and AI trace events
- Provide a simple page for testing the workflow

## Planned Architecture

```text
MealMind
├── backend      # Main API, business workflow, persistence
├── frontend     # Web UI for chat, menu management, and debugging
├── ai-service   # Optional AI workflow helpers or prompt/evaluation utilities
├── evaluation   # Golden cases and evaluation scripts
└── docs         # Design notes and project documentation
```

## Core Workflow

```text
User message
→ Intent detection
→ Slot extraction and merge
→ Clarification if needed
→ Meal search
→ Ranking
→ AI recommendation response
→ Risk guard
→ Trace and feedback
```

## Current Status

This repository is being initialized. The immediate next step is to define the MVP design and build the smallest working version of the app.

