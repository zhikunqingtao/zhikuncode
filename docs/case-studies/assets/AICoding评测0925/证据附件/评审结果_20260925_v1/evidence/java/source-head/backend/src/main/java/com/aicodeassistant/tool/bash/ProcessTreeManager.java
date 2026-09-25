package com.aicodeassistant.tool.bash;

import com.aicodeassistant.tool.process.OwnedProcess;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * ProcessTreeManager — 进程树管理器。
 * <p>
 * 终止已归属的进程范围，使用梯度终止策略:
 * <ol>
 *   <li>SIGTERM 所有子进程和主进程</li>
 *   <li>等待 gracePeriod</li>
 *   <li>如仍存活，SIGKILL 强制杀死</li>
 * </ol>
 * <p>
 * 复用 {@link OwnedProcess} 的启动归属和后代身份快照；普通 Process
 * 只能清理调用时仍可发现的后代，不能追溯已经失去父子关系的进程。
 */
@Component
public class ProcessTreeManager {

    /**
     * 杀死进程树：先 SIGTERM 等待 gracePeriod，再 SIGKILL。
     *
     * @param process     主进程
     * @param gracePeriod SIGTERM 后等待时间
     * @return true 如果已掌握的进程范围确认退出
     */
    public boolean destroyProcessTree(Process process, Duration gracePeriod) {
        return OwnedProcess.terminateTree(process, gracePeriod);
    }
}
