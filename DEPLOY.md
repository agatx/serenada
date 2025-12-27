# Deployment Guide: self-hosted on Hetzner

This guide covers deploying the Connected app on a single linux VPS using Docker Compose. The project is structured to support both local development and production deployment.

## Prerequisites

1.  **Linux VPS**: Ubuntu 20.04+ recommended.
2.  **Domain Name**: Pointed to your VPS IP (e.g., `connected.dowhile.fun`).
3.  **Docker & Docker Compose**: Installed on the VPS.

## Local Development

To run the application locally for development:

```bash
docker-compose up -d --build
```

The app will be accessible at `http://localhost`. It uses `nginx/nginx.dev.conf` and `coturn/turnserver.dev.conf`.

## Production Deployment

### 1. Configuration

#### Environment Variables
The repository includes a [.env.example](file:///Users/alexeygavrilov/Developer/src/connected/.env.example) template. Create your local environment file by copying it:

```bash
cp .env.example .env
```

Note: `.env` and `.env.production` are ignored by git to protect your secrets.

```bash
TURN_HOST=connected.dowhile.fun
# Generate a secure secret: openssl rand -hex 32
TURN_SECRET=your_secure_random_secret
```

#### Nginx & Coturn
- Production Nginx config: [nginx.prod.conf](file:///Users/alexeygavrilov/Developer/src/connected/nginx/nginx.prod.conf)
- Production Coturn config: [turnserver.prod.conf](file:///Users/alexeygavrilov/Developer/src/connected/coturn/turnserver.prod.conf)

### 2. Firewall

Ensure the following ports are open on your VPS firewall (e.g., UFW or Hetzner Cloud Firewall):
-   **80/tcp** (HTTP)
-   **443/tcp** (HTTPS)
-   **3478/udp & tcp** (STUN/TURN Signaling)
-   **49152-65535/udp** (WebRTC Media Range)

### 3. HTTPS (SSL) Setup

1.  Stop Nginx if running: `docker stop connected-nginx`
2.  Install Certbot and generate certificates:
    ```bash
    sudo apt install certbot
    sudo certbot certonly --standalone -d connected.dowhile.fun
    ```
3.  The certificates are mounted into the Nginx container via `docker-compose.prod.yml`.

### 4. Deploying the Stack

A convenience script is provided for deployment. From the project root:

```bash
./deploy.sh
```

This will build the frontend, sync files to the VPS, and restart all services.

**Manual alternative:**
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

## Verification

1.  Navigate to `https://connected.dowhile.fun`.
2.  Verify camera/microphone permissions are requested.
3.  Check logs if issues arise: `docker compose logs -f`.
