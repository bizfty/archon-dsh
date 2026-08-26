package com.bizfty.anchon.dsh.github;

import com.bizfty.anchon.dsh.github.properties.GithubProperties;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySearchBuilder;
import org.kohsuke.github.GHRepositorySearchBuilder.Sort;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedSearchIterable;
import org.kohsuke.github.GitHub;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub 仓库相关服务。
 */
@Service
public class GithubService {

    private final GithubApiClient client;
    private final GithubProperties properties;

    public GithubService(GithubApiClient client, GithubProperties properties) {
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
     * 搜索仓库。
     */
    public Map<String, Object> searchRepositories(String query, int limit) {
        return client.execute(g -> {
            GHRepositorySearchBuilder builder = g.searchRepositories().q(query)
                    .order(GHDirection.DESC).sort(Sort.STARS);
            PagedSearchIterable<GHRepository> page = builder.list();
            List<Map<String, Object>> items = new ArrayList<>();
            for (GHRepository r : page) {
                items.add(toSummary(r));
                if (items.size() >= limit) break;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total_count", page.getTotalCount());
            result.put("items", items);
            result.put("limit", limit);
            return result;
        });
    }

    /**
     * 获取仓库详情。
     */
    public Map<String, Object> getRepository(String owner, String repo) throws IOException {
        return toSummary(resolveRepo(owner, repo));
    }

    /**
     * 列出用户/组织仓库。
     */
    public Map<String, Object> listRepositories(String owner, String type, int limit) {
        return client.execute(g -> {
            String qual = "org".equalsIgnoreCase(type) ? "org:" + owner : "user:" + owner;
            PagedSearchIterable<GHRepository> page = g.searchRepositories().q(qual).list();
            List<Map<String, Object>> items = new ArrayList<>();
            for (GHRepository r : page) {
                items.add(toSummary(r));
                if (items.size() >= limit) break;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", items.size());
            result.put("items", items);
            return result;
        });
    }

    /**
     * 获取 README。
     */
    public Map<String, Object> getReadme(String owner, String repo) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHContent content = client.execute(g -> r.getReadme());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", content.getName());
        result.put("path", content.getPath());
        result.put("content", content.getContent());
        result.put("sha", content.getSha());
        result.put("html_url", content.getHtmlUrl() == null ? null : content.getHtmlUrl().toString());
        return result;
    }

    /**
     * 列出目录内容。
     */
    public Map<String, Object> getContents(String owner, String repo, String path, String ref) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        List<GHContent> contents;
        if (ref != null && !ref.isBlank()) {
            contents = client.execute(g -> r.getDirectoryContent(path == null ? "" : path, ref));
        } else {
            contents = client.execute(g -> r.getDirectoryContent(path == null ? "" : path));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (GHContent c : contents) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("path", c.getPath());
            m.put("type", c.getType());
            m.put("sha", c.getSha());
            m.put("size", c.getSize());
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path == null ? "" : path);
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 获取单个文件内容。
     */
    public Map<String, Object> getFileContent(String owner, String repo, String path, String ref) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHContent content;
        if (ref != null && !ref.isBlank()) {
            content = client.execute(g -> r.getFileContent(path, ref));
        } else {
            content = client.execute(g -> r.getFileContent(path));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", content.getName());
        result.put("path", content.getPath());
        result.put("content", content.getContent());
        result.put("sha", content.getSha());
        result.put("encoding", content.getEncoding());
        result.put("size", content.getSize());
        result.put("html_url", content.getHtmlUrl() == null ? null : content.getHtmlUrl().toString());
        return result;
    }

    /**
     * 获取 Git 树。
     */
    public Map<String, Object> getGitTree(String owner, String repo, String branch, boolean recursive) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        String ref = branch == null || branch.isBlank() ? r.getDefaultBranch() : branch;
        org.kohsuke.github.GHTree tree;
        if (recursive) {
            tree = client.execute(g -> r.getTreeRecursive(ref, 1));
        } else {
            tree = client.execute(g -> r.getTree(ref));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (org.kohsuke.github.GHTreeEntry e : tree.getTree()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", e.getPath());
            m.put("mode", e.getMode());
            m.put("type", e.getType());
            m.put("sha", e.getSha());
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sha", tree.getSha());
        result.put("truncated", tree.isTruncated());
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    private Map<String, Object> toSummary(GHRepository r) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("full_name", r.getFullName());
        m.put("name", r.getName());
        m.put("owner", r.getOwnerName());
        m.put("description", r.getDescription());
        m.put("html_url", r.getHtmlUrl() == null ? null : r.getHtmlUrl().toString());
        m.put("language", r.getLanguage());
        m.put("stargazers_count", r.getStargazersCount());
        m.put("forks_count", r.getForksCount());
        m.put("watchers_count", r.getWatchersCount());
        m.put("open_issues_count", r.getOpenIssueCount());
        m.put("default_branch", r.getDefaultBranch());
        m.put("private", r.isPrivate());
        m.put("archived", r.isArchived());
        m.put("topics", r.listTopics());
        return m;
    }
}