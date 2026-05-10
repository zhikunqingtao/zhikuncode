"""Coordinator multi-agent 测试 fixture.

对应 Task3-5 差异化升级功能测试方案 §11.11 资产 #10。

提供两类常用 fixture：
    - ``coordinator_multi_agent_scenario``: 一个 4 阶段 Research→Synthesis→Implementation→
      Verification 工作流的事件序列，外加两个并行的 agent_spawn 事件，供 Python 侧
      回放/断言脚本使用。
    - ``multi_agent_mailbox_messages``: 收件箱写入样例，覆盖双 agent 跨邮箱通讯。

数据结构与 backend CoordinatorEventBus envelope 对齐：
    {type, ts, uuid, sessionId, workflowId, eventType, payload}
"""
from __future__ import annotations

import time
import uuid
from typing import Any, Dict, List

import pytest


def _envelope(
    *,
    session_id: str,
    workflow_id: str,
    event_type: str,
    payload: Dict[str, Any],
    ts: int | None = None,
) -> Dict[str, Any]:
    return {
        "type": "coordinator_event",
        "ts": ts or int(time.time() * 1000),
        "uuid": str(uuid.uuid4()),
        "sessionId": session_id,
        "workflowId": workflow_id,
        "eventType": event_type,
        "payload": payload,
    }


@pytest.fixture
def coordinator_multi_agent_scenario() -> List[Dict[str, Any]]:
    """返回 4 阶段 + 2 并行 agent_spawn + 1 mailbox_write 的完整事件序列。"""
    session_id = "sess-mtag-1"
    workflow_id = "wf-mtag-1"
    base_ts = int(time.time() * 1000)

    events: List[Dict[str, Any]] = []
    phases = [
        ("", "PLANNING"),
        ("PLANNING", "Research"),
        ("Research", "Synthesis"),
        ("Synthesis", "Implementation"),
        ("Implementation", "Verification"),
    ]
    for idx, (prev, nxt) in enumerate(phases):
        events.append(
            _envelope(
                session_id=session_id,
                workflow_id=workflow_id,
                event_type="phase_transition",
                payload={"previousPhase": prev, "nextPhase": nxt},
                ts=base_ts + idx * 1000,
            )
        )

    # 并行 agent_spawn —— 同 phase，两秒以内起
    events.append(
        _envelope(
            session_id=session_id,
            workflow_id=workflow_id,
            event_type="mailbox_write",
            payload={
                "senderId": "coordinator",
                "recipientId": "agent-researcher",
                "contentLength": 128,
            },
            ts=base_ts + 6000,
        )
    )
    events.append(
        _envelope(
            session_id=session_id,
            workflow_id=workflow_id,
            event_type="mailbox_write",
            payload={
                "senderId": "coordinator",
                "recipientId": "agent-synthesizer",
                "contentLength": 96,
            },
            ts=base_ts + 6200,
        )
    )
    return events


@pytest.fixture
def multi_agent_mailbox_messages() -> List[Dict[str, Any]]:
    """仅收件箱类事件子集，用于 mailbox 隔离与截断断言。"""
    return [
        {
            "senderId": "agent-a",
            "recipientId": "agent-b",
            "content": "hello",
            "contentLength": 5,
        },
        {
            "senderId": "agent-b",
            "recipientId": "agent-a",
            "content": "x" * 1024,
            "contentLength": 1024,
        },
        {
            # content=null 兜底 —— 对齐 CoordinatorEventBus.truncate("") 语义
            "senderId": "agent-c",
            "recipientId": "agent-a",
            "content": None,
            "contentLength": 0,
        },
    ]
