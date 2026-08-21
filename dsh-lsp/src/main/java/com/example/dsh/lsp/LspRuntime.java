package com.example.dsh.lsp;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * LSP 运行时 — 按操作路由到合适的提供者（对应 DSH ctx.lsp）。
 */
@Service
public class LspRuntime {

    private final List<LspProvider> providers;

    public LspRuntime(ObjectProvider<LspProvider> providerProvider) {
        this.providers = providerProvider.orderedStream().toList();
    }

    public Optional<LspResult> goToDefinition(String file, int line, int character) {
        for (LspProvider provider : matching(file)) {
            Optional<LspResult> result = safe(() -> provider.goToDefinition(file, line, character));
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    public Optional<LspResult> findReferences(String file, int line, int character) {
        for (LspProvider provider : matching(file)) {
            Optional<LspResult> result = safe(() -> provider.findReferences(file, line, character));
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    public Optional<LspResult> hover(String file, int line, int character) {
        for (LspProvider provider : matching(file)) {
            Optional<LspResult> result = safe(() -> provider.hover(file, line, character));
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private List<LspProvider> matching(String file) {
        String lower = file == null ? "" : file.toLowerCase();
        return providers.stream()
                .filter(p -> p.extensions().isEmpty()
                        || p.extensions().stream().anyMatch(lower::endsWith))
                .toList();
    }

    private Optional<LspResult> safe(java.util.function.Supplier<Optional<LspResult>> call) {
        try {
            return call.get();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
