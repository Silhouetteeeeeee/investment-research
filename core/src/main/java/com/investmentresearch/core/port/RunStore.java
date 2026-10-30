package com.investmentresearch.core.port;

import com.investmentresearch.core.run.RunSummary;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of run payloads (run_logs). */
public interface RunStore {
    /** Writes a payload that must carry a valid {@code run_id}. */
    RunSummary write(Map<String, Object> payload);

    Optional<Map<String, Object>> read(String runId);

    void deleteAll(List<String> runIds);
}
