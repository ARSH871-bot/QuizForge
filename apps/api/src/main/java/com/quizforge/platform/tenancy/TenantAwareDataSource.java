package com.quizforge.platform.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Applies the current tenant to every transaction, so that Row-Level Security
 * policies have something to enforce against.
 *
 * <p>Two statements are issued at the start of each transaction:
 *
 * <pre>
 *   SET LOCAL ROLE quizforge_app;
 *   SELECT set_config('app.workspace_id', '&lt;uuid&gt;', true);
 * </pre>
 *
 * <p>The role switch is the load-bearing half. PostgreSQL skips RLS entirely
 * for superusers, and the application connects as one; without dropping to a
 * {@code NOBYPASSRLS} role the policies are present and enforce nothing.
 *
 * <p>Both are {@code LOCAL}, so they are discarded on commit or rollback and a
 * pooled connection can never carry one request's tenant or role into the
 * next. That is also why they cannot simply be issued when the connection is
 * handed out: {@code SET LOCAL} outside a transaction is a no-op. The hook is
 * {@link Connection#setAutoCommit(boolean)}, which Spring calls with
 * {@code false} at exactly the moment a transaction begins.
 *
 * <p>When no tenant is in scope — during authentication, or while Flyway runs
 * migrations — the connection is handed through untouched and operates as the
 * owner. Those paths are protected by application-layer authorization instead.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String APP_ROLE = "quizforge_app";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(obtain().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(obtain().getConnection(username, password));
    }

    private DataSource obtain() {
        DataSource target = getTargetDataSource();
        if (target == null) {
            throw new IllegalStateException("targetDataSource is required");
        }
        return target;
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new TenantScopedConnection(connection));
    }

    /**
     * Applies the tenant settings the first time a transaction is opened on
     * the underlying connection, then delegates everything unchanged.
     */
    private static final class TenantScopedConnection implements InvocationHandler {

        private final Connection target;
        private boolean applied;

        private TenantScopedConnection(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("setAutoCommit".equals(method.getName())
                    && args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) {
                Object result = invokeTarget(method, args);
                applyTenant();
                return result;
            }

            // Reset the flag when the connection returns to the pool so the
            // next borrower re-applies rather than inheriting silently.
            if ("close".equals(method.getName())) {
                applied = false;
            }

            return invokeTarget(method, args);
        }

        private Object invokeTarget(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        private void applyTenant() throws SQLException {
            UUID workspaceId = TenantContext.current();
            if (workspaceId == null || applied) {
                return;
            }

            // The role name is a constant, never user input, so interpolating
            // it is safe - SET ROLE does not accept a bind parameter.
            try (Statement statement = target.createStatement()) {
                statement.execute("SET LOCAL ROLE " + APP_ROLE);
            }
            try (PreparedStatement statement = target.prepareStatement(
                    "SELECT set_config('app.workspace_id', ?, true)")) {
                statement.setString(1, workspaceId.toString());
                statement.execute();
            }

            applied = true;
        }
    }
}
