package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.service.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * /undo — 撤销最近一轮 AI 操作的所有文件变更。
 * <p>
 * 执行流程:
 * <ol>
 *     <li>查找最近一个有文件变更的事务</li>
 *     <li>调用 FileHistoryService.rewindFiles() 批量回退</li>
 *     <li>删除事务起点之后的消息</li>
 *     <li>推送 undo_complete 通知前端刷新</li>
 * </ol>
 *
 * @see <a href="SPEC §3.3">命令系统</a>
 */
@Component
public class UndoCommandHandler implements Command {

    private static final Logger log = LoggerFactory.getLogger(UndoCommandHandler.class);

    private final FileHistoryService fileHistoryService;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public UndoCommandHandler(FileHistoryService fileHistoryService,
                              MessageRepository messageRepository,
                              SimpMessagingTemplate messagingTemplate) {
        this.fileHistoryService = fileHistoryService;
        this.messageRepository = messageRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Override public String getName() { return "undo"; }
    @Override public String getDescription() { return "Undo the last AI file changes"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return CommandResult.error("No active session.");
        }

        // 1. 查找最近有文件变更的事务
        Optional<FileHistoryService.TransactionRecord> lastTx =
                fileHistoryService.getLastTransaction(sessionId);

        if (lastTx.isEmpty()) {
            return CommandResult.text("Nothing to undo — no recent file changes found.");
        }

        FileHistoryService.TransactionRecord tx = lastTx.get();
        List<String> changedFiles = tx.changedFiles();

        if (changedFiles.isEmpty()) {
            // 事务存在但无文件变更，移除并返回
            fileHistoryService.removeLastTransaction(sessionId);
            return CommandResult.text("Nothing to undo — last transaction had no file changes.");
        }

        try {
            // 2. 批量回退文件到事务起点
            FileHistoryService.RewindResult result =
                    fileHistoryService.rewindFiles(sessionId, tx.messageId(), changedFiles);

            // 3. 删除事务起点之后的消息
            int deletedMessages = messageRepository.deleteAfterSeqNum(sessionId, tx.startSeqNum());
            log.info("/undo: sessionId={}, messageId={}, restoredFiles={}, deletedMessages={}",
                    sessionId, tx.messageId(), result.restoredFiles().size(), deletedMessages);

            // 4. 移除已撤销的事务记录
            fileHistoryService.removeLastTransaction(sessionId);

            // 5. 推送 undo_complete 通知前端刷新
            messagingTemplate.convertAndSend(
                    "/topic/session/" + sessionId,
                    Map.of(
                            "type", "undo_complete",
                            "restoredFiles", result.restoredFiles(),
                            "deletedMessages", deletedMessages,
                            "errors", result.errors()
                    ));

            // 6. 构建结果文本
            StringBuilder sb = new StringBuilder();
            sb.append("Undo complete — restored ").append(result.restoredFiles().size()).append(" file(s):");
            for (String file : result.restoredFiles()) {
                sb.append("\n  • ").append(file);
            }
            if (!result.errors().isEmpty()) {
                sb.append("\n\nWarnings:");
                for (String err : result.errors()) {
                    sb.append("\n  ⚠ ").append(err);
                }
            }
            if (deletedMessages > 0) {
                sb.append("\n\nRemoved ").append(deletedMessages).append(" message(s) from history.");
            }

            return CommandResult.text(sb.toString());

        } catch (Exception e) {
            log.error("/undo failed: sessionId={}, messageId={}", sessionId, tx.messageId(), e);
            return CommandResult.error("Undo failed: " + e.getMessage());
        }
    }
}
