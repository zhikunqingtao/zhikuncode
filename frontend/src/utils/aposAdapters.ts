import type { RunChecksResponse, RiskAssessment } from '@/types/apos';
import { computeSignal } from '@/store/insightStore';

/**
 * 将后端 RunChecksResponse 转换为前端 RiskAssessment。
 * 从 results[] 中按 check 类型拆出各维度，再通过 computeSignal() 计算信号。
 */
export function mapRunChecksResponseToRiskAssessment(response: RunChecksResponse): RiskAssessment {
  const results = response.results ?? [];
  const tsResult = results.find(r => r.check === 'typescript');
  const lintResult = results.find(r => r.check === 'eslint');
  const testResult = results.find(r => r.check === 'test_match');

  const deterministic: RiskAssessment['deterministic'] = {
    typeCheck: {
      passed: tsResult?.passed ?? true,
      errorCount: tsResult?.errors?.length ?? 0,
      details: tsResult?.errors?.map(e => `${e.file}:${e.line} ${e.message}`).join('\n') ?? '',
    },
    lint: {
      passed: lintResult?.passed ?? true,
      errorCount: lintResult?.errors?.length ?? 0,
      warningCount: lintResult?.warnings?.length ?? 0,
    },
    tests: {
      passed: testResult?.passed ?? true,
      passedCount: testResult?.passed ? 1 : 0,
      failedCount: testResult?.passed === false ? (testResult?.errors?.length ?? 1) : 0,
      coveragePercent: undefined,
    },
  };

  // 从所有结果中收集受影响的文件列表（去重）
  const filesAffected = results
    .flatMap(r => [...(r.errors ?? []), ...(r.warnings ?? [])].map(i => i?.file).filter(Boolean))
    .filter((f, i, arr) => arr.indexOf(f) === i);

  const heuristic: RiskAssessment['heuristic'] = {
    affectedApiCount: 0,
    indirectImpactCount: 0,
    potentialImpactCount: 0,
    hasHighConfidenceImpact: false,
    truncated: results.some(r => r.check === 'timeout'),
    filesAffected,
  };

  // 使用纯规则引擎计算 signal
  const { signal, reason } = computeSignal({ deterministic, heuristic });

  return {
    deterministic,
    heuristic,
    signal,
    reason,
  };
}
