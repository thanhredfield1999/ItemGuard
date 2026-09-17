package com.itemguard.custody;

import java.util.ArrayList;
import java.util.List;

/**
 * Lore for the custody chain.
 *
 * <p>The number of handovers is about the item, so its owner may see it. The identities of other
 * holders are other players' data and stay behind {@code itemguard.history.others}, consistent with
 * the redaction already applied to individual history rows.
 *
 * <p>Wording stays historical on purpose: the plugin records what it observed, and the last recorded
 * holder is not a claim about who is carrying the item right now. ItemGuard uses normal casing.
 */
public final class CustodyPresentation {

    private static final int MAX_NAMES = 8;

    private CustodyPresentation() {
    }

    public static List<String> lore(CustodyChain chain, boolean staffView, boolean vietnamese) {
        return lore(chain, null, staffView, vietnamese);
    }

    /**
     * @param activity collapsed activity figures, or null to omit them
     */
    public static List<String> lore(CustodyChain chain, CustodyActivity activity,
                                    boolean staffView, boolean vietnamese) {
        if (chain == null || chain.distinctHolders() == 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int transfers = chain.transfers();
        int holders = chain.distinctHolders();
        if (vietnamese) {
            lines.add("Đã ghi nhận " + transfers + " lần đổi tay, qua " + holders + " người cầm.");
            lines.add("Tự vứt ra rồi nhặt lại không được tính.");
        } else {
            lines.add("Recorded " + transfers + " handovers across " + holders + " holders.");
            lines.add("Dropping and re-picking your own item is not counted.");
        }
        if (activity != null && activity.selfCycles() > 0) {
            lines.add(vietnamese
                ? "Trong đó " + activity.selfCycles() + " lần tự vứt rồi nhặt lại (không tính)."
                : "Includes " + activity.selfCycles() + " self drop and re-pick cycles (not counted).");
        }
        if (staffView) {
            List<String> names = chain.holderNames();
            List<String> shown = names.size() > MAX_NAMES ? names.subList(names.size() - MAX_NAMES, names.size()) : names;
            String joined = String.join(" -> ", shown);
            if (names.size() > MAX_NAMES) {
                joined = (vietnamese ? "... -> " : "... -> ") + joined;
            }
            lines.add((vietnamese ? "Đã cầm: " : "Held by: ") + joined);
        } else {
            lines.add(vietnamese
                ? "Cần quyền nhân viên để xem danh sách người từng cầm."
                : "Staff permission is required to see who held it.");
        }
        return List.copyOf(lines);
    }
}
