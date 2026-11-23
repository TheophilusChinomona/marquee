#!/usr/bin/env bash
# Installs the resolver as a systemd *user* service that starts on boot
# (linger), so the TV has something to talk to even when no one is logged in.
#
#   ./deploy/install.sh                      # deploy from this checkout
#   REMOTE=you@homebox ./deploy/install.sh   # or push to a box and install there
#
# Idempotent: re-running updates the code and restarts the service.

set -euo pipefail

REMOTE="${REMOTE:-}"
TARGET_DIR="${TARGET_DIR:-services/marquee-resolver}"
UNIT="marquee-resolver.service"
PORT="${RESOLVER_PORT:-8099}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run() {
  if [[ -n "$REMOTE" ]]; then ssh "$REMOTE" "$@"; else bash -lc "$*"; fi
}

echo "==> checking prerequisites"
if [[ -n "$REMOTE" ]]; then
  ssh "$REMOTE" 'command -v node >/dev/null || { echo "node is not installed"; exit 1; }'
  ssh "$REMOTE" 'command -v chromium >/dev/null || echo "warning: chromium not found; set MARQUEE_CHROME in the unit"'
else
  command -v node >/dev/null || { echo "node is not installed"; exit 1; }
fi

echo "==> copying $HERE -> ${REMOTE:+$REMOTE:}~/$TARGET_DIR"
if [[ -n "$REMOTE" ]]; then
  ssh "$REMOTE" "mkdir -p ~/$TARGET_DIR"
  tar czf - -C "$HERE" --exclude node_modules --exclude '*.log' --exclude data --exclude '.env*' --exclude '*.sqlite*' . \
    | ssh "$REMOTE" "tar xzf - -C ~/$TARGET_DIR"
else
  mkdir -p "$HOME/$TARGET_DIR"
  tar czf - -C "$HERE" --exclude node_modules --exclude '*.log' --exclude data --exclude '.env*' --exclude '*.sqlite*' . \
    | tar xzf - -C "$HOME/$TARGET_DIR"
fi

echo "==> installing dependencies"
if run 'command -v npm >/dev/null'; then
  run "cd ~/$TARGET_DIR && npm ci --omit=dev || npm install --omit=dev"
else
  # Arch ships nodejs without npm. playwright-core is pure JavaScript, so a copy
  # from a machine that has it is enough; only the browser binary is platform
  # specific and that comes from the distro.
  echo "    npm not found; expecting node_modules to be vendored already"
fi
run "cd ~/$TARGET_DIR && node -e 'require(\"playwright-core\")' && echo '    playwright-core loads'"

echo "==> installing the systemd user unit"
run "mkdir -p ~/.config/systemd/user ~/.config/marquee ~/.local/share/marquee; chmod 700 ~/.config/marquee ~/.local/share/marquee"
if [[ -n "$REMOTE" ]]; then
  ssh "$REMOTE" "cat > ~/.config/systemd/user/$UNIT" < "$HERE/deploy/$UNIT"
else
  cp "$HERE/deploy/$UNIT" "$HOME/.config/systemd/user/$UNIT"
fi

run "export XDG_RUNTIME_DIR=/run/user/\$(id -u); systemctl --user daemon-reload; systemctl --user enable --now $UNIT; sleep 2; systemctl --user is-active $UNIT"
run "loginctl enable-linger \$(id -un) >/dev/null 2>&1 || sudo loginctl enable-linger \$(id -un)"

cat <<EOF

==> done. verify:

    curl -s http://localhost:$PORT/health
    curl -s "http://localhost:$PORT/resolve?id=/tnkxGv&season=1&episode=1"

From other machines on the LAN the port has to be open. On a box running ufw:

    sudo ufw allow from <your-subnet>/24 to any port $PORT proto tcp

Then point Marquee at it: Profiles -> Server: <host>:<port>

Note: run this on the same home network as the TV. Links minted elsewhere
return 403 to the TV (see README: "Where to run it").
EOF
