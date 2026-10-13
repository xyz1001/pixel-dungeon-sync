package io.github.xyz1001.pdsync.android;

/** Donation copy has no fixed price or customer-entered amount. */
final class ManualPaymentCopy {
    private ManualPaymentCopy() { }
    static String validity() { return "确认赞赏后一年有效，未到期顺延一年。"; }
}
