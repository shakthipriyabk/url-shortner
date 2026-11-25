package com.example.shortener.service;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String code) {
        super("No active link for code '" + code + "'");
    }
}
