package com.aicodeassistant.tool.bash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 路径安全验证器 — 对齐原版 pathValidation.ts。
 * 
 * 核心功能：
 * 1. 路径规范化 + 符号链接解析
 * 2. 项目边界检查（禁止操作工作目录外的文件）
 * 3. 危险路径删除检测
 * 4. 输出重定向验证
 * 5. 进程替换检测
 */
@Component
public class PathValidator {

    private static final Logger log = LoggerFactory.getLogger(PathValidator.class);

    // ──── 命令操作类型分类（对齐原版 COMMAND_OPERATION_TYPE） ────
    
    public enum FileOperationType { READ, WRITE, READ_WRITE, NONE }

    /** 命令 → 操作类型映射（对齐原版 32 个 PathCommand） */
    private static final Map<String, FileOperationType> COMMAND_OPERATION_TYPE = Map.ofEntries(
        Map.entry("cd", FileOperationType.READ),
        Map.entry("ls", FileOperationType.READ),
        Map.entry("find", FileOperationType.READ),
        Map.entry("cat", FileOperationType.READ),
        Map.entry("head", FileOperationType.READ),
        Map.entry("tail", FileOperationType.READ),
        Map.entry("grep", FileOperationType.READ),
        Map.entry("rg", FileOperationType.READ),
        Map.entry("ag", FileOperationType.READ),
        Map.entry("diff", FileOperationType.READ),
        Map.entry("wc", FileOperationType.READ),
        Map.entry("file", FileOperationType.READ),
        Map.entry("stat", FileOperationType.READ),
        Map.entry("readlink", FileOperationType.READ),
        Map.entry("realpath", FileOperationType.READ),
        Map.entry("du", FileOperationType.READ),
        Map.entry("jq", FileOperationType.READ),
        Map.entry("mkdir", FileOperationType.WRITE),
        Map.entry("rm", FileOperationType.WRITE),
        Map.entry("rmdir", FileOperationType.WRITE),
        Map.entry("touch", FileOperationType.WRITE),
        Map.entry("mv", FileOperationType.WRITE),
        Map.entry("cp", FileOperationType.WRITE),
        Map.entry("ln", FileOperationType.WRITE),
        Map.entry("chmod", FileOperationType.WRITE),
        Map.entry("chown", FileOperationType.WRITE),
        Map.entry("sed", FileOperationType.READ_WRITE),
        Map.entry("awk", FileOperationType.READ_WRITE),
        Map.entry("tee", FileOperationType.READ_WRITE),
        Map.entry("git", FileOperationType.READ_WRITE),
        Map.entry("tar", FileOperationType.READ_WRITE),
        Map.entry("zip", FileOperationType.READ_WRITE)
    );

    // ──── 危险删除路径（对齐原版 checkDangerousRemovalPaths） ────
    
    private static final Set<String> DANGEROUS_REMOVAL_PATHS = Set.of(
        "/", "/bin", "/sbin", "/usr", "/usr/bin", "/usr/sbin",
        "/etc", "/var", "/tmp", "/opt", "/lib", "/lib64",
        "/boot", "/dev", "/proc", "/sys", "/run"
    );

    // ──── 受保护的隐藏目录 ────
    
    private static final Set<String> PROTECTED_HIDDEN_DIRS = Set.of(
        ".git", ".ssh", ".gnupg", ".aws", ".config", ".env"
    );

    /** POSIX end-of-options 标记 */
    private static final String END_OF_OPTIONS = "--";

    // ──── PATH_EXTRACTORS 路径提取器（对齐原版） ────

    /**
     * 从命令参数中提取路径参数。
     * 对齐原版 PATH_EXTRACTORS: Record<PathCommand, (args: string[]) => string[]>
     */
    public List<String> extractPaths(String command, List<String> args) {
        List<String> nonFlagArgs = filterOutFlags(args);
        FileOperationType opType = COMMAND_OPERATION_TYPE.getOrDefault(command, FileOperationType.NONE);
        
        if (opType == FileOperationType.NONE) return List.of();
        
        return switch (command) {
            case "cd", "mkdir", "rmdir", "touch" -> nonFlagArgs.isEmpty() ? List.of() : List.of(nonFlagArgs.getFirst());
            case "rm" -> nonFlagArgs; // rm 的所有非 flag 参数都是路径
            case "mv", "cp", "ln" -> nonFlagArgs; // 源和目标都需要验证
            case "cat", "head", "tail", "wc", "stat", "file", "readlink", "realpath", "du" -> nonFlagArgs;
            case "find" -> nonFlagArgs.isEmpty() ? List.of(".") : List.of(nonFlagArgs.getFirst()); // find 第一个参数是搜索路径
            case "grep", "rg", "ag" -> nonFlagArgs.size() > 1 ? nonFlagArgs.subList(1, nonFlagArgs.size()) : List.of();
            case "sed" -> extractSedPaths(args);
            case "chmod", "chown" -> nonFlagArgs.size() > 1 ? nonFlagArgs.subList(1, nonFlagArgs.size()) : List.of();
            case "git" -> extractGitPaths(args);
            default -> nonFlagArgs;
        };
    }

    /**
     * 核心：验证命令路径安全性。
     * 对齐原版 validateCommandPaths(command, args, cwd, context)。
     *
     * @param command  命令名
     * @param args     完整参数列表
     * @param cwd      当前工作目录
     * @param projectRoot 项目根目录（用于边界检查）
     * @return null 表示安全；非 null 为拒绝原因
     */
    public String validateCommandPaths(String command, List<String> args,
                                        Path cwd, Path projectRoot) {
        // 1. 提取路径参数
        List<String> paths = extractPaths(command, args);
        if (paths.isEmpty()) return null;
        
        FileOperationType opType = COMMAND_OPERATION_TYPE.getOrDefault(command, FileOperationType.NONE);
        
        for (String pathStr : paths) {
            if (pathStr == null || pathStr.isBlank()) continue;
            
            // 2. 路径规范化
            Path resolvedPath = resolvePath(pathStr, cwd);
            if (resolvedPath == null) continue;
            
            // 3. 符号链接解析
            Path realPath = resolveSymlink(resolvedPath);
            
            // 4. 项目边界检查（仅对写操作）
            if (opType == FileOperationType.WRITE || opType == FileOperationType.READ_WRITE) {
                String boundaryCheck = checkProjectBoundary(realPath, projectRoot);
                if (boundaryCheck != null) return boundaryCheck;
            }
            
            // 5. 危险删除路径检查
            if ("rm".equals(command)) {
                String dangerCheck = checkDangerousRemovalPaths(command, args, realPath);
                if (dangerCheck != null) return dangerCheck;
            }
            
            // 6. 受保护隐藏目录检查
            String hiddenCheck = checkProtectedHiddenDirs(realPath, opType);
            if (hiddenCheck != null) return hiddenCheck;
        }
        
        return null; // 全部通过
    }

    /**
     * 入口：路径约束检查。
     * 对齐原版 checkPathConstraints(input, cwd, context)。
     */
    public String checkPathConstraints(String fullCommand, Path cwd, Path projectRoot) {
        // 1. 输出重定向检查
        String redirectCheck = checkOutputRedirects(fullCommand, cwd, projectRoot);
        if (redirectCheck != null) return redirectCheck;
        
        // 2. 进程替换检测
        if (fullCommand.contains("<(") || fullCommand.contains(">(")) {
            return "Process substitution detected";
        }
        
        // 3. 复合命令 cd + 写操作检测
        String cdWriteCheck = checkCdPlusWrite(fullCommand, cwd, projectRoot);
        if (cdWriteCheck != null) return cdWriteCheck;
        
        return null;
    }

    // ──── 内部方法 ────

    /** 过滤掉 flag 参数，对齐原版 filterOutFlags */
    List<String> filterOutFlags(List<String> args) {
        List<String> result = new ArrayList<>();
        boolean endOfOptions = false;
        for (String arg : args) {
            if (END_OF_OPTIONS.equals(arg)) {
                endOfOptions = true;
                continue;
            }
            if (!endOfOptions && arg.startsWith("-")) continue;
            result.add(arg);
        }
        return result;
    }

    /** 路径规范化 */
    Path resolvePath(String pathStr, Path cwd) {
        try {
            Path path = Path.of(pathStr);
            if (!path.isAbsolute()) {
                path = cwd.resolve(path);
            }
            return path.normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 符号链接解析 — 对齐原版的 symlink 跟踪 */
    Path resolveSymlink(Path path) {
        try {
            if (Files.isSymbolicLink(path)) {
                Path target = Files.readSymbolicLink(path);
                if (!target.isAbsolute()) {
                    target = path.getParent().resolve(target).normalize();
                }
                log.debug("Symlink resolved: {} → {}", path, target);
                return target;
            }
            // 尝试 toRealPath 解析中间路径中的符号链接
            if (Files.exists(path)) {
                return path.toRealPath();
            }
            return path;
        } catch (IOException e) {
            return path;
        }
    }

    /** 项目边界检查 */
    String checkProjectBoundary(Path targetPath, Path projectRoot) {
        if (projectRoot == null) return null;
        Path normalizedTarget = targetPath.normalize();
        Path normalizedRoot = projectRoot.normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            return "Path outside project boundary: " + targetPath 
                   + " (project: " + projectRoot + ")";
        }
        return null;
    }

    /** 危险删除路径检测 — 对齐原版 checkDangerousRemovalPaths */
    String checkDangerousRemovalPaths(String command, List<String> args, Path resolvedPath) {
        String pathStr = resolvedPath.toString();
        boolean hasForce = args.stream().anyMatch(a -> a.startsWith("-") && a.contains("f"));
        boolean hasRecursive = args.stream().anyMatch(a -> a.startsWith("-") && (a.contains("r") || a.contains("R")));
        
        // 直接危险路径
        if (DANGEROUS_REMOVAL_PATHS.contains(pathStr)) {
            return "Refusing to remove system-critical path: " + pathStr;
        }
        
        // 用户主目录
        String home = System.getProperty("user.home");
        if (pathStr.equals(home) && (hasForce || hasRecursive)) {
            return "Refusing to remove home directory: " + pathStr;
        }
        
        // 根目录的直接子目录 + recursive + force
        if (resolvedPath.getNameCount() <= 1 && hasRecursive && hasForce) {
            return "Refusing recursive forced removal of top-level directory: " + pathStr;
        }
        
        return null;
    }

    /** 受保护隐藏目录检查 */
    String checkProtectedHiddenDirs(Path path, FileOperationType opType) {
        if (opType != FileOperationType.WRITE && opType != FileOperationType.READ_WRITE) return null;
        for (int i = 0; i < path.getNameCount(); i++) {
            String name = path.getName(i).toString();
            if (PROTECTED_HIDDEN_DIRS.contains(name)) {
                return "Write operation targets protected directory: " + name;
            }
        }
        return null;
    }

    /** 输出重定向检查 */
    String checkOutputRedirects(String command, Path cwd, Path projectRoot) {
        // 检测 > 和 >> 重定向
        java.util.regex.Matcher m = Pattern.compile("(?<!\\\\)[>]{1,2}\\s*(\\S+)")
            .matcher(command);
        while (m.find()) {
            String target = m.group(1);
            if (target.startsWith("/dev/")) continue; // /dev/null 等允许
            Path resolvedTarget = resolvePath(target, cwd);
            if (resolvedTarget != null && projectRoot != null) {
                String boundary = checkProjectBoundary(resolvedTarget, projectRoot);
                if (boundary != null) return "Output redirect " + boundary;
            }
        }
        return null;
    }

    /** cd + 写操作检测 */
    String checkCdPlusWrite(String command, Path cwd, Path projectRoot) {
        // 检测 cd /somewhere && rm/mv/cp 模式
        if (command.contains("cd ") && command.contains("&&")) {
            java.util.regex.Matcher cdMatcher = Pattern.compile("cd\\s+(\\S+)")
                .matcher(command);
            if (cdMatcher.find()) {
                String cdTarget = cdMatcher.group(1);
                Path newCwd = resolvePath(cdTarget, cwd);
                if (newCwd != null && projectRoot != null) {
                    String boundary = checkProjectBoundary(newCwd, projectRoot);
                    if (boundary != null) {
                        // 检查 && 后是否有写命令
                        String afterCd = command.substring(command.indexOf("&&") + 2).trim();
                        String firstCmd = afterCd.split("\\s+")[0];
                        FileOperationType op = COMMAND_OPERATION_TYPE
                            .getOrDefault(firstCmd, FileOperationType.NONE);
                        if (op == FileOperationType.WRITE || op == FileOperationType.READ_WRITE) {
                            return "cd to outside project + write operation: cd " 
                                   + cdTarget + " && " + firstCmd;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** sed 路径提取 — sed -i 的文件参数 */
    private List<String> extractSedPaths(List<String> args) {
        List<String> paths = new ArrayList<>();
        boolean hasInPlace = false;
        boolean skipNext = false;
        for (int i = 0; i < args.size(); i++) {
            if (skipNext) { skipNext = false; continue; }
            String arg = args.get(i);
            if ("-i".equals(arg) || arg.startsWith("-i")) hasInPlace = true;
            if ("-e".equals(arg) || "-f".equals(arg)) { skipNext = true; continue; }
            if (!arg.startsWith("-") && i > 0) paths.add(arg);
        }
        return hasInPlace ? paths : List.of(); // 只有 -i 模式才算写操作路径
    }

    /** git 路径提取 */
    private List<String> extractGitPaths(List<String> args) {
        // git 大部分操作都在当前目录，只提取显式路径参数
        return List.of();
    }
}
