export interface Profile { id: string; name: string; avatar: string }
export interface Bootstrap { connected: boolean; profiles: Profile[]; activeProfileId: string | null; credentialStatus: 'remembered' | 'session' | 'none'; developmentPlayer?: boolean }
export interface AppError { code: string; message: string; retryable: boolean }
export interface DesktopApi { bootstrap(): Promise<Bootstrap> }
