# 内存异常排查

## 适用告警
- 内存使用率高 / HighMemoryUsage

## 排查步骤

### 第 1 步：查询内存指标
- 工具：getMetrics
- 参数：metric=memory
- 目的：确认内存使用率是否持续超过 90%

### 第 2 步：查询错误日志
- 工具：getLogs
- 参数：keyword=OutOfMemoryError
- 目的：确认是否存在 OOM 报错

### 第 3 步：查询调用链
- 工具：getTrace
- 参数：无
- 目的：确认下游调用耗时是否受影响

## 证据规则

| 条件 | 结论 |
|---|---|
| memory > 90 | 内存资源饱和 |
| 日志包含 OutOfMemoryError | 存在内存泄漏 |
| latency > baseline | 下游调用受影响 |

## 推荐操作
1. 检查 该服务 上的异常进程
2. 如确认异常，考虑重启该服务