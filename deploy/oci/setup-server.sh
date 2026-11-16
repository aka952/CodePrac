#!/usr/bin/env bash
# One-time setup for a fresh Ubuntu 22.04/24.04 VM on OCI (x86 or Ampere ARM).
# Installs Docker and gVisor, opens ports 80/443 in the VM's own firewall, and pre-pulls the sandbox images.
# Safe to run again.   Usage:  sudo bash deploy/oci/setup-server.sh
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo." >&2
  exit 1
fi

arch="$(dpkg --print-architecture)"
. /etc/os-release

echo "==> Installing Docker"
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg
install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.asc ]; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
fi
echo "deb [arch=${arch} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
  > /etc/apt/sources.list.d/docker.list

echo "==> Installing gVisor (runsc)"
if [ ! -f /usr/share/keyrings/gvisor-archive-keyring.gpg ]; then
  curl -fsSL https://gvisor.dev/archive.key | gpg --dearmor -o /usr/share/keyrings/gvisor-archive-keyring.gpg
fi
echo "deb [arch=${arch} signed-by=/usr/share/keyrings/gvisor-archive-keyring.gpg] https://storage.googleapis.com/gvisor/releases release main" \
  > /etc/apt/sources.list.d/gvisor.list

apt-get update -q
apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin runsc
runsc install
systemctl restart docker

# Let the login user run docker without sudo (takes effect at next login).
login_user="${SUDO_USER:-ubuntu}"
if id "$login_user" >/dev/null 2>&1; then
  usermod -aG docker "$login_user"
fi

echo "==> Opening ports 80 and 443 in the VM firewall"
# OCI's Ubuntu images ship iptables rules that end in a REJECT. New ACCEPT rules must go before it.
for port in 80 443; do
  if ! iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null; then
    reject_line="$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')"
    if [ -n "$reject_line" ]; then
      iptables -I INPUT "$reject_line" -p tcp --dport "$port" -m state --state NEW -j ACCEPT
    else
      iptables -A INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT
    fi
  fi
done
if command -v netfilter-persistent >/dev/null 2>&1; then
  netfilter-persistent save
else
  echo "    netfilter-persistent not found: the rules above last until reboot. Install iptables-persistent to keep them."
fi

echo "==> Pulling sandbox images"
docker pull -q python:3.12-alpine
docker pull -q eclipse-temurin:21-jdk-alpine

echo "==> Checking that the gVisor sandbox can run Java"
if docker run --rm --runtime=runsc --network none eclipse-temurin:21-jdk-alpine java -version >/dev/null 2>&1; then
  echo "    gVisor works."
else
  echo "    gVisor could NOT run the Java sandbox image on this machine."
  echo "    To run without it, add this line to .env:  SANDBOX_RUNTIME="
fi

echo
echo "Done. Also open ports 80 and 443 in the OCI console (VCN security list), then log out and back in."
