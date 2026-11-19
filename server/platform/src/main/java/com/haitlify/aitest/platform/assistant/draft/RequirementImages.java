package com.haitlify.aitest.platform.assistant.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图文需求里的图片引用校验。
 *
 * <p>需求原文允许带 markdown 图片（`![alt](url)`），图片是需求事实的一部分，摘要会把整段原文算进去，
 * 所以「确认时看到的图」和「执行时依据的图」是同一份。这里只做三件确定性的事：
 * 限制数量、拒绝内联 data:（无法审计、体积失控）、只接受 https 或平台自身的 /api/ 资源路径。
 * 不抓取、不解析图片内容——那需要外部访问，属于 Runner/Hermes 的受控能力。
 */
final class RequirementImages {

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    /** 含空白/换行的 data URI 不会被 IMAGE 捕获（URL 段不允许空白），必须单独拦。 */
    private static final Pattern INLINE_DATA = Pattern.compile("!\\[[^\\]]*]\\(\\s*data:");
    private static final int MAX_IMAGES = 10;

    record ImageRef(String alt, String url) {}

    private RequirementImages() {}

    static List<ImageRef> parse(String text) {
        List<ImageRef> refs = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return refs;
        }
        Matcher matcher = IMAGE.matcher(text);
        while (matcher.find()) {
            refs.add(new ImageRef(matcher.group(1), matcher.group(2)));
        }
        return refs;
    }

    static void validate(String text) {
        if (text != null && INLINE_DATA.matcher(text).find()) {
            throw new IllegalArgumentException("需求图片不接受内联 data:（无法审计；含空白/换行的写法同样拒绝）");
        }
        List<ImageRef> refs = parse(text);
        if (refs.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("需求图片最多 " + MAX_IMAGES + " 张（当前 " + refs.size() + " 张）");
        }
        for (ImageRef ref : refs) {
            String url = ref.url();
            if (url.startsWith("data:")) {
                throw new IllegalArgumentException("需求图片不接受内联 data:（无法审计）：" + ref.alt());
            }
            boolean ok = url.startsWith("https://") || url.startsWith("/api/");
            if (!ok) {
                throw new IllegalArgumentException("需求图片只接受 https 或 /api/ 资源路径（拒绝 " + url + "）");
            }
        }
    }
}
