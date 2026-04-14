package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class QuickCommands {

    @Bean
    public Command searchCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "search"; }
            @Override public List<String> getAliases() { return List.of("find", "grep"); }
            @Override public String getDescription() { return "在项目中搜索代码或文本"; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Search the codebase for: " + args);
            }
        };
    }

    @Bean
    public Command blameCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "blame"; }
            @Override public List<String> getAliases() { return List.of(); }
            @Override public String getDescription() { return "查看指定文件的 Git blame 信息"; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String target = (args == null || args.isBlank()) ? "当前文件" : args;
                return CommandResult.text("Show git blame for: " + target);
            }
        };
    }

    @Bean
    public Command symbolsCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "symbols"; }
            @Override public List<String> getAliases() { return List.of("sym"); }
            @Override public String getDescription() { return "列出文件或项目中的符号（类、方法、变量）"; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String scope = (args == null || args.isBlank()) ? "." : args;
                return CommandResult.text("List all symbols in: " + scope);
            }
        };
    }
}
