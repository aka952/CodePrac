# Deploying to Oracle Cloud (OCI)

One VM running Docker Compose: Caddy (HTTP/HTTPS) → nginx (frontend) → Spring Boot backend → Postgres, with user code sandboxed under gVisor. It fits in OCI's Always Free allowance.

> **Before a public launch:** there is no monthly cap on Claude API spend yet. See `docs/PRODUCT_PLAN.md`, Phase 3.
>
> **Sign-in needs a domain.** Everyone signs in with Google, and Google only accepts HTTPS redirect addresses for a public server. Plan on step 5 (a domain with HTTPS), not plain HTTP on the IP.

## What you need

- An OCI account
- The code on GitHub (`https://github.com/aka952/CodePrac`), including this branch
- An SSH key pair on your computer (`ssh-keygen -t ed25519` if you don't have one)
- A domain name (needed for HTTPS, which Google sign-in requires)
- A Google account, to create the sign-in credentials
- Optional: an Anthropic API key (for review grading, Ask AI and inline AI edits)

## 1. Create the VM (OCI console)

**Compute → Instances → Create instance**

| Setting | Value |
|---|---|
| Image | Canonical Ubuntu 24.04 |
| Shape | Ampere → `VM.Standard.A1.Flex`, 2 OCPU and 12 GB memory (up to 4 OCPU / 24 GB is free) |
| Networking | Create a new VCN with a public subnet; assign a public IPv4 address |
| SSH keys | Paste your public key (`~/.ssh/id_ed25519.pub`) |
| Boot volume | Default size is enough |

If creation fails with **"Out of capacity"**, the free ARM pool in that availability domain is full. Try another availability domain, try again later, or upgrade the account to Pay As You Go (the free allowance stays).

Note the instance's **public IP address**.

## 2. Open ports 80 and 443 (OCI console)

**Networking → Virtual cloud networks → your VCN → Security Lists → Default Security List → Add Ingress Rules**

Add two rules: source CIDR `0.0.0.0/0`, IP protocol TCP, destination port `80`; and the same for `443`.

OCI has a second firewall inside the VM itself. The setup script in the next step opens it.

## 3. Set up the server

```bash
ssh ubuntu@<public-ip>

git clone https://github.com/aka952/CodePrac.git codecomp
cd codecomp
sudo bash deploy/oci/setup-server.sh
exit                                   # log out and back in so your user can run docker
```

The script installs Docker and gVisor, opens ports 80/443 in the VM firewall, pulls the sandbox images, and checks that gVisor can run the Java sandbox. It is safe to run again.

## 4. Configure and start

```bash
ssh ubuntu@<public-ip>
cd codecomp

cp deploy/oci/env.example .env && chmod 600 .env
echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"    # copy this line's output into .env
nano .env                                            # set POSTGRES_PASSWORD; optionally ANTHROPIC_API_KEY and SITE_ADDRESS

docker compose up -d --build
```

The first build takes 10–20 minutes, almost all of it Maven downloading dependencies without printing progress. Later builds are fast.

Check it:

```bash
docker compose ps                                          # four services, all "Up"
docker compose logs backend | grep -E "verified|WARN"      # both repo exercises "verified"
```

Then open `http://<public-ip>/`.

## 5. HTTPS with a domain (optional)

1. Create a DNS **A record** for your domain pointing at the public IP.
2. In `.env`, set `SITE_ADDRESS=code.example.com` (your domain, no `http://`).
3. `docker compose up -d`

Caddy gets and renews a Let's Encrypt certificate by itself. This needs ports 80 and 443 reachable from the internet (step 2).

## 5b. Google sign-in

1. Open [Google Cloud Console](https://console.cloud.google.com/), create a project (or pick one).
2. **APIs & Services → OAuth consent screen:** user type **External**; fill in the app name and your email. The `openid`, `email` and `profile` scopes are all the app uses, and they need no Google review.
3. **APIs & Services → Credentials → Create credentials → OAuth client ID:** application type **Web application**.
4. Under **Authorized redirect URIs**, add exactly:
   `https://<your-domain>/api/auth/oauth2/code/google`
5. Copy the client ID and client secret into `.env`:
   ```
   GOOGLE_CLIENT_ID=....apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=...
   ```
6. `docker compose up -d`

While the consent screen is in **Testing** mode, only the Google accounts you list under **Test users** can sign in. Choose **Publish app** to let anyone sign in.

For local development, add `http://localhost:3000/api/auth/oauth2/code/google` as a second redirect URI on the same client (Google allows plain HTTP for localhost), or run without Google: `AUTH_DEV_HEADER=true docker compose up -d`, which lets the browser pick its own user id. Never set that on a public server; the production compose file forces it off.

## 6. Backups

`deploy/oci/backup.sh` dumps the database to `~/codecomp-backups/` and keeps 14 days. Run it nightly:

```bash
crontab -e
# add this line (03:15 UTC every day):
15 3 * * * /home/ubuntu/codecomp/deploy/oci/backup.sh >> /home/ubuntu/codecomp-backups/backup.log 2>&1
```

Restore a dump into a running stack:

```bash
gunzip -c ~/codecomp-backups/codecomp-<timestamp>.sql.gz | docker compose exec -T db psql -U codecomp codecomp
```

Those backups live on the same disk as the database. For an off-machine copy in OCI Object Storage (optional):

1. Create a bucket, e.g. `codecomp-backups`.
2. Create a **dynamic group** matching the instance, and a **policy**:
   `Allow dynamic-group <group> to manage objects in compartment <compartment> where target.bucket.name='codecomp-backups'`
3. Install the OCI CLI on the VM (see Oracle's CLI install guide).
4. Put `BACKUP_BUCKET=codecomp-backups` in front of the command in the cron line.

## Updating

```bash
cd ~/codecomp && git pull && docker compose up -d --build
```

Database migrations run automatically when the backend starts.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Site unreachable, but `curl localhost` works on the VM | One of the two firewalls: the VCN security list (step 2) or the VM's iptables (re-run the setup script) |
| `required variable POSTGRES_PASSWORD is missing` | `.env` is missing or not in the repository root |
| Every code run fails or times out | gVisor problem on this machine. Add `SANDBOX_RUNTIME=` (empty) to `.env`, run `docker compose up -d`, and note that user code is then isolated by plain Docker only |
| Backend can't log in to Postgres after changing the password | Postgres only reads `POSTGRES_PASSWORD` on first start. Change it inside the database too, or (losing all data) `docker compose down -v` and start again |
| Review grading / Ask AI return "not configured" | `ANTHROPIC_API_KEY` is not set in `.env` |
| Sign-in page says sign-in is not configured | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` missing from `.env` |
| Google shows "redirect_uri_mismatch" | The redirect URI in Google Cloud Console must match `https://<your-domain>/api/auth/oauth2/code/google` exactly, including `https` |
| Everyone is signed out after an update | Sessions live in the backend's memory, so a backend restart ends them. Users just sign in again |
| HTTPS certificate not issued | DNS doesn't point at the VM yet, or port 80/443 is closed; see `docker compose logs caddy` |
