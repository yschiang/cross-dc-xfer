package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** v1 固定常數（D30 修 5、D30 修 6）。程式碼以 V1 為準；設定若帶 fixed 段必須逐欄相等。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FixedConstants(
    int declarationMaxAgeDays,
    int abandonedTtlHours,
    int cleanerIntervalHours,
    int fullReconcileHours,
    int searchWindowDays,
    int latePublishToleranceDays,
    int clockMarginDays) {
    public static final FixedConstants V1 = new FixedConstants(7, 24, 24, 6, 30, 19, 1);
}
