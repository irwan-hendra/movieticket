package com.sevnis.movieticket.pricing.requests;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
@JsonFormat(with = {JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES})
public record CustomerRequest(

    @NotBlank(message = "Customer name is required")
    String name,

    @NotNull(message = "Customer age is required")
    @Min(value = 1, message = "Customer age must be positive")
    Integer age
) {

}
