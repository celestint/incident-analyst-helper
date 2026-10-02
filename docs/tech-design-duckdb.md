
## DuckDB 视图设计

启动时创建视图，把多个 CSV 统一成逻辑表：

```sql
-- 日志：列名统一，一条 glob
CREATE VIEW logs AS
SELECT
  log_id,
  timestamp,
  cmdb_id AS service,
  log_name,
  value
FROM read_csv('mock-data/logs/log_*_0304.csv');

-- 指标：列名统一，一条 glob
CREATE VIEW metrics AS
SELECT
  timestamp,
  cmdb_id AS service,
  kpi_name,
  value
FROM read_csv('mock-data/metrics/metric_0304.csv');

-- KPI：服务列叫 tc，改名为 service 统一
CREATE VIEW kpi AS
SELECT
  timestamp,
  tc AS service,
  rr,
  sr,
  cnt,
  mrt
FROM read_csv('mock-data/metrics/kpi_0304.csv');

-- Trace：timestamp 是微秒，除以 1000000 转成秒，与其他数据源对齐
CREATE VIEW traces AS
SELECT
  timestamp / 1000000 AS timestamp,
  cmdb_id AS service,
  parent_id,
  span_id,
  trace_id,
  duration
FROM read_csv('mock-data/traces/trace_0304.csv');
```

## DuckDB 查询示例

1. 查日志
主查询：基于关键词搜日志内容
只有明确要看哪类日志时才加 log_name，一般直接搜 value：
```sql
SELECT timestamp, log_name, value
FROM logs
WHERE service = 'apache01'
  AND value LIKE '%OutOfMemoryError%'
  AND timestamp BETWEEN 1614829800 AND 1614830100
LIMIT 100;
```

2. 查指标
按指标名关键字过滤（如查内存）：
```sql
SELECT timestamp, kpi_name, value
FROM metrics
WHERE service = 'apache01'
  AND kpi_name LIKE '%Memory%'
  AND timestamp BETWEEN 1614829800 AND 1614830100
LIMIT 100;
```

3. 查 KPI（服务级汇总）
查响应时间和成功率：
```sql
SELECT timestamp, rr, sr, cnt, mrt
FROM kpi
WHERE service = 'apache01'
  AND timestamp BETWEEN 1614829800 AND 1614830100
LIMIT 100;
```

mrt 是平均响应时间，sr 是成功率，cnt 是请求数。适合判断“服务是否受影响”。

4. 查 Trace
主查询：按耗时排序找最慢的 Span
不还原调用树，只返回最慢的几个，Agent 用于定位“哪里变慢了”：
```sql
SELECT timestamp, trace_id, span_id, parent_id, duration
FROM traces
WHERE service = 'apache01'
  AND timestamp BETWEEN 1614829800 AND 1614830100
ORDER BY duration DESC
LIMIT 10;
```

可选：按 trace_id 拉取整条调用链
需要看某个具体 trace 的全部 span 时才用：
```sql
SELECT timestamp, trace_id, span_id, parent_id, duration
FROM traces
WHERE trace_id = '369-bcou-dle-way1-c514cf30-43410@0824-2f0e47a816-17492'
ORDER BY timestamp;
```

返回的是扁平 span 列表，调用树由 Java 工具层组装。

### 持久化与导入策略
DuckDB 以持久化模式运行，数据库文件为 ./data/analytics.duckdb。启动时检查视图是否存在：
- 如果不存在，执行 CREATE VIEW 定义视图（首次运行）。
- 如果已存在，跳过创建，直接使用。
视图是虚拟表，查询时才读取 CSV，不会在启动时全量导入。因此重启不会导致数据丢失，也不会重复导入。考虑到一次分析查询频繁，需要加速查询，可以在首次运行时把常用数据物化为表（CREATE TABLE AS SELECT），后续启动直接查表，不再扫描 CSV。
用.gitignore确保数据库文件不被远程上传到github





