package com.aicodeassistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Validates the fully serialized provider request and never mutates it. */
@Component
public class FinalProviderPayloadGuard {
    private static final Logger log=LoggerFactory.getLogger(FinalProviderPayloadGuard.class);
    static final long MAX_REQUEST_BYTES = 64L * 1024 * 1024;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final ModelRegistry models;
    public FinalProviderPayloadGuard(@Lazy ModelRegistry models){this.models=models;}
    public BudgetResult validate(String provider,String model,Object body,int requestedMaxOutput){
        boolean knownModel = models.isKnownModel(model);
        if(!knownModel) log.warn("MODEL_BUDGET_USING_CONSERVATIVE_DEFAULT provider={} model={}",provider,model);
        ModelCapabilities caps=models.getCapabilities(model);
        int margin=Math.max(2048,(int)Math.ceil(caps.contextWindow()*0.05));
        int budget=caps.contextWindow()-requestedMaxOutput-margin;
        if(budget<=0)throw new LlmApiException("INVALID_MODEL_BUDGET_CONFIGURATION",false,0,"INVALID_MODEL_BUDGET_CONFIGURATION",0);
        try{
            enforceWireLimit(body);
            // JSON syntax is charged structurally; ordinary text follows the
            // model's configured character ratio.
            int estimated=estimateValue(body,null,caps.tokenCharRatio(), knownModel && caps.supportsImages() ? MediaScope.ROOT : MediaScope.NONE);
            if(estimated>budget)throw new LlmApiException("CONTEXT_BUDGET_EXCEEDED estimated="+estimated+" budget="+budget,false,0,"CONTEXT_BUDGET_EXCEEDED",0);
            return new BudgetResult(true,estimated,budget,
                    knownModel ? "ESTIMATED" : "CONSERVATIVE_DEFAULT");
        } catch (LlmApiException e) { throw e; }
        catch (Exception e) {
            // Jackson may wrap an exception from the bounded output stream.
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof LlmApiException api) throw api;
            }
            throw new LlmApiException("PAYLOAD_GUARD_SERIALIZATION_FAILED", e, false);
        }
    }
    /** Images use the shared raster estimate; opaque data retains conservative accounting. */
    private int estimateNode(JsonNode node,String fieldName,double ratio, MediaScope scope){
        if(node==null||node.isNull())return 0;
        if(node.isTextual()){
            String value=node.textValue();
            boolean encoded="data".equals(fieldName)||"base64".equals(fieldName)
                    || "encrypted_content".equals(fieldName) || "signature".equals(fieldName)
                    ||value.startsWith("data:image/")||value.startsWith("data:application/");
            return encoded?value.length():(int)Math.ceil(value.length()/ratio);
        }
        if(node.isArray()){
            long sum=2;
            for(JsonNode child:node)sum+=estimateNode(child,fieldName,ratio,scope)+1L;
            return (int)Math.min(Integer.MAX_VALUE,sum);
        }
        if(node.isObject()){
            Integer imageTokens = scope == MediaScope.PARTS ? InlineImageBudget.estimatePart(node) : null;
            if (imageTokens != null) return imageTokens;
            long sum=2;
            var fields=node.fields();
            while(fields.hasNext()){
                var entry=fields.next();
                sum+=(int)Math.ceil(entry.getKey().length()/ratio)
                        +estimateNode(entry.getValue(),entry.getKey(),ratio,mediaField(scope, node, entry.getKey()))+1L;
            }
            return (int)Math.min(Integer.MAX_VALUE,sum);
        }
        return 1;
    }
    private int estimateValue(Object value, String fieldName, double ratio, MediaScope scope) {
        if (value == null) return 0;
        if (value instanceof JsonNode node) return estimateNode(node, fieldName, ratio, scope);
        if (value instanceof CharSequence text) {
            String string = text.toString();
            boolean encoded = "data".equals(fieldName) || "base64".equals(fieldName)
                    || "encrypted_content".equals(fieldName) || "signature".equals(fieldName)
                    || string.startsWith("data:image/") || string.startsWith("data:application/");
            return encoded ? string.length() : (int) Math.ceil(string.length() / ratio);
        }
        long sum = 2;
        if (value instanceof Map<?, ?> map) {
            Integer imageTokens = scope == MediaScope.PARTS ? InlineImageBudget.estimatePart(map) : null;
            if (imageTokens != null) return imageTokens;
            for (var entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                sum += (int) Math.ceil(name.length() / ratio)
                        + estimateValue(entry.getValue(), name, ratio, mediaField(scope, map, name)) + 1L;
            }
            return (int) Math.min(Integer.MAX_VALUE, sum);
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) sum += estimateValue(item, fieldName, ratio, scope) + 1L;
            return (int) Math.min(Integer.MAX_VALUE, sum);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++)
                sum += estimateValue(java.lang.reflect.Array.get(value, i), fieldName, ratio, scope) + 1L;
            return (int) Math.min(Integer.MAX_VALUE, sum);
        }
        return 1;
    }
    private enum MediaScope { ROOT, MESSAGES, PARTS, NONE }

    private static MediaScope mediaField(MediaScope scope, Object parent, String field) {
        if (scope == MediaScope.ROOT && ("messages".equals(field) || "input".equals(field)))
            return MediaScope.MESSAGES;
        if (!"content".equals(field)) return MediaScope.NONE;
        String role = fieldText(parent, "role");
        String type = fieldText(parent, "type");
        if (scope == MediaScope.MESSAGES && ("user".equals(role) || "assistant".equals(role))
                && (type.isEmpty() || "message".equals(type))) return MediaScope.PARTS;
        // Anthropic tool results may contain actual media blocks; tool_use.input is arbitrary JSON.
        if (scope == MediaScope.PARTS && "tool_result".equals(type)) return MediaScope.PARTS;
        return MediaScope.NONE;
    }

    private static String fieldText(Object parent, String field) {
        if (parent instanceof JsonNode node) return node.path(field).asText("");
        Object value = ((Map<?, ?>) parent).get(field);
        return value instanceof String text ? text : "";
    }

    private void enforceWireLimit(Object body) throws java.io.IOException {
        JSON.writeValue(new java.io.OutputStream() {
            private long count;
            private void add(int length) {
                count += length;
                if (count > MAX_REQUEST_BYTES)
                    throw new LlmApiException("模型请求超过 64 MiB 传输限制", false, 0, "PAYLOAD_TOO_LARGE", 0);
            }
            @Override public void write(int value) { add(1); }
            @Override public void write(byte[] bytes, int offset, int length) { add(length); }
        }, body);
    }
    public record BudgetResult(boolean guarded,int estimatedTokens,int inputBudget,String precision){}
}
