package com.investmentresearch.app.config;

import com.investmentresearch.data.adapter.memory.ResearchMemoryStore;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 首次启动时把 Python 版的研究档案导进来；没有配置或已导入就是空操作。 */
@Component
public class MemoryImportRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryImportRunner.class);

    private final ResearchMemoryStore store;
    private final String legacyPath;

    public MemoryImportRunner(
            ResearchMemoryStore store,
            @Value("${APP_MEMORY_IMPORT:}") String legacyPath) {
        this.store = store;
        this.legacyPath = legacyPath;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (legacyPath == null || legacyPath.isBlank()) {
            return;
        }
        Path source = Path.of(legacyPath);
        if (!source.toFile().isFile()) {
            LOG.warn("研究档案旧库不存在，跳过导入：{}", source);
            return;
        }
        if (!store.companies("").isEmpty()) {
            LOG.info("研究档案非空，跳过导入");
            return;
        }
        int imported = store.importLegacy(source);
        LOG.info("研究档案导入完成：{} 行", imported);
    }
}
