-- Detections per run, joined with the run summary (source, status, duration, processing speed)
SELECT r.run_id,
       r.source,
       r.status,
       r.started_at,
       round(r.duration_ms / 1000.0, 1)        AS duration_s,
       round(r.processing_time_ms / 1000.0, 1) AS processing_s,
       count(d.run_id)                         AS detections,
       count(DISTINCT d.class_label)           AS classes
FROM visiontrack.runs r
LEFT JOIN visiontrack.detections d
       ON d.run_id = r.run_id AND d.dt = r.dt
WHERE r.dt >= date_format(current_date - INTERVAL '30' DAY, '%Y-%m-%d')
GROUP BY r.run_id, r.source, r.status, r.started_at, r.duration_ms, r.processing_time_ms
ORDER BY r.started_at DESC;
