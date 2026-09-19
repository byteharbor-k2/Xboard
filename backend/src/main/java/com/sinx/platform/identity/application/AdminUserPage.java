package com.sinx.platform.identity.application;

import java.util.List;

/**
 * One page of the administrator's user list.
 *
 * Paged rather than truncated: the original's admin list returned everything it
 * had, which stops working the moment a panel has more than a screen of
 * customers.
 */
public record AdminUserPage(
    List<AdminUserView> data,
    long total,
    int page,
    int limit
) {
}
