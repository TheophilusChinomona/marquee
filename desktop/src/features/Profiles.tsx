import { useState } from 'react';
import { ArrowRight, Pencil, Plus, Trash2, X } from 'lucide-react';
import { extractAppError, type DesktopApi, type Profile } from '../api/types';
import { avatarClass, ErrorBanner, PROFILE_AVATARS } from '../components/StatusView';

interface ProfilesProps {
  api: DesktopApi;
  profiles: Profile[];
  activeProfileId: string | null;
  onChanged: (nextProfiles: Profile[], nextActiveId: string | null, activated?: boolean) => void;
  onNotify: (message: string) => void;
}

export default function Profiles({
  api,
  profiles,
  activeProfileId,
  onChanged,
  onNotify,
}: ProfilesProps) {
  const [editing, setEditing] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<Profile | null>(null);
  const [nameInput, setNameInput] = useState('');
  const [avatarInput, setAvatarInput] = useState<string>('red');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSelect(profile: Profile) {
    if (busy) return;
    if (editing) {
      setRenameTarget(profile);
      setNameInput(profile.name);
      setAvatarInput(profile.avatar);
      setError(null);
      return;
    }
    if (!api.profileActivate) return;
    setBusy(true);
    setError(null);
    try {
      await api.profileActivate({ id: profile.id });
      onChanged(profiles, profile.id, true);
      onNotify(`Welcome back, ${profile.name}`);
    } catch (err) {
      setError(extractAppError(err, 'Unable to switch profile.').message);
    } finally {
      setBusy(false);
    }
  }

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault();
    if (!api.profileCreate || busy) return;
    setBusy(true);
    setError(null);
    try {
      const created = await api.profileCreate({
        name: nameInput.trim(),
        avatar: avatarInput,
      });
      const updated = [...profiles, created];
      setAddOpen(false);
      setNameInput('');
      setAvatarInput('red');
      onChanged(updated, activeProfileId ?? created.id, false);
      onNotify(`Created profile ${created.name}`);
    } catch (err) {
      setError(extractAppError(err, 'Unable to create profile.').message);
    } finally {
      setBusy(false);
    }
  }

  async function handleRename(event: React.FormEvent) {
    event.preventDefault();
    if (!renameTarget || !api.profileUpdate || busy) return;
    setBusy(true);
    setError(null);
    try {
      const updatedProfile = await api.profileUpdate({
        id: renameTarget.id,
        name: nameInput.trim(),
        avatar: avatarInput,
      });
      const updated = profiles.map((p) => (p.id === updatedProfile.id ? updatedProfile : p));
      setRenameTarget(null);
      onChanged(updated, activeProfileId, false);
      onNotify(`Updated ${updatedProfile.name}`);
    } catch (err) {
      setError(extractAppError(err, 'Unable to update profile.').message);
    } finally {
      setBusy(false);
    }
  }

  async function handleRemove(profile: Profile) {
    if (!api.profileRemove || busy) return;
    if (profiles.length <= 1) {
      setError('Keep at least one profile.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const nextActive = await api.profileRemove({ id: profile.id });
      const remaining = profiles.filter((p) => p.id !== profile.id);
      setRenameTarget(null);
      onChanged(remaining, nextActive, false);
      onNotify(`Removed ${profile.name}`);
    } catch (err) {
      setError(extractAppError(err, 'Unable to remove profile.').message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="profiles-page">
      <span className="eyebrow">MAKE YOURSELF AT HOME</span>
      <h1>Who’s watching?</h1>
      <p>Your favourites. Your place in the story.</p>

      {error && (
        <div style={{ maxWidth: 460, width: '100%', marginTop: 20 }}>
          <ErrorBanner message={error} />
        </div>
      )}

      <div className="profile-grid">
        {profiles.map((profile, i) => (
          <button
            type="button"
            key={profile.id}
            className={`profile-choice ${editing ? 'editing' : ''}`}
            disabled={busy}
            onClick={() => void handleSelect(profile)}
          >
            <span className={`avatar-art ${avatarClass(profile.avatar, i)}`}>
              <span className="avatar-horizon" />
              <span className="avatar-sun" />
              <span className="avatar-mountain" />
              {editing && (
                <span className="avatar-edit">
                  <Pencil size={20} />
                </span>
              )}
            </span>
            <strong>{profile.name}</strong>
            {profile.id === activeProfileId && !editing && (
              <span className="profile-active">CURRENT PROFILE</span>
            )}
          </button>
        ))}

        <button
          type="button"
          className="profile-choice add-profile"
          disabled={busy}
          onClick={() => {
            setNameInput('');
            setAvatarInput('red');
            setError(null);
            setAddOpen(true);
          }}
        >
          <span className="add-avatar">
            <Plus size={30} strokeWidth={1} />
          </span>
          <strong>Add profile</strong>
        </button>
      </div>

      <button
        type="button"
        className="button outline"
        onClick={() => {
          setEditing((v) => !v);
          setError(null);
        }}
      >
        <Pencil size={14} />
        {editing ? 'Done' : 'Manage profiles'}
      </button>

      {editing && (
        <p className="preview-note">Select a profile to rename, change avatar, or remove it.</p>
      )}

      {addOpen && (
        <div className="modal-backdrop">
          <form className="small-modal" onSubmit={handleCreate}>
            <div className="section-heading">
              <h2>A space of your own.</h2>
              <button
                type="button"
                className="icon-button"
                aria-label="Close"
                onClick={() => setAddOpen(false)}
              >
                <X size={18} />
              </button>
            </div>
            <label>
              Profile name
              <input
                autoFocus
                value={nameInput}
                onChange={(e) => setNameInput(e.target.value)}
                maxLength={40}
                placeholder="Your name"
                required
              />
            </label>
            <div className="avatar-picker" role="radiogroup" aria-label="Profile avatar">
              {PROFILE_AVATARS.map((color, idx) => (
                <button
                  type="button"
                  key={color}
                  role="radio"
                  aria-checked={avatarInput === color}
                  aria-label={`Avatar ${color}`}
                  className={`avatar-swatch ${avatarClass(color, idx)} ${avatarInput === color ? 'selected' : ''}`}
                  onClick={() => setAvatarInput(color)}
                />
              ))}
            </div>
            <button className="button primary" type="submit" disabled={busy}>
              Create profile <ArrowRight size={16} />
            </button>
          </form>
        </div>
      )}

      {renameTarget && (
        <div className="modal-backdrop">
          <form className="small-modal" onSubmit={handleRename}>
            <div className="section-heading">
              <h2>Edit profile</h2>
              <button
                type="button"
                className="icon-button"
                aria-label="Close"
                onClick={() => setRenameTarget(null)}
              >
                <X size={18} />
              </button>
            </div>
            <label>
              Profile name
              <input
                autoFocus
                value={nameInput}
                onChange={(e) => setNameInput(e.target.value)}
                maxLength={40}
                required
              />
            </label>
            <div className="avatar-picker" role="radiogroup" aria-label="Profile avatar">
              {PROFILE_AVATARS.map((color, idx) => (
                <button
                  type="button"
                  key={color}
                  role="radio"
                  aria-checked={avatarInput === color}
                  aria-label={`Avatar ${color}`}
                  className={`avatar-swatch ${avatarClass(color, idx)} ${avatarInput === color ? 'selected' : ''}`}
                  onClick={() => setAvatarInput(color)}
                />
              ))}
            </div>
            <div className="modal-actions">
              <button className="button primary" type="submit" disabled={busy}>
                Save changes
              </button>
              {profiles.length > 1 && (
                <button
                  type="button"
                  className="button outline danger"
                  disabled={busy}
                  onClick={() => void handleRemove(renameTarget)}
                >
                  <Trash2 size={15} /> Remove profile
                </button>
              )}
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
