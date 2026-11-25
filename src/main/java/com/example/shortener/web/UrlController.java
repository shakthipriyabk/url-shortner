package com.example.shortener.web;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.UrlMapping;
import com.example.shortener.service.NotFoundException;
import com.example.shortener.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class UrlController {

    private final UrlService service;
    private final ShortenerProperties props;

    public UrlController(UrlService service, ShortenerProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping("/api/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request) {
        UrlMapping m = service.shorten(request.url(), request.ttlDays());
        String shortUrl = props.baseUrl() + "/" + m.shortCode();
        return ResponseEntity.created(URI.create(shortUrl))
                .body(new ShortenResponse(m.shortCode(), shortUrl, m.originalUrl(), m.createdAt(), m.expiresAt()));
    }

    /**
     * 302 (temporary) rather than 301 (permanent): browsers cache a 301 forever, which would hide later clicks from
     * analytics and make expiry/deletion ineffective. The trade-off is that every click reaches us.
     */
    @GetMapping("/{code:[0-9A-Za-z]{1,11}}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String target = service.resolve(code).orElseThrow(() -> new NotFoundException(code));
        service.recordClick(code);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @GetMapping("/api/stats/{code}")
    public StatsResponse stats(@PathVariable String code) {
        UrlMapping m = service.find(code).orElseThrow(() -> new NotFoundException(code));
        return new StatsResponse(m.shortCode(), m.originalUrl(), m.createdAt(), m.expiresAt(), service.clicks(code));
    }
}
