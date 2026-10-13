package io.github.xyz1001.pdsync.android;

import java.math.BigDecimal;
import java.util.Currency;

/** Payment labels contain only server values; there is no bundled price or receiving account. */
final class ManualPaymentCopy {
    private ManualPaymentCopy() { }
    static String amount(long minor, String currency) {
        int digits;
        try { digits = Currency.getInstance(currency).getDefaultFractionDigits(); }
        catch (IllegalArgumentException failure) { return minor + " " + currency + "（最小单位）"; }
        if (digits < 0) return minor + " " + currency + "（最小单位）";
        return currency + " " + BigDecimal.valueOf(minor, digits).toPlainString();
    }
    static String term(String term, long days) { return "calendar_year".equals(term) ? "一年" : days + " 天"; }
    static String orderState(ManualOrderClient.State state) {
        switch (state) {
            case CREATED: return "待付款";
            case REVIEW: return "待核款";
            case APPLYING: return "开通中";
            case APPROVED: return "沙箱已开通";
            case CANCELLED: return "已取消";
            case REJECTED: return "未通过";
            default: throw new IllegalArgumentException("Unknown order state");
        }
    }
}
