package com.lyl.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.UncheckedIOException;
import java.sql.*;
import java.util.stream.Collectors;

@Configuration
public class DataAnalyticsConfig {

    @Value("${analytics.db.path:./data/analytics.duckdb}")
    private String dbPath;

    @Value("${analytics.mock-data.path:classpath:mock-data}")
    private String mockDataPath;

    private Connection connection;

    @PostConstruct
    public void init() {
        try {
            // 加载 DuckDB JDBC 驱动
            Class.forName("org.duckdb.DuckDBDriver");

            // 为 dbPath 的父目录（按配置解析，默认 ./data）创建数据目录
            java.io.File dataDir = new java.io.File(dbPath).getAbsoluteFile().getParentFile();
            if (dataDir != null && !dataDir.exists()) {
                dataDir.mkdirs();
            }

            // 创建或连接 DuckDB
            this.connection = DriverManager.getConnection("jdbc:duckdb:" + dbPath);

            // 创建视图
            createViews();

            System.out.println("DuckDB initialized successfully at: " + dbPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize DuckDB", e);
        }
    }

    private void createViews() throws SQLException {
        String base = resolveMockDataDir();
        try (Statement stmt = connection.createStatement()) {
            // 日志视图
            stmt.execute("""
                CREATE OR REPLACE VIEW logs AS
                SELECT
                  log_id,
                  timestamp,
                  cmdb_id AS service,
                  log_name,
                  value
                FROM read_csv('%s')
            """.formatted(csvPath(base, "logs/log_*_0304.csv")));

            // 指标视图
            stmt.execute("""
                CREATE OR REPLACE VIEW metrics AS
                SELECT
                  timestamp,
                  cmdb_id AS service,
                  kpi_name,
                  value
                FROM read_csv('%s')
            """.formatted(csvPath(base, "metrics/metric_0304.csv")));

            // KPI 视图
            stmt.execute("""
                CREATE OR REPLACE VIEW kpi AS
                SELECT
                  timestamp,
                  tc AS service,
                  rr,
                  sr,
                  cnt,
                  mrt
                FROM read_csv('%s')
            """.formatted(csvPath(base, "metrics/kpi_0304.csv")));

            // Trace 视图
            stmt.execute("""
                CREATE OR REPLACE VIEW traces AS
                SELECT
                  timestamp / 1000000 AS timestamp,
                  cmdb_id AS service,
                  parent_id,
                  span_id,
                  trace_id,
                  duration
                FROM read_csv('%s')
            """.formatted(csvPath(base, "traces/trace_0304.csv")));

            System.out.println("DuckDB views created successfully");
        }
    }

    /**
     * 把 analytics.mock-data.path 解析为文件系统绝对路径。
     * classpath: 前缀依赖 classpath 是真实目录（IDE / mvn test / spring-boot:run 均满足）；
     * fat jar 部署时请在配置里直接给文件系统路径。
     */
    private String resolveMockDataDir() {
        String path = mockDataPath;
        try {
            if (path.startsWith("classpath:")) {
                path = new ClassPathResource(path.substring("classpath:".length())).getFile().getAbsolutePath();
            }
            File dir = new File(path);
            if (!dir.isDirectory()) {
                throw new IllegalStateException("mock-data 目录不存在: " + path
                        + "，请检查 analytics.mock-data.path 配置");
            }
            return dir.getAbsolutePath();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("无法从 classpath 解析 mock-data（fat jar 部署请改用 analytics.mock-data.path 指定文件系统路径）", e);
        }
    }

    private String csvPath(String base, String relative) {
        String full = base.replace('\\', '/') + "/" + relative;
        return full.replace("'", "''");
    }

    /**
     * 每次查询返回新连接；调用方负责关闭。
     * 不能返回共享的长连接——service 里 try-with-resources 会把它关掉，
     * 之后所有查询都会报 "Connection was closed"。
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:" + dbPath);
    }
}
