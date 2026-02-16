#!/usr/bin/env bash
set -euo pipefail


DEFAULT_REPO_URL="https://github.com/agatx/serenada.git"
DEFAULT_INSTALL_DIR="/opt/serenada"

log() {
  printf "[setup] %s\n" "$*"
}

warn() {
  printf "[setup] WARNING: %s\n" "$*" >&2
}

die() {
  printf "[setup] ERROR: %s\n" "$*" >&2
  exit 1
}

OS_NAME="$(uname -s)"
if [ "$OS_NAME" != "Linux" ]; then
  die "Unsupported OS: ${OS_NAME}. This setup script supports Ubuntu/Debian Linux hosts."
fi

if ! command -v apt-get >/dev/null 2>&1; then
  die "Unsupported Linux distribution. This setup script requires apt-get (Ubuntu/Debian)."
fi

OS_ID=""
OS_CODENAME=""
if [ -f /etc/os-release ]; then
  . /etc/os-release
  OS_ID="${ID:-}"
  OS_CODENAME="${VERSION_CODENAME:-${UBUNTU_CODENAME:-}}"
fi

confirm() {
  local prompt="$1"
  local reply
  read -r -p "$prompt [y/N]: " reply
  case "$reply" in
    y|Y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

prompt() {
  local prompt_text="$1"
  local default_value="$2"
  local reply
  if [ -n "$default_value" ]; then
    read -r -p "$prompt_text [$default_value]: " reply
    if [ -z "$reply" ]; then
      printf "%s" "$default_value"
      return
    fi
  else
    read -r -p "$prompt_text: " reply
  fi
  printf "%s" "$reply"
}

get_env_value() {
  local file="$1"
  local key="$2"
  if [ ! -f "$file" ]; then
    return 0
  fi
  awk -F= -v k="$key" '$1 == k { $1=""; sub(/^=/, ""); print; exit }' "$file"
}

backup_file() {
  local file="$1"
  if [ -f "$file" ]; then
    local ts
    ts="$(date +%s)"
    cp "$file" "${file}.bak.${ts}"
    log "Backed up $file to ${file}.bak.${ts}"
  fi
}

apply_kernel_network_tuning() {
  log "Applying kernel network tuning for high concurrent connections..."
  $SUDO tee /etc/sysctl.d/99-serenada-scale.conf >/dev/null <<'SYSCTL'
net.ipv4.ip_local_port_range = 1024 65535
net.netfilter.nf_conntrack_max = 262144
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 8192
net.core.netdev_max_backlog = 16384
net.ipv4.tcp_fin_timeout = 15
SYSCTL

  $SUDO modprobe nf_conntrack >/dev/null 2>&1 || true
  if ! $SUDO sysctl --system >/dev/null; then
    warn "Failed to apply one or more sysctl values. Review /etc/sysctl.d/99-serenada-scale.conf."
  fi
}

if [ "$(id -u)" -ne 0 ]; then
  if command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
  else
    die "This script needs sudo or root to install packages."
  fi
else
  SUDO=""
fi

CURRENT_USER="$(id -un)"
CURRENT_GROUP="$(id -gn)"

if [ "$OS_ID" = "ubuntu" ]; then
  if ! grep -rE "^[[:space:]]*(deb .*universe|Components:.*universe)" /etc/apt/sources.list /etc/apt/sources.list.d/ 2>/dev/null | grep -q .; then
    warn "Ubuntu universe repository is not enabled. Some packages may be unavailable."
    if confirm "Enable the universe repository now?"; then
      if ! command -v add-apt-repository >/dev/null 2>&1; then
        log "Installing software-properties-common..."
        $SUDO apt-get update -y
        $SUDO apt-get install -y software-properties-common
      fi
      $SUDO add-apt-repository -y universe
      $SUDO apt-get update -y
    fi
  fi
fi

missing_packages=()
if ! command -v curl >/dev/null 2>&1; then
  missing_packages+=("curl")
fi
if ! command -v git >/dev/null 2>&1; then
  missing_packages+=("git")
fi
if ! command -v openssl >/dev/null 2>&1; then
  missing_packages+=("openssl")
fi
if ! command -v envsubst >/dev/null 2>&1; then
  missing_packages+=("gettext-base")
fi
if ! command -v docker >/dev/null 2>&1; then
  missing_packages+=("docker.io")
fi

if [ "${#missing_packages[@]}" -gt 0 ]; then
  log "Installing dependencies: ${missing_packages[*]}"
  $SUDO apt-get update -y
  $SUDO apt-get install -y "${missing_packages[@]}"
fi

if command -v systemctl >/dev/null 2>&1; then
  $SUDO systemctl enable --now docker >/dev/null 2>&1 || true
fi

if [ "$(id -u)" -ne 0 ]; then
  DOCKER="$SUDO docker"
else
  DOCKER="docker"
fi

setup_docker_repo() {
  if [ "$OS_ID" != "ubuntu" ]; then
    die "Docker official apt repository setup is only supported for Ubuntu."
  fi
  if [ -z "$OS_CODENAME" ]; then
    die "Unable to detect Ubuntu codename for Docker repository setup."
  fi
  $SUDO apt-get update -y
  $SUDO apt-get install -y ca-certificates gnupg
  $SUDO install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | $SUDO gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
  $SUDO chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${OS_CODENAME} stable" | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null
  $SUDO apt-get update -y
}

if ! $DOCKER compose version >/dev/null 2>&1; then
if ! $DOCKER compose version >/dev/null 2>&1; then
  log "Installing Docker Compose..."
  $SUDO apt-get update -y
  if apt-cache show docker-compose-plugin >/dev/null 2>&1; then
    $SUDO apt-get install -y docker-compose-plugin
  elif apt-cache show docker-compose-v2 >/dev/null 2>&1; then
    $SUDO apt-get install -y docker-compose-v2
  else
    warn "docker-compose-plugin is not available in current apt sources; adding Docker's official apt repository."
    setup_docker_repo
    # Check for conflicting docker.io package
    if dpkg -l | grep -q "ii  docker.io"; then
      warn "Removing conflicting docker.io package to install docker-ce..."
      $SUDO apt-get remove -y docker.io docker-doc docker-compose podman-docker containerd runc || true
    fi
    $SUDO apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  fi
fi
fi

if $DOCKER compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE="$DOCKER compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DOCKER_COMPOSE="${SUDO:+$SUDO }docker-compose"
else
  die "Docker Compose is not available after installation."
fi

REPO_URL="$(prompt "Repository URL" "$DEFAULT_REPO_URL")"
if ! confirm "Use repository: $REPO_URL?"; then
  die "Aborted."
fi

INSTALL_DIR="$(prompt "Install directory" "$DEFAULT_INSTALL_DIR")"

if [ -d "$INSTALL_DIR/.git" ]; then
  log "Existing repository found in $INSTALL_DIR."
  if confirm "Pull latest changes?"; then
    git -C "$INSTALL_DIR" fetch --all
    git -C "$INSTALL_DIR" pull --ff-only
  fi
else
  if [ -d "$INSTALL_DIR" ] && [ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null)" ]; then
    die "Install directory is not empty: $INSTALL_DIR"
  fi
  $SUDO mkdir -p "$INSTALL_DIR"
  $SUDO chown "$CURRENT_USER":"$CURRENT_GROUP" "$INSTALL_DIR"
  log "Cloning repository..."
  git clone "$REPO_URL" "$INSTALL_DIR"
fi

cd "$INSTALL_DIR"

DOMAIN=""
while [ -z "$DOMAIN" ]; do
  DOMAIN="$(prompt "Domain name (e.g. serenada.app)" "")"
  if [ -z "$DOMAIN" ]; then
    warn "Domain name is required."
  fi
done

DETECTED_IPV4="$(curl -4 -s --max-time 4 https://api.ipify.org || true)"
if [ -z "$DETECTED_IPV4" ]; then
  DETECTED_IPV4="$(curl -4 -s --max-time 4 https://ifconfig.co/ip || true)"
fi

IPV4=""
while [ -z "$IPV4" ]; do
  IPV4="$(prompt "Public IPv4" "$DETECTED_IPV4")"
  if [ -z "$IPV4" ]; then
    warn "IPv4 address is required."
  fi
done

DETECTED_IPV6="$(curl -6 -s --max-time 4 https://api64.ipify.org || true)"
if [ -z "$DETECTED_IPV6" ]; then
  DETECTED_IPV6="$(curl -6 -s --max-time 4 https://ifconfig.co/ip || true)"
fi

IPV6="$(prompt "Public IPv6 (leave blank if none)" "$DETECTED_IPV6")"

ENV_FILE="$INSTALL_DIR/.env"
ENV_PROD_FILE="$INSTALL_DIR/.env.production"

reuse_secrets=false
if [ -f "$ENV_FILE" ] || [ -f "$ENV_PROD_FILE" ]; then
  if confirm "Reuse existing secrets if available?"; then
    reuse_secrets=true
  fi
fi

SECRETS_SOURCE=""
if $reuse_secrets; then
  if [ -f "$ENV_FILE" ]; then
    SECRETS_SOURCE="$ENV_FILE"
  elif [ -f "$ENV_PROD_FILE" ]; then
    SECRETS_SOURCE="$ENV_PROD_FILE"
  fi
fi

TURN_SECRET=""
TURN_TOKEN_SECRET=""
ROOM_ID_SECRET=""

if [ -n "$SECRETS_SOURCE" ]; then
  TURN_SECRET="$(get_env_value "$SECRETS_SOURCE" "TURN_SECRET")"
  TURN_TOKEN_SECRET="$(get_env_value "$SECRETS_SOURCE" "TURN_TOKEN_SECRET")"
  ROOM_ID_SECRET="$(get_env_value "$SECRETS_SOURCE" "ROOM_ID_SECRET")"
fi

if [ -z "$TURN_SECRET" ]; then
  TURN_SECRET="$(openssl rand -hex 32)"
fi
if [ -z "$TURN_TOKEN_SECRET" ]; then
  TURN_TOKEN_SECRET="$(openssl rand -hex 32)"
fi
if [ -z "$ROOM_ID_SECRET" ]; then
  ROOM_ID_SECRET="$(openssl rand -hex 32)"
fi

ROOM_ID_ENV="prod"
STUN_HOST="$DOMAIN"
TURN_HOST="$DOMAIN"
ALLOWED_ORIGINS="https://${DOMAIN}"
TRUST_PROXY="1"
VPS_HOST="root@${IPV4}"
REMOTE_DIR="$INSTALL_DIR"

backup_file "$ENV_FILE"
backup_file "$ENV_PROD_FILE"

cat > "$ENV_FILE" <<EOF
STUN_HOST=${STUN_HOST}
TURN_HOST=${TURN_HOST}
TURN_SECRET=${TURN_SECRET}
TURN_TOKEN_SECRET=${TURN_TOKEN_SECRET}
ROOM_ID_SECRET=${ROOM_ID_SECRET}
ROOM_ID_ENV=${ROOM_ID_ENV}
ALLOWED_ORIGINS=${ALLOWED_ORIGINS}
TRUST_PROXY=${TRUST_PROXY}
VPS_HOST=${VPS_HOST}
DOMAIN=${DOMAIN}
REMOTE_DIR=${REMOTE_DIR}
IPV4=${IPV4}
IPV6=${IPV6}
EOF

cp "$ENV_FILE" "$ENV_PROD_FILE"

export DOMAIN IPV4 IPV6 REMOTE_DIR

if [ -n "$IPV6" ]; then
  export IPV6_Run_HTTP="listen [::]:80;"
  export IPV6_Run_HTTPS="listen [::]:443 ssl http2;"
  export IPV6_Run_RELAY="relay-ip=${IPV6}"
  export IPV6_Run_LISTENING="listening-ip=${IPV6}"
else
  export IPV6_Run_HTTP=""
  export IPV6_Run_HTTPS=""
  export IPV6_Run_RELAY=""
  export IPV6_Run_LISTENING=""
fi

envsubst '$DOMAIN $IPV4 $IPV6 $REMOTE_DIR $IPV6_Run_HTTP $IPV6_Run_HTTPS' < nginx/nginx.prod.conf.template > nginx/nginx.prod.conf
envsubst '$DOMAIN $IPV4 $IPV6 $REMOTE_DIR $IPV6_Run_RELAY $IPV6_Run_LISTENING' < coturn/turnserver.prod.conf.template > coturn/turnserver.prod.conf

if [ -f nginx/nginx.legacy.conf.template ]; then
  mkdir -p nginx/conf.d
  envsubst '$DOMAIN' < nginx/nginx.legacy.conf.template > nginx/conf.d/legacy.extra
else
  rm -f nginx/conf.d/legacy.extra
fi

if [ ! -d "/etc/letsencrypt/live/${DOMAIN}" ]; then
  warn "Let's Encrypt certificates not found for ${DOMAIN}."
  if confirm "Obtain certificates now with certbot?"; then
    if ! command -v certbot >/dev/null 2>&1; then
      log "Installing certbot..."
      $SUDO apt-get update -y
      $SUDO apt-get install -y certbot
    fi
    $SUDO certbot certonly --standalone -d "$DOMAIN"
  else
    warn "Skipping certificate setup. Nginx will fail without valid certs."
  fi
fi

BUILD_FRONTEND=false
if [ -f "client/dist/index.html" ]; then
  if confirm "Rebuild frontend assets now?"; then
    BUILD_FRONTEND=true
  fi
else
  warn "client/dist not found."
  if confirm "Build frontend assets now using Docker?"; then
    BUILD_FRONTEND=true
  else
    die "Frontend assets are required. Aborting."
  fi
fi

if $BUILD_FRONTEND; then
  NODE_IMAGE="node:20-bookworm-slim"
  DOCKER_RUN_USER=""
  if [ "$(id -u)" -ne 0 ]; then
    DOCKER_RUN_USER="-u $(id -u):$(id -g)"
  fi
  log "Building frontend assets with ${NODE_IMAGE}..."
  $DOCKER run --rm ${DOCKER_RUN_USER} -v "$INSTALL_DIR/client:/app" -w /app "${NODE_IMAGE}" npm ci
  $DOCKER run --rm ${DOCKER_RUN_USER} -v "$INSTALL_DIR/client:/app" -w /app "${NODE_IMAGE}" npm run build
fi

apply_kernel_network_tuning

log "Starting services with Docker Compose..."
$DOCKER_COMPOSE -f docker-compose.yml -f docker-compose.prod.yml up -d --build

$DOCKER_COMPOSE -f docker-compose.yml -f docker-compose.prod.yml ps

log "Setup complete."
log "App URL: https://${DOMAIN}"
log "Ensure DNS points ${DOMAIN} to ${IPV4}${IPV6:+ and ${IPV6}}."
