package com.haitlify.aitest.platform.document;

class FigmaDesignUnavailableException extends RuntimeException {

    FigmaDesignUnavailableException(String message) {
        super(message);
    }

    FigmaDesignUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
