import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import type {
  AppSettings,
  Bootstrap,
  CatalogProgressEvent,
  CatalogQueryArgs,
  Category,
  ConnectArgs,
  DesktopApi,
  EpgQueryResponse,
  HistoryItem,
  HomeSectionsResponse,
  MediaDetails,
  MediaItem,
  MediaKind,
  MediaRef,
  OnlineStatus,
  Page,
  PlayerControlAction,
  PlayerSnapshot,
  PlayerStateEvent,
  Profile,
  SettingsUpdateArgs,
} from './types';

export const isNative = () => isTauri();

export const nativeApi: DesktopApi = {
  onlineConnect: (args) => invoke<Bootstrap>('online_connect', { args }),
  onlineDisconnect: () => invoke<void>('online_disconnect'),
  onlineCheck: () => invoke<OnlineStatus>('online_check'),
  onlineImportDevelopment: (args) => invoke<Bootstrap>('online_import_development', { args }),
  onlineSearch: (args) => invoke<MediaItem[]>('online_search', { args }),
  bootstrap: () => invoke<Bootstrap>('app_bootstrap'),
  providerConnect: (args: ConnectArgs) => invoke<Bootstrap>('provider_connect', { args }),
  providerDisconnect: () => invoke<void>('provider_disconnect'),
  profilesList: () => invoke<Profile[]>('profiles_list'),
  profileCreate: (args: { name: string; avatar: string }) =>
    invoke<Profile>('profile_create', { args }),
  profileUpdate: (args: { id: string; name: string; avatar: string }) =>
    invoke<Profile>('profile_update', { args }),
  profileRemove: (args: { id: string }) => invoke<string>('profile_remove', { args }),
  profileActivate: (args: { id: string }) => invoke<void>('profile_activate', { args }),
  favoritesList: (args: { offset?: number; limit?: number }) =>
    invoke<Page<MediaItem>>('favorites_list', {
      args: { offset: args.offset ?? 0, limit: args.limit ?? 60 },
    }),
  favoriteSet: (args: { ref: MediaRef; saved: boolean }) =>
    invoke<void>('favorite_set', { args }),
  historyList: () => invoke<HistoryItem[]>('history_list'),
  settingsUpdate: (args: SettingsUpdateArgs) => invoke<AppSettings>('settings_update', { args }),
  catalogQuery: (args: CatalogQueryArgs) => invoke<Page<MediaItem>>('catalog_query', { args }),
  catalogCategories: (args: { kind: MediaKind }) =>
    invoke<Category[]>('catalog_categories', { args }),
  catalogRefresh: () => invoke<string>('catalog_refresh'),
  catalogRefreshCancel: () => invoke<void>('catalog_refresh_cancel'),
  mediaDetails: (args: { ref: MediaRef }) => invoke<MediaDetails>('media_details', { args }),
  epgQuery: (args: { channelIds: string[]; fromMs: number; toMs: number }) =>
    invoke<EpgQueryResponse>('epg_query', { args }),
  homeSections: () => invoke<HomeSectionsResponse>('home_sections'),
  playerStart: (args: { ref: MediaRef; episodeId?: string; resume: boolean }) =>
    invoke<PlayerSnapshot>('player_start', { args }),
  playerControl: (args: PlayerControlAction) =>
    invoke<PlayerSnapshot>('player_control', { command: args }),
  playerSnapshot: () => invoke<PlayerSnapshot>('player_snapshot'),
  onCatalogProgress: async (handler: (event: CatalogProgressEvent) => void) => {
    const unlisten = await listen<CatalogProgressEvent>('catalog-progress', (ev) =>
      handler(ev.payload),
    );
    return unlisten;
  },
  onPlayerState: async (handler: (event: PlayerStateEvent) => void) => {
    const unlisten = await listen<PlayerStateEvent>('player-state', (ev) => handler(ev.payload));
    return unlisten;
  },
};
