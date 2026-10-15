-- Average and spread of the model confidence per class over the last 30 days
SELECT class_label,
       count(*)                                  AS detections,
       round(avg(confidence), 3)                 AS avg_confidence,
       round(approx_percentile(confidence, 0.5), 3) AS median_confidence,
       round(min(confidence), 3)                 AS min_confidence,
       round(max(confidence), 3)                 AS max_confidence
FROM visiontrack.detections
WHERE dt >= date_format(current_date - INTERVAL '30' DAY, '%Y-%m-%d')
GROUP BY class_label
HAVING count(*) >= 5
ORDER BY avg_confidence DESC;
