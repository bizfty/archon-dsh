package com.example.dsh.github;

import com.example.dsh.github.properties.GithubProperties;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueBuilder;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueQueryBuilder;
import org.kohsuke.github.GHIssueQueryBuilder.ForRepository;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHIssueQueryBuilder.Sort;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.GitHub;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GitHub Issue 服务。
 */
@Service
public class GithubIssueService {

    private final GithubApiClient client;
    private final GithubProperties properties;

    public GithubIssueService(GithubApiClient client, GithubProperties properties) {
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
     * 列出 issue。
     */
    public Map<String, Object> listIssues(String owner, String repo, String state, String labels, int limit) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHIssueState s = parseState(state);
        ForRepository builder = client.execute(g -> r.queryIssues());
        if (s != null) {
            builder.state(s);
        }
        if (labels != null && !labels.isBlank()) {
            String first = labels.split(",")[0].trim();
            if (!first.isBlank()) builder.label(first);
        }
        builder.sort(Sort.UPDATED).direction(GHDirection.DESC);
        List<Map<String, Object>> items = new ArrayList<>();
        for (GHIssue issue : builder.list()) {
            items.add(toIssueSummary(issue));
            if (items.size() >= limit) break;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 获取单个 issue。
     */
    public Map<String, Object> getIssue(String owner, String repo, int number) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHIssue issue = client.execute(g -> r.getIssue(number));
        return toIssueDetail(issue);
    }

    /**
     * 创建 issue。
     */
    public Map<String, Object> createIssue(String owner, String repo, String title, String body,
                                          String labels, String assignees) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHIssue issue = client.execute(g -> {
            GHIssueBuilder b = r.createIssue(title);
            if (body != null) b.body(body);
            if (labels != null && !labels.isBlank()) {
                for (String label : labels.split(",")) {
                    b.label(label.trim());
                }
            }
            return b.create();
        });
        if (assignees != null && !assignees.isBlank()) {
            List<GHUser> users = client.execute(g -> {
                List<GHUser> list = new ArrayList<>();
                for (String login : assignees.split(",")) {
                    list.add(g.getUser(login.trim()));
                }
                return list;
            });
            client.execute(g -> {
                issue.setAssignees(users);
                return null;
            });
        }
        return toIssueDetail(issue);
    }

    /**
     * 更新 issue。
     */
    public Map<String, Object> updateIssue(String owner, String repo, int number,
                                           String title, String body, String state) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHIssue issue = client.execute(g -> r.getIssue(number));
        if (title != null) issue.setTitle(title);
        if (body != null) issue.setBody(body);
        if (state != null) {
            GHIssueState s = parseState(state);
            if (s == GHIssueState.CLOSED) issue.close();
            else if (s == GHIssueState.OPEN) issue.reopen();
        }
        return toIssueDetail(issue);
    }

    /**
     * 添加评论。
     */
    public Map<String, Object> addIssueComment(String owner, String repo, int number, String body) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHIssue issue = client.execute(g -> r.getIssue(number));
        GHIssueComment c = client.execute(g -> issue.comment(body));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", c.getId());
        result.put("body", c.getBody());
        result.put("created_at", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        return result;
    }

    /**
     * 列出评论。
     */
    public Map<String, Object> listIssueComments(String owner, String repo, int number) throws IOException {
        GHRepository r = resolveRepo(owner, repo);
        GHIssue issue = client.execute(g -> r.getIssue(number));
        List<GHIssueComment> comments = client.execute(g -> issue.getComments());
        List<Map<String, Object>> items = new ArrayList<>();
        for (GHIssueComment c : comments) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("body", c.getBody());
            m.put("user", c.getUser() == null ? null : c.getUser().getLogin());
            m.put("created_at", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    private GHIssueState parseState(String state) {
        if (state == null) return null;
        String s = state.toLowerCase();
        if ("open".equals(s)) return GHIssueState.OPEN;
        if ("closed".equals(s)) return GHIssueState.CLOSED;
        return null;
    }

    private Map<String, Object> toIssueSummary(GHIssue issue) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", issue.getNumber());
        m.put("title", issue.getTitle());
        m.put("state", issue.getState());
        m.put("body", issue.getBody());
        m.put("html_url", issue.getHtmlUrl() == null ? null : issue.getHtmlUrl().toString());
        m.put("user", issue.getUser() == null ? null : issue.getUser().getLogin());
        m.put("labels", issue.getLabels().stream().map(l -> l.getName()).toList());
        m.put("comments", issue.getCommentsCount());
        return m;
    }

    private Map<String, Object> toIssueDetail(GHIssue issue) throws IOException {
        Map<String, Object> m = toIssueSummary(issue);
        m.put("closed_at", issue.getClosedAt() == null ? null : issue.getClosedAt().toString());
        m.put("assignees", issue.getAssignees().stream().map(GHUser::getLogin).toList());
        return m;
    }
}