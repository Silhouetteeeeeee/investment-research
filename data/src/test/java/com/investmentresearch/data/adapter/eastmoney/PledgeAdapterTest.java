package com.investmentresearch.data.adapter.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.data.persistence.StockMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PledgeAdapterTest {
    @TempDir Path tempDir;

    private EastmoneyTestSupport.Db db;
    private PledgeAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        String url = EastmoneyTestSupport.copySnapshot(tempDir);
        db = EastmoneyTestSupport.openDb(url);
        adapter =
                new PledgeAdapter(
                        EastmoneyTestSupport.cacheStore(url),
                        db.session().getMapper(StockMapper.class),
                        EastmoneyTestSupport.offline(),
                        "http://127.0.0.1:1",
                        EastmoneyTestSupport.CLOCK_END);
    }

    @Test
    void matches_golden_for_every_cached_company() throws Exception {
        EastmoneyTestSupport.exec(
                db.session(), "update company_pledges set fetched_at = '2026-10-31 09:00:00'");
        List<Path> goldens = EastmoneyTestSupport.goldens("get_pledges");
        assertThat(goldens).isNotEmpty();
        for (Path file : goldens) {
            String code = EastmoneyTestSupport.golden(file).path("default_company").asText();
            EastmoneyTestSupport.assertMatchesGolden(file, adapter.pledges(code, 3));
        }
    }
}
