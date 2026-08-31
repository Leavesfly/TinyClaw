package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.security.SecretStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭据查询工具 - 让 Agent 知道有哪些凭据可以引用。
 *
 * <h2>为什么是只读的</h2>
 * <p>没有 set 操作是刻意的：Agent 要能写入凭据，值就必须先经过模型上下文，
 * 而这正是 {@link SecretStore} 要消除的那条泄露路径。写入只能走带外途径
 * （{@code tinyclaw secrets set}）。</p>
 *
 * <h2>输出里为什么带 allowedHosts</h2>
 * <p>出口绑定的拒绝发生在工具执行边界。若 Agent 事前看不到限制，它只能靠试错撞出来，
 * 每次失败都要多花一轮推理。把限制直接摊开，让它一次就选对。</p>
 */
public class SecretsTool implements Tool {

    private final SecretStore store;

    public SecretsTool(SecretStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "secrets";
    }

    @Override
    public String description() {
        return "列出本地保管库中可用的凭据引用名。用法：在任意工具参数中写 ${secret:NAME}，"
                + "执行时会被替换为真实值，且真实值不会出现在对话或上下文里。"
                + "本工具只能查询，不能读取或写入凭据值。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", new LinkedHashMap<>());
        return params;
    }

    @Override
    public String execute(Map<String, Object> args) {
        List<SecretStore.SecretInfo> secrets = store.list();
        if (secrets.isEmpty()) {
            return "保管库中没有凭据。用户可通过 `tinyclaw secrets set --name <NAME> --from-env <ENV_VAR>` 添加。";
        }

        StringBuilder sb = new StringBuilder("可用凭据（引用方式：${secret:NAME}）：\n");
        for (SecretStore.SecretInfo info : secrets) {
            sb.append("- ").append(info.name());
            if (!info.description().isEmpty()) {
                sb.append("：").append(info.description());
            }
            if (!info.allowedHosts().isEmpty()) {
                sb.append("（仅允许发送到 ").append(String.join(", ", info.allowedHosts())).append("）");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
