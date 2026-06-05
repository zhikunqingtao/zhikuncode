package com.aicodeassistant.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PreviewStackDetector {
    private static final Logger log = LoggerFactory.getLogger(PreviewStackDetector.class);
    private final ObjectMapper objectMapper;

    public PreviewStackDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public StackInfo detect(Path workspace) {
        Path packageJson = workspace.resolve("package.json");
        if (!Files.exists(packageJson)) {
            // 检查是否有纯静态 index.html
            if (Files.exists(workspace.resolve("index.html"))) {
                return new StackInfo("static", 8080, "python3 -m http.server 8080");
            }
            return new StackInfo("unknown", 0, "");
        }

        try {
            JsonNode root = objectMapper.readTree(Files.readString(packageJson));
            JsonNode deps = root.path("dependencies");
            JsonNode devDeps = root.path("devDependencies");
            JsonNode scripts = root.path("scripts");

            // 检测 Vite
            if (hasDep(deps, "vite") || hasDep(devDeps, "vite")) {
                String devScript = scripts.path("dev").asText("");
                if (devScript.contains("vite") || hasDep(devDeps, "vite")) {
                    return new StackInfo("vite", 5173, "npm run dev");
                }
            }

            // 检测 Next.js
            if (hasDep(deps, "next") || hasDep(devDeps, "next")) {
                return new StackInfo("next", 3000, "npm run dev");
            }

            // 检测 Create React App
            if (hasDep(deps, "react-scripts") || hasDep(devDeps, "react-scripts")) {
                return new StackInfo("cra", 3000, "npm start");
            }

            // 有 package.json 但无法识别框架
            // 检查是否有 dev script
            if (scripts.has("dev")) {
                return new StackInfo("unknown", 3000, "npm run dev");
            }
            if (scripts.has("start")) {
                return new StackInfo("unknown", 3000, "npm start");
            }

            return new StackInfo("unknown", 0, "");

        } catch (IOException e) {
            log.warn("Failed to read package.json for stack detection: {}", e.getMessage());
            return new StackInfo("unknown", 0, "");
        }
    }

    private boolean hasDep(JsonNode depsNode, String packageName) {
        return depsNode != null && !depsNode.isMissingNode() && depsNode.has(packageName);
    }
}
