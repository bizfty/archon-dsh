package com.example.dsh.github;

import com.example.dsh.github.properties.GithubProperties;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub 元信息服务 — 当前用户、rate limit、组织信息。
 */
@Service
public class GithubMetaService {

    private final GithubApiClient client;
    private final GithubProperties properties;

    public GithubMetaService(GithubApiClient client, GithubProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 获取当前认证用户信息。
     */
    public Map<String, Object> getCurrentUser() {
        return client.execute(g -> {
            GHUser u = g.getMyself();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("login", u.getLogin());
            m.put("name", u.getName());
            m.put("email", u.getEmail());
            m.put("avatar_url", u.getAvatarUrl());
            m.put("html_url", u.getHtmlUrl() == null ? null : u.getHtmlUrl().toString());
            return m;
        });
    }

    /**
     * 获取 rate limit。
     */
    public Map<String, Object> getRateLimit() {
        return client.execute(g -> {
            GHRateLimit r = g.getRateLimit();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("remaining", r.getRemaining());
            m.put("limit", r.getLimit());
            m.put("reset_epoch_second", r.getResetEpochSeconds());
            m.put("reset_date", r.getResetDate() == null ? null : r.getResetDate().toString());
            return m;
        });
    }

    /**
     * 获取用户/组织信息。
     */
    public Map<String, Object> getUserInfo(String login) {
        return client.execute(g -> {
            GHUser u = g.getUser(login);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("login", u.getLogin());
            m.put("name", u.getName());
            m.put("type", u.getType());
            m.put("bio", u.getBio());
            m.put("avatar_url", u.getAvatarUrl());
            m.put("followers", u.getFollowersCount());
            m.put("following", u.getFollowingCount());
            m.put("public_repos", u.getPublicRepoCount());
            return m;
        });
    }
}