# Hindsight REST API

The page in the browser talks to the app through these endpoints. They answer requests from this computer only
(others get `403`). Errors come back as JSON with a `detail` field.

## Video analysis

| Method | Path                  | Description                                                    |
|--------|-----------------------|----------------------------------------------------------------|
| POST   | `/api/analyses`       | multipart field `file` → `202 Accepted`, job JSON + `Location` |
| GET    | `/api/analyses/{id}`  | status (`QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`), progress, result |
| GET    | `/api/analyses`       | all jobs, newest first                                         |
| GET    | `/actuator/health`    | health check for load balancers                                |

```bash
curl -F "file=@street.mp4" http://localhost:8080/api/analyses
curl http://localhost:8080/api/analyses/<id>
```

Completed result (shortened):

```json
{
  "totalObjects": 8,
  "counts":  [{ "label": "person", "count": 4, "firstSeen": "00:00:02.000" }],
  "objects": [{ "id": 1, "label": "person", "firstSeen": "00:00:02.000", "lastSeen": "00:00:04.800",
                "visibleSeconds": 2.8, "confidence": 0.903, "box": { "x": 403, "y": 266, "width": 133, "height": 336 } }]
}
```

## Meetings

| Method | Path                              | Description                                               |
|--------|-----------------------------------|-----------------------------------------------------------|
| GET    | `/api/meetings/devices`           | monitors and microphones (`?refresh=true` probes again)   |
| POST   | `/api/meetings/start`             | `{"title", "monitor", "microphone"}` → starts recording   |
| POST   | `/api/meetings/{id}/stop`         | stops, builds `recording.mp4` (screen + sound) and starts processing |
| GET    | `/api/meetings`, `/api/meetings/{id}` | recorded meetings (read from their folders)           |
| POST   | `/api/meetings/import`            | multipart `file`: an audio or video recording to process like a meeting |
| PATCH  | `/api/meetings/{id}`              | `{"title", "startedAt"}`: rename a meeting or change its date |
| POST   | `/api/meetings/{id}/process`      | runs the processing again                                 |
| GET    | `/api/meetings/{id}/notes`        | the notes (`notes.md`)                                    |
| GET    | `/api/meetings/{id}/transcript`   | the transcript (`transcript.md`)                          |
| GET    | `/api/meetings/{id}/recording`    | the screen recording with sound, playable in the browser  |
| POST   | `/api/meetings/{id}/open-folder`  | opens the meeting folder in Explorer                      |
| GET    | `/api/meetings/notion`            | whether the Notion export is set up (never the token)     |
| POST   | `/api/meetings/{id}/notion`       | sends the notes to Notion in the background; a page sent before is replaced |

## Settings

| Method | Path                       | Description                                                          |
|--------|----------------------------|----------------------------------------------------------------------|
| GET    | `/api/settings`            | the settings in use and the defaults; the Notion token is never returned |
| PUT    | `/api/settings/meetings`   | meetings folder, screen fps and quality, speech engine, notes language, default devices |
| PUT    | `/api/settings/notion`     | `{"token", "parentPageId", "autoExport"}`; a null token keeps the saved one |
| GET    | `/api/settings/gpu`        | graphics card name, driver and free video memory (from `nvidia-smi`) |

Settings are saved in `%LOCALAPPDATA%\VisionTrack\settings.json`.
