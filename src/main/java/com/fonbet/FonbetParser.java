package com.fonbet;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonbet.model.FonbetResponse;

import java.io.IOException;

/**
 * Парсит JSON-ответ Fonbet API в объект FonbetResponse.
 */
public class FonbetParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, true);

    public static FonbetResponse parse(String json) throws IOException {
        return MAPPER.readValue(json, FonbetResponse.class);
    }
}
