import { ChevronDown, ChevronRight, Globe, Radio, Search, Settings, UserRound } from 'lucide-react';
import type { Profile } from '../api/types';

export type NavPage =
  | 'Home'
  | 'Movies'
  | 'Series'
  | 'Live TV'
  | 'My List'
  | 'Search'
  | 'Settings'
  | 'Profiles'
  | 'Connect';

const MAIN_NAV: NavPage[] = ['Home', 'Movies', 'Series', 'Live TV', 'My List'];

interface HeaderProps {
  page: NavPage;
  hasDetail: boolean;
  activeProfile?: Profile;
  profileMenuOpen: boolean;
  onToggleProfileMenu: () => void;
  onNavigate: (page: NavPage) => void;
}

export default function Header({
  page,
  hasDetail,
  activeProfile,
  profileMenuOpen,
  onToggleProfileMenu,
  onNavigate,
}: HeaderProps) {
  const profileName = activeProfile?.name ?? 'Profile';
  return (
    <header className="app-header">
      <button
        type="button"
        className="brand"
        onClick={() => onNavigate('Home')}
        aria-label="Marquee home"
      >
        MARQUEE
        <span />
      </button>
      <nav aria-label="Main navigation">
        {MAIN_NAV.map((item) => (
          <button
            type="button"
            key={item}
            className={page === item && !hasDetail ? 'active' : ''}
            onClick={() => onNavigate(item)}
          >
            {item === 'Live TV' && <Radio size={13} />}
            {item}
          </button>
        ))}
      </nav>
      <div className="header-actions">
        <button
          type="button"
          className={`icon-button search-trigger ${page === 'Search' ? 'active' : ''}`}
          aria-label="Search"
          onClick={() => onNavigate('Search')}
        >
          <Search size={19} />
          <span className="shortcut">Ctrl K</span>
        </button>
        <span className="header-divider" />
        <div className="profile-control">
          <button
            type="button"
            className="profile-trigger"
            onClick={onToggleProfileMenu}
            aria-expanded={profileMenuOpen}
          >
            <span className="mini-avatar">{profileName.slice(0, 1).toUpperCase()}</span>
            <span>{profileName}</span>
            <ChevronDown size={13} />
          </button>
          {profileMenuOpen && (
            <div className="profile-menu" role="menu">
              <span className="menu-eyebrow">YOUR MARQUEE</span>
              <button type="button" role="menuitem" onClick={() => onNavigate('Profiles')}>
                <UserRound size={16} />
                Switch profile
                <ChevronRight size={13} />
              </button>
              <button type="button" role="menuitem" onClick={() => onNavigate('Settings')}>
                <Settings size={16} />
                Settings
                <ChevronRight size={13} />
              </button>
              <div className="menu-line" />
              <button type="button" role="menuitem" onClick={() => onNavigate('Connect')}>
                <Globe size={16} />
                Provider setup
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
