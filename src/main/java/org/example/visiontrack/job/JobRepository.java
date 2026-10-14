package org.example.visiontrack.job;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface JobRepository {

    void save(AnalysisJob job);

    Optional<AnalysisJob> findById(String id);

    List<AnalysisJob> findAll();

    void update(String id, UnaryOperator<AnalysisJob> change);

    void delete(String id);
}
