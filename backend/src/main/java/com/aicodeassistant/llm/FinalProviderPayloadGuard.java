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
            // JSON syntax is charged structurally; ordinary text follows the
            // model's configured character ratio.
            int estimated=estimateValue(body,null,caps.tokenCharRatio());
            if(estimated>budget)throw new LlmApiException("CONTEXT_BUDGET_EXCEEDED estimated="+estimated+" budget="+budget,false,0,"CONTEXT_BUDGET_EXCEEDED",0);
            return new BudgetResult(true,estimated,budget,
                    knownModel ? "ESTIMATED" : "CONSERVATIVE_DEFAULT");
        }catch(LlmApiException e){throw e;}catch(Exception e){throw new LlmApiException("PAYLOAD_GUARD_SERIALIZATION_FAILED",e,false);}
    }
    /**
     * Conservative structural estimator. Base64 image/document payloads are
     * charged one token per character; ordinary text uses a deliberately
     * ModelRegistry ratio. Base64 is charged conservatively at one token per
     * character because it does not follow natural-language token ratios.
     */
    private int estimateNode(JsonNode node,String fieldName,double ratio){
        if(node==null||node.isNull())return 0;
        if(node.isTextual()){
            String value=node.textValue();
            boolean encoded="data".equals(fieldName)||"base64".equals(fieldName)
                    ||value.startsWith("data:image/")||value.startsWith("data:application/");
            return encoded?value.length():(int)Math.ceil(value.length()/ratio);
        }
        if(node.isArray()){
            long sum=2;
            for(JsonNode child:node)sum+=estimateNode(child,fieldName,ratio)+1L;
            return (int)Math.min(Integer.MAX_VALUE,sum);
        }
        if(node.isObject()){
            long sum=2;
            var fields=node.fields();
            while(fields.hasNext()){
                var entry=fields.next();
                sum+=(int)Math.ceil(entry.getKey().length()/ratio)
                        +estimateNode(entry.getValue(),entry.getKey(),ratio)+1L;
            }
            return (int)Math.min(Integer.MAX_VALUE,sum);
        }
        return 1;
    }
    private int estimateValue(Object value, String fieldName, double ratio) {
        if (value == null) return 0;
        if (value instanceof JsonNode node) return estimateNode(node, fieldName, ratio);
        if (value instanceof CharSequence text) {
            String string = text.toString();
            boolean encoded = "data".equals(fieldName) || "base64".equals(fieldName)
                    || string.startsWith("data:image/") || string.startsWith("data:application/");
            return encoded ? string.length() : (int) Math.ceil(string.length() / ratio);
        }
        long sum = 2;
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                sum += (int) Math.ceil(name.length() / ratio)
                        + estimateValue(entry.getValue(), name, ratio) + 1L;
            }
            return (int) Math.min(Integer.MAX_VALUE, sum);
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) sum += estimateValue(item, fieldName, ratio) + 1L;
            return (int) Math.min(Integer.MAX_VALUE, sum);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++)
                sum += estimateValue(java.lang.reflect.Array.get(value, i), fieldName, ratio) + 1L;
            return (int) Math.min(Integer.MAX_VALUE, sum);
        }
        return 1;
    }
    public record BudgetResult(boolean guarded,int estimatedTokens,int inputBudget,String precision){}
}
