import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
// @ts-expect-error type error without @types/node package
import fs from "node:fs";
// @ts-expect-error type error without @types/node package
import path from "node:path";
// @ts-expect-error type error without @types/node package
import process from "node:process";

const host = process.env.TAURI_DEV_HOST;
const isNativeBuild =
  Boolean(process.env.TAURI_ENV_PLATFORM) || process.env.MARQUEE_NATIVE_BUILD === "1";

function stripDemoArtworkInNativeBuild() {
  return {
    name: "strip-demo-artwork-in-native-build",
    closeBundle() {
      if (!isNativeBuild) return;
      const artworkDir = path.resolve(process.cwd(), "dist", "artwork");
      if (fs.existsSync(artworkDir)) {
        fs.rmSync(artworkDir, { recursive: true, force: true });
      }
    },
  };
}

// https://vite.dev/config/
export default defineConfig(() => ({
  plugins: [react(), stripDemoArtworkInNativeBuild()],
  resolve: isNativeBuild
    ? {
        alias: {
          "./PreviewApp": path.resolve(process.cwd(), "src/api/preview-stub.ts"),
        },
      }
    : undefined,
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      ignored: ["**/src-tauri/**"],
    },
  },
}));
