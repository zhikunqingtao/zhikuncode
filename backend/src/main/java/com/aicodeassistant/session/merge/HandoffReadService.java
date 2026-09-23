package com.aicodeassistant.session.merge;

import com.aicodeassistant.authorization.AuthorizationException;
import com.aicodeassistant.tool.ToolInput;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.LongSupplier;
import java.util.*;
import static com.aicodeassistant.session.merge.MergeHandoffData.*;

/** Reads only an independently sealed package bound to the trusted root session. No caller paths. */
@Service
public class HandoffReadService {
    private static final long READ_NANOS = 15_000_000_000L;
    private static final long SEARCH_BYTES = 8 * 1024 * 1024;
    private final MergeProgressRepository repo;
    private final ObjectMapper json;
    private final LongSupplier clock;
    @Autowired
    public HandoffReadService(MergeProgressRepository repo,ObjectMapper json) { this(repo,json,System::nanoTime); }
    HandoffReadService(MergeProgressRepository repo,ObjectMapper json,LongSupplier clock) {
        this.repo=repo; this.json=json; this.clock=clock;
    }
    private Budget budget() { return new Budget(clock); }
    /** The deadline includes integrity checks and catalog traversal, not only matching text. */
    private static final class Budget {
        private final LongSupplier clock;
        private final long started;
        private final long duration;
        private final Map<Path,String> verified = new HashMap<>();
        private Budget(LongSupplier clock) { this(clock,READ_NANOS); }
        private Budget(LongSupplier clock,long duration) { this.clock=clock; this.started=clock.getAsLong(); this.duration=duration; }
        private void check() throws IOException {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("HANDOFF_READ_INTERRUPTED");
            if(clock.getAsLong()-started>=duration) throw new IOException("HANDOFF_READ_BUDGET_EXCEEDED");
        }
    }
    public Ledger binding(String session) {
        return repo.binding(session).orElseThrow(() -> new AuthorizationException("HANDOFF_NOT_BOUND","当前根会话没有可用交接资料"));
    }
    public String authorize(String session,ToolInput input) {
        Budget budget=budget();
        try {
            Ledger ledger=binding(session); verify(ledger,budget); validate(input);
            if(Set.of("read","asset").contains(value(input,"action","list"))) resolve(ledger,value(input,"ref",""),budget);
            return ledger.operationId()+":"+ledger.handoffHash();
        } catch(IOException e) { throw readFailure(e); }
    }
    private static AuthorizationException readFailure(IOException error) {
        String code=Set.of("HANDOFF_READ_BUDGET_EXCEEDED","HANDOFF_READ_INTERRUPTED").contains(Objects.toString(error.getMessage(),""))
                ? error.getMessage() : "HANDOFF_INVALID_RESOURCE";
        return new AuthorizationException(code,error.getMessage());
    }
    private void validate(ToolInput input) throws IOException {
        String action=value(input,"action","list");
        if(!Set.of("list","search","read","asset").contains(action)) throw new IOException("HANDOFF_INVALID_ACTION");
        if("search".equals(action) && (value(input,"query","").isBlank() || value(input,"query","").length()>512)) throw new IOException("HANDOFF_INVALID_QUERY");
    }
    public void verify(Ledger ledger) throws IOException { verify(ledger,budget()); }
    private void verify(Ledger ledger,Budget budget) throws IOException {
        verifyPrepared(Path.of(ledger.packagePath()),ledger.snapshotHash(),ledger.handoffHash(),json,budget);
    }
    public static void verifyPrepared(Path dir,String snapshotHash,String handoffHash,ObjectMapper json) throws IOException {
        verifyPrepared(dir,snapshotHash,handoffHash,json,new Budget(System::nanoTime,Long.MAX_VALUE));
    }
    private static void verifyPrepared(Path dir,String snapshotHash,String handoffHash,ObjectMapper json,Budget budget) throws IOException {
        budget.check();
        if(Files.size(MergePackageService.safeFile(dir,"handoff/ready.json"))>16384) throw new IOException("HANDOFF_HASH_MISMATCH");
        String ready=Files.readString(MergePackageService.safeFile(dir,"handoff/ready.json"));
        budget.check();
        if(!sha256(ready).equals(handoffHash)) throw new IOException("HANDOFF_HASH_MISMATCH");
        JsonNode seal=json.readTree(ready);
        if(!snapshotHash.equals(seal.path("snapshotHash").asText())) throw new IOException("HANDOFF_HASH_MISMATCH");
        checkHash(dir,"snapshot/manifest.json",snapshotHash,budget);
        if(seal.path("derivedHash").asText().isBlank() && Files.exists(dir.resolve("work/recovered/seal.json"))) throw new IOException("HANDOFF_HASH_MISMATCH");
        if(!seal.path("derivedHash").asText().isBlank()) {
            checkHash(dir,"work/recovered/seal.json",seal.path("derivedHash").asText(),budget);
            JsonNode recovered=json.readTree(Files.readString(MergePackageService.safeFile(dir,"work/recovered/seal.json")));
            checkHash(dir,"work/recovered/projection-files.jsonl",recovered.path("catalogHash").asText(),budget);
        }
        checkHash(dir,"handoff/details.jsonl",seal.path("detailsHash").asText(),budget);
        checkHash(dir,"handoff/handoff.md",seal.path("briefHash").asText(),budget);
        checkHash(dir,"handoff/overview.json",seal.path("overviewHash").asText(),budget);
        JsonNode manifest=json.readTree(Files.readString(MergePackageService.safeFile(dir,"snapshot/manifest.json")));
        for(String name:List.of("files.jsonl","records.jsonl","gaps.jsonl","occurrences.jsonl"))
            checkHash(dir,"snapshot/"+name,manifest.path("catalogs").path(name).path("sha256").asText(),budget);
        budget.check();
    }
    private static String hash(Path path,Budget budget) throws IOException {
        budget.check();
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=new byte[65536]; int count;
                while((count=input.read(bytes))!=-1) { budget.check(); digest.update(bytes,0,count); }
            }
            budget.check(); return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void checkHash(Path dir,String path,String expected,Budget budget) throws IOException {
        budget.check(); Path file=MergePackageService.safeFile(dir,path);
        // Request-local only: no stale integrity result survives a tool invocation.
        if(expected.equals(budget.verified.get(file))) return;
        if(!hash(file,budget).equals(expected)) throw new IOException("HANDOFF_HASH_MISMATCH");
        budget.verified.put(file,expected);
    }
    public String brief(String session) throws IOException {
        Budget budget=budget(); var ledger=binding(session); verify(ledger,budget);
        String text=Files.readString(MergePackageService.safeFile(Path.of(ledger.packagePath()),"handoff/handoff.md"));
        budget.check(); return text;
    }
    public record Entry(String ref,String path,String sourceId,String kind,String sha256,long bytes,Set<String> sections) { }
    private record Position(int catalog,long bytes) { }
    private interface Visitor { boolean visit(Entry entry,Position current,Position after) throws IOException; }
    private List<Path> catalogs(Ledger ledger) throws IOException {
        Path dir=Path.of(ledger.packagePath());
        List<Path> paths=new ArrayList<>(List.of(MergePackageService.safeFile(dir,"handoff/details.jsonl"),
                MergePackageService.safeFile(dir,"snapshot/files.jsonl")));
        if(Files.exists(dir.resolve("work/recovered/seal.json"),LinkOption.NOFOLLOW_LINKS))
            paths.add(MergePackageService.safeFile(dir,"work/recovered/projection-files.jsonl"));
        return paths;
    }
    private void catalog(Ledger ledger,Position start,Budget budget,Visitor visitor) throws IOException {
        List<Path> paths=catalogs(ledger);
        if(start.catalog()<0 || start.catalog()>paths.size() || start.bytes()<0 || (start.catalog()==paths.size() && start.bytes()!=0))
            throw new IOException("HANDOFF_INVALID_CURSOR");
        for(int index=start.catalog();index<paths.size();index++) {
            budget.check(); Path path=paths.get(index); long offset=index==start.catalog()?start.bytes():0;
            try(var channel=Files.newByteChannel(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                if(offset>channel.size()) throw new IOException("HANDOFF_INVALID_CURSOR");
                if(offset>0) {
                    channel.position(offset-1); ByteBuffer previous=ByteBuffer.allocate(1); channel.read(previous);
                    if(previous.array()[0]!='\n') throw new IOException("HANDOFF_INVALID_CURSOR");
                }
                channel.position(offset);
                try(var input=new BufferedInputStream(Channels.newInputStream(channel))) {
                    while(true) {
                        budget.check(); var line=new ByteArrayOutputStream(); int next;
                        while((next=input.read())!=-1 && next!='\n') {
                            line.write(next);
                            if((line.size() & 4095)==0) budget.check();
                        }
                        if(next==-1 && line.size()==0) break;
                        long after=offset+line.size()+(next=='\n'?1:0);
                        JsonNode n=json.readTree(line.toByteArray()); budget.check();
                        Set<String> sections=new HashSet<>(); n.path("sections").forEach(v -> sections.add(v.asText()));
                        Entry entry=new Entry(n.path("ref").asText(),n.path("path").asText(),n.path("sourceId").asText(),
                                n.path("kind").asText(index==0?"detail":""),n.path("sha256").asText(),n.path("bytes").asLong(),sections);
                        Position following=after==channel.size()?new Position(index+1,0):new Position(index,after);
                        if(!visitor.visit(entry,new Position(index,offset),following)) return;
                        offset=after;
                    }
                }
            }
        }
    }
    public Entry resolve(Ledger ledger,String ref) throws IOException {
        Budget budget=budget(); verify(ledger,budget); return resolve(ledger,ref,budget);
    }
    private Entry resolve(Ledger ledger,String ref,Budget budget) throws IOException {
        budget.check();
        if(ref!=null && ref.matches(".*@[0-9]+:[0-9]+")) ref=ref.substring(0,ref.lastIndexOf('@'));
        if(ref==null || ref.isBlank() || ref.length()>256) throw new IOException("HANDOFF_INVALID_REF");
        if("overview".equals(ref)) {
            Path path=MergePackageService.safeFile(Path.of(ledger.packagePath()),"handoff/overview.json");
            return new Entry(ref,"handoff/overview.json","","catalog",budget.verified.get(path),Files.size(path),Set.of());
        }
        if(Set.of("gaps","records","manifest","occurrences").contains(ref)) {
            String name=ref.equals("manifest")?"manifest.json":ref+".jsonl";
            Path path=MergePackageService.safeFile(Path.of(ledger.packagePath()),"snapshot/"+name);
            return new Entry(ref,"snapshot/"+name,"","catalog",budget.verified.get(path),Files.size(path),Set.of());
        }
        Entry[] found={null}; String lookup=ref;
        catalog(ledger,new Position(0,0),budget,(e,current,after) -> { if(e.ref().equals(lookup)) { found[0]=e; return false; } return true; });
        if(found[0]==null) throw new IOException("HANDOFF_INVALID_REF");
        checkHash(Path.of(ledger.packagePath()),found[0].path(),found[0].sha256(),budget);
        return found[0];
    }
    public boolean isBoundAsset(String session,String path,String hash) {
        try {
            Budget budget=budget(); Ledger ledger=binding(session); verify(ledger,budget); Path dir=Path.of(ledger.packagePath());
            Path requested=Path.of(path).toAbsolutePath().normalize(); Entry[] found={null};
            catalog(ledger,new Position(0,0),budget,(e,current,after) -> {
                if(e.kind().equals("asset") && e.sha256().equals(hash)
                        && dir.resolve(e.path()).toAbsolutePath().normalize().equals(requested)) { found[0]=e; return false; }
                return true;
            });
            if(found[0]==null) return false;
            return hash(MergePackageService.safeFile(dir,found[0].path()),budget).equals(hash);
        } catch(IOException | RuntimeException invalid) { return false; }
    }
    public Path asset(String session,String ref) throws IOException {
        Budget budget=budget(); Ledger ledger=binding(session); verify(ledger,budget); Entry e=resolve(ledger,ref,budget);
        if(!"asset".equals(e.kind())) throw new IOException("HANDOFF_NOT_ASSET");
        return MergePackageService.safeFile(Path.of(ledger.packagePath()),e.path());
    }
    private record Cursor(String binding,int catalog,long catalogOffset,long offset) {
        private Position position() { return new Position(catalog,catalogOffset); }
    }
    private Cursor cursor(String key,Position position,long offset) { return new Cursor(key,position.catalog(),position.bytes(),offset); }
    public String read(String session,ToolInput input) throws IOException {
        Budget budget=budget(); Ledger ledger=binding(session);
        try { verify(ledger,budget); validate(input); }
        catch(InterruptedIOException cancelled) { throw cancelled; }
        catch(IOException invalid) { throw readFailure(invalid); }
        String action=value(input,"action","list"),query=value(input,"query",""),ref=value(input,"ref",""),source=value(input,"sourceId",""),section=value(input,"section","");
        String key=sha256(ledger.operationId()+ledger.handoffHash()+repo.encode(List.of(action,query,ref,source,section)));
        long rangeStart=0,rangeEnd=Long.MAX_VALUE;
        if(ref.matches(".*@[0-9]+:[0-9]+")) {
            try { String[] span=ref.substring(ref.lastIndexOf('@')+1).split(":"); rangeStart=Long.parseLong(span[0]); rangeEnd=Long.parseLong(span[1]); }
            catch(NumberFormatException invalid) { throw new IOException("HANDOFF_INVALID_REF"); }
            if(rangeEnd<=rangeStart) throw new IOException("HANDOFF_INVALID_REF");
        }
        Cursor cursor=new Cursor(key,0,0,rangeStart); String encoded=value(input,"cursor","");
        if(!encoded.isEmpty()) try {
            if(encoded.length()>2048) throw new IllegalArgumentException();
            cursor=json.readValue(Base64.getUrlDecoder().decode(encoded),Cursor.class);
            if(!key.equals(cursor.binding()) || cursor.catalog()<0 || cursor.catalogOffset()<0 || cursor.offset()<0) throw new IllegalArgumentException();
        } catch(Exception e) { throw new IOException("HANDOFF_INVALID_CURSOR",e); }
        int limit=5; Object requested=input.getRawData().get("limit");
        if(requested instanceof Number n) limit=Math.max(1,Math.min(20,n.intValue()));
        List<Object> entries=new ArrayList<>(); Cursor[] next={null};
        Path dir=Path.of(ledger.packagePath());
        if("read".equals(action)) {
            Entry entry;
            try { entry=resolve(ledger,ref,budget); }
            catch(InterruptedIOException cancelled) { throw cancelled; }
            catch(IOException invalid) { throw readFailure(invalid); }
            if("asset".equals(entry.kind())) throw new IOException("HANDOFF_USE_ASSET");
            try(Reader reader=Files.newBufferedReader(MergePackageService.safeFile(dir,entry.path()))) {
                if(cursor.offset()<rangeStart || cursor.offset()>rangeEnd) throw new IOException("HANDOFF_INVALID_CURSOR");
                skip(reader,cursor.offset(),budget); String text=chunk(reader,(int)Math.min(1500,rangeEnd-cursor.offset()),budget);
                entries.add(Map.of("ref",ref,"offset",cursor.offset(),"text",text));
                if(cursor.offset()+text.length()<rangeEnd && reader.read()!=-1) next[0]=new Cursor(key,0,0,cursor.offset()+text.length());
            }
        } else {
            long[] scanned={0}; Cursor start=cursor; int max=limit;
            // Resolve adjacent parts once per search, including absent refs. Never cache across calls.
            Map<String,Entry> searchCatalog=new HashMap<>();
            if("search".equals(action)) catalog(ledger,new Position(0,0),budget,(e,current,after) -> {
                searchCatalog.putIfAbsent(e.ref(),e); return true;
            });
            catalog(ledger,start.position(),budget,(e,current,after) -> {
                if(!source.isEmpty() && !source.equals(e.sourceId())) return true;
                if(!section.isEmpty() && !e.sections().contains(section)) return true;
                if(scanned[0]>=SEARCH_BYTES-8) { next[0]=cursor(key,current,0); return false; }
                if("list".equals(action)) {
                    entries.add(Map.of("ref",e.ref(),"sourceId",e.sourceId(),"kind",e.kind(),"sections",e.sections()));
                } else if("search".equals(action) && Set.of("text","detail").contains(e.kind())) {
                    Path path=MergePackageService.safeFile(dir,e.path()); checkHash(dir,e.path(),e.sha256(),budget);
                    try(Reader reader=Files.newBufferedReader(path)) {
                        long offset=current.equals(start.position())?start.offset():0;
                        long unprocessed=offset; skip(reader,offset,budget);
                        String carry=""; String part;
                        while(true) {
                            int chars=(int)Math.min(2048,(SEARCH_BYTES-scanned[0]-4)/4);
                            if(chars<=0) { next[0]=cursor(key,current,Math.max(unprocessed,offset-carry.length())); return false; }
                            part=chunk(reader,chars,budget); if(part.isEmpty()) break;
                            scanned[0]+=part.getBytes(StandardCharsets.UTF_8).length;
                            String combined=carry+part; long base=offset-carry.length();
                            int from=(int)Math.max(0,unprocessed-base),match;
                            while((match=combined.indexOf(query,from))>=0) {
                                entries.add(Map.of("ref",e.ref(),"offset",base+match,"text",
                                        excerpt(combined,Math.max(0,match-100),Math.min(combined.length(),match+query.length()+300))));
                                from=match+Character.charCount(combined.codePointAt(match)); unprocessed=base+from;
                                if(entries.size()>=max || resultBytes(entries)>8500) {
                                    next[0]=cursor(key,current,unprocessed); return false;
                                }
                            }
                            offset+=part.length();
                            int tail=Math.max(0,combined.length()-query.length()+1);
                            if(tail>0 && tail<combined.length() && Character.isLowSurrogate(combined.charAt(tail))) tail--;
                            carry=combined.substring(tail);
                        }
                        // A literal can straddle adjacent sealed text parts. Only its prefix is needed.
                        if(!carry.isEmpty() && e.kind().equals("text") && e.ref().matches(".*:p[0-9]+")) {
                            int separator=e.ref().lastIndexOf(":p");
                            String following=e.ref().substring(0,separator+2)+(Integer.parseInt(e.ref().substring(separator+2))+1);
                            if(SEARCH_BYTES-scanned[0]<4L*query.length()+4) {
                                next[0]=cursor(key,current,Math.max(unprocessed,offset-carry.length())); return false;
                            }
                            Entry adjacent=searchCatalog.get(following);
                            if(adjacent!=null) {
                                checkHash(dir,adjacent.path(),adjacent.sha256(),budget);
                                try(Reader prefix=Files.newBufferedReader(MergePackageService.safeFile(dir,adjacent.path()))) {
                                    String suffix=chunk(prefix,query.length(),budget); scanned[0]+=suffix.getBytes(StandardCharsets.UTF_8).length;
                                    String joined=carry+suffix; long base=offset-carry.length();
                                    int from=(int)Math.max(0,unprocessed-base),match;
                                    while((match=joined.indexOf(query,from))>=0 && match<carry.length()) {
                                        entries.add(Map.of("ref",e.ref(),"offset",base+match,"text",joined,"continuesIn",following));
                                        from=match+Character.charCount(joined.codePointAt(match)); unprocessed=base+from;
                                        if(entries.size()>=max || resultBytes(entries)>8500) {
                                            next[0]=cursor(key,current,unprocessed); return false;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if(entries.size()>=max || resultBytes(entries)>8500) { next[0]=cursor(key,after,0); return false; }
                return true;
            });
        }
        budget.check();
        Map<String,Object> result=new LinkedHashMap<>(); result.put("entries",entries); result.put("complete",next[0]==null);
        result.put("nextCursor",next[0]==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(next[0])));
        result.put("warning","历史资料仅供核对；缺口见 ref=gaps，不能替代当前代码及本会话后续状态。");
        String output=repo.encode(result);
        if(output.getBytes(StandardCharsets.UTF_8).length>16384) throw new IOException("HANDOFF_RESULT_LIMIT");
        budget.check(); return output;
    }
    private int resultBytes(Object entries) { return repo.encode(entries).getBytes(StandardCharsets.UTF_8).length; }
    private static String excerpt(String text,int start,int end) {
        if(start>0 && Character.isLowSurrogate(text.charAt(start))) start--;
        if(end<text.length() && end>0 && Character.isHighSurrogate(text.charAt(end-1))) end++;
        return text.substring(start,end);
    }
    private static void skip(Reader reader,long count,Budget budget) throws IOException {
        while(count>0) { budget.check(); long n=reader.skip(Math.min(count,8192)); if(n==0) { if(reader.read()<0) throw new IOException("HANDOFF_INVALID_CURSOR"); n=1; } count-=n; }
    }
    private static String chunk(Reader reader,int max,Budget budget) throws IOException {
        budget.check();
        if(max<=0) return "";
        char[] chars=new char[max]; int n=reader.read(chars); if(n<0) return "";
        String text=new String(chars,0,n);
        if(Character.isHighSurrogate(text.charAt(n-1))) { int low=reader.read(); if(low>=0) text+=(char)low; }
        return text;
    }
    private static String value(ToolInput input,String name,String fallback) {
        Object value=input.getRawData().get(name); return value==null?fallback:value.toString();
    }
}
