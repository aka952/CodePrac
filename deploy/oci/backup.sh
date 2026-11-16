#!/usr/bin/env bash
# Dumps the database to a dated, compressed file and keeps the last 14 days.
# Run from cron as the login user (see docs/DEPLOY_OCI.md).   Usage:  deploy/oci/backup.sh
#
# Optional off-machine copy: set BACKUP_BUCKET to an OCI Object Storage bucket name. Needs the OCI CLI installed
# and the instance allowed to write to the bucket (dynamic group + policy, see docs/DEPLOY_OCI.md).
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
backup_dir="${BACKUP_DIR:-$HOME/codecomp-backups}"
keep_days=14

mkdir -p "$backup_dir"
file="$backup_dir/codecomp-$(date -u +%Y%m%d-%H%M%S).sql.gz"

cd "$repo_dir"
docker compose exec -T db pg_dump -U codecomp codecomp | gzip > "$file.partial"
mv "$file.partial" "$file"
echo "Wrote $file ($(du -h "$file" | cut -f1))"

if [ -n "${BACKUP_BUCKET:-}" ]; then
  oci os object put --auth instance_principal --bucket-name "$BACKUP_BUCKET" --file "$file" --force
  echo "Uploaded to bucket $BACKUP_BUCKET"
fi

find "$backup_dir" -name 'codecomp-*.sql.gz' -mtime "+$keep_days" -delete
