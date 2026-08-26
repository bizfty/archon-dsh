package com.bizfty.anchon.dsh.github;

import com.bizfty.anchon.dsh.github.properties.GithubProperties;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestQueryBuilder;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.GitHub;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub PR 服务。
 */
@Service
public class GithubPullRequestService {

    private final GithubApiClient client;
    private final GithubProperties properties;

    public GithubPullRequestService(GithubApiClient client, GithubProperties properties) {
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
     * 列出 PR。
     */
    public Map<String, Object> listPullRequests(String owner, String repo, String state, int limit) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHIssueState s = parseState(state);
        GHPullRequestQueryBuilder builder = client.execute(g -> r.queryPullRequests());
        if (s != null) builder.state(s);
        builder.sort(GHPullRequestQueryBuilder.Sort.UPDATED).direction(GHDirection.DESC);
        List<Map<String, Object>> items = new ArrayList<>();
        for (GHPullRequest pr : builder.list()) {
            items.add(toPrSummary(pr));
            if (items.size() >= limit) break;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 获取 PR 详情。
     */
    public Map<String, Object> getPullRequest(String owner, String repo, int number) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHPullRequest pr = client.execute(g -> r.getPullRequest(number));
        return toPrDetail(pr);
    }

    /**
     * 创建 PR。
     */
    public Map<String, Object> createPullRequest(String owner, String repo, String title,
                                                 String head, String base, String body, boolean draft) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHPullRequest pr = client.execute(g -> r.createPullRequest(title, head, base, body, true, draft));
        return toPrDetail(pr);
    }

    /**
     * 合并 PR。
     */
    public Map<String, Object> mergePullRequest(String owner, String repo, int number,
                                                String commitMessage, String mergeMethod) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHPullRequest pr = client.execute(g -> r.getPullRequest(number));
        String msg = commitMessage == null ? "Merged #" + number : commitMessage;
        String method = mergeMethod == null ? "merge" : mergeMethod;
        client.execute(g -> {
            if ("squash".equalsIgnoreCase(method)) {
                pr.merge(msg, null, GHPullRequest.MergeMethod.SQUASH);
            } else if ("rebase".equalsIgnoreCase(method)) {
                pr.merge(msg, null, GHPullRequest.MergeMethod.REBASE);
            } else {
                pr.merge(msg);
            }
            return null;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", number);
        result.put("merged", true);
        result.put("merge_method", method);
        return result;
    }

    /**
     * 获取 PR diff。
     */
    public Map<String, Object> getPullRequestDiff(String owner, String repo, int number) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHPullRequest pr = client.execute(g -> r.getPullRequest(number));
        String diff = client.execute(g -> {
            java.net.URL url = pr.getDiffUrl();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github.v3.diff");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (java.io.InputStream is = conn.getInputStream();
                     java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    return bos.toString(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return "(diff fetch failed with HTTP " + code + ")";
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", number);
        result.put("diff", diff);
        result.put("length", diff == null ? 0 : diff.length());
        return result;
    }

    /**
     * 为 PR 添加评论。
     */
    public Map<String, Object> addPullRequestComment(String owner, String repo, int number, String body) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHPullRequest pr = client.execute(g -> r.getPullRequest(number));
        GHIssueComment c = client.execute(g -> pr.comment(body));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", c.getId());
        result.put("body", c.getBody());
        return result;
    }

    private GHIssueState parseState(String state) {
        if (state == null) return null;
        String s = state.toLowerCase();
        if ("open".equals(s)) return GHIssueState.OPEN;
        if ("closed".equals(s) || "merged".equals(s)) return GHIssueState.CLOSED;
        if ("all".equals(s)) return null;
        return null;
    }

    private Map<String, Object> toPrSummary(GHPullRequest pr) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", pr.getNumber());
        m.put("title", pr.getTitle());
        m.put("state", pr.getState());
        m.put("html_url", pr.getHtmlUrl() == null ? null : pr.getHtmlUrl().toString());
        m.put("user", pr.getUser() == null ? null : pr.getUser().getLogin());
        m.put("merged", pr.isMerged());
        m.put("draft", pr.isDraft());
        m.put("additions", pr.getAdditions());
        m.put("deletions", pr.getDeletions());
        m.put("changed_files", pr.getChangedFiles());
        m.put("commits", pr.getCommits());
        return m;
    }

    private Map<String, Object> toPrDetail(GHPullRequest pr) throws IOException {
        Map<String, Object> m = toPrSummary(pr);
        m.put("body", pr.getBody());
        m.put("merged_at", pr.getMergedAt() == null ? null : pr.getMergedAt().toString());
        m.put("base_branch", pr.getBase().getRef());
        m.put("head_branch", pr.getHead().getRef());
        m.put("mergeable_state", pr.getMergeableState());
        return m;
    }
}