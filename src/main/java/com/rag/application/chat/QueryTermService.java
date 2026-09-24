package com.rag.application.chat;

import com.rag.domain.model.QueryTermMapping;
import com.rag.domain.repository.QueryTermMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 查询词映射（术语归一化）：检索前把同义词/俚语改写为标准术语（如"向量库"→"向量数据库"），
 * 规则来自 t_query_term_mapping（管理端 CRUD 维护，P2-⑨ 落地）。
 *
 * 映射表带 60 秒内存缓存：规则表极小且低频变更，避免每个查询都打 MySQL。
 */
@Service
public class QueryTermService {

    private static final Logger log = LoggerFactory.getLogger(QueryTermService.class);
    private static final long CACHE_TTL_MS = 60_000L;

    private final QueryTermMappingRepository repository;

    private volatile List<QueryTermMapping> cachedRules = List.of();
    private volatile long cachedAt = 0L;

    public QueryTermService(QueryTermMappingRepository repository) {
        this.repository = repository;
    }

    /**
     * 对查询应用术语映射（matchType: 1=包含即替换整个词, 2=整词匹配才替换）。
     * 无规则命中或任何异常时原样返回——归一化永远不能阻断检索。
     */
    public String applyMappings(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        try {
            List<QueryTermMapping> rules = loadRules();
            if (rules.isEmpty()) {
                return query;
            }

            String result = query;
            for (QueryTermMapping rule : rules) {
                String source = rule.getSourceTerm();
                String target = rule.getTargetTerm();
                if (source == null || source.isBlank() || target == null || target.isBlank()) {
                    continue;
                }
                int matchType = rule.getMatchType() != null ? rule.getMatchType() : 1;
                if (matchType == 2) {
                    // 整词匹配：前后是非 word 字符（含中文字符按整词处理）
                    result = result.replaceAll("(?<!\\w)" + java.util.regex.Pattern.quote(source) + "(?!\\w)",
                            java.util.regex.Matcher.quoteReplacement(target));
                } else {
                    if (result.contains(source)) {
                        result = result.replace(source, target);
                    }
                }
            }
            if (!result.equals(query)) {
                log.info("Term mapping applied: '{}' -> '{}'", query, result);
            }
            return result;
        } catch (Exception e) {
            log.warn("Term mapping failed, returning original query: {}", e.getMessage());
            return query;
        }
    }

    private List<QueryTermMapping> loadRules() {
        long now = System.currentTimeMillis();
        List<QueryTermMapping> rules = cachedRules;
        if (rules.isEmpty() || now - cachedAt > CACHE_TTL_MS) {
            try {
                cachedRules = rules = repository.findByEnabledTrueAndDeletedFalse().stream()
                        .sorted(Comparator.comparingInt(m -> m.getPriority() != null ? m.getPriority() : 100))
                        .toList();
                cachedAt = now;
            } catch (Exception e) {
                log.warn("Failed to load term mapping rules: {}", e.getMessage());
            }
        }
        return rules;
    }

    /** 管理端修改规则后可调用立即失效缓存 */
    public void invalidateCache() {
        cachedAt = 0L;
    }
}
