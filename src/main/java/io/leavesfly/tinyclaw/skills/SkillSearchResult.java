package io.leavesfly.tinyclaw.skills;

import java.util.List;

/**
 * 技能搜索结果
 */
public class SkillSearchResult {

    private String fullName;
    private String skillName;
    private String description;
    private int stars;
    private String url;
    private String language;
    private String updatedAt;
    private List<String> topics;
    private boolean hasSkillFile;
    private String skillSubdir;
    private boolean trusted = true;
    private String registrySource;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getStars() { return stars; }
    public void setStars(int stars) { this.stars = stars; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }

    public boolean isHasSkillFile() { return hasSkillFile; }
    public void setHasSkillFile(boolean hasSkillFile) { this.hasSkillFile = hasSkillFile; }

    public String getSkillSubdir() { return skillSubdir; }
    public void setSkillSubdir(String skillSubdir) { this.skillSubdir = skillSubdir; }

    public boolean isTrusted() { return trusted; }
    public void setTrusted(boolean trusted) { this.trusted = trusted; }

    public String getRegistrySource() { return registrySource; }
    public void setRegistrySource(String registrySource) { this.registrySource = registrySource; }

    /**
     * 获取安装说明符
     */
    public String getInstallSpecifier() {
        if (skillSubdir != null && !skillSubdir.isEmpty()) {
            return fullName + "/" + skillSubdir;
        }
        return fullName;
    }
}
