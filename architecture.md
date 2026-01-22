## System Architecture

The user adds an autonomous agent as an **admin** to their Google Home.
All interaction flows through the agent, not individual devices or skills.

```mermaid
flowchart LR
    User((User))

    subgraph Client
        App[Dedicated Agent App]
        Echo[Voice Assistants\n(Echo, etc.)]
    end

    subgraph Home
        GH[Google Home\n(Admin)]
        Devices[Smart Home Devices]
    end

    subgraph Agent
        AgentCore[Autonomous Agent]
        Integrations[Integrations Layer]
    end

    User --> App
    User --> Echo
    App --> AgentCore
    Echo --> AgentCore
    AgentCore --> Integrations
    Integrations --> GH
    GH --> Devices
    Devices --> GH
    Integrations --> AgentCore
    AgentCore --> App
    AgentCore --> Echo
