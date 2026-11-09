package com.share.auth.service;

import com.share.auth.dto.LoginResponse;
import com.share.common.constant.PermissionConstants;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 前端菜单服务：按用户权限过滤菜单，服务端下发保证“看不到即调不通”。
 */
@Service
public class MenuService {

    private record MenuDef(String key, String path, String name, String title, String icon, String permission) {
    }

    /** 与前端页面一一对应的菜单定义（工作台无需权限）。 */
    private static final List<MenuDef> MENUS = List.of(
            new MenuDef("dashboard", "/dashboard", "Dashboard", "工作台", "Odometer", null),
            new MenuDef("catalog", "/catalog", "Catalog", "数据目录", "Files", PermissionConstants.CATALOG_READ),
            new MenuDef("datasource", "/datasource", "Datasource", "数据源", "Coin", PermissionConstants.DATASOURCE_READ),
            new MenuDef("task", "/task", "Task", "交换任务", "Refresh", PermissionConstants.TASK_READ),
            new MenuDef("approval", "/approval", "Approval", "申请审批", "DocumentChecked", PermissionConstants.APPROVAL_APPLY),
            new MenuDef("share", "/share", "ShareService", "共享服务", "Connection", PermissionConstants.SHARE_READ),
            new MenuDef("security", "/security", "Security", "安全管控", "Lock", PermissionConstants.SECURITY_READ),
            new MenuDef("file", "/file", "FileExchange", "文件交换", "FolderOpened", PermissionConstants.FILE_READ),
            new MenuDef("audit", "/audit", "Audit", "审计日志", "Tickets", PermissionConstants.AUDIT_READ),
            new MenuDef("monitor", "/monitor", "Monitor", "监控大屏", "DataLine", PermissionConstants.MONITOR_READ),
            new MenuDef("notify", "/notify", "Notify", "消息中心", "Bell", PermissionConstants.NOTIFY_READ),
            new MenuDef("user", "/user", "UserManage", "用户与角色", "User", PermissionConstants.USER_READ),
            new MenuDef("agent", "/agent", "Agent", "前置节点", "Cpu", PermissionConstants.AGENT_READ)
    );

    /**
     * 计算用户可见菜单。
     *
     * @param permissions 已授予权限集合
     * @param admin       是否管理员（全部菜单可见）
     */
    public List<LoginResponse.Menu> menusFor(Set<String> permissions, boolean admin) {
        List<LoginResponse.Menu> result = new ArrayList<>();
        for (MenuDef def : MENUS) {
            boolean visible = admin || def.permission() == null
                    || (permissions != null && permissions.contains(def.permission()));
            if (visible) {
                result.add(new LoginResponse.Menu(def.key(), def.path(), def.name(), def.title(), def.icon(),
                        def.permission(), List.of()));
            }
        }
        return result;
    }

    /** 全部菜单标识（用于前端兜底展示）。 */
    public List<String> allKeys() {
        return MENUS.stream().map(MenuDef::key).toList();
    }
}
