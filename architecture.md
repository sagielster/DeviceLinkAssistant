flowchart TB

    %% User
    User((User))

    %% Client layer
    subgraph Client
        App[Dedicated Agent App]
        Voice[Voice Assistants\n(Echo, etc.)]
    end

    %% Agent layer
    subgraph Agent
        AgentCore[Autonomous Agent]
        Integrations[Integrations Layer]
    end

    %% Home layer
    subgraph Home
        GH[Google Home\n(Admin)]
        Devices[Smart Home Devices]
    end

    %% Intent flow
    User --> App
    User --> Voice

    App --> AgentCore
    Voice --> AgentCore

    %% Authority flow
    AgentCore --> Integrations
    Integrations --> GH
    GH --> Devices
