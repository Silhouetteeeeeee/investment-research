package com.investmentresearch.app.config;

import com.investmentresearch.core.agent.AgentFactory;
import com.investmentresearch.core.agent.AgentProperties;
import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.port.AnalystViewPort;
import com.investmentresearch.core.port.AnnouncementPort;
import com.investmentresearch.core.port.BuybackPort;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.CompanyProfilePort;
import com.investmentresearch.core.port.ConversationRepository;
import com.investmentresearch.core.port.DividendPort;
import com.investmentresearch.core.port.FinancePort;
import com.investmentresearch.core.port.FinancialHistoryPort;
import com.investmentresearch.core.port.ForecastPort;
import com.investmentresearch.core.port.InsiderTradePort;
import com.investmentresearch.core.port.LockupPort;
import com.investmentresearch.core.port.ManagementPort;
import com.investmentresearch.core.port.PeerPort;
import com.investmentresearch.core.port.RunStore;
import com.investmentresearch.core.port.PledgePort;
import com.investmentresearch.core.port.ResearchNotesPort;
import com.investmentresearch.core.port.ResearchPort;
import com.investmentresearch.core.port.SegmentPort;
import com.investmentresearch.core.port.ShareholderPort;
import com.investmentresearch.core.port.ValuationCheckPort;
import com.investmentresearch.core.port.ValuationPort;
import com.investmentresearch.core.run.FileRunStore;
import com.investmentresearch.core.service.AskService;
import com.investmentresearch.core.service.CompanyResolver;
import com.investmentresearch.core.service.ContextMeter;
import com.investmentresearch.core.service.QuestionRouter;
import com.investmentresearch.core.tool.AnalystViewsTool;
import com.investmentresearch.core.tool.AnnouncementsTool;
import com.investmentresearch.core.tool.BuybacksTool;
import com.investmentresearch.core.tool.CalcValuationTool;
import com.investmentresearch.core.tool.CompanyProfileTool;
import com.investmentresearch.core.tool.DividendsTool;
import com.investmentresearch.core.tool.EarningsForecastsTool;
import com.investmentresearch.core.tool.FinanceTool;
import com.investmentresearch.core.tool.FinancialHistoryTool;
import com.investmentresearch.core.tool.InsiderTradesTool;
import com.investmentresearch.core.tool.InstitutionalResearchTool;
import com.investmentresearch.core.tool.LockupExpiriesTool;
import com.investmentresearch.core.tool.ManagementTool;
import com.investmentresearch.core.tool.PeersTool;
import com.investmentresearch.core.tool.PledgesTool;
import com.investmentresearch.core.tool.ResearchNotesTool;
import com.investmentresearch.core.tool.ResolveCompanyTool;
import com.investmentresearch.core.tool.SegmentsTool;
import com.investmentresearch.core.tool.ShareholdersTool;
import com.investmentresearch.core.tool.ToolRegistry;
import com.investmentresearch.core.tool.ToolSchemas;
import com.investmentresearch.core.tool.ValuationTool;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
            FinancialHistoryPort history,
            SegmentPort segments,
            ManagementPort management,
            ShareholderPort shareholders,
            PeerPort peers,
            DividendPort dividends,
            InsiderTradePort insiderTrades,
            BuybackPort buybacks,
            LockupPort lockups,
            PledgePort pledges,
            ForecastPort forecasts,
            ResearchPort research,
            ResearchNotesPort researchNotes,
            AnalystViewPort analystViews,
            AnnouncementPort announcements,
            ValuationPort valuation,
            ValuationCheckPort valuationCheck) {
        return new ToolRegistry(
                List.of(
                        new ResolveCompanyTool(directory, resolver, schemas),
                        new CompanyProfileTool(profiles, resolver, schemas),
                        new FinanceTool(finance, resolver, schemas),
                        new FinancialHistoryTool(history, resolver, schemas),
                        new SegmentsTool(segments, resolver, schemas),
                        new ManagementTool(management, resolver, schemas),
                        new ShareholdersTool(shareholders, resolver, schemas),
                        new PeersTool(peers, resolver, schemas),
                        new DividendsTool(dividends, resolver, schemas),
                        new InsiderTradesTool(insiderTrades, resolver, schemas),
                        new BuybacksTool(buybacks, resolver, schemas),
                        new LockupExpiriesTool(lockups, resolver, schemas),
                        new PledgesTool(pledges, resolver, schemas),
                        new EarningsForecastsTool(forecasts, resolver, schemas),
                        new InstitutionalResearchTool(research, resolver, schemas),
                        new ResearchNotesTool(researchNotes, resolver, schemas),
                        new AnalystViewsTool(analystViews, resolver, schemas),
                        new AnnouncementsTool(announcements, resolver, schemas),
                        new ValuationTool(valuation, resolver, schemas),
                        new CalcValuationTool(valuation, valuationCheck, resolver, schemas)));
    }

    @Bean
    public AgentProperties agentProperties(Environment environment) {
        // README：APP_DATA_DIR 是 SQLite 与运行数据的根目录，未设置时用 ./var。
        // 走 Environment 而不是 getenv，才能和 datasource 的 ${APP_DATA_DIR:./var} 解析到同一处。
        // skills 相对启动目录，spring-boot:run / 测试的启动目录是 app/，需要 APP_SKILLS_DIR=../skills
        return new AgentProperties(
                null,
                Path.of(environment.getProperty("APP_SKILLS_DIR", "skills")),
                Path.of(environment.getProperty("APP_DATA_DIR", "./var")),
                0);
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
    public AssistantEngine assistantEngine(
            ToolRegistry tools, AgentProperties properties, Environment environment) {
        String apiKey = environment.getProperty("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // 缺 key 不能拖垮容器；保留原因供 /api/ask 回「助手不可用」。
            return new UnavailableEngine(MISSING_KEY);
        }
        return AgentFactory.create(tools, properties, apiKey);
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
