package co.ticketing.model.payment.authorization;

public sealed interface PaymentAuthorizationLookup {

    record Approved(String authorizationId)
            implements PaymentAuthorizationLookup {
        public Approved {
            if (authorizationId == null || authorizationId.isBlank()) {
                throw new IllegalArgumentException(
                        "authorizationId is required");
            }
        }
    }

    record Declined(String reason)
            implements PaymentAuthorizationLookup {
        public Declined {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "reason is required");
            }
        }
    }

    record Unknown()
            implements PaymentAuthorizationLookup {
    }
}
