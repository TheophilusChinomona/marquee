# syntax=docker/dockerfile:1

# The smallest thing that can run a headless browser: Debian's Chromium plus the
# fonts and libraries its headless mode needs. Shipping Playwright's own browser
# download instead would add ~300 MB for the same picture.
FROM node:22-bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    NODE_ENV=production \
    MARQUEE_CHROME=/usr/bin/chromium \
    RESOLVER_HOST=0.0.0.0 \
    RESOLVER_PORT=8099

RUN apt-get update && apt-get install -y --no-install-recommends \
        chromium \
        ca-certificates \
        fonts-liberation \
        fonts-noto-color-emoji \
        libasound2 \
        libatk-bridge2.0-0 \
        libatk1.0-0 \
        libcups2 \
        libdrm2 \
        libgbm1 \
        libglib2.0-0 \
        libpango-1.0-0 \
        libpangocairo-1.0-0 \
        libxcomposite1 \
        libxdamage1 \
        libxfixes3 \
        libxkbcommon0 \
        libxrandr2 \
        tini \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Dependencies first: this layer is cached until package-lock.json changes.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY . .

# Non-root, and give Chromium somewhere to keep its profile.
RUN useradd --create-home --uid 10001 resolver \
    && mkdir -p /data \
    && chown -R resolver:resolver /app /data
USER resolver
ENV XDG_CACHE_HOME=/home/resolver/.cache \
    HOME=/home/resolver

EXPOSE 8099

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:'+(process.env.RESOLVER_PORT||8099)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "server.js"]
