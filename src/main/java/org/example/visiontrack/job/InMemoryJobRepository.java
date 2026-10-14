package org.example.visiontrack.job;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

@Repository
public class InMemoryJobRepository implements JobRepository {

    private final Map<String, AnalysisJob> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(AnalysisJob job) {
        jobs.put(job.id(), job);
    }

    @Override
    public Optional<AnalysisJob> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<AnalysisJob> findAll() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(AnalysisJob::createdAt).reversed())
                .toList();
    }

    @Override
    public void update(String id, UnaryOperator<AnalysisJob> change) {
        jobs.computeIfPresent(id, (key, job) -> change.apply(job));
    }

    @Override
    public void delete(String id) {
        jobs.remove(id);
    }
}
