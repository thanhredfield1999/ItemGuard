package com.itemguard.tasks;

import com.itemguard.ItemGuard;
import com.itemguard.dupe.DuplicateFinding;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.mockito.Mockito.*;

/**
 * Who receives a duplicate alert.
 *
 * <p>This test used to assert the opposite for FULL: that the alert went to whoever held
 * {@code itemguard.bypass} — the node whose meaning is "skip the anti-dupe checks" — while
 * {@code itemguard.notify} was not declared in {@code plugin.yml} at all. So on FULL a staff member
 * without op never received an alert, and the one person guaranteed to receive it was the person
 * configured to be exempt from the check. One node, both editions, is the contract now.
 */
class LiteDuplicateNotificationTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void duplicateAlertsGoToNotifyHoldersOnBothEditions(boolean lite) throws Exception {
        var plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.isLiteEdition()).thenReturn(lite);
        when(plugin.getConfigs().isNotifyStaff()).thenReturn(true);
        when(plugin.getMessages().getRaw(anyString(), anyMap())).thenReturn("alert");
        var notifyStaff = mock(Player.class);
        when(notifyStaff.hasPermission("itemguard.notify")).thenReturn(true);
        var bypassOnly = mock(Player.class);
        when(bypassOnly.hasPermission("itemguard.bypass")).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(notifyStaff, bypassOnly));
            var task = new InventoryScanTask(plugin);
            var report = InventoryScanTask.class.getDeclaredMethod("reportFindings", List.class);
            report.setAccessible(true);
            var finding = mock(DuplicateFinding.class);
            when(finding.code()).thenReturn("ABC123");
            report.invoke(task, List.of(finding));
            verify(notifyStaff, times(2)).sendMessage("alert");
            verify(bypassOnly, never()).sendMessage(anyString());
        }
    }

    @Test
    void anAlertNobodyCanReceiveIsCountedAsZeroRecipients() throws Exception {
        var plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.isLiteEdition()).thenReturn(false);
        when(plugin.getConfigs().isNotifyStaff()).thenReturn(true);
        when(plugin.getMessages().getRaw(anyString(), anyMap())).thenReturn("alert");
        var nobody = mock(Player.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(nobody));
            var task = new InventoryScanTask(plugin);
            var report = InventoryScanTask.class.getDeclaredMethod("reportFindings", List.class);
            report.setAccessible(true);
            var finding = mock(DuplicateFinding.class);
            when(finding.code()).thenReturn("ABC123");
            report.invoke(task, List.of(finding));

            verify(nobody, never()).sendMessage(anyString());
            org.junit.jupiter.api.Assertions.assertEquals(
                0,
                task.metrics().alertCount(),
                "a finding whose alert reached nobody must not be counted as a sent alert"
            );
            org.junit.jupiter.api.Assertions.assertEquals(
                1,
                task.metrics().findingCount(),
                "the finding itself still counts: it is the delivery that failed, not the detection"
            );
        }
    }
}
