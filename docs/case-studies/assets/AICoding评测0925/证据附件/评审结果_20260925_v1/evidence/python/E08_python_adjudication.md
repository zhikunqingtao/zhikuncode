# E08 Python 相关追加裁决（独立事实底稿完成后阅读报告）

此补记不修改 `ground_truth_python.md` 的独立结论。阅读范围：冻结的 `inputs/E08.md` 中 Python 判断/3.1探针描述。未取得报告所称 `/tmp/jdelay.py`、`/tmp/mech.py`：两个路径当前不存在，在评测目录及/tmp可读文件名搜索亦无结果，因此不假装审核过原脚本。

1. **3.1核心watcher取消被真实AnyIO取消作用域吞掉、finally无界gather导致HTTP挂起：真阳性。** 独立GT-PY-01和主审复跑、真实Uvicorn A/B均证明；即便报告原脚本缺失，机制和现实可达性已有其他独立证据。给较高实质发现权重合理。
2. 报告正文“特定时序、多秒正常Journey通常安全”是合适的边界。其5/5 `delay=0`明确模拟 `_create_context_for_journey` 不发生任何await让步；而真正成功创建必经 `create_task(new_context)` 和 `await shield(...)`，所以不能只凭该模拟把“缓存页面/单步screenshot”认定为已证明的生产触发路径。真实自然触发由独立探针补足：capacity/duplicate等立即拒绝在首个driver await前抛错。不否认其他取消落点理论上也可能触发，只区分已演示与未演示。
3. 摘要“没有一条测试传入http_request”和正文“全部单参”不准确；现有 `test_disconnect_cancels_work_and_closes_context` 传了SimpleNamespace。正文下一项也承认这一点。正确核心是**未覆盖真实Starlette Request/ASGI取消作用域**，不应让措辞错误抹消有效测试缺口发现。
4. 3.1建议有部分正确方向：改接收模型、让watcher明确退出、用有界等待防HTTP被清理拖死并加真实ASGI测试均合理。但单纯超时“放弃watcher”仍会留后台task；直接删watcher会丢主动断连语义（ASGI/uvicorn并不自动替路由取消业务协程）。建议是方向稿，不等于已验证补丁。
5. 3.3容量拒绝的事实正确，也明确承认可能更安全。但它是本提交有意不驱逐他人/将Journey纳入统一上限的保护策略；单列“中危功能破坏”需要错误映射/等待政策等具体需求支持。独立探针确实发现500和挂起，报告3.3没有具体推出这两个结果，不能自动给它全部问题信用。
6. **3.6将Request=None改Optional[Request]=None是有害修复建议**。已用相同环境独立复核：`Request = None`路由正常注册；`Optional[Request] = None`触发FastAPIError “Invalid args for response field”，因为框架未将这个Optional类型识别为Request注入。见 `probe_request_annotation.log`。原标注没有已证实的运行时缺陷。此错误独立于3.1真阳性，应单独扣建议可靠性，而非否定3.1。
7. 4.3历史间歇测试失败：我只完成一次聚焦65用例，均通过；没有重跑50次，也没有拿到报告历史日志证明或否定其记录。报告把它降级为需关注相对克制。不能将未复现当已证实实现race，也不能将一次通过当反证。
