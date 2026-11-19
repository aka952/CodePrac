# High-level design

The whole system on one page. For what each part owns and how a request moves through it, see [ARCHITECTURE.md](ARCHITECTURE.md).

```mermaid
flowchart TB
    U([User's browser])

    subgraph Edge
        CDN[CloudFront CDN<br/>local: nginx]
        WEB[(Static frontend<br/>S3)]
        ALB[Load balancer<br/>routes /api/* by path]
    end

    subgraph Services["Web services (ECS Fargate, stateless)"]
        ID[identity-service<br/>Google sign-in, JWT]
        EX[exercise-service<br/>snippet exercises, grading]
        RP[repo-service<br/>repo exercises, reviews, test runs]
        RN[run-service<br/>run jobs, cache, live updates]
    end

    subgraph Messaging["Messaging (SQS / SNS)"]
        RQ[[run-requests queue]]
        EV{{run-events topic}}
    end

    subgraph Workers["Sandbox workers (ECS on EC2)"]
        W[sandbox-worker]
        SB[Docker + gVisor<br/>isolated containers]
    end

    subgraph Data["Postgres (RDS): one database per service"]
        DB1[(identity)]
        DB2[(exercise)]
        DB3[(repo)]
        DB4[(run)]
    end

    G[Google OAuth]
    C[Claude API]

    U --> CDN
    CDN --> WEB
    CDN --> ALB
    ALB --> ID & EX & RP & RN

    ID --> G
    RP --> C
    RP -->|submit test run| RN
    RN -->|queue run| RQ
    RQ --> W
    W --> SB
    W -->|result| EV
    EV -->|fan-out| RN
    EV -->|fan-out| RP

    ID --- DB1
    EX --- DB2
    RP --- DB3
    RN --- DB4
```

## How to read it

| Layer | What it does |
|---|---|
| **Edge** | Serves the frontend and sends `/api/*` calls to the right service by path |
| **Web services** | Four stateless services split by domain. Each checks the sign-in token itself, using identity's public key |
| **Messaging** | Code runs are queued, not run inline. Results are published once and delivered to every service that needs them |
| **Sandbox workers** | Run untrusted code in throwaway containers with no network, under gVisor. They have no database |
| **Data** | One Postgres instance, a separate database and login per service. No service reads another's tables |
| **External** | Google for sign-in. Claude for grading reviews and answering language questions |

## Key paths

- **Sign-in:** browser → identity-service → Google → identity issues a JWT cookie. Every other service verifies it locally.
- **Snippet run:** browser → run-service → queue → worker → topic → run-service → browser (live over Server-Sent Events).
- **Repo test run:** browser → repo-service → run-service → queue → worker → topic → run-service *and* repo-service. The browser follows the job on run-service, then gets the score from repo-service.
- **Review:** browser → repo-service → Claude grades the review.
