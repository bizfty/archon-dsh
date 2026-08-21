package com.example.dsh.agent;

import com.example.dsh.core.model.Agent;
import com.example.dsh.core.prompt.SystemPromptContext;
import com.example.dsh.core.prompt.SystemPromptSection;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Agent 作用域注册表 — 对应 DSH 的 scope 注册/遮蔽形态（比 AgentScope 运行实例化完整）。
 * <p>
 * 语义（对齐 DSH scope 的"注册视图沿链下传、同键遮蔽"）：
 * <ul>
 *   <li><b>注册</b>：任意模块可为一个 agent（或通配 {@code "*"}）注册作用域贡献 —
 *       附加 prompt 段（按段键）+ 工具可见性过滤。</li>
 *   <li><b>遮蔽（shadow）</b>：同 (agentId, sectionKey) 的多个注册，order 大者胜出
 *       （深/后注册遮蔽浅/先注册）；精确 agentId 命中优先于通配。</li>
 *   <li><b>组合</b>：不同段键的段按 order 排序输出；工具可见性 = agent 基础可见性
 *       AND 全部命中注册的过滤器（作用域只收窄、不放开，安全语义）。</li>
 * </ul>
 * 运行期 {@link AgentLoopService} 用 {@link #resolve(Agent)} 解析生效作用域。
 */
@Component
public class AgentScopeRegistry {

    /** 通配 agent id。 */
    public static final String WILDCARD = "*";

    private final List<AgentScopeRegistration> registrations = new CopyOnWriteArrayList<>();

    /** 注册一个作用域贡献；同 (agentId, sectionKey) 的后续注册遮蔽先前注册。 */
    public void register(AgentScopeRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("作用域注册不能为 null");
        }
        registrations.add(registration);
    }

    /** 解析 agent 的生效作用域（无注册时退化为 agent 配置本身）。 */
    public AgentScope resolve(Agent agent) {
        List<AgentScopeRegistration> matches = registrations.stream()
                .filter(r -> r.matches(agent.id()))
                .toList();
        if (matches.isEmpty()) {
            return AgentScope.forAgent(agent);
        }

        // 段：同 sectionKey 遮蔽 → 每键保留胜者（精确 agentId 优先于通配；同精度比 order），再按 order 排序
        Map<String, AgentScopeRegistration> bySectionKey = new LinkedHashMap<>();
        for (AgentScopeRegistration r : matches) {
            if (r.section() != null) {
                AgentScopeRegistration prev = bySectionKey.get(r.sectionKey());
                if (prev == null || AgentScopeRegistration.wins(r, prev)) {
                    bySectionKey.put(r.sectionKey(), r);
                }
            }
        }
        List<SystemPromptSection> sections = bySectionKey.values().stream()
                .sorted(Comparator.comparingInt(AgentScopeRegistration::order))
                .map(AgentScopeRegistration::section)
                .toList();

        // 工具可见性：agent 基础 AND 全部命中过滤（作用域只收窄）
        Predicate<String> visibility = agent::isToolVisible;
        for (AgentScopeRegistration r : matches) {
            if (r.toolVisibility() != null) {
                Predicate<String> filter = r.toolVisibility();
                visibility = visibility.and(filter);
            }
        }
        return AgentScope.of(sections, visibility);
    }

    /** 当前注册数（测试/观测）。 */
    public int size() {
        return registrations.size();
    }

    /**
     * 作用域注册项。
     *
     * @param agentId      目标 agent id；{@link #WILDCARD} 匹配所有 agent
     * @param sectionKey   段键：同 (agentId, sectionKey) 互相遮蔽
     * @param section      附加 prompt 段（可为 null：仅贡献工具过滤）
     * @param toolVisibility 工具可见性过滤（可为 null；AND 组合，只收窄）
     * @param order        排序/遮蔽优先级：大者胜出，同键时遮蔽小的
     */
    public record AgentScopeRegistration(
            String agentId,
            String sectionKey,
            SystemPromptSection section,
            Predicate<String> toolVisibility,
            int order) {

        public AgentScopeRegistration {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("作用域注册需指定 agentId（或用 * 通配）");
            }
            if (sectionKey == null || sectionKey.isBlank()) {
                throw new IllegalArgumentException("作用域注册需指定 sectionKey");
            }
        }

        boolean matches(String targetAgentId) {
            return WILDCARD.equals(agentId) || agentId.equals(targetAgentId);
        }

        /** 遮蔽胜者判定：精确 agentId 优先于通配；同精度比 order（大者胜）。 */
        static boolean wins(AgentScopeRegistration candidate, AgentScopeRegistration current) {
            int specificity = Integer.compare(specificity(candidate), specificity(current));
            if (specificity != 0) {
                return specificity > 0;
            }
            return candidate.order() >= current.order();
        }

        private static int specificity(AgentScopeRegistration r) {
            return WILDCARD.equals(r.agentId()) ? 0 : 1;
        }

        /** 便捷构造：仅附加 prompt 段。 */
        public static AgentScopeRegistration section(String agentId, String sectionKey,
                                                     SystemPromptSection section, int order) {
            return new AgentScopeRegistration(agentId, sectionKey, section, null, order);
        }

        /** 便捷构造：仅贡献工具可见性过滤。 */
        public static AgentScopeRegistration toolFilter(String agentId, String sectionKey,
                                                        Predicate<String> toolVisibility, int order) {
            return new AgentScopeRegistration(agentId, sectionKey, null, toolVisibility, order);
        }
    }

    /** 测试辅助：固定内容段。 */
    public static SystemPromptSection fixedSection(int order, String text) {
        return new SystemPromptSection() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public String render(SystemPromptContext context) {
                return text;
            }
        };
    }
}
