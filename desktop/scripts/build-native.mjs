import { execSync } from 'node:child_process';
import process from 'node:process';

execSync('npx tsc && npx vite build', {
  stdio: 'inherit',
  env: { ...process.env, MARQUEE_NATIVE_BUILD: '1' },
});
