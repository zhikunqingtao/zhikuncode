package com.aicodeassistant.skill;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublishOssSkillTest {

    @Test
    void bundledSkillIsExplicitOnlyAndCanCallOnlyPublicationOrClarificationTools() throws Exception {
        ClassPathResource resource = new ClassPathResource("skills/bundled/publish-oss.md");
        String markdown;
        try (var input = resource.getInputStream()) {
            markdown = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        SkillDefinition skill = SkillDefinition.fromMarkdown("publish-oss.md", markdown,
                SkillDefinition.SkillSource.BUNDLED, null);

        assertThat(skill.name()).isEqualTo("publish-oss");
        assertThat(skill.isUserInvocable()).isTrue();
        assertThat(skill.frontmatter().allowedTools())
                .containsExactlyElementsOf(List.of("PublishArtifact", "AskUserQuestion"));
        assertThat(skill.frontmatter().whenToUse()).contains("绝不自动上传");
        assertThat(skill.content())
                .contains("不得因文件生成", "仅调用一次 `PublishArtifact`", "永久公开地址",
                        "不得扫描或列举工作区", "精确路径", "没有记录时由发布策略现场校验")
                .doesNotContain("OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET");
    }
}
