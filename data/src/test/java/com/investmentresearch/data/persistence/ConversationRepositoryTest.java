package com.investmentresearch.data.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.port.ConversationRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationRepositoryTest {
    @TempDir Path tempDir;

    private String url;
    private SqlSession session;
    private ConversationRepository repository;

    @BeforeEach
    void setUp() {
        url =
                "jdbc:sqlite:"
                        + tempDir.resolve("app.db").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration/sqlite")
                .load()
                .migrate();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO));
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setEnvironment(
                new Environment(
                        "test",
                        new JdbcTransactionFactory(),
                        new UnpooledDataSource("org.sqlite.JDBC", url, null, null)));
        configuration.addMapper(ConversationMapper.class);
        configuration.addMapper(MessageMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        repository =
                new ConversationRepositoryImpl(
                        session.getMapper(ConversationMapper.class),
                        session.getMapper(MessageMapper.class),
                        new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    @Test
    void create_append_recent_usage_delete_round_trip() {
        long id = repository.create("贵州茅台 · 现在能买吗", "600519", "贵州茅台");

        Conversation created = repository.find(id).orElseThrow();
        assertThat(created.title()).isEqualTo("贵州茅台 · 现在能买吗");
        assertThat(created.primaryCode()).isEqualTo("600519");
        assertThat(created.primaryName()).isEqualTo("贵州茅台");
        assertThat(created.companies()).containsExactly("600519");
        assertThat(created.messageCount()).isZero();
        assertThat(created.usage()).isEmpty();
        assertThat(created.createdAt()).isNotBlank();
        assertThat(created.updatedAt()).isNotBlank();

        assertThat(repository.append(id, "user", Map.of("content", "现在能买吗"))).isEqualTo(1);
        assertThat(repository.append(id, "assistant", Map.of("content", "先看估值"))).isEqualTo(2);

        Conversation afterAppend = repository.find(id).orElseThrow();
        assertThat(afterAppend.messageCount()).isEqualTo(2);
        assertThat(afterAppend.updatedAt()).isGreaterThanOrEqualTo(created.updatedAt());

        Map<String, Object> usage = Map.of("input_tokens", 12, "output_tokens", 34);
        repository.updateUsage(id, usage);
        assertThat(repository.find(id).orElseThrow().usage()).containsAllEntriesOf(usage);

        assertThat(repository.recent("", 10)).extracting(Conversation::id).containsExactly(id);

        repository.delete(id);
        assertThat(repository.find(id)).isEmpty();
        assertThat(repository.messages(id)).isEmpty();
        assertThat(repository.recent("", 10)).isEmpty();
    }

    @Test
    void recent_matches_title_or_code_and_orders_by_updated_at() throws InterruptedException {
        long older = repository.create("贵州茅台 · 估值", "600519", "贵州茅台");
        long newer = repository.create("宁德时代 · 估值", "300750", "宁德时代");

        Thread.sleep(20);
        repository.append(older, "user", Map.of("content", "再看一眼"));

        assertThat(repository.recent("茅台", 10))
                .extracting(Conversation::id)
                .containsExactly(older);
        assertThat(repository.recent("300750", 10))
                .extracting(Conversation::id)
                .containsExactly(newer);
        assertThat(repository.recent("", 10))
                .extracting(Conversation::id)
                .containsExactly(older, newer);
    }

    @Test
    void messages_flatten_payload_in_seq_order() {
        long id = repository.create("标题", "600519", "贵州茅台");
        repository.append(id, "user", Map.of("content", "第一问", "n", 1));
        repository.append(id, "assistant", Map.of("content", "第一答", "tool_calls", List.of()));
        repository.append(id, "user", Map.of("content", "第二问"));

        List<StoredMessage> messages = repository.messages(id);

        assertThat(messages)
                .extracting(StoredMessage::role)
                .containsExactly("user", "assistant", "user");
        assertThat(messages)
                .extracting(message -> message.payload().get("content"))
                .containsExactly("第一问", "第一答", "第二问");
        assertThat(messages.get(1).payload()).containsEntry("tool_calls", List.of());
    }

    @Test
    void delete_removes_messages_too() {
        long id = repository.create("标题", "600519", "贵州茅台");
        repository.append(id, "user", Map.of("content", "你好"));
        repository.append(id, "assistant", Map.of("content", "在"));

        repository.delete(id);

        assertThat(repository.messages(id)).isEmpty();
        assertThat(repository.find(id)).isEmpty();
    }

    @Test
    void messages_skips_unreadable_payload_rows() throws Exception {
        long id = repository.create("标题", "600519", "贵州茅台");
        repository.append(id, "user", Map.of("content", "正常"));
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO message (conversation_id, seq, role, payload_json, created_at)"
                            + " VALUES ("
                            + id
                            + ", 99, 'assistant', 'not json', '2026-01-01T00:00:00.000000')");
        }

        assertThat(repository.messages(id))
                .extracting(StoredMessage::role)
                .containsExactly("user");
    }

    @Test
    void schema_is_created_on_a_fresh_database() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            assertThat(existsInSqliteMaster(statement, "table", "conversation")).isTrue();
            assertThat(existsInSqliteMaster(statement, "table", "message")).isTrue();
            assertThat(existsInSqliteMaster(statement, "index", "idx_message_conversation")).isTrue();
        }
    }

    private static boolean existsInSqliteMaster(Statement statement, String type, String name)
            throws Exception {
        try (ResultSet rows =
                statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = '"
                                + type
                                + "' AND name = '"
                                + name
                                + "'")) {
            return rows.next();
        }
    }
}
