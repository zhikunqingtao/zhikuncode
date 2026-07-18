package com.aicodeassistant.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolGatewayArchitectureTest {
    @Test
    void bytecodeHasNoExecutionBypass() throws Exception {
        Path classes = Path.of("target/classes");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(classes)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".class")).toList()) {
                ClassReader reader = new ClassReader(Files.readAllBytes(path));
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    private String caller;
                    @Override public void visit(int version, int access, String name, String signature,
                                                String superName, String[] interfaces) {
                        caller = name;
                    }
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String invokedName,
                                                                 String invokedDescriptor, boolean isInterface) {
                                if ("com/aicodeassistant/tool/Tool".equals(owner) && "call".equals(invokedName)
                                        && !"com/aicodeassistant/authorization/ToolExecutionGateway".equals(caller)) {
                                    violations.add(caller + "#" + name + " invokes Tool.call");
                                }
                                if ("com/aicodeassistant/tool/ToolExecutionPipeline".equals(owner)
                                        && "execute".equals(invokedName)
                                        && !caller.startsWith("com/aicodeassistant/tool/StreamingToolExecutor")
                                        // The external MCP request/response adapter is already synchronous
                                        // and still enters the complete authorization pipeline.
                                        && !"com/aicodeassistant/mcp/server/McpServerToolHandler".equals(caller)) {
                                    violations.add(caller + "#" + name + " bypasses StreamingToolExecutor");
                                }
                                if ("com/aicodeassistant/hook/HookRegistry".equals(owner)
                                        && "register".equals(invokedName)
                                        && invokedDescriptor.endsWith("Ljava/lang/String;)V")) {
                                    violations.add(caller + "#" + name + " registers a hook without explicit role");
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertThat(violations).as("tool execution architecture bypasses").isEmpty();
    }
}
