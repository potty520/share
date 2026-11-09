package com.share.common.security;

/** 当前请求的用户上下文。 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long userId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getUserId();
    }

    public static String username() {
        LoginUser u = HOLDER.get();
        return u == null ? "anonymous" : u.getUsername();
    }

    public static Long deptId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getDeptId();
    }

    public static boolean isAdmin() {
        LoginUser u = HOLDER.get();
        return u != null && u.isAdmin();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
