package com.aicodeassistant.artifact.meoo;

/** Stable, non-secret error codes; provider output is never included in exceptions. */
public final class MeooException extends RuntimeException {
    private final String code;
    public MeooException(String code) { super(code); this.code = code; }
    public String code() { return code; }
    public static String guidance(String code) {
        if(code==null) return "请检查秒悟项目状态，不要自动重发。";
        return switch(code) {
            case "MEOO_DISABLED" -> "请部署管理员启用 ZHIKUN_MEOO_ENABLED 并重启服务。";
            case "MEOO_CREDENTIALS_UNAVAILABLE", "MEOO_CREDENTIAL_PERMISSIONS", "MEOO_ACCOUNT_CREDENTIAL_REQUIRED", "MEOO_ACCOUNT_ID_REQUIRED", "MEOO_CREDENTIALS_INVALID", "MEOO_AUTH_FAILED" -> "请管理员检查官方账号 credentials.json、文件权限及登录状态；不要把密钥发进聊天。";
            case "MEOO_IMAGE_SCRIPTS_REQUIRED" -> "先在普通开发流程中补齐 scripts/setup.sh 与 scripts/start.sh，验证后重新请求发布。";
            case "MEOO_PORT_CONFIGURATION_REQUIRED" -> "启动服务必须监听 0.0.0.0:${PORT:-9000}，请先适配并验证。";
            case "MEOO_PERSISTENT_STORAGE_ADAPTATION_REQUIRED" -> "项目依赖本地数据库；秒悟容器磁盘不作为持久化存储，请先适配外部存储。";
            case "MEOO_APP_ENV_REQUIRED" -> "项目需要额外运行变量或密钥；新站点不会自动继承这些配置，请先完成应用适配。";
            case "MEOO_MULTISERVICE_UNSUPPORTED", "MEOO_RUNTIME_UNSUPPORTED" -> "项目含不支持的运行环境或多个服务，请先适配为平台支持的单服务应用。";
            case "MEOO_STATIC_DIRECTORY_REQUIRED", "MEOO_STATIC_RESOURCE_MISSING", "MEOO_INDEX_REQUIRED" -> "请选择包含 index.html 及全部本地依赖的静态目录，前端工程须先构建。";
            case "MEOO_VERIFICATION_REQUIRED", "MEOO_VERIFICATION_WORKSPACE_MISMATCH", "MEOO_VERIFICATION_STALE" -> "请用 VerifyJourney 的 publication_path 与 publication_runtime 验证精确发布内容，并提供通过的 verification_id。";
            case "MEOO_SNAPSHOT_CHANGED", "MEOO_APPROVED_SNAPSHOT_REQUIRED" -> "发布内容与授权不一致，请重新检查、验证并完成本次授权。";
            case "MEOO_SENSITIVE_CONTENT" -> "文件包含疑似密钥或连接密码，请移除后重新检查；不要把凭证上传到站点。";
            case "MEOO_QUOTA_EXCEEDED" -> "请在秒悟平台检查账号额度；不会自动购买额度或创建另一个项目重试。";
            case "MEOO_BUILD_FAILED" -> "请到项目设置查看远端构建结果，修复后由用户重新发起发布。";
            case "MEOO_ACCESS_UNVERIFIED" -> "部署已完成，请检查项目访问权限与就绪状态；尚不能确认匿名访问。";
            case "MEOO_CLI_VERSION_MISMATCH", "MEOO_CLI_UNAVAILABLE" -> "请管理员预先安装官方 @aliyun-meoo/cli@0.5.3 并配置可执行路径。";
            case "MEOO_IGNORE_SELF_EXCLUDED" -> "请在 .dockerignore 最后一行添加 !/.dockerignore，保留打包规则文件后重新验证。";
            case "MEOO_IGNORE_RULE_UNSUPPORTED" -> "请简化 .dockerignore 的字符类或转义规则后重新检查；支持星号、双星号、问号及否定规则。";
            default -> "请检查发布路径、配置及项目状态；失败或远端结果不明时不会自动重发。";
        };
    }
}
