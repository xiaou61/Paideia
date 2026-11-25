package io.github.xiaou61.account.internal.home;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首页布局用例。布局是"每用户一份的界面配置"，不属于任何权限判定。
 *
 * <p>服务端**只做透明存取**：把前端提交的任意 JSON 存进 TEXT 列，读回时原样返回。
 * 字段结构（版块顺序/显隐、快捷入口排序/自定义链接）由前端拥有，这样以后加字段不用改表、
 * 也不用改后端。没有行时返回一个最小默认 `{ "version": 1 }`，前端据此退回内置默认布局。
 *
 * <p>JSON 用 Boot 4 自带的 Jackson 3（`tools.jackson.*`）与本模块其它处（`OAuthHttp`）一致；
 * 容器不暴露该 ObjectMapper Bean，故自建一份。
 */
@Service
public class HomeLayoutService {

    private final HomeLayoutMapper mapper;

    /** 自建 ObjectMapper：Boot 4 组合里容器不暴露该 Bean（与 OAuthHttp 同口径）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    HomeLayoutService(HomeLayoutMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode get(long userId) {
        return mapper.findJson(userId).map(this::parse).orElseGet(this::defaultLayout);
    }

    @Transactional
    public JsonNode save(long userId, JsonNode layout) {
        JsonNode node = layout == null ? defaultLayout() : layout;
        mapper.upsert(userId, node.toString());
        return node;
    }

    /** 注销级联。放在这一层是因为删除语句属于账户域。 */
    @Transactional
    public int deleteByUser(long userId) {
        return mapper.deleteByUser(userId);
    }

    private JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception exception) {
            // 存坏了的行不该让首页整页 500：退回默认布局，前端照常渲染。
            return defaultLayout();
        }
    }

    private JsonNode defaultLayout() {
        ObjectNode node = JSON.createObjectNode();
        node.put("version", 1);
        return node;
    }
}
