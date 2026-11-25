package com.example.shortener.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShortenRequest(
        @NotBlank @Size(max = 2048) String url,
        @Min(1) @Max(3650) Integer ttlDays) {
}
