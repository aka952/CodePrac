#!/usr/bin/env bash
# Runs on the EC2 instance as the ubuntu user, after the commit to deploy is checked out (see .github/workflows/ci.yml).
# Writes .env from the /codecomp/* parameters in SSM Parameter Store, then starts the stack. Compose runs the one-off
# seed job first; backend and worker start only if it succeeds.   Usage:  bash deploy/aws/deploy.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

token="$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
export AWS_DEFAULT_REGION="$(curl -sf -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/placement/region)"

umask 077
{
  echo "COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml"
  aws ssm get-parameters-by-path --path /codecomp --with-decryption \
      --query 'Parameters[].[Name,Value]' --output text |
    while IFS=$'\t' read -r name value; do echo "${name#/codecomp/}=$value"; done
} > .env.new
mv .env.new .env

if ! grep -q '^POSTGRES_PASSWORD=.' .env; then
  echo "Parameter /codecomp/POSTGRES_PASSWORD is missing (see docs/DEPLOY_AWS.md)." >&2
  exit 1
fi

docker compose build
# Seed on its own first. Compose's depends_on gate alone is not enough: `up` replaces the running backend and worker
# with the new images before it finds out that seeding failed, and leaves them stopped. This way a bad exercise file
# fails the deploy while the old version keeps serving.
docker compose run --rm seed
docker compose up -d --remove-orphans
docker compose ps -a
