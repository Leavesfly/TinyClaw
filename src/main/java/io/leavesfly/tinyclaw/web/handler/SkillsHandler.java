package io.leavesfly.tinyclaw.web.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import io.leavesfly.tinyclaw.config.Config;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.skills.SkillInfo;
import io.leavesfly.tinyclaw.skills.SkillsLoader;
import io.leavesfly.tinyclaw.web.SecurityMiddleware;
import io.leavesfly.tinyclaw.web.WebUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 处理技能管理 API（/api/skills）。
 */
public class SkillsHandler {

    private static final TinyClawLogger logger = TinyClawLogger.getLogger("web");

    private final Config config;
    private final SkillsLoader skillsLoader;
    private final SecurityMiddleware security;

    public SkillsHandler(Config config, SkillsLoader skillsLoader, SecurityMiddleware security) {
        this.config = config;
        this.skillsLoader = skillsLoader;
        this.security = security;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!security.preCheck(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String corsOrigin = config.getGateway().getCorsOrigin();

        try {
            if (WebUtils.API_SKILLS.equals(path) && WebUtils.HTTP_METHOD_GET.equals(method)) {
                List<SkillInfo> skills = skillsLoader.listSkills();
                ArrayNode result = WebUtils.MAPPER.createArrayNode();
                for (SkillInfo skill : skills) {
                    ObjectNode skillNode = WebUtils.MAPPER.createObjectNode();
                    skillNode.put("name", skill.getName());
                    skillNode.put("description", skill.getDescription() != null ? skill.getDescription() : "");
                    skillNode.put("source", skill.getSource());
                    skillNode.put("path", skill.getPath());
                    result.add(skillNode);
                }
                WebUtils.sendJson(exchange, 200, result, corsOrigin);

            } else if (path.startsWith(WebUtils.API_SKILLS + WebUtils.PATH_SEPARATOR)
                    && WebUtils.HTTP_METHOD_GET.equals(method)) {
                String name = URLDecoder.decode(
                        path.substring(WebUtils.API_SKILLS.length() + 1), StandardCharsets.UTF_8);
                String content = skillsLoader.loadSkill(name);
                if (content != null) {
                    ObjectNode result = WebUtils.MAPPER.createObjectNode();
                    result.put("name", name);
                    result.put("content", content);
                    WebUtils.sendJson(exchange, 200, result, corsOrigin);
                } else {
                    WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Skill not found"), corsOrigin);
                }
            } else {
                WebUtils.sendJson(exchange, 404, WebUtils.errorJson("Not found"), corsOrigin);
            }
        } catch (Exception e) {
            logger.error("Skills API error", Map.of("error", e.getMessage()));
            WebUtils.sendJson(exchange, 500, WebUtils.errorJson(e.getMessage()), corsOrigin);
        }
    }
}
