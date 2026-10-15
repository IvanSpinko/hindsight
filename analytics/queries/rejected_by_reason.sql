-- Data quality: records the cleaning step rejected, by reason
SELECT record_type,
       reject_reason,
       count(*) AS records
FROM visiontrack.rejected
WHERE dt >= date_format(current_date - INTERVAL '30' DAY, '%Y-%m-%d')
GROUP BY record_type, reject_reason
ORDER BY records DESC;
