package com.investmentresearch.app.config;

import com.investmentresearch.core.agent.AgentFactory;
import com.investmentresearch.core.agent.AgentProperties;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.QuotePort;
import com.investmentresearch.core.port.TradingRepository;
import com.investmentresearch.core.tool.GetTradingStateTool;
import com.investmentresearch.core.tool.SaveTradePlanTool;
import com.investmentresearch.core.tool.SaveTradingJournalTool;
import com.investmentresearch.core.tool.ToolRegistry;
import com.investmentresearch.core.tool.ToolSchemas;
import com.investmentresearch.core.trading.QuoteCache;
import com.investmentresearch.core.trading.TradingContext;
import com.investmentresearch.core.trading.TradingService;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 交易 Agent 的装配；执行器、调度器、页面 bean 在后续任务里追加。 */
@Configuration
public class TradingConfig {
    private static final String MISSING_KEY = "请先设置环境变量 DEEPSEEK_API_KEY";

    @Bean
    public TradingContext tradingContext() {
        return new TradingContext();
    }

    @Bean
    public QuoteCache quoteCache() {
        return new QuoteCache();
    }

    /** 与聊天 registry 同类型，但不参与无限定符的按类型注入。 */
    @Bean(defaultCandidate = false)
    public ToolRegistry tradingToolRegistry(
            @Qualifier("toolRegistry") ToolRegistry toolRegistry,
            ToolSchemas schemas,
            TradingRepository repository,
            CompanyDirectory directory,
            QuotePort quotes,
            TradingContext context) {
        return toolRegistry.plus(
                List.of(
                        new GetTradingStateTool(repository, quotes, context, schemas),
                        new SaveTradePlanTool(context, repository, directory, quotes, schemas),
                        new SaveTradingJournalTool(repository, context, schemas)));
    }

    /**
     * 交易引擎与聊天引擎同类型；不是默认候选，避免聊天链路的按类型注入出现歧义，
     * 需要它的 bean 用 {@code @Qualifier("tradingEngine")} 按名取。
     */
    @Bean(defaultCandidate = false)
    public AssistantEngine tradingEngine(
            @Qualifier("tradingToolRegistry") ToolRegistry tools, Environment environment) {
        String apiKey = environment.getProperty("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return new AssistantConfig.UnavailableEngine(MISSING_KEY);
        }
        return AgentFactory.create(tools, tradingProperties(environment), apiKey);
    }

    @Bean
    public TradingService tradingService(
            TradingRepository repository,
            @Qualifier("tradingEngine") AssistantEngine engine,
            TradingContext context,
            QuoteCache quotes) {
        return new TradingService(repository, engine, context, quotes, Clock.systemDefaultZone());
    }

    private static AgentProperties tradingProperties(Environment environment) {
        return new AgentProperties(
                null,
                Path.of(environment.getProperty("APP_SKILLS_DIR", "skills")),
                Path.of(environment.getProperty("APP_DATA_DIR", "./var")),
                14,
                "/prompts/trading.md",
                true);
    }
}
