use super::NativePlayer;
use crate::{
    models::{AppError, MediaItem, MediaKind},
    store::{Store, now_ms},
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const PROGRESS_SAVE_INTERVAL_MS: i64 = 10_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EndReason {
    NaturalEof,
    Stop,
    Quit,
    Error,
    Redirect,
    Other(i32),
}

impl EndReason {
    pub fn from_mpv(code: i32) -> Self {
        match code {
            0 => EndReason::NaturalEof,
            2 => EndReason::Stop,
            3 => EndReason::Quit,
            4 => EndReason::Error,
            5 => EndReason::Redirect,
            other => EndReason::Other(other),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TrackInfo {
    pub id: i64,
    pub kind: String,
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lang: Option<String>,
    pub selected: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QueueItem {
    pub episode_id: String,
    pub season: i32,
    pub number: i32,
    pub title: String,
}

#[derive(Debug, Clone)]
pub struct QueueEpisode {
    pub item: QueueItem,
    pub url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerSnapshot {
    pub session_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media: Option<MediaItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub episode_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub episode_title: Option<String>,
    pub status: String,
    pub position_ms: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<i64>,
    pub seekable: bool,
    pub volume: f64,
    pub muted: bool,
    pub tracks: Vec<TrackInfo>,
    pub selected_audio_id: Option<i64>,
    pub selected_subtitle_id: Option<i64>,
    pub queue: Vec<QueueItem>,
    pub error: Option<AppError>,
}

impl PlayerSnapshot {
    pub fn idle() -> Self {
        Self {
            session_id: String::new(),
            media: None,
            episode_id: None,
            episode_title: None,
            status: "stopped".into(),
            position_ms: 0,
            duration_ms: None,
            seekable: false,
            volume: 100.0,
            muted: false,
            tracks: vec![],
            selected_audio_id: None,
            selected_subtitle_id: None,
            queue: vec![],
            error: None,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerStateEvent {
    pub session_id: String,
    pub snapshot: PlayerSnapshot,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "action", content = "value", rename_all = "camelCase")]
pub enum PlayerControlCommand {
    Pause,
    Resume,
    SeekAbsoluteMs(i64),
    SeekRelativeMs(i64),
    Volume(f64),
    Mute(bool),
    AudioTrack(Option<i64>),
    SubtitleTrack(Option<i64>),
    PreviousEpisode,
    NextEpisode,
    SelectEpisode(String),
    Stop,
}

#[derive(Debug, Clone, Default)]
pub struct EnginePoll {
    pub paused: bool,
    pub buffering: bool,
    pub idle_active: bool,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub seekable: bool,
    pub volume: f64,
    pub muted: bool,
    pub tracks: Vec<TrackInfo>,
    pub selected_audio_id: Option<i64>,
    pub selected_subtitle_id: Option<i64>,
    pub end_event: Option<EndReason>,
}

pub trait MediaEngine: Send {
    fn load(&mut self, url: &str, start_ms: u64) -> Result<(), AppError>;
    fn set_pause(&mut self, paused: bool) -> Result<(), AppError>;
    fn seek_absolute_ms(&mut self, ms: u64) -> Result<(), AppError>;
    fn set_volume(&mut self, volume: f64) -> Result<(), AppError>;
    fn set_mute(&mut self, muted: bool) -> Result<(), AppError>;
    fn set_audio_track(&mut self, id: Option<i64>) -> Result<(), AppError>;
    fn set_subtitle_track(&mut self, id: Option<i64>) -> Result<(), AppError>;
    fn stop(&mut self) -> Result<(), AppError>;
    fn poll_state(&mut self) -> EnginePoll;
    fn shutdown(&mut self);
}

fn parse_track_list(raw: &str) -> (Vec<TrackInfo>, Option<i64>, Option<i64>) {
    let Ok(Value::Array(entries)) = serde_json::from_str::<Value>(raw) else {
        return (Vec::new(), None, None);
    };
    let mut tracks = Vec::new();
    let mut selected_audio = None;
    let mut selected_sub = None;
    for entry in entries {
        let Some(kind) = entry["type"].as_str() else {
            continue;
        };
        if kind != "audio" && kind != "sub" {
            continue;
        }
        let Some(id) = entry["id"].as_i64() else {
            continue;
        };
        let lang = entry["lang"]
            .as_str()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(String::from);
        let title = entry["title"]
            .as_str()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(String::from)
            .or_else(|| lang.clone())
            .unwrap_or_else(|| {
                if kind == "audio" {
                    format!("Audio {id}")
                } else {
                    format!("Subtitle {id}")
                }
            });
        let selected = entry["selected"].as_bool().unwrap_or(false);
        if selected {
            if kind == "audio" {
                selected_audio = Some(id);
            } else {
                selected_sub = Some(id);
            }
        }
        tracks.push(TrackInfo {
            id,
            kind: kind.to_string(),
            title,
            lang,
            selected,
        });
    }
    (tracks, selected_audio, selected_sub)
}

impl MediaEngine for NativePlayer {
    fn load(&mut self, url: &str, start_ms: u64) -> Result<(), AppError> {
        NativePlayer::load(self, url, start_ms)
    }

    fn set_pause(&mut self, paused: bool) -> Result<(), AppError> {
        self.command(&["set", "pause", if paused { "yes" } else { "no" }])
    }

    fn seek_absolute_ms(&mut self, ms: u64) -> Result<(), AppError> {
        self.command(&[
            "seek",
            &format!("{:.3}", ms as f64 / 1000.0),
            "absolute+exact",
        ])
    }

    fn set_volume(&mut self, volume: f64) -> Result<(), AppError> {
        self.command(&["set", "volume", &volume.clamp(0.0, 100.0).to_string()])
    }

    fn set_mute(&mut self, muted: bool) -> Result<(), AppError> {
        self.command(&["set", "mute", if muted { "yes" } else { "no" }])
    }

    fn set_audio_track(&mut self, id: Option<i64>) -> Result<(), AppError> {
        match id {
            Some(id) if id > 0 => self.command(&["set", "aid", &id.to_string()]),
            None => self.command(&["set", "aid", "no"]),
            _ => Err(AppError::validation("Invalid audio track identifier.")),
        }
    }

    fn set_subtitle_track(&mut self, id: Option<i64>) -> Result<(), AppError> {
        match id {
            Some(id) if id > 0 => self.command(&["set", "sid", &id.to_string()]),
            None => self.command(&["set", "sid", "no"]),
            _ => Err(AppError::validation("Invalid subtitle track identifier.")),
        }
    }

    fn stop(&mut self) -> Result<(), AppError> {
        self.command(&["stop"])
    }

    fn poll_state(&mut self) -> EnginePoll {
        let num = |key: &str| {
            self.property(key)
                .and_then(|v| v.parse::<f64>().ok())
                .filter(|v| v.is_finite())
                .unwrap_or(0.0)
        };
        let (tracks, selected_audio_id, selected_subtitle_id) = self
            .property("track-list")
            .map(|raw| parse_track_list(&raw))
            .unwrap_or_else(|| (Vec::new(), None, None));

        EnginePoll {
            paused: self.property("pause").as_deref() == Some("yes"),
            buffering: self.property("paused-for-cache").as_deref() == Some("yes"),
            idle_active: self.property("idle-active").as_deref() == Some("yes"),
            position_ms: (num("time-pos").max(0.0) * 1000.0) as i64,
            duration_ms: (num("duration").max(0.0) * 1000.0) as i64,
            seekable: self.property("seekable").as_deref() == Some("yes"),
            volume: num("volume"),
            muted: self.property("mute").as_deref() == Some("yes"),
            tracks,
            selected_audio_id,
            selected_subtitle_id,
            end_event: self.poll_end_reason().map(EndReason::from_mpv),
        }
    }

    fn shutdown(&mut self) {
        NativePlayer::shutdown(self);
    }
}

pub struct StartSessionRequest {
    pub provider_id: String,
    pub profile_id: String,
    pub media: MediaItem,
    pub episode_id: Option<String>,
    pub episode_title: Option<String>,
    pub url: String,
    pub start_ms: u64,
    pub queue: Vec<QueueEpisode>,
    pub autoplay_next: bool,
    pub subtitles_default: bool,
}

struct ActivePlayback {
    session_id: String,
    provider_id: String,
    profile_id: String,
    media: MediaItem,
    episode_id: Option<String>,
    episode_title: Option<String>,
    queue: Vec<QueueEpisode>,
    autoplay_next: bool,
    status: String,
    position_ms: i64,
    duration_ms: i64,
    seekable: bool,
    volume: f64,
    muted: bool,
    tracks: Vec<TrackInfo>,
    selected_audio_id: Option<i64>,
    selected_subtitle_id: Option<i64>,
    error: Option<AppError>,
    eof_handled: bool,
    suppress_next_end: bool,
    last_saved_at_ms: i64,
}

impl ActivePlayback {
    fn snapshot(&self) -> PlayerSnapshot {
        PlayerSnapshot {
            session_id: self.session_id.clone(),
            media: Some(self.media.clone()),
            episode_id: self.episode_id.clone(),
            episode_title: self.episode_title.clone(),
            status: self.status.clone(),
            position_ms: self.position_ms.max(0),
            duration_ms: if self.duration_ms > 0 {
                Some(self.duration_ms)
            } else {
                None
            },
            seekable: self.seekable && self.media.media_ref.kind != MediaKind::Live,
            volume: self.volume,
            muted: self.muted,
            tracks: self.tracks.clone(),
            selected_audio_id: self.selected_audio_id,
            selected_subtitle_id: self.selected_subtitle_id,
            queue: self.queue.iter().map(|q| q.item.clone()).collect(),
            error: self.error.clone(),
        }
    }
}

pub struct SessionController {
    engine: Option<Box<dyn MediaEngine>>,
    active: Option<ActivePlayback>,
}

impl Default for SessionController {
    fn default() -> Self {
        Self {
            engine: None,
            active: None,
        }
    }
}

impl SessionController {
    #[cfg(test)]
    pub fn with_engine(engine: Box<dyn MediaEngine>) -> Self {
        Self {
            engine: Some(engine),
            active: None,
        }
    }

    pub fn has_engine(&self) -> bool {
        self.engine.is_some()
    }

    pub fn set_engine(&mut self, engine: Box<dyn MediaEngine>) {
        self.engine = Some(engine);
    }

    pub fn is_active(&self) -> bool {
        self.active
            .as_ref()
            .is_some_and(|a| !matches!(a.status.as_str(), "stopped"))
    }

    pub fn snapshot(&self) -> PlayerSnapshot {
        self.active
            .as_ref()
            .map(ActivePlayback::snapshot)
            .unwrap_or_else(PlayerSnapshot::idle)
    }

    fn persist_current(&mut self, store: &mut Store, now: i64, force_complete: bool) {
        let Some(active) = self.active.as_mut() else {
            return;
        };
        if active.media.media_ref.kind == MediaKind::Live || active.duration_ms <= 0 {
            return;
        }
        let pos = if force_complete {
            active.duration_ms
        } else {
            active.position_ms.clamp(0, active.duration_ms)
        };
        let ep_id = active.episode_id.as_deref().unwrap_or("");
        let _ = store.save_history(
            &active.provider_id,
            &active.profile_id,
            active.media.media_ref.kind.as_str(),
            &active.media.media_ref.id,
            ep_id,
            pos,
            active.duration_ms,
            now,
        );
        active.last_saved_at_ms = now;
    }

    pub fn start(
        &mut self,
        req: StartSessionRequest,
        store: &mut Store,
        now: i64,
    ) -> Result<PlayerSnapshot, AppError> {
        // Save and cleanly end any prior session before loading the new item.
        if self.active.is_some() {
            self.persist_current(store, now, false);
        }

        let engine = self.engine.as_mut().ok_or_else(|| {
            AppError::player("Player runtime is not initialized. Please restart Marquee.")
        })?;

        let replacing = self.active.is_some();
        engine.load(&req.url, req.start_ms)?;
        let _ = engine.set_pause(false);
        if !req.subtitles_default {
            let _ = engine.set_subtitle_track(None);
        }

        let session_id = uuid::Uuid::new_v4().to_string();
        let active = ActivePlayback {
            session_id,
            provider_id: req.provider_id,
            profile_id: req.profile_id,
            media: req.media,
            episode_id: req.episode_id,
            episode_title: req.episode_title,
            queue: req.queue,
            autoplay_next: req.autoplay_next,
            status: "loading".into(),
            position_ms: req.start_ms as i64,
            duration_ms: 0,
            seekable: false,
            volume: 100.0,
            muted: false,
            tracks: vec![],
            selected_audio_id: None,
            selected_subtitle_id: None,
            error: None,
            eof_handled: false,
            suppress_next_end: replacing,
            last_saved_at_ms: now,
        };
        let snap = active.snapshot();
        self.active = Some(active);
        Ok(snap)
    }

    pub fn control(
        &mut self,
        command: PlayerControlCommand,
        store: &mut Store,
        now: i64,
    ) -> Result<PlayerSnapshot, AppError> {
        match command {
            PlayerControlCommand::Stop => {
                self.stop_and_save(store, now);
                return Ok(self.snapshot());
            }
            _ => {}
        }

        let Some(active) = self.active.as_ref() else {
            return Err(AppError::player("No active playback session."));
        };
        let is_live = active.media.media_ref.kind == MediaKind::Live;
        let seekable = active.seekable && !is_live;
        let current_pos = active.position_ms;
        let duration = active.duration_ms;

        match command {
            PlayerControlCommand::Pause => {
                if let Some(engine) = self.engine.as_mut() {
                    engine.set_pause(true)?;
                }
                if let Some(a) = self.active.as_mut() {
                    a.status = "paused".into();
                }
                self.persist_current(store, now, false);
            }
            PlayerControlCommand::Resume => {
                if let Some(engine) = self.engine.as_mut() {
                    engine.set_pause(false)?;
                }
                if let Some(a) = self.active.as_mut() {
                    a.status = "playing".into();
                }
            }
            PlayerControlCommand::SeekAbsoluteMs(target_ms) => {
                if !seekable {
                    return Err(AppError::player("This stream cannot seek."));
                }
                let clamped = if duration > 0 {
                    target_ms.clamp(0, duration)
                } else {
                    target_ms.max(0)
                };
                if let Some(engine) = self.engine.as_mut() {
                    engine.seek_absolute_ms(clamped as u64)?;
                }
                if let Some(a) = self.active.as_mut() {
                    a.position_ms = clamped;
                }
                self.persist_current(store, now, false);
            }
            PlayerControlCommand::SeekRelativeMs(delta_ms) => {
                if !seekable {
                    return Err(AppError::player("This stream cannot seek."));
                }
                let target = current_pos.saturating_add(delta_ms);
                let clamped = if duration > 0 {
                    target.clamp(0, duration)
                } else {
                    target.max(0)
                };
                if let Some(engine) = self.engine.as_mut() {
                    engine.seek_absolute_ms(clamped as u64)?;
                }
                if let Some(a) = self.active.as_mut() {
                    a.position_ms = clamped;
                }
                self.persist_current(store, now, false);
            }
            PlayerControlCommand::Volume(v) if v.is_finite() => {
                let clamped = v.clamp(0.0, 100.0);
                if let Some(engine) = self.engine.as_mut() {
                    engine.set_volume(clamped)?;
                }
                if let Some(a) = self.active.as_mut() {
                    a.volume = clamped;
                }
            }
            PlayerControlCommand::Mute(m) => {
                if let Some(engine) = self.engine.as_mut() {
                    engine.set_mute(m)?;
                }
                if let Some(a) = self.active.as_mut() {
                    a.muted = m;
                }
            }
            PlayerControlCommand::AudioTrack(id) => {
                if let Some(engine) = self.engine.as_mut() {
                    engine.set_audio_track(id)?;
                }
                if let Some(a) = self.active.as_mut() {
                    a.selected_audio_id = id;
                }
            }
            PlayerControlCommand::SubtitleTrack(id) => {
                if let Some(engine) = self.engine.as_mut() {
                    engine.set_subtitle_track(id)?;
                }
                if let Some(a) = self.active.as_mut() {
                    a.selected_subtitle_id = id;
                }
            }
            PlayerControlCommand::PreviousEpisode => {
                self.switch_relative_episode(-1, store, now)?;
            }
            PlayerControlCommand::NextEpisode => {
                self.switch_relative_episode(1, store, now)?;
            }
            PlayerControlCommand::SelectEpisode(ep_id) => {
                self.switch_to_episode_id(&ep_id, store, now)?;
            }
            PlayerControlCommand::Stop => unreachable!(),
            PlayerControlCommand::Volume(_) => {
                return Err(AppError::validation("Invalid volume value."));
            }
        }

        Ok(self.snapshot())
    }

    fn switch_relative_episode(
        &mut self,
        delta: isize,
        store: &mut Store,
        now: i64,
    ) -> Result<(), AppError> {
        let target_id = {
            let active = self
                .active
                .as_ref()
                .ok_or_else(|| AppError::player("No active playback session."))?;
            let current_id = active
                .episode_id
                .as_deref()
                .ok_or_else(|| AppError::validation("Episode navigation is only available for series."))?;
            let idx = active
                .queue
                .iter()
                .position(|q| q.item.episode_id == current_id)
                .ok_or_else(|| AppError::validation("Current episode is not in the queue."))?;
            let next_idx = idx as isize + delta;
            if next_idx < 0 || (next_idx as usize) >= active.queue.len() {
                return Err(AppError::validation("No further episode in that direction."));
            }
            active.queue[next_idx as usize].item.episode_id.clone()
        };
        self.switch_to_episode_id(&target_id, store, now)
    }

    fn switch_to_episode_id(
        &mut self,
        episode_id: &str,
        store: &mut Store,
        now: i64,
    ) -> Result<(), AppError> {
        self.persist_current(store, now, false);
        let (url, title) = {
            let active = self
                .active
                .as_ref()
                .ok_or_else(|| AppError::player("No active playback session."))?;
            let found = active
                .queue
                .iter()
                .find(|q| q.item.episode_id == episode_id)
                .ok_or_else(|| AppError::validation("Selected episode was not found."))?;
            let label = format!(
                "S{} E{} · {}",
                found.item.season, found.item.number, found.item.title
            );
            (found.url.clone(), label)
        };

        let engine = self
            .engine
            .as_mut()
            .ok_or_else(|| AppError::player("Player runtime unavailable."))?;
        engine.load(&url, 0)?;
        let _ = engine.set_pause(false);

        if let Some(active) = self.active.as_mut() {
            active.episode_id = Some(episode_id.to_string());
            active.episode_title = Some(title);
            active.status = "loading".into();
            active.position_ms = 0;
            active.duration_ms = 0;
            active.error = None;
            active.eof_handled = false;
            active.suppress_next_end = true;
            active.last_saved_at_ms = now;
        }
        Ok(())
    }

    /// Polls native engine state, persists progress every 10s, and handles natural EOF
    /// episode auto-advance exactly once.
    pub fn tick(&mut self, store: &mut Store, now: i64) -> Option<PlayerSnapshot> {
        if self.active.is_none() || self.engine.is_none() {
            return None;
        }
        let poll = self.engine.as_mut()?.poll_state();

        let mut trigger_advance_id: Option<String> = None;
        let mut should_save_periodic = false;
        let mut should_save_completed = false;

        if let Some(active) = self.active.as_mut() {
            if active.status == "stopped" {
                return None;
            }

            if poll.duration_ms > 0 {
                active.duration_ms = poll.duration_ms;
            }
            if poll.position_ms > 0 || active.status == "playing" {
                active.position_ms = poll.position_ms.max(0);
            }
            active.seekable = poll.seekable;
            active.volume = poll.volume;
            active.muted = poll.muted;
            active.tracks = poll.tracks;
            active.selected_audio_id = poll.selected_audio_id;
            active.selected_subtitle_id = poll.selected_subtitle_id;

            if let Some(end_reason) = poll.end_event {
                if active.suppress_next_end && !matches!(end_reason, EndReason::NaturalEof) {
                    // Expected stop/redirect end event from item replacement.
                    active.suppress_next_end = false;
                } else {
                    active.suppress_next_end = false;
                    match end_reason {
                        EndReason::NaturalEof => {
                            if !active.eof_handled
                                && matches!(
                                    active.status.as_str(),
                                    "loading" | "playing" | "paused" | "buffering"
                                )
                            {
                                active.eof_handled = true;
                                should_save_completed = true;
                                if active.autoplay_next {
                                    if let Some(cur_ep) = active.episode_id.as_deref() {
                                        if let Some(idx) = active
                                            .queue
                                            .iter()
                                            .position(|q| q.item.episode_id == cur_ep)
                                        {
                                            if let Some(next_ep) = active.queue.get(idx + 1) {
                                                trigger_advance_id =
                                                    Some(next_ep.item.episode_id.clone());
                                            }
                                        }
                                    }
                                }
                                if trigger_advance_id.is_none() {
                                    active.status = "ended".into();
                                }
                            }
                        }
                        EndReason::Error => {
                            active.status = "error".into();
                            active.error = Some(AppError::player(
                                "Playback failed or stream connection was lost. Try again.",
                            ));
                        }
                        EndReason::Stop | EndReason::Quit => {
                            if active.status != "ended" {
                                active.status = "stopped".into();
                            }
                        }
                        EndReason::Redirect | EndReason::Other(_) => {}
                    }
                }
            } else if !matches!(active.status.as_str(), "ended" | "stopped" | "error") {
                if poll.buffering {
                    active.status = "buffering".into();
                } else if poll.paused {
                    active.status = "paused".into();
                } else if !poll.idle_active {
                    active.status = "playing".into();
                    active.suppress_next_end = false;
                }

                if active.status == "playing"
                    && now.saturating_sub(active.last_saved_at_ms) >= PROGRESS_SAVE_INTERVAL_MS
                {
                    should_save_periodic = true;
                }
            }
        }

        if should_save_completed {
            self.persist_current(store, now, true);
        } else if should_save_periodic {
            self.persist_current(store, now, false);
        }

        if let Some(next_ep_id) = trigger_advance_id {
            // Advance to the next episode cleanly without re-persisting the already completed one.
            let next_info = self.active.as_ref().and_then(|a| {
                a.queue
                    .iter()
                    .find(|q| q.item.episode_id == next_ep_id)
                    .map(|q| {
                        (
                            q.url.clone(),
                            format!("S{} E{} · {}", q.item.season, q.item.number, q.item.title),
                        )
                    })
            });
            if let Some((url, title)) = next_info {
                if let Some(engine) = self.engine.as_mut() {
                    if engine.load(&url, 0).is_ok() {
                        let _ = engine.set_pause(false);
                        if let Some(a) = self.active.as_mut() {
                            a.episode_id = Some(next_ep_id);
                            a.episode_title = Some(title);
                            a.status = "loading".into();
                            a.position_ms = 0;
                            a.duration_ms = 0;
                            a.error = None;
                            a.eof_handled = false;
                            a.last_saved_at_ms = now;
                        }
                    } else if let Some(a) = self.active.as_mut() {
                        a.status = "error".into();
                        a.error = Some(AppError::player("Unable to load the next episode."));
                    }
                }
            }
        }

        Some(self.snapshot())
    }

    pub fn stop_and_save(&mut self, store: &mut Store, now: i64) {
        if self.active.is_some() {
            self.persist_current(store, now, false);
        }
        if let Some(engine) = self.engine.as_mut() {
            let _ = engine.stop();
        }
        if let Some(active) = self.active.as_mut() {
            active.status = "stopped".into();
            active.suppress_next_end = true;
        }
    }

    pub fn shutdown(&mut self, store: &mut Store) {
        self.stop_and_save(store, now_ms());
        if let Some(mut engine) = self.engine.take() {
            engine.shutdown();
        }
        self.active = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::MediaRef;
    use std::collections::VecDeque;

    #[derive(Default)]
    struct FakeEngine {
        loaded: Vec<(String, u64)>,
        polls: VecDeque<EnginePoll>,
        stopped_count: usize,
        paused: bool,
        volume: f64,
        muted: bool,
        audio_track: Option<i64>,
        subtitle_track: Option<i64>,
        last_seek_ms: Option<u64>,
    }

    impl FakeEngine {
        fn push_poll(&mut self, poll: EnginePoll) {
            self.polls.push_back(poll);
        }
    }

    impl MediaEngine for FakeEngine {
        fn load(&mut self, url: &str, start_ms: u64) -> Result<(), AppError> {
            self.loaded.push((url.into(), start_ms));
            Ok(())
        }
        fn set_pause(&mut self, paused: bool) -> Result<(), AppError> {
            self.paused = paused;
            Ok(())
        }
        fn seek_absolute_ms(&mut self, ms: u64) -> Result<(), AppError> {
            self.last_seek_ms = Some(ms);
            Ok(())
        }
        fn set_volume(&mut self, volume: f64) -> Result<(), AppError> {
            self.volume = volume;
            Ok(())
        }
        fn set_mute(&mut self, muted: bool) -> Result<(), AppError> {
            self.muted = muted;
            Ok(())
        }
        fn set_audio_track(&mut self, id: Option<i64>) -> Result<(), AppError> {
            self.audio_track = id;
            Ok(())
        }
        fn set_subtitle_track(&mut self, id: Option<i64>) -> Result<(), AppError> {
            self.subtitle_track = id;
            Ok(())
        }
        fn stop(&mut self) -> Result<(), AppError> {
            self.stopped_count += 1;
            Ok(())
        }
        fn poll_state(&mut self) -> EnginePoll {
            self.polls.pop_front().unwrap_or(EnginePoll {
                paused: self.paused,
                seekable: true,
                volume: 80.0,
                ..EnginePoll::default()
            })
        }
        fn shutdown(&mut self) {}
    }

    fn series_request(provider: &str, profile: &str, autoplay_next: bool) -> StartSessionRequest {
        StartSessionRequest {
            provider_id: provider.into(),
            profile_id: profile.into(),
            media: MediaItem {
                media_ref: MediaRef {
                    kind: MediaKind::Series,
                    id: "series-1".into(),
                },
                name: "Horizon".into(),
                category_id: "1".into(),
                category_name: None,
                poster_url: None,
                backdrop_url: None,
                year: None,
                rating: None,
                container_extension: None,
                added: None,
                progress: None,
                episode_id: None,
                position_ms: None,
                duration_ms: None,
                rank: None,
            },
            episode_id: Some("ep-101".into()),
            episode_title: Some("S1 E1 · Pilot".into()),
            url: "http://example.test/series/u/p/ep-101.mp4".into(),
            start_ms: 0,
            queue: vec![
                QueueEpisode {
                    item: QueueItem {
                        episode_id: "ep-101".into(),
                        season: 1,
                        number: 1,
                        title: "Pilot".into(),
                    },
                    url: "http://example.test/series/u/p/ep-101.mp4".into(),
                },
                QueueEpisode {
                    item: QueueItem {
                        episode_id: "ep-102".into(),
                        season: 1,
                        number: 2,
                        title: "Signal".into(),
                    },
                    url: "http://example.test/series/u/p/ep-102.mp4".into(),
                },
            ],
            autoplay_next,
            subtitles_default: false,
        }
    }

    #[test]
    fn natural_eof_advances_once_and_duplicate_eof_does_not_skip_episode() {
        let mut db = Store::open_memory().unwrap();
        let prov = db.connect_provider("acc", "http://a.test/").unwrap();
        let prof = db.profiles().unwrap()[0].id.clone();

        let mut fake = FakeEngine::default();
        fake.push_poll(EnginePoll {
            position_ms: 500_000,
            duration_ms: 1_000_000,
            seekable: true,
            ..EnginePoll::default()
        });
        fake.push_poll(EnginePoll {
            position_ms: 1_000_000,
            duration_ms: 1_000_000,
            seekable: true,
            end_event: Some(EndReason::NaturalEof),
            ..EnginePoll::default()
        });
        // Duplicate stale EOF while next episode is loading must not advance again!
        fake.push_poll(EnginePoll {
            position_ms: 0,
            duration_ms: 0,
            seekable: true,
            end_event: Some(EndReason::Stop),
            ..EnginePoll::default()
        });

        let mut controller = SessionController::with_engine(Box::new(fake));
        controller
            .start(series_request(&prov, &prof, true), &mut db, 1000)
            .unwrap();

        let s1 = controller.tick(&mut db, 2000).unwrap();
        assert_eq!(s1.status, "playing");
        assert_eq!(s1.episode_id.as_deref(), Some("ep-101"));

        let s2 = controller.tick(&mut db, 3000).unwrap();
        assert_eq!(s2.episode_id.as_deref(), Some("ep-102"));
        assert_eq!(s2.status, "loading");

        // Completed ep-101 removed from Continue Watching (100% >= 95%)
        assert!(db.continue_history(&prov, &prof).unwrap().is_empty());
        assert_eq!(db.all_history(&prov, &prof).unwrap().len(), 1);
    }

    #[test]
    fn manual_stop_error_and_autoplay_off_never_advance_or_falsely_complete() {
        let mut db = Store::open_memory().unwrap();
        let prov = db.connect_provider("acc", "http://a.test/").unwrap();
        let prof = db.profiles().unwrap()[0].id.clone();

        // 1. Error end reason does not advance and does not mark complete
        let mut fake = FakeEngine::default();
        fake.push_poll(EnginePoll {
            position_ms: 200_000,
            duration_ms: 1_000_000,
            seekable: true,
            ..EnginePoll::default()
        });
        fake.push_poll(EnginePoll {
            position_ms: 200_000,
            duration_ms: 1_000_000,
            seekable: true,
            end_event: Some(EndReason::Error),
            ..EnginePoll::default()
        });
        let mut controller = SessionController::with_engine(Box::new(fake));
        controller
            .start(series_request(&prov, &prof, true), &mut db, 1000)
            .unwrap();
        controller.tick(&mut db, 12_000); // triggers 10s periodic save (20% progress)
        let err_snap = controller.tick(&mut db, 13_000).unwrap();
        assert_eq!(err_snap.status, "error");
        assert_eq!(err_snap.episode_id.as_deref(), Some("ep-101"));
        assert_eq!(db.continue_history(&prov, &prof).unwrap().len(), 1);

        // 2. Autoplay off ends cleanly without advancing
        let mut fake2 = FakeEngine::default();
        fake2.push_poll(EnginePoll {
            position_ms: 940_000,
            duration_ms: 1_000_000,
            seekable: true,
            end_event: Some(EndReason::NaturalEof),
            ..EnginePoll::default()
        });
        let mut ctrl2 = SessionController::with_engine(Box::new(fake2));
        ctrl2
            .start(series_request(&prov, &prof, false), &mut db, 20_000)
            .unwrap();
        let end_snap = ctrl2.tick(&mut db, 21_000).unwrap();
        assert_eq!(end_snap.status, "ended");
        assert_eq!(end_snap.episode_id.as_deref(), Some("ep-101"));

        // 3. Manual stop saves exact 94% progress and never advances
        let mut fake3 = FakeEngine::default();
        fake3.push_poll(EnginePoll {
            position_ms: 940_000,
            duration_ms: 1_000_000,
            seekable: true,
            ..EnginePoll::default()
        });
        let mut ctrl3 = SessionController::with_engine(Box::new(fake3));
        ctrl3
            .start(series_request(&prov, &prof, true), &mut db, 30_000)
            .unwrap();
        ctrl3.tick(&mut db, 31_000);
        let stopped = ctrl3
            .control(PlayerControlCommand::Stop, &mut db, 32_000)
            .unwrap();
        assert_eq!(stopped.status, "stopped");
        assert_eq!(stopped.episode_id.as_deref(), Some("ep-101"));
        let hist = db.continue_history(&prov, &prof).unwrap();
        assert_eq!(hist.len(), 1);
        assert_eq!(hist[0].episode_id, "ep-101");
        assert_eq!(hist[0].position_ms, 940_000);
    }

    #[test]
    fn episode_controls_use_provider_ids_and_live_disables_seek() {
        let mut db = Store::open_memory().unwrap();
        let prov = db.connect_provider("acc", "http://a.test/").unwrap();
        let prof = db.profiles().unwrap()[0].id.clone();

        let fake = FakeEngine::default();
        let mut ctrl = SessionController::with_engine(Box::new(fake));
        ctrl.start(series_request(&prov, &prof, true), &mut db, 1000)
            .unwrap();
        let next = ctrl
            .control(PlayerControlCommand::NextEpisode, &mut db, 2000)
            .unwrap();
        assert_eq!(next.episode_id.as_deref(), Some("ep-102"));
        let prev = ctrl
            .control(PlayerControlCommand::PreviousEpisode, &mut db, 3000)
            .unwrap();
        assert_eq!(prev.episode_id.as_deref(), Some("ep-101"));
        let sel = ctrl
            .control(PlayerControlCommand::SelectEpisode("ep-102".into()), &mut db, 4000)
            .unwrap();
        assert_eq!(sel.episode_id.as_deref(), Some("ep-102"));

        // Live stream rejects seek
        ctrl.start(
            StartSessionRequest {
                provider_id: prov.clone(),
                profile_id: prof.clone(),
                media: MediaItem {
                    media_ref: MediaRef {
                        kind: MediaKind::Live,
                        id: "ch-1".into(),
                    },
                    name: "News Live".into(),
                    category_id: "1".into(),
                    category_name: None,
                    poster_url: None,
                    backdrop_url: None,
                    year: None,
                    rating: None,
                    container_extension: Some("ts".into()),
                    added: None,
                    progress: None,
                    episode_id: None,
                    position_ms: None,
                    duration_ms: None,
                    rank: None,
                },
                episode_id: None,
                episode_title: None,
                url: "http://example.test/live/u/p/ch-1.ts".into(),
                start_ms: 0,
                queue: vec![],
                autoplay_next: false,
                subtitles_default: false,
            },
            &mut db,
            5000,
        )
        .unwrap();
        let seek_err = ctrl
            .control(PlayerControlCommand::SeekAbsoluteMs(10_000), &mut db, 6000)
            .unwrap_err();
        assert_eq!(seek_err.code, "player");
    }
}
