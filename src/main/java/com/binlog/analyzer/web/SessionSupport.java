package com.binlog.analyzer.web;

import com.binlog.analyzer.model.ConnectionContext;

import javax.servlet.http.HttpSession;

public final class SessionSupport {

    public static final String CONNECTION_ATTRIBUTE = "binlog.connection";

    private SessionSupport() {
    }

    public static ConnectionContext requireConnection(HttpSession session) {
        Object value = session.getAttribute(CONNECTION_ATTRIBUTE);
        if (!(value instanceof ConnectionContext)) {
            throw new ApiException("会话中没有可用的数据库连接，请先点击“获取数据字典”");
        }
        return (ConnectionContext) value;
    }
}
