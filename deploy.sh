#!/bin/bash
set -e

warn() {
    echo "⚠️  $1"
}

ANDROID_RELEASE_APK="client-android/app/build/outputs/apk/release/serenada.apk"
DEPLOY_TOOLS_DIR="client/dist/tools"
LOCAL_FCM_SERVICE_ACCOUNT_FILE="secrets/service-account.json"
REMOTE_FCM_SERVICE_ACCOUNT_FILE=""

# Load configuration from .env.production
if [ -f .env.production ]; then
    export $(grep -v '^#' .env.production | xargs)
else
    echo "❌ .env.production not found. Please create it from .env.example."
    exit 1
fi

# Validate required variables
REQUIRED_VARS=("VPS_HOST" "DOMAIN" "REMOTE_DIR" "IPV4")
for var in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        echo "❌ Error: $var is not set in .env.production"
        exit 1
    fi
done

echo "🚀 Starting production deployment for $DOMAIN..."

# 1. Build the frontend
echo "📦 Building frontend..."
(cd client && npm run build)

# 1.5 Include Android release APK in /tools (best effort)
echo "📱 Preparing Android release APK for /tools..."
if [ ! -f "$ANDROID_RELEASE_APK" ]; then
    echo "ℹ️  Release APK not found at $ANDROID_RELEASE_APK, attempting build..."
    if (cd client-android && ./gradlew :app:assembleRelease); then
        echo "✅ Android release build completed."
    else
        warn "Android release build failed. Continuing deployment without updating /tools/serenada.apk."
    fi
fi

if [ -f "$ANDROID_RELEASE_APK" ]; then
    mkdir -p "$DEPLOY_TOOLS_DIR"
    if cp "$ANDROID_RELEASE_APK" "$DEPLOY_TOOLS_DIR/serenada.apk"; then
        echo "✅ Included $DEPLOY_TOOLS_DIR/serenada.apk"
    else
        warn "Failed to copy Android APK into $DEPLOY_TOOLS_DIR. Continuing deployment."
    fi
else
    warn "Android release APK is unavailable. Skipping /tools/serenada.apk upload."
fi

# 2. Generate configuration files from templates
echo "⚙️ Generating configuration files..."
export DOMAIN IPV4 IPV6 REMOTE_DIR

# Prepare IPv6 variables for templates
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

# Optional: Legacy redirects
if [ -f nginx/nginx.legacy.conf.template ]; then
    mkdir -p nginx/conf.d
    envsubst '$DOMAIN' < nginx/nginx.legacy.conf.template > nginx/conf.d/legacy.extra
else
    # Cleanup if template doesn't exist
    rm -f nginx/conf.d/legacy.extra
fi

# Optional: Firebase service account for Android push
SYNC_FCM_SERVICE_ACCOUNT=false
if [ -f "$LOCAL_FCM_SERVICE_ACCOUNT_FILE" ]; then
    REMOTE_FCM_SERVICE_ACCOUNT_FILE="/app/secrets/service-account.json"
    SYNC_FCM_SERVICE_ACCOUNT=true
    echo "🔐 Found Firebase service account at $LOCAL_FCM_SERVICE_ACCOUNT_FILE"
else
    echo "ℹ️  No Firebase service account found at $LOCAL_FCM_SERVICE_ACCOUNT_FILE; Android push will rely on .env settings."
fi

# 3. Sync files to VPS
echo "📤 Syncing files to VPS..."
rsync -avzR \
    --exclude 'server/server' \
    --exclude 'server/server_test' \
    --exclude '*.template' \
    --exclude 'server/data' \
    docker-compose.yml \
    docker-compose.prod.yml \
    .env.production \
    server/ \
    client/dist/ \
    nginx/ \
    coturn/ \
    "$VPS_HOST:$REMOTE_DIR/"

if [ "$SYNC_FCM_SERVICE_ACCOUNT" = true ]; then
    rsync -avzR "$LOCAL_FCM_SERVICE_ACCOUNT_FILE" "$VPS_HOST:$REMOTE_DIR/"
fi

# 4. Apply kernel network tuning on VPS
echo "🛠️ Applying kernel network tuning on VPS..."
ssh "$VPS_HOST" <<'EOF'
set -e

if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
else
  SUDO=""
fi

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
  echo "⚠️  Failed to apply one or more sysctl values. Check /etc/sysctl.d/99-serenada-scale.conf" >&2
fi
EOF

# 5. Copy production env file and restart services
echo "🔄 Restarting production services..."
ssh "$VPS_HOST" "cd $REMOTE_DIR && \
    cp .env.production .env && \
    if [ -f $LOCAL_FCM_SERVICE_ACCOUNT_FILE ]; then \
      chmod 600 $LOCAL_FCM_SERVICE_ACCOUNT_FILE; \
      awk '!/^FCM_SERVICE_ACCOUNT_FILE=|^FCM_SERVICE_ACCOUNT_JSON=/' .env > .env.tmp && mv .env.tmp .env; \
      echo FCM_SERVICE_ACCOUNT_FILE=$REMOTE_FCM_SERVICE_ACCOUNT_FILE >> .env; \
      echo '✅ Configured FCM_SERVICE_ACCOUNT_FILE in .env'; \
    fi && \
    docker compose -f docker-compose.yml -f docker-compose.prod.yml down && \
    docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build"

# 6. Verify deployment
echo "✅ Verifying deployment..."
sleep 3
ssh "$VPS_HOST" "docker ps"
curl -sI "https://$DOMAIN" | head -n 1

echo ""
echo "🎉 Deployment complete! App is live at https://$DOMAIN"
