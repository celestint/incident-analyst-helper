import request from './index'
import type { ApiResponse, EvaluationStats, DrillRow, DrillMetric } from '@/types'

export interface EvaluationQuery {
  /** 起始日期 yyyy-MM-dd（可选，排除更早数据） */
  since?: string
  /** 场景标签（可选，单看某标签桶） */
  tag?: string
  /** all=全量（与 tag 互斥）；缺省=仅正式数据（tag 为空） */
  scope?: string
}

export interface DrillQuery extends EvaluationQuery {
  metric: DrillMetric
  /** toolCalls 指标的分布桶（1-3/4-6/7-9/10+） */
  bucket?: string
  /** slow 指标取前 N（默认 5） */
  limit?: number
}

export const statsApi = {
  // 汇总指标
  evaluation(params: EvaluationQuery = {}): Promise<ApiResponse<EvaluationStats>> {
    return request.get('/stats/evaluation', { params })
  },

  // 按指标下钻明细
  drill(params: DrillQuery): Promise<ApiResponse<DrillRow[]>> {
    return request.get('/stats/evaluation/incidents', { params })
  },
}
