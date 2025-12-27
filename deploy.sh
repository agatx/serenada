#!/bin/bash
set -e

# Configuration
VPS_HOST="root@46.224.118.74"
REMOTE_DIR="/opt/connected"

echo "🚀 Starting production deployment..."

# 1. Build the frontend
echo "📦 Building frontend..."
(cd client && npm run build)

# 2. Sync files to VPS
echo "📤 Syncing files to VPS..."
rsync -avz --exclude '.git' \
           --exclude 'client/node_modules' \
           --exclude 'client/src' \
           --exclude 'client/public' \
           ./ "$VPS_HOST:$REMOTE_DIR/"

# 3. Copy production env file and restart services
echo "🔄 Restarting production services..."
ssh "$VPS_HOST" "cd $REMOTE_DIR && \
    cp .env.production .env && \
    docker compose -f docker-compose.yml -f docker-compose.prod.yml down && \
    docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build"

# 4. Verify deployment
echo "✅ Verifying deployment..."
sleep 3
ssh "$VPS_HOST" "docker ps"
curl -sI https://connected.dowhile.fun | head -n 1

echo ""
echo "🎉 Deployment complete! App is live at https://connected.dowhile.fun"
