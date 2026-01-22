flowchart LR
    User((User))

    subgraph Client
        App[Dedicated Agent App]
        Echo[Voice Assistants<br/>Echo Devices]
    end

    subgraph Home
        GH[Google Home Admin]
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
