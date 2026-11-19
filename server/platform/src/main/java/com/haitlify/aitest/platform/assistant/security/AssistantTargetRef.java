package com.haitlify.aitest.platform.assistant.security;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * targetRef 的作用域校验（A0 早期开放项「targetRef 缺项目 URL 白名单」的最小收口）。
 *
 * <p>targetRef 是「这次操作指向哪个业务对象」，会被写进账本并进入 Hermes 提示。
 * 之前只校验非空，于是可以写 URL 或指向别的项目。现在要求：
 * <ul>
 *   <li>形如 {@code <namespace>:<id>/<path>}，namespace 只允许已知类别；</li>
 *   <li>不能是 URL（http/https/file 等）——真正的访问地址由已编译脚本的 startUrl 决定，且那里有私网校验；</li>
 *   <li>namespace 为 {@code project} 时，id 必须等于本次请求的 projectId（不能借 targetRef 跳到别的项目）。</li>
 * </ul>
 *
 * <p>限制：这仍不是「项目 URL 白名单」本身——按项目的允许域名清单需要产品给出清单来源，
 * 目前先用「脚本 URL 私网拒绝 + targetRef 项目作用域」防住越权与内网访问。
 */
public final class AssistantTargetRef {

    private static final Set<String> NAMESPACES = Set.of("project", "case", "requirement", "draft", "operation", "resource");
    private static final Pattern SHAPE = Pattern.compile("^([a-z]{3,16}):([A-Za-z0-9._-]{1,64})(/[A-Za-z0-9._/-]{0,200})?$");
    private static final Pattern URL_LIKE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$");

    private AssistantTargetRef() {
    }

    public static void validate(String targetRef, Long projectId) {
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef 不能为空");
        }
        if (URL_LIKE.matcher(targetRef.trim()).matches()) {
            throw new IllegalArgumentException("targetRef 不能是 URL，请写成 project:<id>/<资源> 形式：" + targetRef);
        }
        var matcher = SHAPE.matcher(targetRef.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("targetRef 形状不合法（应为 namespace:id/path）：" + targetRef);
        }
        String namespace = matcher.group(1).toLowerCase(Locale.ROOT);
        if (!NAMESPACES.contains(namespace)) {
            throw new IllegalArgumentException("targetRef 的 namespace 不受支持：" + namespace);
        }
        String id = matcher.group(2);
        if ("project".equals(namespace) && projectId != null && !id.equals(String.valueOf(projectId))) {
            throw new IllegalArgumentException("targetRef 指向其它项目（期望 project:" + projectId + "，实际 project:" + id + "）");
        }
    }
}
