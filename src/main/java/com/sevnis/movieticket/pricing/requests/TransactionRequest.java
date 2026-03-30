package com.sevnis.movieticket.pricing.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;

@Builder
public record TransactionRequest(

    @NotNull(message = "Transaction ID is required")
    @Min(value = 1, message = "Transaction ID must be positive")
    Long transactionId,

    @NotNull(message = "Transaction must have customer list")
    @Size(min = 1, message = "Transaction must have at least one customer")
    @Valid
    List<CustomerRequest> customers
) {

}
