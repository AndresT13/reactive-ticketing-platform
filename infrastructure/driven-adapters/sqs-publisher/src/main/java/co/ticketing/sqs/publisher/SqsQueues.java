package co.ticketing.sqs.publisher;

/** Queue URLs, provided by configuration. Reservations and payments use separate queues (priority isolation). */
public record SqsQueues(String reservationRequestsUrl, String paymentRequestsUrl) {
}
