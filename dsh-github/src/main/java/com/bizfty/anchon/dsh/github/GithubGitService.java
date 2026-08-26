package com.bizfty.anchon.dsh.github;

import com.bizfty.anchon.dsh.github.properties.GithubProperties;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTag;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.GitHub;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub Git 操作服务 — 分支/commit/tag。
 */
@Service
public class GithubGitService {

    private final GithubApiClient client;
    private final GithubProperties properties;

    public GithubGitService(GithubApiClient client, GithubProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    private String defaultOwner() {
        if (properties.getDefaultOwner() != null && !properties.getDefaultOwner().isBlank()) {
            return properties.getDefaultOwner();
        }
        return client.execute(GitHub::getMyself).getLogin();
    }

    private GHRepository resolveRepo(String owner, String repo) throws IOException {
        String o = (owner == null || owner.isBlank()) ? defaultOwner() : owner;
        return client.execute(g -> g.getRepository(o + "/" + repo));
    }

    /**
     * 列出 refs (分支)。
     */
    public Map<String, Object> listBranches(String owner, String repo, int limit) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHRef[] refs = client.execute(g -> r.getRefs());
        List<Map<String, Object>> items = new ArrayList<>();
        for (GHRef ref : refs) {
            String fullRef = ref.getRef();
            if (fullRef != null && fullRef.startsWith("refs/heads/")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", fullRef.substring("refs/heads/".length()));
                m.put("sha", ref.getObject().getSha());
                items.add(m);
            }
            if (items.size() >= limit) break;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 创建分支。
     */
    public Map<String, Object> createBranch(String owner, String repo, String branch, String fromRef) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        String ref = fromRef == null || fromRef.isBlank() ? r.getDefaultBranch() : fromRef;
        GHRef targetRef = client.execute(g -> r.getRef("heads/" + ref));
        String sha = targetRef.getObject().getSha();
        GHRef newRef = client.execute(g -> r.createRef("refs/heads/" + branch, sha));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch);
        result.put("sha", newRef.getObject().getSha());
        return result;
    }

    /**
     * 列出 tag。
     */
    public Map<String, Object> listTags(String owner, String repo, int limit) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        PagedIterable<GHTag> page = client.execute(g -> r.listTags());
        List<Map<String, Object>> items = new ArrayList<>();
        for (GHTag t : page) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.getName());
            GHCommit c = t.getCommit();
            m.put("commit_sha", c == null ? null : c.getSHA1());
            items.add(m);
            if (items.size() >= limit) break;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 列出 commit 历史。
     */
    public Map<String, Object> listCommits(String owner, String repo, String ref, int limit) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        PagedIterable<GHCommit> commits = client.execute(g -> {
            if (ref == null || ref.isBlank()) {
                return r.listCommits();
            }
            return r.queryCommits().from(ref).list();
        });
        List<Map<String, Object>> items = new ArrayList<>();
        int n = 0;
        for (GHCommit c : commits) {
            if (n >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sha", c.getSHA1());
            m.put("message", c.getCommitShortInfo().getMessage());
            m.put("author", c.getAuthor() == null ? null : c.getAuthor().getLogin());
            m.put("date", c.getCommitDate() == null ? null : c.getCommitDate().toString());
            items.add(m);
            n++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 获取 commit 详情。
     */
    public Map<String, Object> getCommit(String owner, String repo, String sha) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHCommit c = client.execute(g -> r.getCommit(sha));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sha", c.getSHA1());
        m.put("message", c.getCommitShortInfo().getMessage());
        m.put("author", c.getAuthor() == null ? null : c.getAuthor().getLogin());
        m.put("date", c.getCommitDate() == null ? null : c.getCommitDate().toString());
        m.put("url", c.getUrl() == null ? null : c.getUrl().toString());
        m.put("additions", c.getLinesAdded());
        m.put("deletions", c.getLinesDeleted());
        m.put("changes", c.getLinesChanged());
        return m;
    }
}