import type { ReactNode } from 'react';
import { AlertCircle, ArrowRight, Film, RefreshCw } from 'lucide-react';

export function avatarClass(avatar: string, index = 0): string {
  switch (avatar) {
    case 'red':
      return 'avatar-0';
    case 'blue':
      return 'avatar-1';
    case 'green':
      return 'avatar-2';
    case 'purple':
      return 'avatar-purple';
    case 'orange':
      return 'avatar-orange';
    default:
      return `avatar-${index % 3}`;
  }
}

export const PROFILE_AVATARS = ['red', 'blue', 'green', 'purple', 'orange'] as const;

export function EmptyState({
  title,
  description,
  action,
  onAction,
}: {
  title: string;
  description: string;
  action?: string;
  onAction?: () => void;
}) {
  return (
    <div className="empty-state">
      <Film size={34} strokeWidth={1} />
      <h2>{title}</h2>
      <p>{description}</p>
      {action && onAction && (
        <button type="button" className="button secondary" onClick={onAction}>
          {action}
          <ArrowRight size={15} />
        </button>
      )}
    </div>
  );
}

export function ErrorBanner({
  message,
  retryable,
  onRetry,
}: {
  message: string;
  retryable?: boolean;
  onRetry?: () => void;
}) {
  return (
    <div className="inline-alert" role="alert">
      <AlertCircle size={17} />
      <span>{message}</span>
      {retryable && onRetry && (
        <button type="button" className="button secondary compact" onClick={onRetry}>
          <RefreshCw size={13} /> Retry
        </button>
      )}
    </div>
  );
}

export function SettingRow({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <div className="setting-row">
      <div>
        <strong>{title}</strong>
        <p>{description}</p>
      </div>
      {children}
    </div>
  );
}

export function Toggle({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string;
  value: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      className={`toggle ${value ? 'on' : ''}`}
      role="switch"
      aria-checked={value}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!value)}
    >
      <span />
    </button>
  );
}
