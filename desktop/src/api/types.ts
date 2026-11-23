export type MediaKind = 'live' | 'movie' | 'series';

export interface MediaRef {
  kind: MediaKind;
  id: string;
}

export interface MediaItem {
  ref: MediaRef;
  name: string;
  categoryId: string;
  categoryName?: string;
  posterUrl?: string;
  backdropUrl?: string;
  year?: string;
  rating?: number;
  containerExtension?: string;
  added?: number;
  progress?: number;
  episodeId?: string;
  positionMs?: number;
  durationMs?: number;
  rank?: number;
}

export interface Category {
  id: string;
  name: string;
  kind: MediaKind;
  count: number;
}

export interface Page<T> {
  items: T[];
  total: number;
  offset: number;
  limit: number;
}

export interface Profile {
  id: string;
  name: string;
  avatar: string;
}

export interface AppSettings {
  autoplayNext: boolean;
  subtitlesDefault: boolean;
  preferredAudio: string;
  compactLibrary: boolean;
}

export interface CacheStatus {
  hasCatalogue: boolean;
  stale: boolean;
  refreshedAtMs?: number;
  liveCount: number;
  movieCount: number;
  seriesCount: number;
  refreshing: boolean;
}

export type CredentialStatus = 'remembered' | 'session' | 'none';

export interface OnlineStatus {
  connected: boolean;
  baseUrl?: string | null;
  credentialStatus: CredentialStatus;
  warning?: string | null;
  developmentImport: boolean;
}

export interface OnlineConnectArgs {
  baseUrl: string;
  clientId: string;
  clientSecret: string;
  remember: boolean;
}

export interface Bootstrap {
  online?: OnlineStatus;
  activeProviderId?: string | null;
  providerUrl?: string | null;
  credentialWarning?: string | null;
  connected: boolean;
  profiles: Profile[];
  activeProfileId: string | null;
  credentialStatus: CredentialStatus;
  cacheStatus: CacheStatus;
  settings: AppSettings;
  developmentPlayer?: boolean;
}

export interface AppError {
  code: string;
  message: string;
  retryable: boolean;
}

export interface EpisodeDetails {
  id: string;
  season: number;
  number: number;
  title: string;
  containerExtension?: string;
  imageUrl?: string;
  plot?: string;
  durationMinutes?: number;
  durationMs?: number;
  rating?: number;
  progress?: number;
  positionMs?: number;
}

export interface SeasonDetails {
  number: number;
  episodes: EpisodeDetails[];
}

export interface MediaDetails {
  ref: MediaRef;
  name: string;
  categoryId: string;
  categoryName?: string;
  posterUrl?: string;
  backdropUrl?: string;
  plot?: string;
  genre?: string;
  year?: string;
  releaseDate?: string;
  rating?: number;
  durationMinutes?: number;
  cast?: string;
  seasons?: SeasonDetails[];
  related: MediaItem[];
  inMyList: boolean;
  resumePositionMs?: number;
  resumeDurationMs?: number;
  resumeEpisodeId?: string;
}

export interface HistoryItem {
  ref: MediaRef;
  episodeId?: string;
  positionMs: number;
  durationMs: number;
  updatedAtMs: number;
  completed: boolean;
  item?: MediaItem;
}

export interface HomeSection {
  id: string;
  kind: 'continue' | 'live' | 'forYou' | 'because' | 'top10' | 'newReleases' | 'myList' | string;
  eyebrow?: string;
  title: string;
  items: MediaItem[];
}

export interface HomeSectionsResponse {
  hero: MediaItem[];
  sections: HomeSection[];
  cacheStatus: CacheStatus;
}

export interface CatalogProgressEvent {
  refreshId: string;
  status: 'running' | 'completed' | 'failed' | 'cancelled' | string;
  stage: string;
  completedCategories: number;
  totalCategories: number;
  liveCount: number;
  movieCount: number;
  seriesCount: number;
  error?: AppError;
}

export interface EpgProgram {
  id: string;
  channelId: string;
  title: string;
  description?: string;
  startMs: number;
  endMs: number;
}

export interface EpgQueryResponse {
  channels: Record<string, EpgProgram[]>;
}

export interface TrackInfo {
  id: number;
  kind: 'audio' | 'sub';
  title: string;
  lang?: string;
  selected: boolean;
}

export interface QueueItem {
  episodeId: string;
  season: number;
  number: number;
  title: string;
}

export type PlayerStatus =
  | 'resolving'
  | 'loading'
  | 'playing'
  | 'paused'
  | 'buffering'
  | 'ended'
  | 'stopped'
  | 'error';

export interface PlayerSnapshot {
  sessionId: string;
  media?: MediaItem;
  episodeId?: string;
  episodeTitle?: string;
  status: PlayerStatus;
  positionMs: number;
  durationMs?: number;
  seekable: boolean;
  volume: number;
  muted: boolean;
  tracks: TrackInfo[];
  selectedAudioId?: number | null;
  selectedSubtitleId?: number | null;
  queue: QueueItem[];
  error?: AppError | null;
}

export interface PlayerStateEvent {
  sessionId: string;
  snapshot: PlayerSnapshot;
}

export type PlayerControlAction =
  | { action: 'pause' }
  | { action: 'resume' }
  | { action: 'seekAbsoluteMs'; value: number }
  | { action: 'seekRelativeMs'; value: number }
  | { action: 'volume'; value: number }
  | { action: 'mute'; value: boolean }
  | { action: 'audioTrack'; value: number | null }
  | { action: 'subtitleTrack'; value: number | null }
  | { action: 'previousEpisode' }
  | { action: 'nextEpisode' }
  | { action: 'selectEpisode'; value: string }
  | { action: 'overlayVisible'; value: boolean }
  | { action: 'stop' };

export type CatalogSort = 'recommended' | 'year' | 'title' | 'added';

export interface ConnectArgs {
  baseUrl: string;
  username: string;
  password: string;
  remember: boolean;
}

export interface CatalogQueryArgs {
  kind?: MediaKind;
  categoryId?: string;
  query?: string;
  offset?: number;
  limit?: number;
  sort?: CatalogSort;
}

export interface SettingsUpdateArgs {
  autoplayNext?: boolean;
  subtitlesDefault?: boolean;
  preferredAudio?: string;
  compactLibrary?: boolean;
}

export interface DesktopApi {
  onlineConnect?(args: OnlineConnectArgs): Promise<Bootstrap>;
  onlineDisconnect?(): Promise<void>;
  onlineCheck?(): Promise<OnlineStatus>;
  onlineImportDevelopment?(args: { baseUrl: string; remember: boolean }): Promise<Bootstrap>;
  onlineSearch?(args: { query: string }): Promise<MediaItem[]>;
  bootstrap(): Promise<Bootstrap>;
  providerConnect?(args: ConnectArgs): Promise<Bootstrap>;
  providerDisconnect?(): Promise<void>;
  profilesList?(): Promise<Profile[]>;
  profileCreate?(args: { name: string; avatar: string }): Promise<Profile>;
  profileUpdate?(args: { id: string; name: string; avatar: string }): Promise<Profile>;
  profileRemove?(args: { id: string }): Promise<string>;
  profileActivate?(args: { id: string }): Promise<void>;
  favoritesList?(args: { offset?: number; limit?: number }): Promise<Page<MediaItem>>;
  favoriteSet?(args: { ref: MediaRef; saved: boolean }): Promise<void>;
  historyList?(): Promise<HistoryItem[]>;
  settingsUpdate?(args: SettingsUpdateArgs): Promise<AppSettings>;
  catalogQuery?(args: CatalogQueryArgs): Promise<Page<MediaItem>>;
  catalogCategories?(args: { kind: MediaKind }): Promise<Category[]>;
  catalogRefresh?(): Promise<string>;
  catalogRefreshCancel?(): Promise<void>;
  mediaDetails?(args: { ref: MediaRef }): Promise<MediaDetails>;
  epgQuery?(args: { channelIds: string[]; fromMs: number; toMs: number }): Promise<EpgQueryResponse>;
  homeSections?(): Promise<HomeSectionsResponse>;
  playerStart?(args: { ref: MediaRef; episodeId?: string; resume: boolean }): Promise<PlayerSnapshot>;
  playerControl?(args: PlayerControlAction): Promise<PlayerSnapshot>;
  playerSnapshot?(): Promise<PlayerSnapshot>;
  onCatalogProgress?(handler: (event: CatalogProgressEvent) => void): Promise<() => void>;
  onPlayerState?(handler: (event: PlayerStateEvent) => void): Promise<() => void>;
}

export function extractAppError(err: unknown, fallback = 'Something went wrong. Please try again.'): AppError {
  if (err && typeof err === 'object') {
    const candidate = err as Partial<AppError>;
    if (typeof candidate.message === 'string' && candidate.message.trim().length > 0) {
      return {
        code: typeof candidate.code === 'string' ? candidate.code : 'unknown',
        message: candidate.message,
        retryable: typeof candidate.retryable === 'boolean' ? candidate.retryable : true,
      };
    }
  }
  return { code: 'unknown', message: fallback, retryable: true };
}
