-- Detections per class over the last 30 days (raw detections on sampled frames, not tracked objects)
SELECT class_label,
       count(*)               AS detections,
       count(DISTINCT run_id) AS runs
FROM visiontrack.detections
WHERE dt >= date_format(current_date - INTERVAL '30' DAY, '%Y-%m-%d')
GROUP BY class_label
ORDER BY detections DESC
LIMIT 50;
