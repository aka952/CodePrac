# Deploying to AWS

The same single-machine setup as [DEPLOY_OCI.md](DEPLOY_OCI.md), on one EC2 instance:

- Docker Compose runs Caddy, the frontend, the API, the worker and Postgres. A one-off `seed` container loads the exercises before the API and worker start.
- The infrastructure is defined in AWS CDK ([deploy/aws/](../deploy/aws/)).
- GitHub Actions deploys every push to `main` after the tests pass.

**Why EC2 and not Fargate or ECS-only:** the worker starts a sandbox container for every code run through the Docker socket and uses gVisor. Fargate allows neither.

```
GitHub Actions ──(OIDC role, ssm:SendCommand)──► EC2 t4g.medium, Ubuntu 24.04 ARM, Elastic IP
                                                   └─ git checkout <sha> → deploy/aws/deploy.sh
                                                        ├─ .env  ◄── SSM Parameter Store /codecomp/*
                                                        └─ docker compose up: db → seed (exits) → backend, worker → frontend, caddy
```

What the CDK stack creates (`deploy/aws/lib/codecomp-stack.ts`):

| Resource | Notes |
|---|---|
| VPC with one public subnet | No NAT gateway (none needed, no hourly cost) |
| Security group | Ports 80 and 443 only. **No SSH**: you get a shell through SSM Session Manager |
| EC2 instance | `t4g.medium` (2 vCPU, 4 GB), 30 GB encrypted gp3 disk, IMDSv2 only. On first boot it clones the repository to `/opt/codecomp` and runs `deploy/oci/setup-server.sh` (Docker, gVisor, sandbox images) |
| Elastic IP | Fixed address for DNS |
| Instance role | SSM agent, plus read access to `/codecomp/*` parameters |
| GitHub OIDC provider and deploy role | Only the `main` branch of this repository can assume it, and it can only send commands to this instance. No AWS keys are stored in GitHub |

## What you need

- An AWS account, and the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) signed in to it (`aws sts get-caller-identity` works)
- The [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html) for the AWS CLI, to get a shell on the instance
- Node.js 22
- A domain name (needed for HTTPS, which Google sign-in requires)
- Google sign-in credentials ([DEPLOY_OCI.md, step 5b](DEPLOY_OCI.md#5b-google-sign-in)); the redirect URI is `https://<your-domain>/api/auth/oauth2/code/google`
- Optional: an Anthropic API key

## 1. Create the infrastructure

```bash
cd deploy/aws
npm ci
npx cdk bootstrap        # once per account and region
npx cdk deploy
```

It prints three outputs: `PublicIp`, `InstanceId` and `DeployRoleArn`. Keep them.

If the account already has a GitHub OIDC provider (`token.actions.githubusercontent.com`), the deploy fails because only one is allowed per account. Reuse the existing one:

```bash
npx cdk deploy -c githubOidcProviderArn=arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com
```

The first boot takes a few minutes (Docker, gVisor, sandbox images). It is finished when `/opt/codecomp` exists and `docker` works in a session (step 4).

## 2. Store the settings

The instance builds its `.env` from SSM Parameter Store on every deploy, so secrets never live in the repository or in GitHub. Use the same region as the stack:

```bash
put() { aws ssm put-parameter --name "/codecomp/$1" --type SecureString --overwrite --value "$2"; }

put POSTGRES_PASSWORD "$(openssl rand -hex 24)"     # set once: Postgres only reads it on first start
put SITE_ADDRESS code.example.com                    # your domain; Caddy then gets an HTTPS certificate
put GOOGLE_CLIENT_ID '...'
put GOOGLE_CLIENT_SECRET '...'
put ANTHROPIC_API_KEY '...'                          # optional
```

Any other variable from the README's configuration table can be set the same way, for example `/codecomp/SANDBOX_RUNTIME`.

## 3. Point the domain at the instance

Create an `A` record for your domain with the `PublicIp` output as its value.

## 4. First deploy

You can deploy by hand once, which is also the quickest way to check the instance:

```bash
aws ssm start-session --target <InstanceId>
sudo -iu ubuntu
cd /opt/codecomp && bash deploy/aws/deploy.sh
```

The first build takes 10–20 minutes, mostly Maven downloading dependencies. Then:

```bash
docker compose ps -a                                        # seed "Exited (0)", the other five "Up"
docker compose logs worker | grep -E "verified|WARN"        # every repo exercise "verified"
```

Open `https://<your-domain>/`.

## 5. Deploy from GitHub Actions

In the GitHub repository go to **Settings → Secrets and variables → Actions → Variables** and add:

| Variable | Value |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | The `DeployRoleArn` output |
| `AWS_REGION` | The stack's region, e.g. `eu-central-1` |

From then on every push to `main` runs the tests, then the `deploy` job. That job:

1. assumes the deploy role through OIDC,
2. sends an SSM command to the instance that checks out the pushed commit and runs `deploy/aws/deploy.sh`,
3. waits for the command, prints its output, and fails if the deploy failed.

The job is skipped while `AWS_DEPLOY_ROLE_ARN` is not set.

If seeding fails (for example, a broken exercise YAML), `seed` exits with 1. Compose then leaves the API and worker as they were and the job fails.

## Updating

Push to `main`. Or deploy by hand as in step 4, after `git pull`. Database migrations and seeding run on every deploy; both are safe to repeat.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `cdk deploy` fails on `GithubOidc` with "already exists" | The account already has GitHub's OIDC provider; pass `-c githubOidcProviderArn=...` (step 1) |
| `aws ssm start-session` says the target is not connected | The instance is still booting, or the Session Manager plugin is missing on your computer |
| `Parameter /codecomp/POSTGRES_PASSWORD is missing` | Step 2 was skipped, or the parameters are in a different region |
| Deploy job: `Could not assume role` | `AWS_DEPLOY_ROLE_ARN` is wrong, or the push was not to `main` of the repository named in `deploy/aws/cdk.json` |
| Deploy job: `service "seed" didn't complete successfully` | `docker compose logs seed` on the instance: a bad exercise file or no database connection |
| Deploy job times out on the first run | The first build is slow; re-run the job, Maven's downloads are cached by then |

Everything else (gVisor, Google sign-in, HTTPS) is the same as on OCI: see [DEPLOY_OCI.md, Troubleshooting](DEPLOY_OCI.md#troubleshooting).

## Cost

Roughly $30 a month in `us-east-1`, on demand: the instance about $25, the disk about $2.40, the public IPv4 address about $3.60. Other regions differ slightly.

## Backups

The database lives on the instance's disk. `deploy/oci/backup.sh` works here for local dumps (cron line in [DEPLOY_OCI.md, step 6](DEPLOY_OCI.md#6-backups)), but its off-machine upload targets OCI Object Storage. For AWS, take EBS snapshots (Data Lifecycle Manager) or copy the dumps to S3.

## Removing it

```bash
cd deploy/aws && npx cdk destroy
```

This deletes the instance **and its disk, including the database**. Back it up first. The SSM parameters are not part of the stack; delete them with `aws ssm delete-parameter`.
