/**
 * 报告文本格式化工具
 */

/**
 * 归一化判断逻辑文本，统一为 "1. xxx" 编号 + 换行的格式：
 * 1. "第X步"/"第X步：" 写法归一为 "X."
 * 2. 对未换行的 "N." 列表标记前插入换行——仅当标记紧跟句末标点/空白/文首/CJK 字符，
 *    且后跟中文或字母时才断行，避免误伤小数（95.2）、版本号（v1.2）等数字点号
 */
export const formatJudgmentLogic = (text: string): string => {
  if (!text) return ''
  let normalized = text
    // 全角数字/句点/空格归一为半角：LLM 偶发输出全角"１．内容"，与 SOP 的半角 "N. 内容" 不一致
    .replace(/[０-９]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0xfee0))
    .replace(/．/g, '.')
    .replace(/　/g, ' ')
  normalized = normalized.replace(/第\s*(\d+)\s*步\s*[:：]?\s*/g, '$1. ')
  // 列表标记断行：前置为文首/句末标点/空白/CJK，后跟中文或字母（排除小数、版本号后跟数字的情况）
  normalized = normalized.replace(
    /(^|[。；;！!？?\n])\s*(\d{1,2})\.(?=\s*[\u4e00-\u9fa5A-Za-z])/g,
    '$1\n$2. '
  )
  // "1.xxx2.xxx" 直接拼接的场景：CJK 字符后紧跟 "N." 也断行（数字开头后跟数字的已被上面的前瞻排除）
  normalized = normalized.replace(
    /(?<=[\u4e00-\u9fa5])(\d{1,2})\.(?=\s*[\u4e00-\u9fa5A-Za-z])/g,
    '\n$1. '
  )
  // 行首编号统一为 "N. "（单个半角空格），与推荐SOP 格式完全一致
  normalized = normalized.replace(/(^|\n)(\d{1,2})\.\s*/g, '$1$2. ')
  return normalized.replace(/\n{2,}/g, '\n').trim()
}
