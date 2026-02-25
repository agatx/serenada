# Deployment Guide: self-hosted on Hetzner

This guide covers deploying the Serenada app on a single linux VPS using Docker Compose. The project is structured to support both local development and production deployment.

## Prerequisites

1.  **Linux VPS**: Ubuntu 20.04+ recommended.
2.  **Domain Name**: Pointed to your VPS IP (e.g., `serenada.app`).
3.  **Docker & Docker Compose**: Installed on the VPS.

## Local Development

To run the application locally for development:

```bash
docker compose up -d --build
```

The app will be accessible at `http://localhost`. It uses `nginx/nginx.dev.conf` and `coturn/turnserver.dev.conf`.

## Production Deployment

### 1. Configuration

#### Environment Variables
The repository includes an [.env.example](.env.example) template. Create your production environment file:

```bash
cp .env.example .env.production
```

Edit `.env.production` and set the following required variables:
- `VPS_HOST`: SSH connection string (e.g., `root@1.2.3.4`)
- `DOMAIN`: Your app domain (e.g., `serenada.app`)
- `REMOTE_DIR`: Deployment path on VPS (e.g., `/opt/serenada`)
- `IPV4`: VPS Public IPv4 address
- `IPV6`: VPS Public IPv6 address
- `TURN_SECRET`: Secure secret for TURN (generate with `openssl rand -hex 32`)
- `TURN_TOKEN_SECRET` *(optional, recommended)*: Separate secret for TURN tokens (falls back to `TURN_SECRET` if unset)
- `ROOM_ID_SECRET`: Secure secret for Room IDs (generate with `openssl rand -hex 32`)
- `PUSH_SUBSCRIBER_EMAIL` *(optional)*: Contact email for Web Push VAPID (`mailto:...`)
- `FCM_SERVICE_ACCOUNT_FILE` or `FCM_SERVICE_ACCOUNT_JSON` *(optional, required for native Android push receive)*:
  - `FCM_SERVICE_ACCOUNT_FILE`: absolute path on VPS to Firebase service-account JSON
  - `FCM_SERVICE_ACCOUNT_JSON`: inline JSON string (alternative to file path)
- `RATE_LIMIT_BYPASS_IPS` *(optional, test-only)*: Comma-separated exact IPs/CIDRs that bypass HTTP rate limits (e.g. `127.0.0.1,10.0.0.0/8`)
- `ENABLE_INTERNAL_STATS` *(optional, default disabled)*: Set to `1` only for controlled load testing to expose `/api/internal/stats`
- `INTERNAL_STATS_TOKEN` *(required when internal stats are enabled)*: Required as `X-Internal-Token` header on `/api/internal/stats`

> [!WARNING]
> Keep `ENABLE_INTERNAL_STATS` disabled in normal production operation.
> Enable it only for short-lived controlled diagnostics/load tests.

#### Configuration Templates
Serenada uses templates to generate final configuration files during deployment. This ensures that domain names and IP addresses are consistently applied across all services.
- [nginx.prod.conf.template](nginx/nginx.prod.conf.template)
- [turnserver.prod.conf.template](coturn/turnserver.prod.conf.template)

#### Canonical Domain and Mirrors
Production Nginx applies a host-based SEO policy:
- `serenada.app` is treated as the canonical domain (`index, follow`).
- Any other domain is treated as a mirror (`noindex, follow`).
- Canonical hints are sent as:
  - HTML canonical tag in `client/index.html` (`https://serenada.app/`)
  - HTTP `Link: <https://serenada.app<request-path>>; rel="canonical"` response header
- Sensitive call routes are always blocked from indexing on all hosts:
  - `/call/*` responses send `X-Robots-Tag: noindex, nofollow`
- `robots.txt` is host-aware:
  - All hosts include `Disallow: /call/`
  - Canonical host includes `Sitemap: https://serenada.app/sitemap.xml`
  - Mirror hosts omit sitemap entries

### 2. Firewall

Ensure the following ports are open on your VPS firewall (e.g., UFW or Hetzner Cloud Firewall):
-   **80/tcp** (HTTP)
-   **443/tcp** (HTTPS)
-   **3478/udp & tcp** (STUN/TURN Signaling)
-   **5349/tcp** (STUN/TURN over TLS)
-   **49152-65535/udp** (WebRTC Media Range)

### Android Push Build Config (optional)
Native Android push receive uses Firebase Messaging and expects these Gradle properties when building `client-android`:
- `firebaseAppId`
- `firebaseApiKey`
- `firebaseProjectId`
- `firebaseSenderId`

Example:
```bash
cd client-android
./gradlew :app:assembleRelease \
  -PfirebaseAppId=1:1234567890:android:abc123 \
  -PfirebaseApiKey=AIza... \
  -PfirebaseProjectId=your-project-id \
  -PfirebaseSenderId=1234567890
```
If you use the default `local7559` WebRTC provider, keep `client-android/app/libs/libwebrtc-7559_173-arm64.aar.sha256` in sync with the AAR bytes; Gradle verifies this checksum before `preBuild`.

### 3. HTTPS (SSL) Setup

Serenada expects Let's Encrypt certificates to be located at `/etc/letsencrypt/live/${DOMAIN}/`.

1.  Stop Nginx if running: `docker stop serenada-nginx`
2.  Install Certbot and generate certificates:
    ```bash
    sudo apt install certbot
    sudo certbot certonly --standalone -d your-domain.com
    ```
3.  The certificates are mounted into the containers via `docker-compose.prod.yml`.

### 4. Deploying the Stack

A convenience script is provided for deployment. It uses `envsubst` to process templates, builds the frontend, syncs files via `rsync`, and restarts services via SSH.
A high-concurrency kernel tuning profile is also applied on the VPS and persisted at `/etc/sysctl.d/99-serenada-scale.conf`:
- `net.ipv4.ip_local_port_range = 1024 65535`
- `net.netfilter.nf_conntrack_max = 262144`
- `net.core.somaxconn = 65535`
- `net.ipv4.tcp_max_syn_backlog = 8192`
- `net.core.netdev_max_backlog = 16384`
- `net.ipv4.tcp_fin_timeout = 15`

> [!WARNING]
> **Manual Restarts**: If you need to restart services manually on the server, you **MUST** include both compose files.
> Do NOT run `docker compose up -d`.
> Run `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d` instead.
> Omitting the production file will cause the app to revert to development defaults (wrong Nginx config, no TLS/cert mounts, and missing production overrides) and break connectivity.

From the project root on your local machine:
```bash
./deploy.sh
```

`deploy.sh` also publishes the Android APK to `https://<your-domain>/tools/serenada.apk` by copying `client-android/app/build/outputs/apk/release/serenada.apk` into the built web assets (`client/dist/tools/serenada.apk`) before sync. If the release APK is missing, the script will attempt `./gradlew :app:assembleRelease` in `client-android/`. If that build fails, deployment continues and prints a warning instead of failing.

If `secrets/service-account.json` exists in the repo root, `deploy.sh` also syncs it to `${REMOTE_DIR}/secrets/service-account.json` and injects `FCM_SERVICE_ACCOUNT_FILE=${REMOTE_DIR}/secrets/service-account.json` into the deployed `.env` (overriding any existing `FCM_SERVICE_ACCOUNT_FILE` / `FCM_SERVICE_ACCOUNT_JSON` entries for that deployment).

### 5. Android and iOS Deep Link Association Files

Android deep links require `/.well-known/assetlinks.json` to be served from your domain. The file lives at:

```
client/public/.well-known/assetlinks.json
```

Update it with your **release** signing certificate SHA-256 fingerprint before deployment.

iOS universal links require `/.well-known/apple-app-site-association` to be served from your domain (no `.json` suffix). The file lives at:

```
client/public/.well-known/apple-app-site-association
```

Update the `appID` value (`<TEAM_ID>.app.serenada.ios`) to match your Apple Developer Team ID and iOS bundle identifier before deployment.

### 6. Advanced: Legacy Redirects
If you need to support redirects from old domains (e.g. `connected.dowhile.fun`), you can create a template at `nginx/nginx.legacy.conf.template`. The deployment script will automatically generate an `extra` configuration for Nginx if this file exists.

## Verification

1.  Navigate to `https://your-domain.com`.
2.  Verify camera/microphone permissions are requested.
3.  Verify room ID endpoint works (used by clients for call start and Android Settings host validation):
    ```bash
    curl -sS https://your-domain.com/api/room-id
    ```
    Expected response shape:
    ```json
    {"roomId":"..."}
    ```
4.  Check logs if issues arise: `docker compose logs -f`.
    Access logs redact `/call/<id>` in both the request path and the referrer field.
5.  Verify canonical/mirror SEO headers:
    ```bash
    # Canonical domain should be indexable
    curl -sI https://serenada.app | rg -i "x-robots-tag|link"

    # Mirror domain should be noindex
    curl -sI https://your-mirror-domain.example | rg -i "x-robots-tag|link"

    # Call routes must never be indexable
    curl -sI https://serenada.app/call/test-room | rg -i "x-robots-tag"

    # robots.txt must block call routes
    curl -s https://serenada.app/robots.txt | rg -i "disallow: /call/"
    ```
