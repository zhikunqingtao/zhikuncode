/**
 * PromptComposerChips — 桌面输入区 mini-chip 行（权限模式 + 模型选择）
 *
 * 仅桌面分支渲染（index.tsx isMobile=false）；每个 chip 内嵌透明原生
 * select 承担交互，视觉为令牌化胶囊。
 *
 * 行为复用出处：
 * - 权限/模型切换：乐观更新后校验发送结果——无绑定会话或 WS 发送失败/抛错时
 *   回滚本地 store 并经 notificationStore 推送错误通知，失败处理与文案风格
 *   对齐 components/dialog/SettingsPanel.tsx handlePermissionModeChange，
 *   避免 chip 显示与服务端实际状态发散（后端枚举大写）；
 * - 模型切换：数据复用 components/layout/Header.tsx 模型 select 逻辑
 *   （loaded=false 时不主动 fetch，Header 已 fetch）；defaultModel 偏好经
 *   saveConfig 持久化（createSession 读取 configStore.defaultModel），
 *   不属于本次回滚范围。
 * 运行中/压缩中 chips 不禁用（与 Header 模型 select 现状一致）。
 */

import React from 'react';
import { ChevronDown, Shield } from 'lucide-react';
import { PERMISSION_MODES, type PermissionMode } from '@/types';
import { usePermissionStore } from '@/store/permissionStore';
import { useSessionStore } from '@/store/sessionStore';
import { useModelStore } from '@/store/modelStore';
import { useConfigStore } from '@/store/configStore';
import { useNotificationStore } from '@/store/notificationStore';
import { isWsConnected, sendSetModel, sendSetPermissionMode } from '@/api/stompClient';
import { isSessionBound } from '@/api/dispatch';
import { getPermissionModeLabel } from '@/components/layout/StatusBar';

/** 两个 chip 共用的胶囊壳（v2 令牌：hairline + surface2 + hover2 + accent2-ring） */
const CHIP_SHELL =
    'relative inline-flex h-7 items-center gap-1.5 rounded-full border border-hairline ' +
    'bg-surface2 px-2.5 text-xs text-t2 transition-interactive duration-fast ' +
    'hover:bg-hover2 hover:text-t1 focus-within:ring-[3px] focus-within:ring-accent2-ring';

/** chip 内嵌的透明原生 select（承担全部交互与键盘可达性） */
const OVERLAY_SELECT =
    'absolute inset-0 w-full cursor-pointer opacity-0';

/** 权限模式 chip：Shield + 当前模式名 + ChevronDown */
export const PermissionModeChip: React.FC = () => {
    const { permissionMode, setPermissionMode } = usePermissionStore();
    const sessionId = useSessionStore(s => s.sessionId);
    const addNotification = useNotificationStore(s => s.addNotification);
    const hasBoundSession = Boolean(sessionId && isSessionBound(sessionId));

    // 乐观更新 + 失败回滚（对齐 SettingsPanel.tsx handlePermissionModeChange，见文件头注释）
    const handleChange = (mode: PermissionMode) => {
        const prevMode = permissionMode;
        setPermissionMode(mode);
        if (!hasBoundSession) {
            setPermissionMode(prevMode);
            addNotification({
                key: 'permission-mode-no-session',
                level: 'error',
                message: '请先创建或选择会话，再切换权限模式',
            });
            return;
        }
        let sent = false;
        try {
            // 同步到后端，后端枚举使用大写值
            sent = sendSetPermissionMode(mode.toUpperCase());
        } catch {
            sent = false;
        }
        if (!sent) {
            setPermissionMode(prevMode);
            addNotification({
                key: 'permission-mode-send-failed',
                level: 'error',
                message: '权限模式切换发送失败，请检查连接后重试',
            });
        }
    };

    return (
        <span className={CHIP_SHELL}>
            <Shield size={12} aria-hidden="true" />
            <span>{getPermissionModeLabel(permissionMode)}</span>
            <ChevronDown size={12} aria-hidden="true" />
            <select
                aria-label="权限模式"
                value={permissionMode}
                onChange={e => handleChange(e.target.value as PermissionMode)}
                className={OVERLAY_SELECT}
            >
                {PERMISSION_MODES.map(mode => (
                    <option key={mode} value={mode}>
                        {getPermissionModeLabel(mode)}
                    </option>
                ))}
            </select>
        </span>
    );
};

/** 回滚会话模型（setModel 仅接受 string，prevModel 可能为 null，故走 setState 精确还原） */
const rollbackModel = (prevModel: string | null): void => {
    useSessionStore.setState({ model: prevModel });
};

/** 模型 chip：accent 点 + 当前模型名 + ChevronDown */
export const ModelChip: React.FC = () => {
    const { sessionId, model, setModel } = useSessionStore();
    const {
        models: availableModels,
        loading: modelsLoading,
        error: modelsError,
    } = useModelStore();
    const addNotification = useNotificationStore(s => s.addNotification);
    const hasBoundSession = Boolean(sessionId && isSessionBound(sessionId));

    // 乐观更新 + 失败回滚（与 PermissionModeChip 同一策略，见文件头注释）
    const handleChange = (newModel: string) => {
        if (!newModel) return;
        const prevModel = model;
        setModel(newModel);
        // defaultModel 偏好持久化不在回滚范围内（createSession 读取该值）
        void useConfigStore.getState().saveConfig({ defaultModel: newModel });
        if (!hasBoundSession) {
            rollbackModel(prevModel);
            addNotification({
                key: 'model-no-session',
                level: 'error',
                message: '请先创建或选择会话，再切换模型',
            });
            return;
        }
        // sendSetModel 无返回值（sendSetPermissionMode 返回 boolean）：
        // 以 WS 连接状态 + 异常捕获判定发送失败
        let sendFailed = false;
        try {
            if (isWsConnected()) {
                sendSetModel(newModel);
            } else {
                sendFailed = true;
            }
        } catch {
            sendFailed = true;
        }
        if (sendFailed) {
            rollbackModel(prevModel);
            addNotification({
                key: 'model-send-failed',
                level: 'error',
                message: '模型切换发送失败，请检查连接后重试',
            });
        }
    };

    const currentLabel = availableModels.find(m => m.id === model)?.displayName
        ?? model
        ?? (modelsLoading
            ? '模型加载中…'
            : modelsError
            ? '模型列表加载失败'
            : '暂无可用模型');

    return (
        <span className={CHIP_SHELL}>
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent2" aria-hidden="true" />
            <span className="max-w-[140px] truncate">{currentLabel}</span>
            <ChevronDown size={12} aria-hidden="true" />
            <select
                aria-label="模型选择"
                value={model || ''}
                onChange={(e) => handleChange(e.target.value)}
                disabled={modelsLoading || availableModels.length === 0}
                className={OVERLAY_SELECT}
            >
                {availableModels.length === 0 && (
                    <option value="">
                        {modelsLoading ? '模型加载中…'
                            : modelsError ? '模型列表加载失败' : '暂无可用模型'}
                    </option>
                )}
                {availableModels.map(m => (
                    <option key={m.id} value={m.id}>{m.displayName}</option>
                ))}
            </select>
        </span>
    );
};
