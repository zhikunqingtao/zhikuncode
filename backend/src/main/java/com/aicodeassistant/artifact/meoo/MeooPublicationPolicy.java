package com.aicodeassistant.artifact.meoo;

import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.verify.EvidenceStore;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.io.*;

@Service
public class MeooPublicationPolicy {
    private static final Set<String> STATIC_EXT = Set.of("html","htm","css","js","mjs","json","geojson","png","jpg","jpeg","gif","svg","webp","ico","avif","woff","woff2","ttf","otf","eot","wasm","glb","gltf","bin","hdr","ktx2","mp3","mp4","ogg","wav","webm","pdf","txt","xml","webmanifest","vert","frag");
    private static final Set<String> EXCLUDED = Set.of(".npmrc",".pypirc",".netrc",".git-credentials",".htpasswd",".docker",".azure",".gcloud",".git",".svn",".hg",".ssh",".aws",".meoo",".codex",".zhikun",".ai-code-assistant","node_modules","__pycache__",".venv","venv",".cache","coverage","test-results","playwright-report","preview","screenshots","credentials","secrets",".DS_Store");
    private static final Pattern SECRET = Pattern.compile("(?:meoo_ak_|ats_|sk-ant-|sk-|ghp_|LTAI)[A-Za-z0-9_-]{16,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?i:(?:api[_-]?key|access[_-]?key|secret|password|token|credential)\\s*[=:]\\s*['\"]?[A-Za-z0-9_./+\\-=]{16,})|(?:postgres(?:ql)?|mysql|mongodb|redis)://[^\\s:@]+:[^\\s@]+@");
    private final MeooPublishProperties properties;
    private final EvidenceStore evidence;
    public MeooPublicationPolicy(MeooPublishProperties properties, EvidenceStore evidence) { this.properties=properties; this.evidence=evidence; }
    public record FileFact(String relativePath, Path source, long size, String sha256) {}
    public record Snapshot(Path root, String relativePath, String runtime, String name, String account,
                           List<FileFact> files, long size, String sha256, String verificationId) {
        public Map<String,Object> facts() { return Map.of("root",root.toString(),"path",relativePath,"runtime",runtime,"name",name,"account",account,"sha256",sha256,"size",size,"fileCount",files.size(),"verificationId",verificationId); }
        public String summary() { return "创建新的秒悟公网应用（占用项目额度）\n应用: "+name+"\n模式: "+runtime+"\n路径: "+(relativePath.isEmpty()?root.toString():relativePath)+"\n文件: "+files.size()+" / "+size+" bytes\nSHA-256: "+sha256+"\n账号: "+account+(runtime.equals("image")?"\n上传源码并在云端执行构建和启动脚本":"\n仅上传静态产物，不同步云端沙箱"); }
    }
    public Snapshot inspect(ToolInput input, ToolUseContext context, boolean requireVerification) {
        String account=properties.credential().account();
        String runtime=input.getString("runtime", "");
        if(!Set.of("static","image").contains(runtime)) throw error("MEOO_RUNTIME_REQUIRED");
        String requested=input.getString("path", "");
        if(requested.isBlank()) throw error("MEOO_PATH_REQUIRED");
        try {
            Path workspace=Path.of(context.workingDirectory()).toRealPath();
            Path raw=Path.of(requested);
            for(Path part:raw) if(part.toString().equals("..")) throw error("MEOO_PATH_ESCAPE");
            Path target=(raw.isAbsolute()?raw:workspace.resolve(raw)).normalize();
            if(!target.startsWith(workspace)) throw error("MEOO_PATH_ESCAPE");
            rejectLinks(workspace,target);
            boolean single=Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS);
            if(!single && !Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS)) throw error("MEOO_PATH_NOT_FOUND");
            if(single && (!runtime.equals("static") || !target.getFileName().toString().toLowerCase().endsWith(".html"))) throw error("MEOO_STATIC_HTML_REQUIRED");
            Path root=single?target.getParent():target;
            String name=input.getString("name",single?target.getFileName().toString():root.getFileName().toString()).strip();
            if(name.isEmpty() || name.length()>100 || name.chars().anyMatch(Character::isISOControl)) throw error("MEOO_NAME_INVALID");
            List<String> rules=List.of("dist","build",".next","*.log","Thumbs.db",".core.*","core");
            Path ignoreFile=root.resolve(".dockerignore");
            if(runtime.equals("image") && Files.exists(ignoreFile)) {
                rejectLinks(root,ignoreFile);
                if(Files.size(ignoreFile)>65536) throw error("MEOO_IGNORE_TOO_LARGE");
                rules=Files.readAllLines(ignoreFile);
            }
            DockerIgnore ignore=new DockerIgnore(runtime.equals("image")?rules:List.of());
            // Without this file the CLI switches back to its default excludes.
            if(runtime.equals("image") && Files.exists(ignoreFile) && ignore.ignored(".dockerignore",false))
                throw error("MEOO_IGNORE_SELF_EXCLUDED");
            List<Path> candidates;
            if(single) candidates=List.of(target);
            else {
                candidates=new ArrayList<>();
                Files.walkFileTree(root,new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attrs) {
                        if(dir.equals(root)) return FileVisitResult.CONTINUE;
                        String relative=root.relativize(dir).toString().replace('\\','/');
                        return forbidden(relative) || ignore.ignored(relative,true) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) {
                        candidates.add(file);
                        if(candidates.size()>100000) throw error("MEOO_TOO_MANY_FILES");
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            if(candidates.size()>100000) throw error("MEOO_TOO_MANY_FILES");
            List<FileFact> files=new ArrayList<>(); long total=0;
            for(Path p:candidates) {
                String relative=root.relativize(p).toString().replace('\\','/');
                if(forbidden(relative) || Files.isSymbolicLink(p)) continue;
                if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) continue;
                if(ignore.ignored(relative,false)) continue;
                if(runtime.equals("static") && !staticAsset(relative)) continue;
                rejectLinks(root,p);
                long size=Files.size(p); total+=size;
                if(total>properties.getMaxBytes() || files.size()>=properties.getMaxFiles()) throw error("MEOO_PACKAGE_LIMIT");
                scanSecrets(p);
                files.add(new FileFact(single?"index.html":relative,p,size,hash(p)));
            }
            files.sort(Comparator.comparing(FileFact::relativePath));
            if(files.isEmpty()) throw error("MEOO_EMPTY_PACKAGE");
            Set<String> names=new HashSet<>(); files.forEach(f->names.add(f.relativePath()));
            if(runtime.equals("static")) {
                if(!names.contains("index.html")) throw error("MEOO_INDEX_REQUIRED");
                validateStatic(files,names,single);
            } else validateImage(root,names,files);
            StringBuilder manifest=new StringBuilder();
            for(FileFact f:files) manifest.append(f.relativePath()).append('\0').append(f.size()).append('\0').append(f.sha256()).append('\n');
            String verification=input.getString("verification_id", "");
            String digest=hash(manifest.toString().getBytes(StandardCharsets.UTF_8));
            if(requireVerification || !verification.isBlank()) checkEvidence(verification,root,files,digest,runtime);
            return new Snapshot(root,workspace.relativize(target).toString(),runtime,name,account,List.copyOf(files),total,digest,verification);
        } catch(MeooException e) { throw e; }
        catch(Exception e) { throw error("MEOO_INSPECTION_FAILED"); }
    }
    private void checkEvidence(String id, Path root, List<FileFact> files, String digest, String runtime) throws IOException {
        if(id.isBlank()) throw error("MEOO_VERIFICATION_REQUIRED");
        var bundle=evidence.findById(id).orElseThrow(()->error("MEOO_VERIFICATION_REQUIRED"));
        if(!"verified".equals(bundle.verdict()) || bundle.createdAt()==null)
            throw error("MEOO_VERIFICATION_REQUIRED");
        boolean bound=bundle.items()!=null && bundle.items().stream().anyMatch(item->item.meta()!=null && item.meta().get("workspace") instanceof String w && root.equals(Path.of(w)) && digest.equals(item.meta().get("meooSnapshotSha256")) && runtime.equals(item.meta().get("meooRuntime")));
        if(!bound) throw error("MEOO_VERIFICATION_WORKSPACE_MISMATCH");
        for(FileFact f:files) if(Files.getLastModifiedTime(f.source()).toInstant().isAfter(bundle.createdAt())) throw error("MEOO_VERIFICATION_STALE");
    }
    private static void validateImage(Path root, Set<String> names, List<FileFact> files) throws IOException {
        if(!names.containsAll(Set.of("scripts/setup.sh","scripts/start.sh"))) throw error("MEOO_IMAGE_SCRIPTS_REQUIRED");
        if(Files.exists(root.resolve("docker-compose.yml")) || Files.exists(root.resolve("compose.yaml")) || Files.exists(root.resolve("compose.yml")) || Files.exists(root.resolve("docker-compose.yaml"))) throw error("MEOO_MULTISERVICE_UNSUPPORTED");
        if(Files.exists(root.resolve("Cargo.toml")) || Files.exists(root.resolve("Gemfile")) || files.stream().anyMatch(f->f.relativePath().endsWith(".csproj"))) throw error("MEOO_RUNTIME_UNSUPPORTED");
        String start=Files.readString(root.resolve("scripts/start.sh"));
        if(!start.contains("9000") && !start.contains("PORT")) throw error("MEOO_PORT_CONFIGURATION_REQUIRED");
        for(FileFact f:files) {
            if(f.size()>2*1024*1024 || !f.relativePath().matches(".*\\.(js|mjs|ts|py|java|go|sh|json|ya?ml|properties)$")) continue;
            String text=Files.readString(f.source());
            if(Pattern.compile("(?i)(sqlite3?|better-sqlite3|jdbc:sqlite|sqlite://)").matcher(text).find()) throw error("MEOO_PERSISTENT_STORAGE_ADAPTATION_REQUIRED");
            var environment=Pattern.compile("process\\.env\\.([A-Z][A-Z0-9_]+)|(?:getenv|environ\\.get)\\(['\"]([A-Z][A-Z0-9_]+)['\"]").matcher(text);
            while(environment.find()) {
                String key=environment.group(1)!=null?environment.group(1):environment.group(2);
                if(!Set.of("PORT","NODE_ENV","HOME","PATH","TMPDIR","LANG").contains(key)) throw error("MEOO_APP_ENV_REQUIRED");
            }
        }
    }
    private static void validateStatic(List<FileFact> files, Set<String> names, boolean single) throws IOException {
        for(FileFact f:files) if(f.relativePath().matches(".*\\.(js|mjs)$"))
            validateModuleReferences(Files.readString(f.source()),f.relativePath(),names,single);
        for(FileFact f:files) if(f.relativePath().endsWith(".css")) validateCss(Files.readString(f.source()),f.relativePath(),names,single);
        for(FileFact f:files) if(f.relativePath().endsWith(".html") || f.relativePath().endsWith(".htm")) {
            var doc=Jsoup.parse(Files.readString(f.source()));
            if(!doc.select("base[href]").isEmpty()) throw error("MEOO_BASE_PATH_UNSUPPORTED");
            for(var element:doc.select("[src],link[href],video[poster]")) {
                String ref=element.hasAttr("src")?element.attr("src"):element.hasAttr("poster")?element.attr("poster"):element.attr("href");
                checkReference(ref,f.relativePath(),names,single);
            }
            for(var element:doc.select("script[type=module]:not([src])"))
                validateModuleReferences(element.data(),f.relativePath(),names,single);
            for(var element:doc.select("style")) validateCss(element.data(),f.relativePath(),names,single);
            for(var element:doc.select("[style]")) validateCss(element.attr("style"),f.relativePath(),names,single);
            for(var element:doc.select("[srcset]")) {
                String srcset=element.attr("srcset");
                if(!srcset.strip().startsWith("data:")) for(String candidate:srcset.split(","))
                    checkReference(candidate.strip().split("\\s+",2)[0],f.relativePath(),names,single);
            }
            for(var element:doc.select("object[data]")) checkReference(element.attr("data"),f.relativePath(),names,single);
            // ES import maps describe local module files not present in src attributes.
            for(var element:doc.select("script[type=importmap]")) {
                var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(element.data());
                var imports=json.path("imports");
                var values=imports.elements();
                while(values.hasNext()) { String ref=values.next().asText(); if(!ref.endsWith("/")) checkReference(ref,f.relativePath(),names,single); }
            }
        }
    }
    private static void validateModuleReferences(String script,String source,Set<String> names,boolean single) {
        var imports=Pattern.compile("(?:from\\s*|import\\s*\\(?\\s*)['\"](\\.{1,2}/[^'\"]+)['\"]").matcher(script);
        while(imports.find()) checkReference(imports.group(1),source,names,single);
    }
    private static void validateCss(String css,String source,Set<String> names,boolean single) {
        Pattern urls=Pattern.compile("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)",Pattern.CASE_INSENSITIVE);
        // A font src declaration is an ordered fallback list; one usable source is sufficient.
        var fonts=Pattern.compile("src\\s*:\\s*([^;{}]+)",Pattern.CASE_INSENSITIVE).matcher(css);
        StringBuffer rest=new StringBuffer();
        while(fonts.find()) {
            var refs=urls.matcher(fonts.group(1));boolean found=false,usable=false;
            while(refs.find()) {
                found=true;
                try { checkReference(refs.group(1).strip(),source,names,single);usable=true; }
                catch(MeooException e) { if(single || !e.code().equals("MEOO_STATIC_RESOURCE_MISSING")) throw e; }
            }
            if(found && !usable) throw error("MEOO_STATIC_RESOURCE_MISSING");
            fonts.appendReplacement(rest,found?"":java.util.regex.Matcher.quoteReplacement(fonts.group()));
        }
        fonts.appendTail(rest);
        var refs=urls.matcher(rest);
        while(refs.find()) checkReference(refs.group(1).strip(),source,names,single);
        var imports=Pattern.compile("@import\\s+['\"]([^'\"]+)['\"]",Pattern.CASE_INSENSITIVE).matcher(rest);
        while(imports.find()) checkReference(imports.group(1),source,names,single);
    }
    private static void checkReference(String ref,String source,Set<String> names,boolean single) {
        if(ref.isBlank() || ref.startsWith("#") || ref.matches("(?i)^(https?:|data:|blob:|//).*")) return;
        String path=ref.split("[?#]",2)[0];
        if(path.isBlank()) return;
        if(single) throw error("MEOO_STATIC_DIRECTORY_REQUIRED");
        Path parent=Path.of(source).getParent();
        String normalized=(path.startsWith("/")?Path.of(path.substring(1)):(parent==null?Path.of(path):parent.resolve(path))).normalize().toString().replace('\\','/');
        if(!names.contains(normalized)) throw error("MEOO_STATIC_RESOURCE_MISSING");
    }
    static boolean forbidden(String relative) {
        for(String part:relative.split("/")) {
            String n=part.toLowerCase(Locale.ROOT);
            if(EXCLUDED.stream().anyMatch(e->e.equalsIgnoreCase(part)) || n.startsWith(".env") || n.matches(".*\\.(db(?:-wal|-shm)?|sqlite(?:3)?(?:-wal|-shm)?|pem|key|p12|pfx|jks|log|map)$") || n.equals("credentials.json") || n.startsWith("id_rsa") || n.startsWith("id_ed25519")) return true;
        }
        return false;
    }
    private static boolean staticAsset(String path) {
        String name=Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        if(name.startsWith("readme") || name.equals("package.json") || name.endsWith("lock.json") || name.startsWith("tsconfig")) return false;
        int dot=name.lastIndexOf('.'); return dot>=0 && STATIC_EXT.contains(name.substring(dot+1));
    }
    public static void rejectLinks(Path root,Path file) {
        Path p=root;
        if(Files.isSymbolicLink(root)) throw error("MEOO_SYMLINK_FORBIDDEN");
        for(Path part:root.relativize(file)) { p=p.resolve(part); if(Files.isSymbolicLink(p)) throw error("MEOO_SYMLINK_FORBIDDEN"); }
    }
    private static void scanSecrets(Path path) throws IOException {
        try(InputStream in=Files.newInputStream(path)) {
            byte[] buffer=new byte[65536]; String overlap="";
            for(int n;(n=in.read(buffer))!=-1;) {
                String text=overlap+new String(buffer,0,n,StandardCharsets.ISO_8859_1);
                if(SECRET.matcher(text).find()) throw error("MEOO_SENSITIVE_CONTENT");
                overlap=text.substring(Math.max(0,text.length()-1024));
            }
        }
    }
    public static String hash(Path path) throws IOException {
        try(InputStream in=Files.newInputStream(path)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[65536];
            for(int n;(n=in.read(b))!=-1;) digest.update(b,0,n);
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public void stage(Snapshot snapshot,Path destination) throws IOException {
        Files.createDirectories(destination);
        for(FileFact f:snapshot.files()) {
            rejectLinks(snapshot.root(),f.source());
            Path target=destination.resolve(f.relativePath()); Files.createDirectories(target.getParent());
            try(InputStream in=Files.newInputStream(f.source(),LinkOption.NOFOLLOW_LINKS)) { Files.copy(in,target); }
            scanSecrets(target);
            if(Files.size(target)!=f.size() || !hash(target).equals(f.sha256())) throw error("MEOO_SNAPSHOT_CHANGED");
        }
    }
    static MeooException error(String code) { return new MeooException(code); }
}
