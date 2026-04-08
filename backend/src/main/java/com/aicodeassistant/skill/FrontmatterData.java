package com.aicodeassistant.skill;

import java.util.List;
import java.util.Map;

/**
 * Skill Markdown 前置数据 — 对应源码 FrontmatterData 的 16 字段定义。
 * <p>
 * 从 Markdown 文件的 YAML front matter (--- 分隔符之间) 解析而来。
 *
 * @param description             技能描述 (null 时从 markdown 第一段落提取)
 * @param name                    显示名称 (覆盖文件名)
 * @param allowedTools            允许的工具列表
 * @param argumentHint            参数提示文本
 * @param arguments               参数定义列表
 * @param whenToUse               模型自动调用的条件描述
 * @param version                 版本号
 * @param model                   指定模型 ('inherit' | model name)
 * @param disableModelInvocation  禁止模型自动调用
 * @param userInvocable           用户可手动调用 (默认 true)
 * @param hooks                   钩子配置
 * @param effort                  推理努力等级
 * @param context                 执行上下文: 'inline' (默认) | 'fork'
 * @param agent                   关联代理名称 (仅 context='fork' 时有效)
 * @param paths                   文件路径 glob 模式列表
 * @param shell                   Shell 类型: 'bash' (默认) | 'powershell'
 * @see <a href="SPEC §4.7.3">Frontmatter 字段定义</a>
 */
public record FrontmatterData(
        String description,
        String name,
        List<String> allowedTools,
        String argumentHint,
        List<String> arguments,
        String whenToUse,
        String version,
        String model,
        boolean disableModelInvocation,
        boolean userInvocable,
        Map<String, Object> hooks,
        String effort,
        String context,
        String agent,
        List<String> paths,
        String shell
) {

    /**
     * 创建默认 FrontmatterData — 所有可选字段使用默认值。
     */
    public static FrontmatterData defaults() {
        return new FrontmatterData(
                null, null, List.of(), null, List.of(),
                null, null, null, false, true,
                Map.of(), null, "inline", null, List.of(), "bash"
        );
    }

    /**
     * 判断是否为 fork 执行模式。
     */
    public boolean isFork() {
        return "fork".equalsIgnoreCase(context);
    }

    /**
     * 获取有效模型 — 'inherit' 返回 null (使用父模型)。
     */
    public String resolvedModel() {
        if (model == null || "inherit".equalsIgnoreCase(model)) {
            return null;
        }
        return model;
    }
}
