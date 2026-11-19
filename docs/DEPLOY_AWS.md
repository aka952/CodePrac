# Deploying to AWS

The microservices on managed AWS services, defined in AWS CDK ([deploy/aws/](../deploy/aws/)) and deployed by GitHub Actions.

> **Status:** the stack synthesizes (`npx cdk synth`, also checked in CI) but has not been deployed. Treat this guide as the intended procedure, not a tested one.

```
Browser ─► CloudFront ─┬─ /*      → S3 (the built frontend)
                       └─ /api/*  → ALB (only reachable from CloudFront) ─ path rules ─┐
                                                                                      │
   ┌──────────────────────────── private subnets ──────────────────────────────────────┤
   │  /api/auth/*                         → identity  (Fargate)                         │
   │  /api/exercises*, /api/points/snippets → exercise (Fargate)                        │
   │  /api/repos*, /api/edits, /api/points/repos → repo (Fargate) ─Service Connect─► run /internal/runs
   │  /api/runs, /api/jobs/*, /api/languages → run     (Fargate)                        │
   │                                                                                    │
   │  run ─► SQS run-requests ─► worker (ECS on EC2 Auto Scaling group, Docker + gVisor) │
   │  worker ─► SNS run-events ─┬► SQS run-events-run  → run                           │
   │                            └► SQS run-events-repo → repo                          │
   │  RDS Postgres 16: databases identity, exercise, repo, run (one login each)         │
   └────────────────────────────────────────────────────────────────────────────────────┘
```

**Why the worker is on EC2, not Fargate:** it starts a sandbox container for every run through the host's Docker socket, and those containers run under gVisor. Fargate offers neither a Docker daemon nor a choice of container runtime.

## What the stack creates

| Resource | Notes |
|---|---|
| VPC | 2 AZs, public subnets (ALB, NAT) and private subnets (everything else), 1 NAT gateway |
| RDS Postgres 16 | `db.t4g.micro`, encrypted, 7-day backups, deletion protection. One database and one login per service, created by the `db-init` task |
| ECS cluster | Service Connect namespace `codecomp.internal`: services call each other as `http://identity:8080`, `http://run:8080` |
| 4 Fargate services | identity, exercise, repo, run. 1 task each, scaling on CPU (identity to 2, the others to 4). Rolling deploys with automatic rollback |
| Worker | ECS service on an EC2 Auto Scaling group (`t3.medium`, ECS-optimized Amazon Linux 2023, gVisor installed at boot). One worker per host; scales from 1 to 4 on the depth of the run-requests queue |
| SQS + SNS | `run-requests` (visibility 2 min, dead-letter after 3 receives), topic `run-events`, and one queue per subscriber (`run-events-run`, `run-events-repo`), each with its own dead-letter queue |
| ALB | Path rules per service; no rule for `/internal/*`, so it answers 404. Its security group admits only CloudFront |
| CloudFront + S3 | The frontend from a private bucket; `/api/*` forwarded to the ALB uncached, with cookies and headers |
| Secrets Manager | DB passwords (generated), and three set by hand: `codecomp/jwt-private-key`, `codecomp/google-client-secret`, `codecomp/anthropic-api-key` |
| ECR | One repository per image, immutable tags (the git commit) |
| CloudWatch | Logs per service (`/codecomp/<name>`, 30 days), alarms on every dead-letter queue and on ALB 5xx |
| GitHub OIDC deploy role | Only the `main` branch of this repository can assume it. Pushes images, runs the one-off tasks, runs `cdk deploy`, uploads the frontend |

Security groups say who may call whom: the ALB reaches the four web services; exercise, repo and run reach identity (for its public keys); only repo reaches run (for `/internal/runs`); only the services and the one-off tasks reach Postgres.

## What you need

- An AWS account and the AWS CLI signed in to it (`aws sts get-caller-identity` works), Node.js 22, Docker, `jq`
- Google sign-in credentials (below); the redirect URI is `https://<your-cloudfront-domain>/api/auth/oauth2/code/google`
- Optional: an Anthropic API key

## 1. Create the infrastructure

```bash
cd deploy/aws
npm ci
npx cdk bootstrap        # once per account and region
npx cdk deploy -c googleClientId=<id>.apps.googleusercontent.com
```

Without an image tag, every service is created with 0 tasks: there are no images yet. The outputs include `SiteUrl` and `DeployRoleArn`.

`cdk.json` holds the ID of CloudFront's origin-facing prefix list for `us-east-1`. In another region, look yours up and pass it with `-c cloudFrontPrefixListId=...`:

```bash
aws ec2 describe-managed-prefix-lists --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing
```

If the account already has GitHub's OIDC provider, pass `-c githubOidcProviderArn=arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com`.

## 2. Set the secrets

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt.pem   # signs sign-in tokens
aws secretsmanager put-secret-value --secret-id codecomp/jwt-private-key --secret-string file://jwt.pem && rm jwt.pem
aws secretsmanager put-secret-value --secret-id codecomp/google-client-secret --secret-string '...'
aws secretsmanager put-secret-value --secret-id codecomp/anthropic-api-key --secret-string '...'   # optional
```

Every identity-service instance signs with this key, and every other service checks tokens against its public half (published at `/api/auth/jwks`). Replacing it signs everyone out.

### Google sign-in

1. [Google Cloud Console](https://console.cloud.google.com/) → create a project → **APIs & Services → OAuth consent screen**: user type External. The app uses only `openid`, `email` and `profile`.
2. **Credentials → Create credentials → OAuth client ID**, type Web application. Authorized redirect URI: `https://<SiteUrl domain>/api/auth/oauth2/code/google`.
3. Put the client ID in the `cdk deploy` command (step 1) and the secret in Secrets Manager (above).

While the consent screen is in Testing mode, only the listed test users can sign in.

## 3. Deploy

By hand, or let GitHub Actions do it (step 4). Either way it is one script:

```bash
deploy/aws/deploy.sh "$(git rev-parse HEAD)"
```

It:

1. builds the five images and pushes them to ECR, tagged with the commit;
2. runs the one-off tasks with the new images: `db-init` (creates missing database logins), then the exercise and repo **seed** tasks. If seeding fails (a broken exercise file), the deploy stops here and the old version keeps serving;
3. runs `cdk deploy -c imageTag=<commit>`: ECS replaces each service's tasks one by one, and rolls a service back if its new tasks fail their health checks. Each service applies its own Flyway migrations at startup;
4. uploads the frontend to S3 and clears CloudFront's cache.

Then open the `SiteUrl` output.

## 4. Deploy from GitHub Actions

In the repository: **Settings → Secrets and variables → Actions → Variables**:

| Variable | Value |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | The `DeployRoleArn` output |
| `AWS_REGION` | The stack's region |

Every push to `main` then runs the tests, `cdk synth`, and the `deploy` job, which assumes the role through OIDC (no AWS keys in GitHub) and runs `deploy/aws/deploy.sh`. The job is skipped while `AWS_DEPLOY_ROLE_ARN` is not set.

## Rolling deploys: the rules they impose

Old and new versions run side by side for a few minutes, so:

- a migration must not break the old code (add columns and tables; drop them a release later);
- the messages and events in `backend/contracts` may gain fields, never lose or rename them: an old run-service must still read a new worker's events.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `cdk deploy` fails on `GithubOidc` with "already exists" | The account already has GitHub's OIDC provider; pass `-c githubOidcProviderArn=...` |
| `codecomp-db-init failed` / `codecomp-*-seed failed` | CloudWatch logs `/codecomp/db-init`, `/codecomp/exercise-seed`, `/codecomp/repo-seed` |
| A service's deployment rolls back | `/codecomp/<service>` logs. identity: `JWT_PRIVATE_KEY` not a PKCS#8 PEM (step 2) |
| Everyone signed out after a deploy | The JWT key changed, or identity started without one and made a temporary key |
| Alarm on `run-requests-dlq` | Runs that no worker could finish (workers crashing). run-service marks them FAILED |
| Alarm on `run-events-*-dlq` | Events a service could not process; their logs say why. Redrive them once fixed |
| Runs stay QUEUED | No worker running: check the worker service and the Auto Scaling group (`runsc` install in the instance's user data log) |

## Cost

Not measured, since it has not been deployed. The fixed part is roughly $120–150 a month in `us-east-1` on demand: the NAT gateway (~$33 plus traffic), the ALB (~$18), RDS `db.t4g.micro` (~$13), four Fargate tasks at 0.5 vCPU / 1 GB (~$70), one `t3.medium` worker host (~$30), plus small amounts for CloudFront, SQS, SNS, Secrets Manager and CloudWatch. The single-EC2 setup this replaces cost about $30.

## Removing it

```bash
cd deploy/aws && npx cdk destroy
```

The database has deletion protection and is snapshotted on removal: turn protection off in the RDS console first. The ECR repositories are retained (with their images); delete them by hand if you want them gone.
