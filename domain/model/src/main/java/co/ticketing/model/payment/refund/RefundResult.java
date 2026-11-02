package co.ticketing.model.payment.refund;

public sealed interface RefundResult {

    record Refunded(String refundId) implements RefundResult {
        public Refunded {
            if (refundId == null || refundId.isBlank()) {
                throw new IllegalArgumentException("refundId is required");
            }
        }
    }

    record RequiresManualReview(String reason) implements RefundResult {
        public RequiresManualReview {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason is required");
            }
        }
    }
}
