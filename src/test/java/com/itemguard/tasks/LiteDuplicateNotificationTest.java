package com.itemguard.tasks;

import com.itemguard.ItemGuard;
import com.itemguard.dupe.DuplicateFinding;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.mockito.Mockito.*;

class LiteDuplicateNotificationTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void notificationsUseLiteNotifyPermissionWithoutChangingFullPolicy(boolean lite) throws Exception {
        var plugin = mock(ItemGuard.class, RETURNS_DEEP_STUBS);
        when(plugin.isLiteEdition()).thenReturn(lite);
        when(plugin.getConfigs().isNotifyStaff()).thenReturn(true);
        when(plugin.getMessages().getRaw(anyString(), anyMap())).thenReturn("alert");
        var liteStaff = mock(Player.class);
        when(liteStaff.hasPermission("itemguard.notify")).thenReturn(true);
        var fullStaff = mock(Player.class);
        when(fullStaff.hasPermission("itemguard.bypass")).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(liteStaff, fullStaff));
            var task = new InventoryScanTask(plugin);
            var report = InventoryScanTask.class.getDeclaredMethod("reportFindings", List.class);
            report.setAccessible(true);
            var finding = mock(DuplicateFinding.class);
            when(finding.code()).thenReturn("ABC123");
            report.invoke(task, List.of(finding));
            verify(lite ? liteStaff : fullStaff, times(2)).sendMessage("alert");
            verify(lite ? fullStaff : liteStaff, never()).sendMessage(anyString());
        }
    }
}
