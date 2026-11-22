use super::NativePlayer;
use crate::models::AppError;
use serde::{Deserialize, Serialize};
use std::sync::Mutex;

#[derive(Default)]
pub struct PlayerService(pub Mutex<Option<NativePlayer>>);

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerSnapshot {
    pub paused: bool,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub seekable: bool,
    pub volume: f64,
    pub muted: bool,
    pub version: String,
    pub end_reason: Option<i32>,
}
#[derive(Deserialize)]
#[serde(tag = "action", content = "value", rename_all = "camelCase")]
pub enum PlayerCommand {
    Pause(bool),
    Seek(f64),
    Volume(f64),
    Mute(bool),
    Stop,
}

impl PlayerService {
    pub fn active(&self) -> bool {
        self.0.lock().map(|v| v.is_some()).unwrap_or(false)
    }
    pub fn snapshot(&self) -> Result<PlayerSnapshot, AppError> {
        let lock = self
            .0
            .lock()
            .map_err(|_| AppError::player("Player state unavailable."))?;
        let p = lock
            .as_ref()
            .ok_or_else(|| AppError::player("No active playback."))?;
        let number = |key: &str| {
            p.property(key)
                .and_then(|v| v.parse::<f64>().ok())
                .unwrap_or(0.0)
        };
        Ok(PlayerSnapshot {
            paused: p.property("pause").as_deref() == Some("yes"),
            position_ms: (number("time-pos").max(0.0) * 1000.0) as u64,
            duration_ms: (number("duration").max(0.0) * 1000.0) as u64,
            seekable: p.property("seekable").as_deref() == Some("yes"),
            volume: number("volume"),
            muted: p.property("mute").as_deref() == Some("yes"),
            version: p.property("mpv-version").unwrap_or_default(),
            end_reason: p.poll_end_reason(),
        })
    }
    pub fn control(&self, command: PlayerCommand) -> Result<(), AppError> {
        let lock = self
            .0
            .lock()
            .map_err(|_| AppError::player("Player state unavailable."))?;
        let p = lock
            .as_ref()
            .ok_or_else(|| AppError::player("No active playback."))?;
        match command {
            PlayerCommand::Pause(value) => {
                p.command(&["set", "pause", if value { "yes" } else { "no" }])
            }
            PlayerCommand::Mute(value) => {
                p.command(&["set", "mute", if value { "yes" } else { "no" }])
            }
            PlayerCommand::Volume(value) if value.is_finite() => {
                p.command(&["set", "volume", &value.clamp(0.0, 100.0).to_string()])
            }
            PlayerCommand::Seek(value) if value.is_finite() => {
                if p.property("seekable").as_deref() != Some("yes") {
                    return Err(AppError::player("This stream cannot seek."));
                }
                let duration = p
                    .property("duration")
                    .and_then(|v| v.parse::<f64>().ok())
                    .unwrap_or(0.0);
                p.command(&[
                    "seek",
                    &value.clamp(0.0, duration.max(0.0)).to_string(),
                    "absolute+exact",
                ])
            }
            PlayerCommand::Stop => p.command(&["stop"]),
            _ => Err(AppError::player("Invalid player control value.")),
        }
    }
}
