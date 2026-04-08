package com.aicodeassistant.tool.task;

/**
 * 可取消工具接口 — 工具实现此接口以支持中断传播。
 * <p>
 * 三层中断传播 Layer 2: 工具级取消。
 * BashTool/REPLTool 等持有子进程的工具实现此接口，
 * 在 TaskCoordinator 取消任务时可被调用。
 *
 * @see <a href="SPEC §4.1.3a">TaskCoordinator 三层中断传播</a>
 */
public interface Cancellable {

    /** 取消/清理工具持有的资源（子进程等） */
    void cancel();
}
