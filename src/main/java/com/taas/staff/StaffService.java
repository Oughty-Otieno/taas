package com.taas.staff;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.taas.common.InvalidRequest;
import com.taas.common.NotFoundException;
import com.taas.common.Rejection;
import com.taas.ledger.ActionEvent;
import com.taas.ledger.EventType;
import com.taas.workflow.CommandRunner;
import com.taas.workflow.CommandRunner.Caller;
import com.taas.workflow.CommandRunner.Command;
import com.taas.workflow.Reply;

/**
 * Named staff users and their roles. An admin creates users, changes their role and disables or re-enables them;
 * each change is a ledger event naming who made it. The directory is operational data (like the customer
 * directory): written in the same transaction as the event, not rebuilt by replay, and the email address never
 * enters the ledger.
 *
 * <p>Two guards: nobody changes their own role or status, and the last active admin cannot be demoted or disabled.
 * A disabled user's access tokens stop working at once (see {@code SecurityConfig}).
 */
@Service
public class StaffService {

    public static final List<String> ROLES = List.of("admin", "rules-manager", "operations", "compliance", "auditor");
    static final String RULES = "access-policy@v1";
    private static final String ROLE_PATTERN = "admin|rules-manager|operations|compliance|auditor";

    public record StaffUser(String userId, String displayName, String email, String role, String status,
            String statusNote, String createdBy, Instant createdAt, Instant updatedAt) {
    }

    public record Create(
            @Pattern(regexp = "[a-z][a-z0-9._-]{1,39}") String userId,
            @NotBlank @Size(max = 80) String displayName,
            @Email @Size(max = 120) String email,
            @NotBlank @Pattern(regexp = ROLE_PATTERN) String role) {
    }

    public record ChangeRole(@NotBlank @Pattern(regexp = ROLE_PATTERN) String role) {
    }

    public record ChangeStatus(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
            @Size(max = 300) String reason) {
    }

    private final CommandRunner runner;
    private final JdbcTemplate jdbc;

    public StaffService(CommandRunner runner, JdbcTemplate jdbc) {
        this.runner = runner;
        this.jdbc = jdbc;
    }

    // --- Reads ----------------------------------------------------------------------------------

    public List<StaffUser> users() {
        return jdbc.query("SELECT * FROM staff_user ORDER BY status, display_name", this::map);
    }

    public Optional<StaffUser> user(String userId) {
        return jdbc.query("SELECT * FROM staff_user WHERE user_id = ?", this::map, userId).stream().findFirst();
    }

    /** Is this user active and still in this role? Asked for every request made with a staff user's token. */
    public boolean isActiveIn(String userId, String role) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM staff_user WHERE user_id = ? AND role = ? AND status = 'ACTIVE'",
                Integer.class, userId, role);
        return n != null && n > 0;
    }

    // --- Changes --------------------------------------------------------------------------------

    public Reply create(Caller caller, Create c) {
        String id = c.userId() != null ? c.userId() : generateId(c.displayName());
        Command cmd = new Command("create-staff-user", id, null, c);
        return runner.execute(caller, cmd, null, tx -> {
            if (lock(id).isPresent()) {
                throw Rejection.conflict("USER_EXISTS", "User " + id + " already exists");
            }
            jdbc.update("""
                    INSERT INTO staff_user (user_id, display_name, email, role, status, created_by, created_at,
                                            updated_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)""", id, c.displayName().trim(), c.email(), c.role(),
                    caller.clientId(), utc(tx.now()), utc(tx.now()));
            ActionEvent e = tx.record(EventType.STAFF_USER_CREATED, id, null, RULES, fields("userId", id,
                    "displayName", c.displayName().trim(), "role", c.role(), "createdBy", caller.clientId()));
            return Reply.of(201, body("actionId", e.id(), "userId", id, "role", c.role(), "status", "ACTIVE"));
        });
    }

    public Reply changeRole(Caller caller, String userId, ChangeRole c) {
        Command cmd = new Command("change-staff-role", userId, null, Map.of("userId", userId, "role", c.role()));
        return runner.execute(caller, cmd, null, tx -> {
            StaffUser u = requireOther(caller, userId);
            if (u.role().equals(c.role())) {
                throw Rejection.conflict("NO_CHANGE", u.displayName() + " is already " + c.role());
            }
            if ("admin".equals(u.role()) && "ACTIVE".equals(u.status())) {
                requireAnotherAdmin(u);
            }
            jdbc.update("UPDATE staff_user SET role = ?, updated_at = ? WHERE user_id = ?", c.role(), utc(tx.now()),
                    userId);
            ActionEvent e = tx.record(EventType.STAFF_ROLE_CHANGED, userId, null, RULES, fields("userId", userId,
                    "displayName", u.displayName(), "fromRole", u.role(), "toRole", c.role(), "changedBy",
                    caller.clientId()));
            return Reply.of(200, body("actionId", e.id(), "userId", userId, "role", c.role()));
        });
    }

    public Reply changeStatus(Caller caller, String userId, ChangeStatus c) {
        boolean disable = "DISABLED".equals(c.status());
        if (disable && (c.reason() == null || c.reason().isBlank())) {
            throw new InvalidRequest("A reason is required to disable a user");
        }
        Command cmd = new Command("change-staff-status", userId, null, Map.of("userId", userId, "status", c));
        return runner.execute(caller, cmd, null, tx -> {
            StaffUser u = requireOther(caller, userId);
            if (u.status().equals(c.status())) {
                throw Rejection.conflict("NO_CHANGE", u.displayName() + " is already " + c.status().toLowerCase());
            }
            if (disable && "admin".equals(u.role())) {
                requireAnotherAdmin(u);
            }
            jdbc.update("UPDATE staff_user SET status = ?, status_note = ?, updated_at = ? WHERE user_id = ?",
                    c.status(), c.reason(), utc(tx.now()), userId);
            ActionEvent e = tx.record(disable ? EventType.STAFF_USER_DISABLED : EventType.STAFF_USER_ENABLED, userId,
                    null, RULES, fields("userId", userId, "displayName", u.displayName(), "role", u.role(),
                            "changedBy", caller.clientId(), "reason", c.reason()));
            return Reply.of(200, body("actionId", e.id(), "userId", userId, "status", c.status()));
        });
    }

    // --------------------------------------------------------------------------------------------

    private StaffUser requireOther(Caller caller, String userId) {
        if (userId.equals(caller.clientId())) {
            throw new Rejection(HttpStatus.FORBIDDEN, "CANNOT_CHANGE_SELF",
                    "Nobody may change their own role or status; ask another admin");
        }
        return lock(userId).orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
    }

    /** Locks the active admins, so two admins cannot remove each other at the same moment and leave none. */
    private void requireAnotherAdmin(StaffUser leaving) {
        List<String> admins = jdbc.queryForList(
                "SELECT user_id FROM staff_user WHERE role = 'admin' AND status = 'ACTIVE' ORDER BY user_id FOR UPDATE",
                String.class);
        if (admins.stream().noneMatch(a -> !a.equals(leaving.userId()))) {
            throw Rejection.conflict("LAST_ADMIN", leaving.displayName()
                    + " is the last active admin; make someone else an admin first");
        }
    }

    private Optional<StaffUser> lock(String userId) {
        return jdbc.query("SELECT * FROM staff_user WHERE user_id = ? FOR UPDATE", this::map, userId).stream()
                .findFirst();
    }

    private String generateId(String displayName) {
        String base = displayName.trim().toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
        if (base.length() < 2 || !Character.isLetter(base.charAt(0))) {
            base = "user." + base;
        }
        base = base.length() > 34 ? base.substring(0, 34) : base;
        String id = base;
        for (int n = 2; user(id).isPresent(); n++) {
            id = base + n;
        }
        return id;
    }

    private StaffUser map(ResultSet rs, int i) throws SQLException {
        return new StaffUser(rs.getString("user_id"), rs.getString("display_name"), rs.getString("email"),
                rs.getString("role"), rs.getString("status"), rs.getString("status_note"), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static SortedMap<String, String> fields(Object... pairs) {
        SortedMap<String, String> out = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                out.put((String) pairs[i], pairs[i + 1].toString());
            }
        }
        return out;
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
