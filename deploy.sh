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

# 6. Ensure SSL auto-renewal is configured for webroot (zero-downtime)
echo "🔒 Checking SSL auto-renewal cron job..."
COMPOSE_PROJECT=$(basename "$REMOTE_DIR")
ssh "$VPS_HOST" <<RENEWAL_EOF
set -e

if [ "\$(id -u)" -ne 0 ]; then
  SUDO="sudo"
else
  SUDO=""
fi

CERT_CONF="/etc/letsencrypt/renewal/${DOMAIN}.conf"
if ! \$SUDO test -f "\$CERT_CONF"; then
    echo "⚠️  No certbot renewal config at \$CERT_CONF — bootstrap the cert first (see DEPLOY.md)"
    exit 0
fi

WEBROOT_VOL="${COMPOSE_PROJECT}_certbot-webroot"
WEBROOT_PATH=\$(docker volume inspect "\$WEBROOT_VOL" -f '{{.Mountpoint}}' 2>/dev/null || true)
if [ -z "\$WEBROOT_PATH" ]; then
    echo "⚠️  Docker volume \$WEBROOT_VOL not found — is the stack running? Skipping renewal config."
    exit 0
fi

# Install (or refresh) the post-renewal hook. Runs after every successful
# 'certbot renew' to reload nginx (graceful) and signal coturn
# (SIGUSR2 = re-read TLS certs) so both pick up the new cert without
# dropping in-flight connections. coturn must be signaled explicitly —
# it loads certs once at startup and won't notice file changes otherwise.
\$SUDO mkdir -p /etc/letsencrypt/renewal-hooks/deploy
\$SUDO tee /etc/letsencrypt/renewal-hooks/deploy/serenada-reload.sh >/dev/null <<'HOOK_EOF'
#!/bin/bash
set -e
docker exec serenada-nginx nginx -s reload || true
docker kill -s USR2 serenada-coturn || true
HOOK_EOF
\$SUDO chmod +x /etc/letsencrypt/renewal-hooks/deploy/serenada-reload.sh

# Force renewal to use webroot for zero-downtime. Covers fresh deploys AND any
# prior config using authenticator=standalone or authenticator=nginx — both of
# which fail silently when the live nginx runs in Docker on ports 80/443.
# Re-run certbot (instead of sed-editing the conf) so certbot writes a valid
# renewal config itself. Reload hooks come from renewal-hooks/deploy/ above,
# not --deploy-hook, so all renewals fire the same hook script.
#
# Use --force-renewal (not --keep-until-expiring): the latter can early-exit
# when the cert isn't due, leaving the renewal config still pointing at
# standalone/nginx. Force-renewal guarantees certbot rewrites the lineage
# config with authenticator=webroot. This block only runs once per VPS (the
# AUTH+path check skips it on subsequent deploys), so the extra issuance is a
# one-time cost during migration.
#
# Checking authenticator alone is NOT enough: a config can say webroot but
# carry a stale/malformed path (e.g. a hand-edited [webroot] section pointing
# at a path that only exists inside the nginx container). That exact state
# expired serenada.app in July 2026 — certbot failed twice daily with
# "Missing command line flag or config entry" while the authenticator check
# reported everything as already migrated. So also require webroot_path to
# match the live docker volume mountpoint.
AUTH=\$(\$SUDO awk -F' = ' '/^authenticator/{print \$2; exit}' "\$CERT_CONF")
CONF_WEBROOT=\$(\$SUDO awk -F' = ' '/^webroot_path/{print \$2; exit}' "\$CERT_CONF" | tr -d ' ' | sed 's/,\$//')
if [ "\$AUTH" != "webroot" ] || [ "\$CONF_WEBROOT" != "\$WEBROOT_PATH" ]; then
    echo "🔧 Cert renewal config invalid (authenticator=\$AUTH, webroot_path=\${CONF_WEBROOT:-unset}); reconfiguring to webroot..."
    DOMAINS=\$(\$SUDO certbot certificates --cert-name "${DOMAIN}" 2>/dev/null \\
        | awk -F': ' '/Domains:/{print \$2; exit}')
    [ -z "\$DOMAINS" ] && DOMAINS="${DOMAIN}"
    DOMAIN_ARGS=""
    for d in \$DOMAINS; do DOMAIN_ARGS="\$DOMAIN_ARGS -d \$d"; done
    \$SUDO certbot certonly --non-interactive \\
        --webroot -w "\$WEBROOT_PATH" \\
        --cert-name "${DOMAIN}" \\
        \$DOMAIN_ARGS \\
        --force-renewal
    echo "✅ Cert renewal switched to webroot"
fi

# Drop any legacy renew_hook from the cert config (now handled by
# renewal-hooks/deploy/) to avoid double-running on each renewal.
\$SUDO sed -i '/^renew_hook = /d' "\$CERT_CONF"

# Install weekly cron (Sun 3am), replacing any old certbot entry. The renewal
# config stores webroot_path; reload hooks live in renewal-hooks/deploy/.
# Plain 'certbot renew' is enough — no flags belong in the cron line.
# We use a dedicated cron entry (not the system certbot.timer) so the schedule
# and command are explicit and visible to operators alongside the rest of the
# deploy. 'certbot renew' is idempotent, so co-existence with the system timer
# is harmless if both are enabled.
if command -v crontab >/dev/null 2>&1; then
    CRON_CMD="0 3 * * 0 certbot renew --quiet"
    # '|| true' matters: with no pre-existing crontab (or one with no other
    # entries), grep exits 1 and set -e would kill the subshell BEFORE the
    # echo — installing an empty crontab while the deploy still reports
    # success. That silently left serenada.app without the cron entry.
    ( \$SUDO crontab -l 2>/dev/null | grep -v 'certbot renew' || true; echo "\$CRON_CMD" ) | \$SUDO crontab -
    \$SUDO crontab -l | grep -qF "\$CRON_CMD"
    echo "✅ SSL auto-renewal cron job is configured (weekly, zero-downtime)"
else
    echo "⚠️  crontab not found — install cron (apt install cron) to enable SSL auto-renewal"
fi

# Prove the renewal path actually works end-to-end (staging ACME server, real
# HTTP-01 challenge through the live nginx). A misconfigured lineage otherwise
# fails silently twice a day in certbot.timer until the cert expires — this
# turns that into a visible deploy failure.
echo "🧪 Verifying cert renewal end-to-end (certbot renew --dry-run)..."
if \$SUDO certbot renew --cert-name "${DOMAIN}" --dry-run --quiet; then
    echo "✅ Renewal dry-run succeeded for ${DOMAIN}"
else
    echo "❌ Renewal dry-run FAILED for ${DOMAIN} — auto-renewal is broken."
    echo "   Debug on the VPS with: certbot renew --cert-name ${DOMAIN} --dry-run"
    exit 1
fi
RENEWAL_EOF

# 7. Verify deployment
echo "✅ Verifying deployment..."
sleep 3
ssh "$VPS_HOST" "docker ps"
curl -sI "https://$DOMAIN" | head -n 1

echo ""
echo "🎉 Deployment complete! App is live at https://$DOMAIN"
