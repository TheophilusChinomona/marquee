import { invoke, isTauri } from '@tauri-apps/api/core';
import type { DesktopApi } from './types';
export const isNative = () => isTauri();
export const nativeApi: DesktopApi = { bootstrap: () => invoke('app_bootstrap') };
