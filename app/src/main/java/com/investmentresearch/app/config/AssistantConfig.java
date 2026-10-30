package com.investmentresearch.app.config;

import com.investmentresearch.core.agent.AgentFactory;
import com.investmentresearch.core.agent.AgentProperties;
import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.port.AnnouncementPort;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.CompanyProfilePort;
import com.investmentresearch.core.port.ConversationRepository;
import com.investmentresearch.core.port.FinancePort;
import com.investmentresearch.core.port.PeerPort;
import com.investmentresearch.core.port.RunStore;
import com.investmentresearch.core.port.SegmentPort;
import com.investmentresearch.core.port.ValuationPort;
import com.investmentresearch.core.run.FileRunStore;
import com.investmentresearch.core.service.AskService;
import com.investmentresearch.core.service.CompanyResolver;
import com.investmentresearch.core.service.ContextMeter;
import com.investmentresearch.core.service.QuestionRouter;
import com.investmentresearch.core.tool.AnnouncementsTool;
import com.investmentresearch.core.tool.CompanyProfileTool;
import com.investmentresearch.core.tool.FinanceTool;
import com.investmentresearch.core.tool.PeersTool;
import com.investmentresearch.core.tool.ResolveCompanyTool;
import com.investmentresearch.core.tool.SegmentsTool;
import com.investmentresearch.core.tool.ToolRegistry;
import com.investmentresearch.core.tool.ToolSchemas;
import com.investmentresearch.core.tool.ValuationTool;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the assistant graph over the already-scanned data adapters. */
@Configuration
public class AssistantConfig {
    private static final String MISSING_KEY = "请先设置环境变量 DEEPSEEK_API_KEY";

    @Bean
    public ToolSchemas toolSchemas() {
        return ToolSchemas.loadDefault();
    }

    @Bean
    public CompanyResolver companyResolver(CompanyDirectory directory) {
        return new CompanyResolver(directory);
    }

    /** Registration order matches the Python {@code schemas()} export. */
    @Bean
    public ToolRegistry toolRegistry(
            CompanyDirectory directory,
            CompanyResolver resolver,
            ToolSchemas schemas,
            CompanyProfilePort profiles,
            FinancePort finance,
            SegmentPort segments,
            PeerPort peers,
            AnnouncementPort announcements,
            ValuationPort valuation) {
        return new ToolRegistry(
                List.of(
                        new ResolveCompanyTool(directory),
                        new CompanyProfileTool(profiles, resolver, schemas),
                        new FinanceTool(finance, resolver, schemas),
                        new SegmentsTool(segments, resolver, schemas),
                        new PeersTool(peers, resolver, schemas),
                        new AnnouncementsTool(announcements, resolver, schemas),
                        new ValuationTool(valuation, resolver, schemas)));
    }

    @Bean
    public AgentProperties agentProperties() {
        // README：APP_DATA_DIR 是 SQLite 与运行数据的根目录，未设置时用 ./var
        String dataDir = System.getenv().getOrDefault("APP_DATA_DIR", "./var");
        return new AgentProperties(null, null, Path.of(dataDir), 0);
    }

    @Bean
    public RunStore runStore(AgentProperties properties) {
        return new FileRunStore(properties.dataDir().resolve("runs"));
    }

    @Bean
    public QuestionRouter questionRouter(CompanyDirectory directory) {
        return new QuestionRouter(directory);
    }

    @Bean
    public ContextMeter contextMeter(ConversationRepository repository) {
        return new ContextMeter(repository);
    }

    @Bean
    public AssistantEngine assistantEngine(ToolRegistry tools, AgentProperties properties) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // 缺 key 不能拖垮容器；保留原因供 /api/ask 回「助手不可用」。
            return new UnavailableEngine(MISSING_KEY);
        }
        return AgentFactory.create(tools, properties);
    }

    @Bean
    public AskService askService(
            ConversationRepository repository,
            AssistantEngine engine,
            QuestionRouter router,
            ContextMeter meter,
            RunStore runStore) {
        return new AskService(repository, engine, router, meter, runStore);
    }

    /** 缺 DEEPSEEK_API_KEY 时的占位引擎；reason 只含变量名，不含密钥值。 */
    public static final class UnavailableEngine implements AssistantEngine {
        private final String reason;

        public UnavailableEngine(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }

        @Override
        public AskResult ask(
                long conversationId,
                String code,
                String name,
                String question,
                StreamListener listener) {
            throw new IllegalStateException(reason);
        }

        @Override
        public void deleteSession(long conversationId) {}
    }
}
